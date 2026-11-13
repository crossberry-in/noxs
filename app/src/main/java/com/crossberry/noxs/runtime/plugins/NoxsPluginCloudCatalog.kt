/*
 * Noxs — original implementation.
 * NoxsPluginCloudCatalog: brings NXMirror cloud plugins (published with
 * `nxc plugin deploy`) into the Plugin Store. The store list, the details
 * page, search and the install path all read the SAME RegistryEntry shape,
 * so cloud plugins are first-class citizens — no second implementation.
 *
 * Listing: GET {NXMirror}/cloud/plugins with the signed-in account token
 * (the encrypted app session, or the guest CLI token file as a fallback
 * wired by the caller). Items are validated field by field (the response
 * is untrusted), deduplicated per plugin name keeping the newest publish,
 * cached like the registry (atomic tmp + rename, throttled refresh, and
 * the last-good cache when offline).
 *
 * Install: the artifact (.noxs-plugin — gzipped tar) is downloaded once,
 * verified against the published sha256, and the embedded plugin.json is
 * parsed in memory (TarReader — regular files only). The authoritative
 * metadata (id, version, permissions, SDK requirements) must match the
 * cloud item or the install is refused; the entry handed to the installer
 * is built from the parsed plugin.json, so the store card (which carries
 * only what the cloud list published) can never widen a plugin's powers.
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.shared.MiniJson
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.TarReader
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

class NoxsPluginCloudCatalog(
    private val cacheDir: File,
    private val tokenProvider: () -> String?,
    private val fetcher: HeaderFetcher = HttpsHeaderFetcher(),
    private val minRefreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS
) {

    class CloudException(message: String) : Exception(message)

    /** Download boundary — tests inject a fake. Headers carry the auth token. */
    fun interface HeaderFetcher {
        fun get(url: String, headers: Map<String, String>, timeoutMs: Int): ByteArray
    }

    /** One validated `cloud/plugins` item (fields mirror the NXMirror API). */
    data class CloudItem(
        val name: String,
        val version: String,
        val sha256: String,
        val downloadUrl: String,
        val owner: String?,
        val publishedAt: String?,
        val size: Long
    )

    /** Details-page content extracted from a verified .noxs-plugin artifact. */
    class CloudArtifact(
        val item: CloudItem,
        val meta: PluginMeta,
        val readme: String?,
        val iconSvg: ByteArray?
    )

    private val cacheFile = File(cacheDir, "cloud-items.json")
    private val fetchStamp = File(cacheDir, ".cloud-last-fetch")
    private val lock = Any()

    /** True when the last listing refresh hit the network successfully. */
    var lastRefreshOk: Boolean = false
        private set

    // ------------------------------------------------------------- listing

    /**
     * Cloud items: fresh when possible, last-good cache otherwise. Refreshes
     * are throttled like the registry; without a signed-in session the cache
     * is served and the store simply shows no cloud plugins.
     */
    fun items(refresh: Boolean = true, force: Boolean = false): List<CloudItem> = synchronized(lock) {
        if (refresh) {
            val throttled = !force &&
                runCatching { fetchStamp.lastModified() }.getOrDefault(0L) >
                    System.currentTimeMillis() - minRefreshIntervalMs
            if (!throttled) {
                runCatching { refreshLocked() }
                    .onFailure { NoxsLog.w(TAG, "cloud refresh failed: ${it.javaClass.simpleName}") }
            } else {
                lastRefreshOk = true // served from the fresh-enough cache
            }
        }
        readCache()
    }

    fun item(id: String): CloudItem? = items(refresh = false).firstOrNull { it.name == id }

    fun entry(id: String): RegistryEntry? = item(id)?.let { cardEntry(it) }

    /** Cloud entries for the store list; invalid items are dropped silently. */
    fun entries(refresh: Boolean = true, force: Boolean = false): List<RegistryEntry> =
        items(refresh, force).mapNotNull { cardEntry(it) }

    /** Adopts a cloud listing payload into the cache (atomic, validates first). */
    @Synchronized
    fun adopt(text: String) {
        val items = parseItems(text)
        cacheDir.mkdirs()
        val tmp = File(cacheDir, ".cloud-items.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(cacheFile)) cacheFile.writeText(text, Charsets.UTF_8)
        cacheDir.mkdirs()
        fetchStamp.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        lastRefreshOk = true
        // keep deduplicated items reachable through item()/entry()
        adoptCache = items
    }

    private var adoptCache: List<CloudItem> = emptyList()

    private fun readCache(): List<CloudItem> {
        if (adoptCache.isNotEmpty()) return adoptCache
        if (!cacheFile.isFile) return emptyList()
        return runCatching { parseItems(cacheFile.readText(Charsets.UTF_8)) }
            .getOrElse { emptyList() }
    }

    private fun refreshLocked() {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw CloudException("No NXMirror session")
        val bytes = fetcher.get(
            "$API_BASE/cloud/plugins",
            mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $token"
            ),
            FETCH_TIMEOUT_MS
        )
        if (bytes.isEmpty() || bytes.size > MAX_LIST_BYTES) {
            throw CloudException("cloud list payload invalid")
        }
        val text = String(bytes, Charsets.UTF_8)
        val items = parseItems(text) // reject broken payloads before caching
        cacheDir.mkdirs()
        val tmp = File(cacheDir, ".cloud-items.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(cacheFile)) cacheFile.writeText(text, Charsets.UTF_8)
        fetchStamp.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        lastRefreshOk = true
        adoptCache = items
    }

    // -------------------------------------------------------------- mapping

    /**
     * The store-card entry for one cloud item: only what the listing proves.
     * The artifact's plugin.json supplies the rest at details/install time.
     */
    fun cardEntry(item: CloudItem): RegistryEntry? {
        if (!PluginJson.validId(item.name)) return null
        if (PluginSemver.parse(item.version) == null) return null
        if (!item.downloadUrl.startsWith("https://")) return null
        if (!Regex("^[0-9a-fA-F]{64}$").matches(item.sha256)) return null
        return RegistryEntry(
            id = item.name,
            name = item.name,
            version = item.version,
            description = "",
            logo = null,
            readme = null,
            logoUrl = null,
            readmeUrl = null,
            release = null,
            permissions = emptyList(),
            category = null,
            keywords = emptyList(),
            minimumNoxsVersion = null,
            artifact = item.downloadUrl,
            checksum = item.sha256.lowercase(),
            releaseTag = null,
            updatedAt = item.publishedAt,
            author = item.owner
        )
    }

    /**
     * The install-time entry: authoritative plugin.json fields win, the cloud
     * item contributes the artifact URL, published checksum, owner and date.
     */
    fun authoritativeEntry(item: CloudItem, meta: PluginMeta): RegistryEntry = RegistryEntry(
        id = meta.id,
        name = meta.name.ifBlank { item.name },
        version = meta.version,
        description = meta.description,
        logo = meta.logo,
        readme = meta.readme,
        logoUrl = null,
        readmeUrl = null,
        release = null,
        permissions = meta.permissions,
        category = meta.category,
        keywords = meta.keywords,
        minimumNoxsVersion = meta.minimumNoxsVersion,
        artifact = item.downloadUrl,
        checksum = item.sha256.lowercase(),
        releaseTag = null,
        updatedAt = item.publishedAt,
        author = item.owner ?: meta.author,
        license = meta.license,
        repository = meta.repository,
        commands = meta.commands,
        sdkVersion = meta.sdkVersion,
        minimumSdkVersion = meta.minimumSdkVersion,
        maximumSdkVersion = meta.maximumSdkVersion,
        apiFeatures = meta.apiFeatures
    )

    // ------------------------------------------------------------- install

    /**
     * Downloads the artifact once, verifies the published sha256 and parses
     * the embedded plugin.json; returns the authoritative entry plus the
     * artifact bytes for the installer (no second download).
     */
    fun resolveInstallable(id: String): Pair<RegistryEntry, ByteArray> {
        val cloud = item(id) ?: throw CloudException("Unknown cloud plugin")
        val bytes = verifiedArtifact(cloud)
        val meta = artifactMeta(bytes)
            ?: throw CloudException("The plugin package has no valid plugin.json")
        if (meta.id != cloud.name || meta.version != cloud.version) {
            throw CloudException("The plugin package does not match its cloud entry")
        }
        if (PluginValidation.validate(meta).isNotEmpty()) {
            throw CloudException("The plugin package has invalid metadata")
        }
        cacheArtifactExtras(cloud, meta, bytes)
        return authoritativeEntry(cloud, meta) to bytes
    }

    /**
     * Details-page content: verified artifact parsed into meta + README.md +
     * icon.svg. Small files are cached on disk keyed by the artifact sha256,
     * so revisiting a plugin (or opening its details after installing) never
     * re-downloads the package.
     */
    fun inspect(id: String): CloudArtifact {
        val cloud = item(id) ?: throw CloudException("Unknown cloud plugin")
        readCachedArtifact(cloud)?.let { return it }
        val bytes = verifiedArtifact(cloud)
        val meta = artifactMeta(bytes)
            ?: throw CloudException("The plugin package has no valid plugin.json")
        if (meta.id != cloud.name || meta.version != cloud.version) {
            throw CloudException("The plugin package does not match its cloud entry")
        }
        if (PluginValidation.validate(meta).isNotEmpty()) {
            throw CloudException("The plugin package has invalid metadata")
        }
        val readme = extractEntryText(bytes, meta.readme?.takeIf { it.isNotBlank() } ?: "README.md", MAX_README_BYTES)
        val iconSvg = extractEntryBytes(bytes, "icon.svg", MAX_ICON_BYTES)
        writeCachedArtifact(cloud, meta, readme, iconSvg)
        return CloudArtifact(cloud, meta, readme, iconSvg)
    }

    /**
     * The authoritative entry from the on-disk artifact cache only — no
     * network. Used to enrich store cards once a plugin was inspected or
     * installed; returns null when nothing verified is cached for the id.
     */
    fun cachedAuthoritativeEntry(id: String): RegistryEntry? {
        val cloud = item(id) ?: return null
        val artifact = readCachedArtifact(cloud) ?: return null
        if (artifact.meta.id != cloud.name || artifact.meta.version != cloud.version) return null
        if (PluginValidation.validate(artifact.meta).isNotEmpty()) return null
        return authoritativeEntry(cloud, artifact.meta)
    }

    // ------------------------------------------------------------ internals

    /** Downloads and sha256-verifies one artifact. */
    private fun verifiedArtifact(cloud: CloudItem): ByteArray = try {
        val bytes = fetcher.get(cloud.downloadUrl, emptyMap(), ARTIFACT_TIMEOUT_MS)
        if (bytes.isEmpty() || bytes.size > MAX_ARTIFACT_BYTES) {
            throw CloudException("The plugin package has an invalid size")
        }
        if (PluginChecksum.sha256(bytes) != cloud.sha256.lowercase()) {
            throw CloudException("The plugin package failed its integrity check")
        }
        bytes
    } catch (e: CloudException) {
        throw e
    } catch (e: Exception) {
        NoxsLog.w(TAG, "artifact fetch failed: ${e.javaClass.simpleName}")
        throw CloudException("The plugin package could not be downloaded")
    }

    /**
     * In-memory plugin.json extraction (regular files only, entry-count and
     * size bounded). Returns null when the archive carries no plugin.json.
     */
    private fun artifactMeta(bytes: ByteArray): PluginMeta? = tarFile(bytes, "plugin.json", MAX_META_BYTES)
        ?.let { runCatching { PluginJson.parseMeta(it) }.getOrNull() }

    /** Bounded single-entry extraction shared by meta/readme/icon readers. */
    private fun tarFile(bytes: ByteArray, name: String, maxBytes: Long): String? = try {
        var found: String? = null
        var count = 0
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
            TarReader(gzip).readAll { entry, stream ->
                count++
                if (count > MAX_ARCHIVE_ENTRIES) {
                    throw CloudException("The plugin package has too many entries")
                }
                when (entry.type) {
                    TarReader.Type.DIR -> Unit
                    TarReader.Type.REGULAR ->
                        if (entry.name == name && found == null && entry.size in 1..maxBytes) {
                            found = String(
                                readBounded(stream, entry.size, maxBytes)
                                    ?: throw CloudException("The plugin package has invalid metadata"),
                                Charsets.UTF_8
                            )
                        }
                    else -> throw CloudException("The plugin package contains unsupported entries")
                }
            }
        }
        found
    } catch (e: CloudException) {
        throw e
    } catch (e: Exception) {
        NoxsLog.w(TAG, "artifact parse failed: ${e.javaClass.simpleName}")
        throw CloudException("The plugin package could not be unpacked")
    }

    private fun extractEntryText(bytes: ByteArray, name: String, maxBytes: Long): String? =
        runCatching { tarFile(bytes, name, maxBytes) }.getOrNull()

    private fun extractEntryBytes(bytes: ByteArray, name: String, maxBytes: Long): ByteArray? =
        extractEntryText(bytes, name, maxBytes)?.toByteArray(Charsets.UTF_8)

    /** Extracts the small docs files into the sha256-keyed artifact cache. */
    private fun cacheArtifactExtras(cloud: CloudItem, meta: PluginMeta, bytes: ByteArray) {
        runCatching {
            val readme = extractEntryText(bytes, meta.readme?.takeIf { it.isNotBlank() } ?: "README.md", MAX_README_BYTES)
            val icon = extractEntryText(bytes, "icon.svg", MAX_ICON_BYTES)
            writeCachedArtifact(cloud, meta, readme, icon)
        }
    }

    /**
     * Parses a cloud listing payload: `{"success":…,"items":[…]}`. Invalid
     * items are dropped; one valid item per plugin name survives (newest
     * publish wins by publishedAt, ISO-8601 sorts lexicographically).
     */
    private fun parseItems(text: String): List<CloudItem> {
        val root = try {
            (MiniJson.parse(text) as? Map<*, *>)?.mapKeys { it.key.toString() }
                ?: throw CloudException("cloud list payload invalid")
        } catch (e: MiniJson.JsonException) {
            throw CloudException("cloud list payload invalid")
        }
        if (root["success"] != true) {
            throw CloudException(
                (root["error"] as? String)?.takeIf { it.isNotBlank() } ?: "cloud list rejected"
            )
        }
        val raw = (root["items"] as? List<*>).orEmpty()
        val items = raw.mapNotNull { element ->
            val map = (element as? Map<*, *>)?.mapKeys { it.key.toString() } ?: return@mapNotNull null
            if ((map["type"] as? String ?: "plugin") != "plugin") return@mapNotNull null
            val name = map["name"] as? String ?: return@mapNotNull null
            val version = map["version"] as? String ?: return@mapNotNull null
            val sha256 = map["sha256"] as? String ?: return@mapNotNull null
            val downloadUrl = map["downloadUrl"] as? String ?: return@mapNotNull null
            CloudItem(
                name = name.trim(),
                version = version.trim(),
                sha256 = sha256.trim().lowercase(),
                downloadUrl = downloadUrl.trim(),
                owner = (map["owner"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                publishedAt = (map["publishedAt"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                size = (map["size"] as? Number)?.toLong() ?: 0L
            )
        }.filter {
            PluginJson.validId(it.name) &&
                PluginSemver.parse(it.version) != null &&
                it.downloadUrl.startsWith("https://") &&
                Regex("^[0-9a-f]{64}$").matches(it.sha256) &&
                it.size in 1..MAX_ARTIFACT_BYTES
        }
        return items
            .groupBy { it.name }
            .map { (_, group) -> group.maxByOrNull { it.publishedAt ?: "" }!! }
    }

    // ------------------------------------------------- small-file disk cache

    private fun artifactCacheDir(cloud: CloudItem): File = File(File(cacheDir, "cloud"), cloud.sha256)

    private fun readCachedArtifact(cloud: CloudItem): CloudArtifact? {
        val dir = artifactCacheDir(cloud)
        val metaFile = File(dir, "plugin.json")
        if (!metaFile.isFile) return null
        val meta = runCatching { PluginJson.parseMetaFile(metaFile) }.getOrNull() ?: return null
        val readmeFile = File(dir, "readme.md")
        val iconFile = File(dir, "icon.svg")
        return CloudArtifact(
            cloud, meta,
            readmeFile.takeIf { it.isFile }?.runCatching { readText(Charsets.UTF_8) }.getOrNull(),
            iconFile.takeIf { it.isFile }?.runCatching { readBytes() }.getOrNull()
        )
    }

    private fun writeCachedArtifact(cloud: CloudItem, meta: PluginMeta, readme: String?, iconSvg: ByteArray?) {
        runCatching {
            val dir = artifactCacheDir(cloud)
            dir.mkdirs()
            File(dir, "plugin.json").writeText(
                PluginJsonMetaWriter.write(meta), Charsets.UTF_8
            )
            if (readme != null) File(dir, "readme.md").writeText(readme, Charsets.UTF_8)
            iconSvg?.let { File(dir, "icon.svg").writeBytes(it) }
        }
    }

    private object PluginJsonMetaWriter {
        /** Minimal JSON string escape for the cached meta round-trip. */
        private fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        private fun quoted(value: String): String = "\"${escape(value)}\""

        fun write(meta: PluginMeta): String = buildString {
            append("{")
            append("\"id\":${quoted(meta.id)},")
            append("\"name\":${quoted(meta.name)},")
            append("\"version\":${quoted(meta.version)},")
            append("\"description\":${quoted(meta.description)},")
            append("\"main\":${quoted(meta.main)},")
            append("\"permissions\":[${meta.permissions.joinToString(",") { quoted(it) }}],")
            append("\"minimumNoxsVersion\":${quoted(meta.minimumNoxsVersion)},")
            meta.logo?.let { append("\"logo\":${quoted(it)},") }
            meta.readme?.let { append("\"readme\":${quoted(it)},") }
            meta.author?.let { append("\"author\":${quoted(it)},") }
            meta.license?.let { append("\"license\":${quoted(it)},") }
            meta.category?.let { append("\"category\":${quoted(it)},") }
            append("\"commands\":[${meta.commands.joinToString(",") { quoted(it) }}],")
            append("\"keywords\":[${meta.keywords.joinToString(",") { quoted(it) }}],")
            meta.homepage?.let { append("\"homepage\":${quoted(it)},") }
            meta.repository?.let { append("\"repository\":${quoted(it)},") }
            append("\"sdkVersion\":${quoted(meta.sdkVersion)}")
            meta.minimumSdkVersion?.let { append(",\"minimumSdkVersion\":${quoted(it)}") }
            meta.maximumSdkVersion?.let { append(",\"maximumSdkVersion\":${quoted(it)}") }
            if (meta.apiFeatures.isNotEmpty()) {
                append(",\"apiFeatures\":[${meta.apiFeatures.joinToString(",") { quoted(it) }}]")
            }
            append("}")
        }
    }

    /** Reads at most [max] bytes from [stream]; null when the entry is bigger. */
    private fun readBounded(stream: java.io.InputStream, size: Long, max: Long): ByteArray? {
        if (size > max) return null
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "PluginCloudCatalog"
        const val API_BASE = "https://nxmirror.netlify.app/api"
        const val FETCH_TIMEOUT_MS = 15_000
        const val ARTIFACT_TIMEOUT_MS = 60_000
        const val MAX_LIST_BYTES = 2L * 1024L * 1024L
        const val MAX_ARTIFACT_BYTES = NoxsPluginInstaller.MAX_ARTIFACT_BYTES
        const val MAX_META_BYTES = 64L * 1024L
        const val MAX_README_BYTES = 2L * 1024L * 1024L
        const val MAX_ICON_BYTES = 256L * 1024L
        const val MAX_ARCHIVE_ENTRIES = 512
        const val DEFAULT_REFRESH_INTERVAL_MS = 5L * 60L * 1000L
    }
}

/** Default cloud fetcher over HTTPS (delegates to the shared bounded client). */
class HttpsHeaderFetcher(
    private val timeoutMs: Int = NoxsPluginCloudCatalog.FETCH_TIMEOUT_MS
) : NoxsPluginCloudCatalog.HeaderFetcher {
    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): ByteArray =
        // Bound at the largest legitimate payload (an artifact); the callers
        // apply their own tighter limits for listings and small files.
        PluginHttp.get(url, timeoutMs, NoxsPluginCloudCatalog.MAX_ARTIFACT_BYTES, headers)
}
