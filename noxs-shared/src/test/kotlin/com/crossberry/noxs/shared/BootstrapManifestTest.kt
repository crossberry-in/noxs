package com.crossberry.noxs.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapManifestTest {

    private val valid = """
    {
      "schema": 1,
      "arch": "arm64-v8a",
      "rootfs": {
        "url": "https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64v8/bookworm/rootfs.tar.xz",
        "sha256": "",
        "format": "tar.xz",
        "sizeBytes": 0
      },
      "proot": {
        "url": "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-aarch64-static",
        "sha256": ""
      },
      "generatedAt": "2026-01-01T00:00:00Z",
      "source": "scripts/build-rootfs.sh"
    }
    """.trimIndent()

    @Test fun `parses valid unpinned manifest`() {
        val m = BootstrapManifest.parse(valid)
        assertEquals("arm64-v8a", m.arch)
        assertEquals("tar.xz", m.rootfs.format)
        assertFalse(m.isPinned)
        assertFalse(m.rootfs.isPinned)
    }

    @Test fun `parses pinned manifest`() {
        val m = BootstrapManifest.parse(valid.replace("\"sha256\": \"\"", "\"sha256\": \"ABCDEF01\""))
        assertTrue(m.rootfs.isPinned)
        assertEquals("abcdef01", m.rootfs.sha256) // normalised to lowercase
    }

    @Test fun `parses manifest without proot section`() {
        val json = """
        {
          "schema": 1,
          "arch": "arm64-v8a",
          "rootfs": {
            "url": "https://example.test/rootfs.tar.gz",
            "sha256": "450fe15cad1eddfa7c19e4191f4de2d5c46b0c201ddee1db8d6f41d2fec7a742",
            "format": "tar.gz",
            "sizeBytes": 48389910
          }
        }
        """.trimIndent()
        val m = BootstrapManifest.parse(json)
        assertEquals(null, m.proot)
        assertTrue(m.isPinned)
        assertEquals("tar.gz", m.rootfs.format)
        assertEquals("", m.offlineBundledAsset)
    }

    @Test fun `parses offline bundled asset`() {
        val json = """
        {
          "schema": 1,
          "arch": "arm64-v8a",
          "rootfs": {
            "url": "https://example.test/rootfs.tar.gz",
            "sha256": "",
            "format": "tar.gz",
            "sizeBytes": 199725282
          },
          "offline": {
            "bundledAsset": "bootstrap/arm64-v8a/rootfs.bin",
            "packages": ["nano", "curl"]
          }
        }
        """.trimIndent()
        val m = BootstrapManifest.parse(json)
        assertEquals("bootstrap/arm64-v8a/rootfs.bin", m.offlineBundledAsset)
    }

    @Test fun `rejects http urls`() {
        val bad = valid.replace("https://github.com/debuerreotype", "http://insecure.example")
        try {
            BootstrapManifest.parse(bad)
            assertFalse("Expected rejection of http:// url", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test fun `rejects unsupported schema and arch`() {
        try { BootstrapManifest.parse(valid.replace("\"schema\": 1", "\"schema\": 9")); assertFalse(true) }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("schema")) }
        try { BootstrapManifest.parse(valid.replace("arm64-v8a", "mips")); assertFalse(true) }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("arch")) }
    }

    @Test fun `rejects malformed json`() {
        try { BootstrapManifest.parse("{ not json"); assertFalse(true) }
        catch (e: IllegalArgumentException) { /* expected */ }
    }
}

class MiniJsonTest {

    @Test fun `parses nested structures`() {
        val v = MiniJson.parse("""{"a":[1,2,{"b":null,"c":true,"d":"x"}],"e":-5.5,"f":"é\n"}""")
        val map = v as Map<*, *>
        val arr = map["a"] as List<*>
        assertEquals(3, arr.size)
        assertEquals(null, (arr[2] as Map<*, *>)["b"])
        assertEquals(true, (arr[2] as Map<*, *>)["c"])
        assertEquals(-5.5, (map["e"] as Double), 1e-9)
        assertEquals("é\n", map["f"])
    }

    @Test fun `rejects trailing garbage and bad escapes`() {
        listOf("""{} x""", """"\q"""", """[1,""").forEach {
            try { MiniJson.parse(it); assertFalse(true) } catch (ignored: MiniJson.JsonException) {}
        }
    }
}
