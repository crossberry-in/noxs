/*
 * Noxs — original implementation.
 * Ubuntu-first migration manager tests (spec §9): install classification,
 * backup planning and honest user-data scoping. Pure JVM (no Android).
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoxsMigrationManagerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun marker(content: String): File =
        temporary.newFolder().resolve("install.marker").apply { writeText(content) }

    // ------------------------------------------------------- classification

    @Test fun `distro=ubuntu classifies as ubuntu`() {
        assertEquals("ubuntu", NoxsMigrationManager.classifyInstall(
            marker("installed=1\narch=arm64-v8a\nrootfs_sha256=abc\ndistro=ubuntu\nrelease=noble\nsuite=noble\n")
        ))
    }

    @Test fun `suite=bookworm classifies as legacy debian`() {
        assertEquals("debian", NoxsMigrationManager.classifyInstall(
            marker("installed=1\narch=arm64-v8a\nrootfs_sha256=abc\nsuite=bookworm\n")
        ))
    }

    @Test fun `legacy markers without a distro key stay debian`() {
        assertEquals("debian", NoxsMigrationManager.classifyInstall(
            marker("installed=1\narch=arm64-v8a\nrootfs_sha256=abc\nsuite=bookworm\ntransport=https\n")
        ))
    }

    @Test fun `missing marker classifies as unknown`() {
        assertEquals("unknown", NoxsMigrationManager.classifyInstall(
            temporary.newFolder().resolve("nope.marker")
        ))
    }

    @Test fun `noble suite without distro key still reads ubuntu`() {
        assertEquals("ubuntu", NoxsMigrationManager.classifyInstall(
            marker("installed=1\nsuite=noble\n")
        ))
    }

    @Test fun `isLegacyDebianInstall matches only debian`() {
        assertTrue(NoxsMigrationManager.isLegacyDebianInstall(marker("suite=bookworm\n")))
        assertFalse(NoxsMigrationManager.isLegacyDebianInstall(marker("distro=ubuntu\nsuite=noble\n")))
        assertFalse(NoxsMigrationManager.isLegacyDebianInstall(marker("")))
    }

    // -------------------------------------------------------- backup plan

    @Test fun `plan includes only existing user data entries`() {
        val base = temporary.newFolder()
        val paths = NoxsPaths(base)
        // Simulate a legacy rootfs with the noxs home and root dir present,
        // but no resources.conf.
        File(paths.rootfs, "home/noxs").mkdirs()
        File(paths.rootfs, "home/noxs/keep.txt").writeText("data\n")
        File(paths.rootfs, "root").mkdirs()
        // No etc/noxs/resources.conf → must be excluded from the plan.
        paths.installMarker.writeText("suite=bookworm\n")

        val plan = NoxsMigrationManager.planBackup(paths, nowMs = 0L)

        assertEquals("debian", plan.legacyDistro)
        assertEquals(listOf("home/noxs", "root"), plan.backupEntries)
        assertTrue(plan.backupFileName.startsWith("debian-home-backup-"))
        assertTrue(plan.backupFileName.endsWith(".tar.gz"))
    }

    @Test fun `plan file name is UTC-stamped and stable within the same ms`() {
        val base = temporary.newFolder()
        val paths = NoxsPaths(base)
        File(paths.rootfs, "home/noxs").mkdirs()
        paths.installMarker.writeText("suite=bookworm\n")

        val a = NoxsMigrationManager.planBackup(paths, nowMs = 1_700_000_000_000L)
        val b = NoxsMigrationManager.planBackup(paths, nowMs = 1_700_000_000_000L)
        assertEquals(a.backupFileName, b.backupFileName)
        assertFalse(a.backupFileName.contains(' '))
    }

    @Test fun `plan for a missing rootfs is empty and honest`() {
        val base = temporary.newFolder()
        val paths = NoxsPaths(base)
        paths.installMarker.writeText("suite=bookworm\n")
        val plan = NoxsMigrationManager.planBackup(paths, nowMs = 0L)
        assertEquals(emptyList<String>(), plan.backupEntries)
    }

    @Test fun `user data entries never touch system partitions`() {
        // The backup scope must stay inside the app-private rootfs.
        NoxsMigrationManager.USER_DATA_ENTRIES.forEach { entry ->
            assertFalse(entry.startsWith("/"))
            assertFalse(entry.contains(".."))
        }
    }
}
