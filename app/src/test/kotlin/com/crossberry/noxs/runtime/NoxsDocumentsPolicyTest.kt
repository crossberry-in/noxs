/*
 * Noxs — original implementation.
 * JVM tests for the DocumentsProvider policy layer: the home-only allowlist,
 * traversal and symlink-escape rejection, display-name sanitization,
 * collision-safe naming and MIME detection. These are the security decisions
 * of NoxsDocumentsProvider, kept Android-free so they run as fast unit tests.
 */
package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoxsDocumentsPolicyTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var paths: NoxsPaths
    private lateinit var fs: NoxsEditorFilesystem

    private val home: File get() = paths.rootfsHomeNoxs

    @Before
    fun setUp() {
        paths = NoxsPaths(temporary.newFolder("base"))
        home.mkdirs()
        fs = NoxsEditorFilesystem(paths.rootfs)
    }

    private fun homeFile(rel: String, size: Int = 3): File =
        File(home, rel).apply {
            parentFile?.mkdirs()
            writeText("x".repeat(size))
        }

    // ---- root discovery / child resolution ----

    @Test
    fun `home root resolves when the environment exists`() {
        assertEquals(home.canonicalFile, NoxsDocumentsPolicy.resolve(fs, NoxsDocumentsPolicy.HOME_DOC))
    }

    @Test
    fun `missing home reports unavailability instead of resolving`() {
        home.deleteRecursively()
        assertThrows(NoxsDocumentsPolicy.PolicyException::class.java) {
            NoxsDocumentsPolicy.resolve(fs, NoxsDocumentsPolicy.HOME_DOC)
        }
    }

    @Test
    fun `children of home resolve to their canonical host files`() {
        val f = homeFile("projects/demo/main.txt")
        assertEquals(f.canonicalFile, NoxsDocumentsPolicy.resolve(fs, "/home/noxs/projects/demo/main.txt"))
    }

    // ---- allowlist / traversal / symlink escapes ----

    @Test
    fun `paths outside home are rejected even inside the rootfs`() {
        File(paths.rootfs, "etc").mkdirs()
        assertThrows(NoxsDocumentsPolicy.PolicyException::class.java) {
            NoxsDocumentsPolicy.resolve(fs, "/etc/passwd")
        }
        assertThrows(NoxsDocumentsPolicy.PolicyException::class.java) {
            NoxsDocumentsPolicy.resolve(fs, "/root")
        }
    }

    @Test
    fun `dot-dot traversal is rejected`() {
        homeFile("secret", 5)
        assertThrows(NoxsDocumentsPolicy.PolicyException::class.java) {
            NoxsDocumentsPolicy.resolve(fs, "/home/noxs/../../etc/passwd")
        }
    }

    @Test
    fun `rootfs root and relative or crafted ids are rejected`() {
        assertThrows(NoxsDocumentsPolicy.PolicyException::class.java) {
            NoxsDocumentsPolicy.resolve(fs, "/")
        }
        assertThrows(NoxsDocumentsPolicy.PolicyException::class.java) {
            NoxsDocumentsPolicy.resolve(fs, "/home/noxs-different/file")
        }
    }

    @Test
    fun `symlink escaping the rootfs is rejected`() {
        val outside = temporary.newFolder("outside")
        File(outside, "victim.txt").writeText("secret")
        java.nio.file.Files.createSymbolicLink(
            File(home, "escape").toPath(),
            java.nio.file.FileSystems.getDefault().getPath(outside.absolutePath)
        )
        assertThrows(NoxsDocumentsPolicy.PolicyException::class.java) {
            NoxsDocumentsPolicy.resolve(fs, "/home/noxs/escape")
        }
    }

    @Test
    fun `symlink inside the rootfs but outside home is rejected by the allowlist`() {
        File(paths.rootfs, "etc").mkdirs()
        File(paths.rootfs, "etc/noxs-private.conf").writeText("confidential")
        java.nio.file.Files.createSymbolicLink(
            File(home, "leak").toPath(),
            java.nio.file.FileSystems.getDefault().getPath(paths.rootfs.absolutePath + "/etc/noxs-private.conf")
        )
        assertThrows(NoxsDocumentsPolicy.PolicyException::class.java) {
            NoxsDocumentsPolicy.resolve(fs, "/home/noxs/leak")
        }
    }

    @Test
    fun `isExposedDirectory mirrors the policy`() {
        File(home, "docs").mkdirs()
        assertTrue(NoxsDocumentsPolicy.isExposedDirectory(fs, NoxsDocumentsPolicy.HOME_DOC))
        assertTrue(NoxsDocumentsPolicy.isExposedDirectory(fs, "/home/noxs/docs"))
        assertFalse(NoxsDocumentsPolicy.isExposedDirectory(fs, "/etc"))
        assertFalse(NoxsDocumentsPolicy.isExposedDirectory(fs, "/home/noxs/absent"))
    }

    // ---- display-name sanitization ----

    @Test
    fun `display names are sanitized against separators traversal and control chars`() {
        assertEquals("a-b", NoxsDocumentsPolicy.sanitizeDisplayName("a/b"))
        assertEquals("a-b", NoxsDocumentsPolicy.sanitizeDisplayName("a\\b"))
        assertEquals("untitled", NoxsDocumentsPolicy.sanitizeDisplayName(""))
        assertEquals("untitled", NoxsDocumentsPolicy.sanitizeDisplayName(".."))
        assertEquals("untitled", NoxsDocumentsPolicy.sanitizeDisplayName("/"))
        assertEquals("a b", NoxsDocumentsPolicy.sanitizeDisplayName(" a b "))
        assertTrue(NoxsDocumentsPolicy.sanitizeDisplayName("\u0007bell").none { it.code == 0x7 })
        assertEquals("_hidden", NoxsDocumentsPolicy.sanitizeDisplayName(".hidden"))
        assertEquals(255, NoxsDocumentsPolicy.sanitizeDisplayName("x".repeat(1000)).length)
        assertEquals("plain.txt", NoxsDocumentsPolicy.sanitizeDisplayName("plain.txt"))
    }

    // ---- collision-safe naming ----

    @Test
    fun `unique child never overwrites and numbers duplicates`() {
        val dir = temporary.newFolder("dir")
        File(dir, "report.txt").writeText("v1")
        assertEquals("new.txt", NoxsDocumentsPolicy.uniqueChild(dir, "new.txt"))
        assertEquals("report (1).txt", NoxsDocumentsPolicy.uniqueChild(dir, "report.txt"))
        File(dir, "report (1).txt").writeText("v2")
        assertEquals("report (2).txt", NoxsDocumentsPolicy.uniqueChild(dir, "report.txt"))
        // extensionless names
        File(dir, "Makefile").writeText("v1")
        assertEquals("Makefile (1)", NoxsDocumentsPolicy.uniqueChild(dir, "Makefile"))
    }

    // ---- MIME ----

    @Test
    fun `mime detection covers directories extensions and unknowns`() {
        assertEquals(
            "vnd.android.document/directory",
            NoxsDocumentsPolicy.mimeFor("whatever", true) { null }
        )
        assertEquals("text/plain", NoxsDocumentsPolicy.mimeFor("notes.txt", false) { "text/plain" })
        assertEquals(
            "application/octet-stream",
            NoxsDocumentsPolicy.mimeFor("blob.bin", false) { null }
        )
    }
}
