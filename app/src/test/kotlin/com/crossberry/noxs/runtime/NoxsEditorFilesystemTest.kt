/*
 * Noxs — original implementation.
 * JVM tests for the NXEditor filesystem adapter: path validation (traversal,
 * symlink escapes, control characters), listing, read/save with external
 * change detection, create/rename/delete, Unicode + space names, and the
 * size cap. Runs against a TemporaryFolder fake rootfs.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class NoxsEditorFilesystemTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootfs: File
    private lateinit var fs: NoxsEditorFilesystem

    private fun setupRootfs() {
        rootfs = tmp.newFolder("rootfs")
        File(rootfs, "home/noxs").mkdirs()
        fs = NoxsEditorFilesystem(rootfs)
    }

    // ---------------------------------------------------------- validation

    @Test
    fun `absolute guest path maps to the host rootfs`() {
        setupRootfs()
        val host = fs.validateGuestPath("/home/noxs")
        assertEquals(File(rootfs, "home/noxs").canonicalPath, host.canonicalPath)
    }

    @Test(expected = NoxsEditorFilesystem.FilesystemException::class)
    fun `relative path is rejected`() {
        setupRootfs()
        fs.validateGuestPath("home/noxs")
    }

    @Test(expected = NoxsEditorFilesystem.FilesystemException::class)
    fun `dotdot traversal is rejected`() {
        setupRootfs()
        fs.validateGuestPath("/home/noxs/../../etc")
    }

    @Test(expected = NoxsEditorFilesystem.FilesystemException::class)
    fun `literal traversal segment anywhere is rejected`() {
        setupRootfs()
        fs.validateGuestPath("/home/noxs/../../etc/passwd")
    }

    @Test(expected = NoxsEditorFilesystem.FilesystemException::class)
    fun `control characters are rejected`() {
        setupRootfs()
        fs.validateGuestPath("/home/noxs/a\u0001b")
    }

    @Test(expected = NoxsEditorFilesystem.FilesystemException::class)
    fun `nul byte is rejected`() {
        setupRootfs()
        fs.validateGuestPath("/home/noxs/a\u0000b")
    }

    @Test
    fun `symlink escaping the rootfs is rejected`() {
        setupRootfs()
        val outside = tmp.newFolder("outside")
        val victim = File(rootfs, "home/noxs/data")
        Files.createSymbolicLink(victim.toPath(), outside.toPath())
        try {
            fs.validateGuestPath("/home/noxs/data")
            throw AssertionError("expected FilesystemException")
        } catch (expected: NoxsEditorFilesystem.FilesystemException) {
            // containment held
        }
    }

    @Test
    fun `symlink inside the rootfs is allowed and resolves`() {
        setupRootfs()
        File(rootfs, "home/noxs/projects").mkdirs()
        File(rootfs, "home/noxs/projects/a.txt").writeText("hello")
        Files.createSymbolicLink(
            File(rootfs, "home/noxs/current").toPath(),
            File(rootfs, "home/noxs/projects").toPath()
        )
        val host = fs.validateGuestPath("/home/noxs/current/a.txt")
        assertEquals("hello", host.readText())
        assertEquals("/home/noxs/projects/a.txt", fs.guestPathOf(host))
    }

    // ------------------------------------------------------------- listing

    @Test
    fun `listing sorts directories first and reports sizes`() {
        setupRootfs()
        File(rootfs, "home/noxs/zed.txt").writeText("12345")
        File(rootfs, "home/noxs/aDir").mkdirs()
        File(rootfs, "home/noxs/alpha.txt").writeText("x")
        val entries = fs.list("/home/noxs")
        assertEquals(listOf("aDir", "alpha.txt", "zed.txt"), entries.map { it.name })
        assertTrue(entries.first().isDirectory)
        assertEquals(5L, entries.last { it.name == "zed.txt" }.sizeBytes)
        assertEquals("/home/noxs/zed.txt", entries.last { it.name == "zed.txt" }.guestPath)
    }

    @Test
    fun `listing a file raises`() {
        setupRootfs()
        File(rootfs, "home/noxs/f.txt").writeText("x")
        try {
            fs.list("/home/noxs/f.txt")
            throw AssertionError("expected FilesystemException")
        } catch (expected: NoxsEditorFilesystem.FilesystemException) {
        }
    }

    // --------------------------------------------------------- read / save

    @Test
    fun `read and save roundtrip preserves content`() {
        setupRootfs()
        File(rootfs, "home/noxs/app.py").writeText("print('hi')\n")
        val loaded = fs.read("/home/noxs/app.py")
        assertEquals("print('hi')\n", loaded.text)
        assertNotNull(loaded.lastModifiedMs)

        fs.write("/home/noxs/app.py", "print('bye')\n", expectedLastModifiedMs = loaded.lastModifiedMs)
        assertEquals("print('bye')\n", File(rootfs, "home/noxs/app.py").readText())
    }

    @Test(expected = NoxsEditorFilesystem.FilesystemException::class)
    fun `save without force detects external modification`() {
        setupRootfs()
        val f = File(rootfs, "home/noxs/app.py")
        f.writeText("original\n")
        val loaded = fs.read("/home/noxs/app.py")
        // A terminal command rewrote the file behind the editor's back:
        f.writeText("rewritten by terminal\n")
        fs.write("/home/noxs/app.py", "editor content\n", expectedLastModifiedMs = loaded.lastModifiedMs)
    }

    @Test
    fun `forced save overwrites after external modification`() {
        setupRootfs()
        val f = File(rootfs, "home/noxs/app.py")
        f.writeText("original\n")
        val loaded = fs.read("/home/noxs/app.py")
        f.writeText("rewritten by terminal\n")
        fs.write("/home/noxs/app.py", "editor content\n", expectedLastModifiedMs = loaded.lastModifiedMs, force = true)
        assertEquals("editor content\n", f.readText())
    }

    @Test(expected = NoxsEditorFilesystem.FilesystemException::class)
    fun `oversized files are refused`() {
        setupRootfs()
        val f = File(rootfs, "home/noxs/big.bin")
        val out = java.io.FileOutputStream(f)
        out.write(ByteArray(3_000_000))
        out.close()
        fs.read("/home/noxs/big.bin")
    }

    @Test
    fun `unicode and space file names work end to end`() {
        setupRootfs()
        val name = "/home/noxs/my projects/пример файл — test.md"
        File(rootfs, "home/noxs/my projects").mkdirs()
        fs.createFile(name)
        fs.write(name, "# заголовок\n", force = true)
        val loaded = fs.read(name)
        assertEquals("# заголовок\n", loaded.text)
        val entries = fs.list("/home/noxs/my projects")
        assertEquals("пример файл — test.md", entries.single().name)
        fs.delete(name)
        assertFalse(File(rootfs, "home/noxs/my projects", "пример файл — test.md").exists())
    }

    // ---------------------------------------------- create / rename / delete

    @Test
    fun `create rename delete file`() {
        setupRootfs()
        fs.createFile("/home/noxs/new.txt")
        assertTrue(File(rootfs, "home/noxs/new.txt").isFile)
        fs.write("/home/noxs/new.txt", "data", force = true)
        fs.rename("/home/noxs/new.txt", "/home/noxs/renamed.txt")
        assertFalse(File(rootfs, "home/noxs/new.txt").exists())
        assertEquals("data", File(rootfs, "home/noxs/renamed.txt").readText())
        fs.delete("/home/noxs/renamed.txt")
        assertFalse(File(rootfs, "home/noxs/renamed.txt").exists())
    }

    @Test
    fun `create directory and delete recursively keeps symlink targets`() {
        setupRootfs()
        fs.createDirectory("/home/noxs/pkg/src")
        assertTrue(File(rootfs, "home/noxs/pkg/src").isDirectory)
        fs.createFile("/home/noxs/pkg/src/main.kt")
        val outside = tmp.newFolder("precious")
        File(outside, "keep.txt").writeText("keep me")
        Files.createSymbolicLink(
            File(rootfs, "home/noxs/pkg/link").toPath(),
            outside.toPath()
        )
        fs.delete("/home/noxs/pkg")
        assertFalse(File(rootfs, "home/noxs/pkg").exists())
        // the symlink target survived the recursive delete
        assertEquals("keep me", File(outside, "keep.txt").readText())
    }

    @Test(expected = NoxsEditorFilesystem.FilesystemException::class)
    fun `rename onto an existing target is rejected`() {
        setupRootfs()
        fs.createFile("/home/noxs/a.txt")
        fs.createFile("/home/noxs/b.txt")
        fs.rename("/home/noxs/a.txt", "/home/noxs/b.txt")
    }

    @Test
    fun `root directory deletion is refused`() {
        setupRootfs()
        try {
            fs.delete("/")
            throw AssertionError("expected FilesystemException")
        } catch (expected: NoxsEditorFilesystem.FilesystemException) {
        }
    }

    // --------------------------------------------------------- scope mapping

    @Test
    fun `scope mapping covers the bundled languages`() {
        setupRootfs()
        assertEquals("source.python", fs.scopeFor("main.py"))
        assertEquals("source.python", fs.scopeFor("MAIN.PY"))
        assertEquals("source.kotlin", fs.scopeFor("build.gradle.kts"))
        assertEquals("text.html.basic", fs.scopeFor("index.html"))
        assertEquals("source.dockerfile", fs.scopeFor("Dockerfile"))
        assertEquals("source.makefile", fs.scopeFor("Makefile"))
        assertEquals("source.shell", fs.scopeFor("run.sh"))
        assertNull(fs.scopeFor("data.unknownext"))
        assertNull(fs.scopeFor("script.bat"))
    }
}
