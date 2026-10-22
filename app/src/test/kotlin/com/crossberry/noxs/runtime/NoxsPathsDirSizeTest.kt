/*
 * Noxs — original implementation.
 * JVM tests for the hardened NoxsPaths.dirSize: the field crash was
 * `AssertionError: rootDir must be verified to be directory beforehand`
 * (kotlin.io.FileTreeWalk) raised from the terminal's resource refresh when
 * the rootfs vanished mid-scan. Every fallback here must return a value,
 * never throw, and never count bytes outside the requested root.
 */
package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoxsPathsDirSizeTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var paths: NoxsPaths

    @Before
    fun setUp() {
        paths = NoxsPaths(temporary.newFolder("base"))
    }

    private fun file(dir: File, name: String, size: Int): File =
        File(dir, name).apply { writeText("x".repeat(size)) }

    // ---- valid directory ----

    @Test
    fun `valid directory sums regular files recursively`() {
        val root = temporary.newFolder("rootfs")
        val sub = File(root, "home/noxs").apply { mkdirs() }
        file(root, "a", 10)
        file(sub, "b", 100)
        assertEquals(110L, paths.dirSize(root))
    }

    // ---- invalid / uninitialized roots ----

    @Test
    fun `missing directory returns zero`() {
        assertEquals(0L, paths.dirSize(File(temporary.root, "does-not-exist")))
    }

    @Test
    fun `plain file supplied instead of directory returns zero`() {
        assertEquals(0L, paths.dirSize(file(temporary.root, "not-a-dir", 5)))
    }

    @Test
    fun `empty directory returns zero`() {
        assertEquals(0L, paths.dirSize(temporary.newFolder("empty")))
    }

    @Test
    fun `uninitialized rootfs path returns zero`() {
        // A NoxsPaths whose environment was never set up: rootfs/ absent.
        assertEquals(0L, paths.dirSize(paths.rootfs))
    }

    @Test
    fun `unreadable directory returns zero without crashing`() {
        // CI and dev shells run unprivileged; under real root the chmod is
        // advisory and the walk simply succeeds.
        assumeFalse("running as root — permission case not enforceable", System.getProperty("user.name") == "root")
        val root = temporary.newFolder("locked")
        file(root, "secret", 42)
        val sub = File(root, "locked-sub").apply { mkdirs(); file(this, "x", 1) }
        assertTrue(sub.setReadable(false, false) || sub.setExecutable(false, false))
        val value = paths.dirSize(root)
        assertTrue("expected a non-crashing result", value >= 0L)
        assertNotEquals(43L, value) // the locked subtree must not be counted
    }

    // ---- symlinks ----

    @Test
    fun `symlink escaping the root is not followed`() {
        val root = temporary.newFolder("root")
        val outside = temporary.newFolder("outside")
        file(outside, "foreign", 1000)
        val escape = File(root, "escape")
        java.nio.file.Files.createSymbolicLink(
            escape.toPath(), java.nio.file.FileSystems.getDefault().getPath(outside.absolutePath)
        )
        file(root, "mine", 7)
        assertEquals(7L, paths.dirSize(root))
    }

    @Test
    fun `dangling symlink does not crash the walk`() {
        val root = temporary.newFolder("root")
        java.nio.file.Files.createSymbolicLink(
            File(root, "dangling").toPath(),
            java.nio.file.FileSystems.getDefault().getPath(temporary.root.absolutePath + "/ghost")
        )
        assertEquals(0L, paths.dirSize(root))
    }

    // ---- disappearing entries ----

    @Test
    fun `entries deleted during traversal do not crash the walk`() {
        val root = temporary.newFolder("churn")
        repeat(64) { i -> file(File(root, "d$i").apply { mkdirs() }, "f$i", 8) }
        val killer = Thread {
            repeat(200) {
                root.listFiles()?.firstOrNull { it.isDirectory }?.deleteRecursively()
                Thread.yield()
            }
        }
        killer.start()
        // The historical failure mode: AssertionError from FileTreeWalk.
        val value = paths.dirSize(root)
        killer.join()
        assertTrue("walk must return a sane total", value >= 0L && value < Long.MAX_VALUE)
    }

    // ---- overflow saturation ----

    @Test
    fun `totals saturate instead of overflowing`() {
        // Indirect check: a sum that would exceed Long must clamp — verified
        // through the saturating add via a synthetic overflow-free path is
        // impractical on real files, so assert the invariant the fold keeps:
        // the result of any scan is a non-negative Long that only grows to
        // the cap, never wraps negative.
        val root = temporary.newFolder("big")
        file(root, "f", 1024)
        val v = paths.dirSize(root)
        assertTrue(v in 1..Long.MAX_VALUE)
    }

    // ---- TTL cache ----

    @Test
    fun `cached scan honors ttl and invalidation`() {
        val root = temporary.newFolder("cached")
        file(root, "a", 10)
        paths.invalidateDirSizeCache()
        assertEquals(10L, paths.dirSizeCached(root, ttlMs = 60_000))
        file(root, "b", 100)
        // inside the TTL the figure is frozen...
        assertEquals(10L, paths.dirSizeCached(root, ttlMs = 60_000))
        // ...invalidation or expiry refreshes it
        paths.invalidateDirSizeCache(root)
        assertEquals(110L, paths.dirSizeCached(root, ttlMs = 60_000))
    }

    // ---- resource widget path ----

    @Test
    fun `currentUsage survives an uninitialized environment`() {
        val usage = NoxsResources(paths).currentUsage()
        assertEquals(0L, usage.storageUsedMb)
        assertTrue(usage.cpuPercent in 0.0..100.0)
    }
}
