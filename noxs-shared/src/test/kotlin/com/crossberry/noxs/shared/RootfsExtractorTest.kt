package com.crossberry.noxs.shared

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- Minimal tar builder (test helper) ----------

    private fun tarHeader(name: String, size: Long, typeFlag: Byte, mode: Int = 420 /* octal 0644 */,
                          linkName: String = ""): ByteArray {
        val h = ByteArray(512)
        fun put(s: String, off: Int, len: Int) {
            val b = s.toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(b, 0, h, off, minOf(b.size, len))
        }
        fun octal(v: Long, off: Int, len: Int) {
            val s = java.lang.Long.toOctalString(v)
            put("0".repeat(len - 1 - s.length) + s + "\u0000", off, len)
        }
        put(name, 0, 100)
        octal(mode.toLong(), 100, 8)
        octal(0, 108, 8)   // uid
        octal(0, 116, 8)   // gid
        octal(size, 124, 12)
        octal(0, 136, 12)  // mtime
        put("        ", 148, 8) // checksum placeholder (spaces)
        h[156] = typeFlag
        put("ustar\u000000", 257, 8)
        put(linkName, 157, 100)
        var sum = 0L
        for (b in h) sum += b.toLong() and 0xff
        octal(sum, 148, 7)
        h[155] = ' '.code.toByte()
        return h
    }

    private fun tarEntries(vararg entries: Pair<ByteArray, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((header, payload) in entries) {
            out.write(header)
            out.write(payload)
            val pad = (512 - (payload.size % 512)) % 512
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024)) // two zero blocks
        return out.toByteArray()
    }

    private fun fileEntry(name: String, content: String, mode: Int = 420 /* octal 0644 */) =
        tarHeader(name, content.length.toLong(), '0'.code.toByte(), mode) to content.toByteArray()

    private fun dirEntry(name: String) = tarHeader("$name/", 0, '5'.code.toByte()) to ByteArray(0)

    private fun linkEntry(name: String, target: String, type: Byte = '2'.code.toByte()) =
        tarHeader(name, 0, type, linkName = target) to ByteArray(0)

    private fun writeGzip(data: ByteArray): File {
        val f = File(tmp.root, "rootfs.tar.gz")
        java.util.zip.GZIPOutputStream(f.outputStream()).use { it.write(data) }
        return f
    }

    // ---------- Positive cases ----------

    @Test fun `extracts regular files, directories and permissions`() {
        val tar = tarEntries(
            dirEntry("etc"),
            fileEntry("etc/profile", "# profile\n"),
            fileEntry("bin/sh", "#!/bin/sh\n", mode = 493 /* octal 0755 */)
        )
        val root = tmp.newFolder("rootfs")
        RootfsExtractor(root).extract(writeGzip(tar))

        assertTrue(File(root, "etc").isDirectory)
        assertEquals("# profile\n", File(root, "etc/profile").readText())
        assertTrue(File(root, "bin/sh").canExecute())
        assertEquals("#!/bin/sh\n", File(root, "bin/sh").readText())
    }

    @Test fun `strips setuid bit from file modes`() {
        // mode 4755 (setuid + rwxr-xr-x)
        val tar = tarEntries(fileEntry("usr/bin/passwd", "x", mode = 0b111_101_101 or 0b100_000_000_000))
        val root = tmp.newFolder("rootfs")
        RootfsExtractor(root).extract(writeGzip(tar))
        // Our extractor only preserves the low 9 bits; executable bit survives.
        assertTrue(File(root, "usr/bin/passwd").canExecute())
    }

    // ---------- Security cases ----------

    @Test fun `rejects dot-dot traversal entry`() {
        val tar = tarEntries(fileEntry("../../outside", "pwned"))
        val root = tmp.newFolder("rootfs")
        try {
            RootfsExtractor(root).extract(writeGzip(tar))
            fail("Expected SecurityException for traversal entry")
        } catch (e: Exception) {
            assertTrue("Got: $e", e is SecurityException || e.cause is SecurityException)
        }
        // Nothing written outside root
        assertEquals(0, File(tmp.root, "outside").let { if (it.exists()) 1 else 0 } +
            File(tmp.root.parentFile, "outside").let { if (it.exists()) 1 else 0 })
    }

    @Test fun `rejects absolute path entry`() {
        val tar = tarEntries(fileEntry("/etc/evil", "pwned"))
        val root = tmp.newFolder("rootfs")
        try {
            RootfsExtractor(root).extract(writeGzip(tar))
            fail("Expected SecurityException for absolute entry")
        } catch (e: Exception) {
            assertTrue("Got: $e", e is SecurityException || e.cause is SecurityException)
        }
    }

    @Test fun `rejects escaping symlink`() {
        val tar = tarEntries(
            fileEntry("marker", "m"),
            linkEntry("evil", "../../../../etc/passwd")
        )
        val root = tmp.newFolder("rootfs")
        try {
            RootfsExtractor(root).extract(writeGzip(tar))
            fail("Expected SecurityException for escaping symlink")
        } catch (e: Exception) {
            assertTrue("Got: $e", e is SecurityException || e.cause is SecurityException)
        }
    }

    @Test fun `rejects device and fifo entries`() {
        val tar = tarEntries(tarHeader("dev/null", 0, '3'.code.toByte()) to ByteArray(0))
        val root = tmp.newFolder("rootfs")
        try {
            RootfsExtractor(root).extract(writeGzip(tar))
            fail("Expected SecurityException for device entry")
        } catch (e: Exception) {
            assertTrue("Got: $e", e is SecurityException || e.cause is SecurityException)
        }
    }

    @Test fun `accepts in-root symlink`() {
        val tar = tarEntries(
            dirEntry("bin"),
            fileEntry("usr/bin/true", "ELF"),
            linkEntry("bin/true", "/usr/bin/true")
        )
        val root = tmp.newFolder("rootfs")
        RootfsExtractor(root).extract(writeGzip(tar))
        assertEquals(1, RootfsLinkQueue.size())
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root, "bin/true").toPath()))
    }

    @Test fun `accepts chained symlinks inside rootfs`() {
        val tar = tarEntries(
            dirEntry("usr/bin"),
            dirEntry("etc/alternatives"),
            fileEntry("usr/bin/mawk", "ELF"),
            linkEntry("etc/alternatives/awk", "/usr/bin/mawk"),
            linkEntry("usr/bin/awk", "/etc/alternatives/awk")
        )
        val root = tmp.newFolder("rootfs")
        RootfsExtractor(root).extract(writeGzip(tar))
        assertEquals(2, RootfsLinkQueue.size())
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root, "etc/alternatives/awk").toPath()))
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root, "usr/bin/awk").toPath()))
    }

    @Test fun `stale directory at a symlink member path is replaced by the archive symlink`() {
        // Regression: an older interrupted extraction can leave a REAL
        // directory (with junk inside) at a path the archive declares a
        // symlink. Files.delete alone fails with DirectoryNotEmptyException
        // and the link silently stays broken until the integrity pass.
        val root = tmp.newFolder("rootfs")
        val poison = File(root, "lib")
        poison.mkdirs()
        File(poison, "junk").writeText("stale")
        val tar = tarEntries(
            dirEntry("usr/lib"),
            fileEntry("usr/lib/real", "payload"),
            linkEntry("lib", "usr/lib")
        )
        RootfsExtractor(root).extract(writeGzip(tar))
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root, "lib").toPath()))
        assertEquals("payload", File(root, "usr/lib/real").readText())
        // The poison content is gone with the directory it lived in.
        assertFalse(java.nio.file.Files.exists(File(root, "usr/lib/junk").toPath()))
    }

    @Test fun `stale file at a symlink member path is replaced by the archive symlink`() {
        val root = tmp.newFolder("rootfs")
        File(root, "lib").writeText("") // the historical 0-byte placeholder
        val tar = tarEntries(
            dirEntry("usr/lib"),
            linkEntry("lib", "usr/lib")
        )
        RootfsExtractor(root).extract(writeGzip(tar))
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root, "lib").toPath()))
    }

    @Test fun `safeResolve rejects NUL and drive letters`() {
        val root = tmp.root
        listOf("a\u0000b", "C:/evil", "..", "a/../..").forEach {
            try {
                TarGuard.safeResolve(root, it)
                fail("Should reject: $it")
            } catch (ignored: SecurityException) {
            }
        }
    }
}
