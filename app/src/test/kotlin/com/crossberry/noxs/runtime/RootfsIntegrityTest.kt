/*
 * Noxs — original implementation.
 * JVM tests for the rootfs integrity verifier + targeted repair
 * (the fix for the "No such file or directory" system-executable reports:
 * /usr/bin/docker, /usr/local/bin/sudo, /usr/bin/clear).
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RootfsIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("rootfs")

    /** Builds a structurally healthy rootfs that SHIPS /lib64 (the tolerant
     *  usr-merge variant; files + symlinks; no apt layer). */
    private fun healthyRootfs(root: File) {
        File(root, "usr/bin").mkdirs()
        File(root, "usr/sbin").mkdirs()
        File(root, "usr/lib/aarch64-linux-gnu").mkdirs()
        File(root, "usr/lib64").mkdirs()
        File(root, "usr/local/bin").mkdirs()
        File(root, "usr/bin/dash").writeText("dash")
        Files.createSymbolicLink(File(root, "usr/bin/sh").toPath(), java.nio.file.Paths.get("dash"))
        File(root, "usr/bin/bash").writeText("bash")
        File(root, "usr/bin/env").writeText("env")
        File(root, "usr/bin/clear").writeText("clear")
        File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").writeText("loader")
        Files.createSymbolicLink(File(root, "bin").toPath(), java.nio.file.Paths.get("usr/bin"))
        Files.createSymbolicLink(File(root, "sbin").toPath(), java.nio.file.Paths.get("usr/sbin"))
        Files.createSymbolicLink(File(root, "lib").toPath(), java.nio.file.Paths.get("usr/lib"))
        Files.createSymbolicLink(File(root, "lib64").toPath(), java.nio.file.Paths.get("usr/lib64"))
        Files.createSymbolicLink(
            File(root, "usr/lib/ld-linux-aarch64.so.1").toPath(),
            java.nio.file.Paths.get("aarch64-linux-gnu/ld-linux-aarch64.so.1")
        )
        Files.createSymbolicLink(
            File(root, "usr/lib64/ld-linux-aarch64.so.1").toPath(),
            java.nio.file.Paths.get("../lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        )
        File(root, "usr/local/bin/sudo").writeText("#!/bin/sh\nexec \"$@\"\n")
        File(root, "usr/local/bin/sudo").setExecutable(true)
        File(root, "usr/bin/su").writeText("#!/bin/sh\n")
        File(root, "usr/bin/su").setExecutable(true)
    }

    /**
     * Builds the GENUINE Ubuntu 24.04 arm64 base layout — exactly what the
     * release tarball ships (verified: `tar -tzf` + PT_INTERP of ./usr/bin/ls):
     * /bin /sbin /lib usr-merge symlinks, the /lib/ld-linux-aarch64.so.1
     * interpreter chain, and NO /lib64 or /usr/lib64 anywhere. The v1.4.0
     * gate regression failed setup on exactly this layout.
     */
    private fun arm64BaseRootfs(root: File) {
        File(root, "usr/bin").mkdirs()
        File(root, "usr/sbin").mkdirs()
        File(root, "usr/lib/aarch64-linux-gnu").mkdirs()
        File(root, "usr/local/bin").mkdirs()
        File(root, "usr/bin/dash").writeText("dash")
        Files.createSymbolicLink(File(root, "usr/bin/sh").toPath(), java.nio.file.Paths.get("dash"))
        File(root, "usr/bin/bash").writeText("bash")
        File(root, "usr/bin/env").writeText("env")
        File(root, "usr/bin/clear").writeText("clear")
        File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").writeText("loader")
        Files.createSymbolicLink(File(root, "bin").toPath(), java.nio.file.Paths.get("usr/bin"))
        Files.createSymbolicLink(File(root, "sbin").toPath(), java.nio.file.Paths.get("usr/sbin"))
        Files.createSymbolicLink(File(root, "lib").toPath(), java.nio.file.Paths.get("usr/lib"))
        Files.createSymbolicLink(
            File(root, "usr/lib/ld-linux-aarch64.so.1").toPath(),
            java.nio.file.Paths.get("aarch64-linux-gnu/ld-linux-aarch64.so.1")
        )
        File(root, "usr/local/bin/sudo").writeText("#!/bin/sh\nexec \"\$@\"\n")
        File(root, "usr/local/bin/sudo").setExecutable(true)
        File(root, "usr/bin/su").writeText("#!/bin/sh\n")
        File(root, "usr/bin/su").setExecutable(true)
    }

    @Test
    fun `genuine arm64 base image without lib64 verifies clean`() {
        val root = root()
        arm64BaseRootfs(root)
        val report = RootfsIntegrity.verify(root)
        assertTrue(report.healthy)
        assertTrue(report.structuralDamage.isEmpty())
        assertTrue(report.missingExecutables.isEmpty())
        assertTrue(report.missingShims.isEmpty())
        // Regression for the v1.4.0 setup blocker: absence of /lib64 is the
        // correct arm64 layout and must never be reported as damage.
        assertTrue(report.rows.none { !it.ok && it.path == "/lib64" })
        assertTrue(report.rows.first { it.path == "/lib64" }.ok)
        assertTrue(report.rows.first { it.path == "/lib/ld-linux-aarch64.so.1" }.ok)
    }

    @Test
    fun `arm64 base with stale placeholder is structural and repair heals it`() {
        val root = root()
        arm64BaseRootfs(root)
        Files.delete(File(root, "bin").toPath())
        File(root, "bin").writeText("") // historical extraction poison
        val report = RootfsIntegrity.verify(root)
        assertFalse(report.healthy)
        assertTrue(report.structuralDamage.contains("/bin"))
        val applied = RootfsIntegrity.repair(root)
        assertTrue(applied.any { it.contains("/bin") })
        assertTrue(RootfsIntegrity.verify(root).healthy)
    }

    @Test
    fun `arm64 interpreter path break is structural damage`() {
        val root = root()
        arm64BaseRootfs(root)
        Files.delete(File(root, "usr/lib/ld-linux-aarch64.so.1").toPath())
        val report = RootfsIntegrity.verify(root)
        assertFalse(report.healthy)
        // Both the loader link and the PT_INTERP path every arm64 ELF uses.
        assertTrue(report.structuralDamage.any { it == "/usr/lib/ld-linux-aarch64.so.1" })
        assertTrue(report.structuralDamage.any { it == "/lib/ld-linux-aarch64.so.1" })
    }

    @Test
    fun `healthy rootfs verifies clean`() {
        val root = root()
        healthyRootfs(root)
        val report = RootfsIntegrity.verify(root)
        assertTrue(report.healthy)
        assertTrue(report.structuralDamage.isEmpty())
        assertTrue(report.missingExecutables.isEmpty())
        assertTrue(report.missingShims.isEmpty())
        assertTrue(report.rows.isNotEmpty())
        assertTrue(report.rows.all { it.ok })
    }

    @Test
    fun `zero-byte lib64 placeholder is detected as structural damage`() {
        val root = root()
        healthyRootfs(root)
        Files.delete(File(root, "lib64").toPath())
        File(root, "lib64").writeText("") // the historical extraction poison
        val report = RootfsIntegrity.verify(root)
        assertFalse(report.healthy)
        assertTrue(report.structuralDamage.any { it == "/lib64" })
        assertTrue(report.rows.any { !it.ok && it.path == "/lib64" && it.detail.contains("0-byte") })
    }

    @Test
    fun `missing loader link with real loader present is structural damage`() {
        val root = root()
        healthyRootfs(root)
        Files.delete(File(root, "usr/lib/ld-linux-aarch64.so.1").toPath())
        val report = RootfsIntegrity.verify(root)
        assertFalse(report.healthy)
        assertTrue(report.structuralDamage.any { it == "/usr/lib/ld-linux-aarch64.so.1" })
        assertTrue(report.rows.first { it.path == "/usr/lib/ld-linux-aarch64.so.1" }.detail.contains("repairable"))
    }

    @Test
    fun `missing clear and sudo are classified separately`() {
        val root = root()
        healthyRootfs(root)
        File(root, "usr/bin/clear").delete()
        File(root, "usr/local/bin/sudo").delete()
        val report = RootfsIntegrity.verify(root)
        assertTrue(report.missingExecutables.contains("usr/bin/clear"))
        assertTrue(report.missingShims.contains("usr/local/bin/sudo"))
        assertFalse(report.healthy)
    }

    @Test
    fun `zero-byte clear is a restore candidate, not structural`() {
        val root = root()
        healthyRootfs(root)
        File(root, "usr/bin/clear").writeText("")
        val report = RootfsIntegrity.verify(root)
        assertTrue(report.missingExecutables.contains("usr/bin/clear"))
        assertTrue(report.structuralDamage.isEmpty())
    }

    @Test
    fun `repair removes placeholders and restores links without touching real files`() {
        val root = root()
        healthyRootfs(root)
        Files.delete(File(root, "lib64").toPath())
        File(root, "lib64").writeText("")
        Files.delete(File(root, "usr/lib/ld-linux-aarch64.so.1").toPath())
        File(root, "usr/bin/clear").delete()

        val applied = RootfsIntegrity.repair(root)

        assertTrue(applied.isNotEmpty())
        assertTrue(Files.isSymbolicLink(File(root, "lib64").toPath()))
        assertTrue(Files.isSymbolicLink(File(root, "usr/lib/ld-linux-aarch64.so.1").toPath()))
        // clear is a real Ubuntu binary: repair never fabricates content for it
        assertFalse(File(root, "usr/bin/clear").exists())
        // su/sudo shims restored by the configurator path
        assertTrue(File(root, "usr/local/bin/sudo").isFile)
        // the real loader was never deleted or replaced
        assertTrue(File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").length() > 0)
        val after = RootfsIntegrity.verify(root)
        assertTrue(after.structuralDamage.isEmpty())
        assertEquals(listOf("usr/bin/clear"), after.missingExecutables)
    }

    @Test
    fun `repair never overwrites a real directory at a link path`() {
        val root = root()
        healthyRootfs(root)
        Files.delete(File(root, "bin").toPath())
        File(root, "bin").mkdirs() // pre-usr-merge style real directory
        val applied = RootfsIntegrity.repair(root)
        assertTrue(File(root, "bin").isDirectory && !Files.isSymbolicLink(File(root, "bin").toPath()))
        assertTrue(applied.none { it.startsWith("restored /bin ") })
    }

    @Test
    fun `repair never deletes a non-empty file at a link path`() {
        val root = root()
        healthyRootfs(root)
        Files.delete(File(root, "bin").toPath())
        File(root, "bin").writeText("unexpected real content")
        RootfsIntegrity.repair(root)
        assertTrue(File(root, "bin").isFile)
        assertEquals("unexpected real content", File(root, "bin").readText())
    }

    // ---------- Minimal tar builder (same helper style as RootfsExtractorTest) ----------

    private fun tarHeader(name: String, size: Long, typeFlag: Byte, mode: Int = 420): ByteArray {
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
        octal(0, 108, 8)
        octal(size, 124, 12)
        octal(0, 136, 12)
        put("        ", 148, 8)
        h[156] = typeFlag
        put("ustar\u000000", 257, 8)
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
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun fileEntry(name: String, content: String, mode: Int = 420) =
        tarHeader(name, content.length.toLong(), '0'.code.toByte(), mode) to content.toByteArray()

    private fun writeGzip(data: ByteArray, name: String): File {
        val f = File(tmp.root, name)
        java.util.zip.GZIPOutputStream(f.outputStream()).use { it.write(data) }
        return f
    }

    @Test
    fun `restoreFromArchive extracts only the wanted members`() {
        val root = root()
        healthyRootfs(root)
        File(root, "usr/bin/clear").delete()
        File(root, "usr/bin/bash").delete()

        // Archive entries use the "./"-prefixed form the real ubuntu-base
        // tarball ships; the integrity restore must match that too.
        val archive = writeGzip(
            tarEntries(
                fileEntry("./usr/bin/clear", "clear-binary-content"),
                fileEntry("./usr/bin/bash", "bash-binary-content"),
                fileEntry("./usr/bin/secret", "must-not-be-extracted")
            ),
            "rootfs-arm64.tar.gz"
        )

        val restored = RootfsIntegrity.restoreFromArchive(root, archive, listOf("usr/bin/clear", "usr/bin/bash"))
        assertEquals(setOf("usr/bin/clear", "usr/bin/bash"), restored.toSet())
        assertEquals("clear-binary-content", File(root, "usr/bin/clear").readText())
        assertEquals("bash-binary-content", File(root, "usr/bin/bash").readText())
        assertFalse(File(root, "usr/bin/secret").exists())
    }

    @Test
    fun `restoreFromArchive with no archive is a no-op`() {
        val root = root()
        healthyRootfs(root)
        File(root, "usr/bin/clear").delete()
        val missing = RootfsIntegrity.restoreFromArchive(root, File(tmp.root, "absent.tar.gz"), listOf("usr/bin/clear"))
        assertTrue(missing.isEmpty())
        assertFalse(File(root, "usr/bin/clear").exists())
    }
}
