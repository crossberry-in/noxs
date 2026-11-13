/*
 * Noxs — original implementation.
 * JVM tests for NoxsPluginCloudCatalog and its NoxsPluginManager wiring:
 * cloud listing validation/dedupe, authoritative artifact resolution
 * (sha256 + plugin.json cross-checks), the details-page artifact cache,
 * and cloud install/update through the single manager install path.
 * No network: listings and artifacts are faked; the SDK is pre-seeded.
 */
package com.crossberry.noxs.runtime.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class NoxsPluginCloudCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ----------------------------------------------------- tar.gz fixtures

    /** Minimal ustar header builder (same approach as NoxsPluginInstallerTest). */
    private fun tarHeader(name: String, size: Long, typeFlag: Byte, mode: Int = 420 /* octal 0644 */): ByteArray {
        val header = ByteArray(512)
        fun put(s: String, off: Int, len: Int) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(bytes, 0, header, off, minOf(bytes.size, len))
        }
        fun octal(v: Long, off: Int, len: Int) {
            val text = java.lang.Long.toOctalString(v)
            val padded = "0".repeat(len - 1 - text.length) + text + "\u0000"
            System.arraycopy(padded.toByteArray(Charsets.US_ASCII), 0, header, off, len)
        }
        put(name, 0, 100)
        octal(mode.toLong(), 100, 8)
        octal(0L, 108, 8)          // uid
        octal(0L, 116, 8)          // gid
        octal(size, 124, 12)
        octal(0L, 136, 12)         // mtime
        header[156] = typeFlag
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFFL
        val checksumText = java.lang.Long.toOctalString(checksum)
        val paddedChecksum = "0".repeat(6 - checksumText.length) + checksumText + "\u0000 "
        System.arraycopy(paddedChecksum.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)
        return header
    }

    private fun tarEntry(name: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tarHeader(name, content.size.toLong(), '0'.code.toByte()))
        out.write(content)
        val padding = (512 - (content.size % 512)) % 512
        out.write(ByteArray(padding))
        return out.toByteArray()
    }

    private fun gzip(vararg entries: ByteArray): ByteArray {
        val tar = ByteArrayOutputStream()
        entries.forEach { tar.write(it) }
        tar.write(ByteArray(1024)) // end-of-archive blocks
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    /** Mirrors the `nxc plugin deploy` scaffold: plugin.json + js + README + icon. */
    private fun cloudTarBytes(
        id: String = "test",
        version: String = "1.0.0",
        sdkVersion: String = "0.0.2",
        description: String = "A Noxs plugin"
    ): ByteArray {
        val pluginJson = """
            {"id":"$id","name":"$id","version":"$version",
             "description":"$description","main":"plugin.js",
             "permissions":["ui"],"minimumNoxsVersion":"0.16.0",
             "sdkVersion":"$sdkVersion"}
        """.trimIndent().toByteArray()
        return gzip(
            tarEntry("plugin.js", "globalThis.__NOXS_PLUGIN__={};".toByteArray()),
            tarEntry("plugin.json", pluginJson),
            tarEntry("README.md", "# $id\n\nCloud plugin docs.".toByteArray()),
            tarEntry("icon.svg", "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray())
        )
    }

    /** Mirrors the NXMirror `GET cloud/plugins` response shape. */
    private fun itemsJson(
        name: String = "test",
        version: String = "1.0.0",
        sha: String,
        url: String = "https://mirror.invalid/$name-$version.noxs-plugin",
        owner: String = "admin"
    ): String = """
        {"success":true,"items":[{"id":"3e3491dd-5fd4","type":"plugin",
          "name":"$name","version":"$version","filename":"$name-$version.noxs-plugin",
          "size":415,"sha256":"$sha","downloadUrl":"$url","owner":"$owner",
          "publishedAt":"2026-11-13T14:54:55.678Z"}]}
    """.trimIndent()

    private class FakeHeaderFetcher(
        vararg payloads: Pair<String, ByteArray>
    ) : NoxsPluginCloudCatalog.HeaderFetcher {
        private val queue = ArrayDeque(payloads.toList())
        var lastUrl: String? = null
            private set
        var lastHeaders: Map<String, String> = emptyMap()
            private set

        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): ByteArray {
            if (!url.startsWith("https://")) throw AssertionError("non-https fetch attempted")
            lastUrl = url
            lastHeaders = headers
            return queue.removeFirstOrNull()?.second
                ?: throw AssertionError("unexpected extra fetch: $url")
        }
    }

    private class FakeRegistryFetcher(vararg payloads: Pair<String, ByteArray>) :
        NoxsPluginRegistry.Fetcher {
        private val queue = ArrayDeque(payloads.toList())
        override fun get(url: String, timeoutMs: Int): ByteArray =
            queue.removeFirstOrNull()?.second
                ?: throw AssertionError("unexpected registry fetch: $url")
    }

    private fun catalog(fetcher: NoxsPluginCloudCatalog.HeaderFetcher) =
        NoxsPluginCloudCatalog(
            cacheDir = tmp.newFolder("plugins-cache"),
            tokenProvider = { "test-token" },
            fetcher = fetcher
        )

    private fun seedSdk(rootfsHome: File, version: String = "0.0.2") {
        val dir = File(rootfsHome, ".noxs/sdk/$version").apply { mkdirs() }
        File(dir, "sdk.json").writeText(
            """{"name":"noxs-plugin-sdk","version":"$version","apiVersion":"1",
               "entry":"index.js","status":"stable","features":["ui"]}""".trimIndent()
        )
        File(dir, "index.js").writeText("// sdk entry\n")
    }

    private fun manager(
        home: File,
        cache: File,
        cloud: NoxsPluginCloudCatalog?,
        registryFetcher: NoxsPluginRegistry.Fetcher
    ): NoxsPluginManager = NoxsPluginManager(
        rootfsHome = home,
        cacheDir = cache,
        appVersion = "1.2.0",
        fetcher = registryFetcher,
        guestBinDir = null,
        cloudCatalog = cloud
    )

    private val registryJson = """
        {"version":1,"plugins":[{"id":"code","name":"Code","version":"1.0.0",
          "description":"Official registry plugin","permissions":["ui"],
          "minimumNoxsVersion":"0.12.0",
          "artifact":"https://artifacts.invalid/code.noxs-plugin"}]}
    """.trimIndent()

    // ------------------------------------------------------------- listing

    @Test
    fun `adopted listing maps validated items to store entries`() {
        val tar = cloudTarBytes()
        val cat = catalog(FakeHeaderFetcher())
        cat.adopt(itemsJson(sha = PluginChecksum.sha256(tar)))

        val entries = cat.entries(refresh = false)
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("test", entry.id)
        assertEquals("test", entry.name)
        assertEquals("1.0.0", entry.version)
        assertEquals("admin", entry.author)
        assertEquals("https://mirror.invalid/test-1.0.0.noxs-plugin", entry.artifact)
        assertEquals(PluginChecksum.sha256(tar), entry.checksum)
        assertEquals("2026-11-13T14:54:55.678Z", entry.updatedAt)
        assertNull(entry.readmeUrl)
    }

    @Test
    fun `invalid cloud items are dropped`() {
        val cat = catalog(FakeHeaderFetcher())
        val good = PluginChecksum.sha256(cloudTarBytes())
        cat.adopt("""
            {"success":true,"items":[
              {"type":"plugin","name":"Bad Name","version":"1.0.0","sha256":"$good",
               "downloadUrl":"https://mirror.invalid/x"},
              {"type":"plugin","name":"ok-name","version":"not-a-semver","sha256":"$good",
               "downloadUrl":"https://mirror.invalid/x"},
              {"type":"plugin","name":"plain-http","version":"1.0.0","sha256":"$good",
               "downloadUrl":"http://mirror.invalid/x"},
              {"type":"plugin","name":"bad-sha","version":"1.0.0","sha256":"deadbeef",
               "downloadUrl":"https://mirror.invalid/x"},
              {"type":"package","name":"not-a-plugin","version":"1.0.0","sha256":"$good",
               "downloadUrl":"https://mirror.invalid/x"}
            ]}
        """.trimIndent())

        assertTrue(cat.entries(refresh = false).isEmpty())
        assertNull(cat.item("ok-name"))
    }

    @Test
    fun `failed listing keeps the last-good cache`() {
        val tar = cloudTarBytes()
        val cat = catalog(FakeHeaderFetcher())
        cat.adopt(itemsJson(sha = PluginChecksum.sha256(tar)))
        assertEquals(1, cat.items(refresh = false).size)

        // A forced refresh with no queued payload fails — the cache survives.
        runCatching { cat.items(refresh = true, force = true) }
        assertEquals(1, cat.items(refresh = false).size)
        assertTrue(cat.item("test") != null)
    }

    @Test
    fun `listing fetch carries the bearer token`() {
        val tar = cloudTarBytes()
        val fetcher = FakeHeaderFetcher(
            "https://nxmirror.netlify.app/api/cloud/plugins" to
                itemsJson(sha = PluginChecksum.sha256(tar)).toByteArray()
        )
        val cat = catalog(fetcher)
        val entries = cat.entries(refresh = true, force = true)
        assertEquals(1, entries.size)
        assertEquals("https://nxmirror.netlify.app/api/cloud/plugins", fetcher.lastUrl)
        assertEquals("Bearer test-token", fetcher.lastHeaders["Authorization"])
        assertTrue(cat.lastRefreshOk)
    }

    // -------------------------------------------------- artifact resolution

    @Test
    fun `resolveInstallable returns the authoritative entry and bytes`() {
        val tar = cloudTarBytes()
        val fetcher = FakeHeaderFetcher(
            "https://mirror.invalid/test-1.0.0.noxs-plugin" to tar
        )
        val cat = catalog(fetcher)
        cat.adopt(itemsJson(sha = PluginChecksum.sha256(tar)))

        val (entry, bytes) = cat.resolveInstallable("test")
        assertEquals("test", entry.id)
        assertEquals("1.0.0", entry.version)
        assertEquals("A Noxs plugin", entry.description)
        assertEquals(listOf("ui"), entry.permissions)
        assertEquals("0.0.2", entry.sdkVersion)
        assertEquals("admin", entry.author)
        assertEquals(PluginChecksum.sha256(tar), entry.checksum)
        assertTrue(entry.minimumNoxsVersion == "0.16.0")
        assertTrue(bytes.contentEquals(tar))
    }

    @Test
    fun `resolveInstallable refuses a tampered artifact`() {
        val tar = cloudTarBytes()
        val fetcher = FakeHeaderFetcher(
            "https://mirror.invalid/test-1.0.0.noxs-plugin" to cloudTarBytes(description = "tampered")
        )
        val cat = catalog(fetcher)
        cat.adopt(itemsJson(sha = PluginChecksum.sha256(tar)))

        val failure = runCatching { cat.resolveInstallable("test") }.exceptionOrNull()
        assertTrue(failure is NoxsPluginCloudCatalog.CloudException)
        assertTrue(failure!!.message?.contains("integrity") == true)
    }

    @Test
    fun `resolveInstallable refuses an artifact that lies about its identity`() {
        val fetcher = FakeHeaderFetcher(
            "https://mirror.invalid/test-1.0.0.noxs-plugin" to cloudTarBytes(id = "other")
        )
        val cat = catalog(fetcher)
        cat.adopt(itemsJson(sha = PluginChecksum.sha256(cloudTarBytes(id = "other"))))

        val failure = runCatching { cat.resolveInstallable("test") }.exceptionOrNull()
        assertTrue(failure is NoxsPluginCloudCatalog.CloudException)
        assertTrue(failure!!.message?.contains("does not match") == true)
    }

    @Test
    fun `inspect extracts docs and icon and caches them on disk`() {
        val tar = cloudTarBytes()
        val fetcher = FakeHeaderFetcher(
            "https://mirror.invalid/test-1.0.0.noxs-plugin" to tar
        )
        val cat = catalog(fetcher)
        cat.adopt(itemsJson(sha = PluginChecksum.sha256(tar)))

        val artifact = cat.inspect("test")
        assertTrue(artifact.readme!!.contains("Cloud plugin docs"))
        assertNotNull(artifact.iconSvg)
        assertEquals("0.0.2", artifact.meta.sdkVersion)

        // A second inspect must come from the disk cache — no queued payload.
        val cached = cat.inspect("test")
        assertTrue(cached.readme!!.contains("Cloud plugin docs"))
        assertNotNull(cached.iconSvg)
    }

    // ------------------------------------------------- manager integration

    @Test
    fun `cloud plugins merge into the catalog without shadowing the registry`() {
        val tar = cloudTarBytes()
        val cache = tmp.newFolder("mgr-cache")
        val home = tmp.newFolder("mgr-home")
        val cloud = NoxsPluginCloudCatalog(
            cacheDir = File(cache, "plugins"),
            tokenProvider = { "test-token" },
            fetcher = FakeHeaderFetcher()
        )
        cloud.adopt(itemsJson(name = "test", sha = PluginChecksum.sha256(tar)))
        val mgr = manager(
            home, cache, cloud,
            registryFetcher = FakeRegistryFetcher(
                NoxsPluginRegistry.REGISTRY_URL to registryJson.toByteArray()
            )
        )

        val catalog = mgr.catalog(refresh = true)
        assertEquals(listOf("code", "test"), catalog.map { it.id })
        val test = catalog.first { it.id == "test" }
        assertEquals("admin", test.author)

        // A registry plugin with the same id wins over the cloud listing.
        val shadowedRegistry = """
            {"version":1,"plugins":[{"id":"test","name":"Test","version":"0.9.0",
              "description":"Official","permissions":["ui"],
              "artifact":"https://artifacts.invalid/test.noxs-plugin"}]}
        """.trimIndent()
        val cache2 = tmp.newFolder("mgr-cache-2")
        val home2 = tmp.newFolder("mgr-home-2")
        val mgr2 = manager(
            home2, cache2, cloud,
            registryFetcher = FakeRegistryFetcher(
                NoxsPluginRegistry.REGISTRY_URL to shadowedRegistry.toByteArray()
            )
        )
        val catalog2 = mgr2.catalog(refresh = true)
        assertEquals(1, catalog2.count { it.id == "test" })
        assertEquals("Official", catalog2.first { it.id == "test" }.description)
    }

    @Test
    fun `manager installs a cloud plugin end-to-end`() {
        val tar = cloudTarBytes()
        val cache = tmp.newFolder("install-cache")
        val home = tmp.newFolder("install-home")
        seedSdk(home)
        val cloud = NoxsPluginCloudCatalog(
            cacheDir = File(cache, "plugins"),
            tokenProvider = { "test-token" },
            fetcher = FakeHeaderFetcher(
                "https://mirror.invalid/test-1.0.0.noxs-plugin" to tar
            )
        )
        cloud.adopt(itemsJson(sha = PluginChecksum.sha256(tar)))
        val mgr = manager(
            home, cache, cloud,
            registryFetcher = FakeRegistryFetcher(
                NoxsPluginRegistry.REGISTRY_URL to registryJson.toByteArray()
            )
        )

        val installed = mgr.install("test")
        assertEquals("test", installed.meta.id)
        assertEquals("1.0.0", installed.meta.version)
        assertEquals("0.0.2", installed.meta.sdkVersion)
        assertTrue(installed.enabled)
        assertTrue(File(installed.dir, "README.md").isFile)
        assertTrue(File(installed.dir, "icon.svg").isFile)
        assertTrue(File(installed.dir, "plugin.js").isFile)

        // The installed plugin shows up in the store cards with a real
        // description (authoritative metadata, not the bare cloud listing).
        val card = mgr.storeCards(refresh = false).first { it.entry.id == "test" }
        assertEquals("A Noxs plugin", card.entry.description)
        assertTrue(card.installed != null)
        assertTrue(!card.updateAvailable)
    }

    @Test
    fun `manager update replaces a cloud redeploy at the same version`() {
        val cache = tmp.newFolder("update-cache")
        val home = tmp.newFolder("update-home")
        seedSdk(home)
        val v1 = cloudTarBytes(description = "first upload")
        val v1again = cloudTarBytes(description = "redeployed content")
        val cloud = NoxsPluginCloudCatalog(
            cacheDir = File(cache, "plugins"),
            tokenProvider = { "test-token" },
            fetcher = FakeHeaderFetcher(
                "https://mirror.invalid/test-1.0.0.noxs-plugin" to v1,
                "https://mirror.invalid/test-1.0.0.noxs-plugin" to v1again
            )
        )
        cloud.adopt(itemsJson(sha = PluginChecksum.sha256(v1)))
        val mgr = manager(
            home, cache, cloud,
            registryFetcher = FakeRegistryFetcher(
                NoxsPluginRegistry.REGISTRY_URL to registryJson.toByteArray()
            )
        )
        assertEquals("first upload", mgr.install("test").meta.description)

        cloud.adopt(itemsJson(sha = PluginChecksum.sha256(v1again)))
        val updated = mgr.update("test")
        assertEquals("1.0.0", updated.meta.version)
        assertEquals("redeployed content", updated.meta.description)
        assertTrue(updated.enabled)
    }

    @Test
    fun `unknown ids fail with the stable message`() {
        val cache = tmp.newFolder("unknown-cache")
        val home = tmp.newFolder("unknown-home")
        val cloud = NoxsPluginCloudCatalog(
            cacheDir = File(cache, "plugins"),
            tokenProvider = { "test-token" },
            fetcher = FakeHeaderFetcher()
        )
        val mgr = manager(
            home, cache, cloud,
            registryFetcher = FakeRegistryFetcher(
                NoxsPluginRegistry.REGISTRY_URL to registryJson.toByteArray()
            )
        )
        val failure = runCatching { mgr.install("missing-plugin") }.exceptionOrNull()
        assertTrue(failure is NoxsPluginManager.ManagerException)
        assertEquals("Unknown cloud plugin", failure!!.message)
    }
}
