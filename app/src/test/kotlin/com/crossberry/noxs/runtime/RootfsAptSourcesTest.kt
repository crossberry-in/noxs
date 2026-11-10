package com.crossberry.noxs.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsAptSourcesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test fun `single HTTPS Noble arm64 source replaces base image definitions`() {
        val paths = NoxsPaths(temporary.newFolder("rootfs-base"))
        val apt = File(paths.rootfs, "etc/apt")
        val sourceDir = File(apt, "sources.list.d")
        sourceDir.mkdirs()
        File(apt, "sources.list").writeText("deb http://old.example noble main\n")
        // The genuine ubuntu-base image ships this file — the configurator
        // must remove it along with any other active source definitions.
        File(sourceDir, "ubuntu.sources").writeText("stock base image sources\n")
        File(sourceDir, "extra.list").writeText("deb http://extra.example noble main\n")
        File(sourceDir, "preferences").writeText("keep non-source apt configuration\n")

        RootfsConfigurator.configureAptSources(paths, useHttps = true, arch = "arm64-v8a")

        assertFalse(File(apt, "sources.list").exists())
        val definitions = sourceDir.listFiles().orEmpty().filter {
            it.name.endsWith(".list") || it.name.endsWith(".sources")
        }
        assertEquals(listOf("noxs.sources"), definitions.map { it.name })
        val source = File(sourceDir, "noxs.sources").readText()
        // Official Ubuntu repositories ONLY — no Debian host may appear.
        assertTrue(source.contains("URIs: https://ports.ubuntu.com/ubuntu-ports"))
        assertTrue(source.contains("Suites: noble noble-updates noble-backports"))
        assertTrue(source.contains("Suites: noble-security"))
        assertTrue(source.contains("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"))
        assertTrue(source.contains("Components: main restricted universe multiverse"))
        assertFalse(source.contains("http://"))
        assertFalse(source.contains("debian.org"))
        assertFalse(source.contains("bookworm"))
        assertTrue(File(sourceDir, "preferences").isFile)
    }

    @Test fun `amd64 builds use the main archive instead of the ports mirror`() {
        val paths = NoxsPaths(temporary.newFolder("rootfs-amd64"))
        RootfsConfigurator.configureAptSources(paths, useHttps = true, arch = "x86_64")
        val source = File(paths.rootfs, "etc/apt/sources.list.d/noxs.sources").readText()
        assertTrue(source.contains("URIs: https://archive.ubuntu.com/ubuntu"))
        assertTrue(source.contains("URIs: https://security.ubuntu.com/ubuntu"))
        assertTrue(source.contains("Suites: noble-security"))
        assertFalse(source.contains("ports.ubuntu.com"))
    }

    @Test fun `dpkg status backups get owner write access without touching outside files`() {
        val base = temporary.newFolder("permissions-base")
        val paths = NoxsPaths(base)
        val dpkgDir = File(paths.rootfs, "var/lib/dpkg").apply { mkdirs() }
        val status = File(dpkgDir, "status").apply { writeText("status\n") }
        val backup = File(dpkgDir, "status-old").apply { writeText("backup\n") }
        val outside = File(base, "outside-state").apply { writeText("untouched\n") }
        val readOnly = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ
        )
        listOf(status, backup, outside).forEach { Files.setPosixFilePermissions(it.toPath(), readOnly) }

        RootfsConfigurator.repairDpkgPermissions(paths)

        assertTrue(Files.getPosixFilePermissions(status.toPath()).contains(PosixFilePermission.OWNER_WRITE))
        assertTrue(Files.getPosixFilePermissions(backup.toPath()).contains(PosixFilePermission.OWNER_WRITE))
        assertFalse(Files.getPosixFilePermissions(outside.toPath()).contains(PosixFilePermission.OWNER_WRITE))
    }

    @Test fun `dpkg repair refuses a parent symlink before writing outside rootfs`() {
        val base = temporary.newFolder("symlink-base")
        val paths = NoxsPaths(base)
        val outside = File(base, "outside").apply { mkdirs() }
        val varDir = File(paths.rootfs, "var").apply { mkdirs() }
        Files.createSymbolicLink(File(varDir, "lib").toPath(), outside.toPath())

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            RootfsConfigurator.repairDpkgPermissions(paths)
        }
        assertFalse(File(outside, "dpkg").exists())
    }

    @Test fun `temporary CA bootstrap uses the same signed repositories over HTTP`() {
        val paths = NoxsPaths(temporary.newFolder("bootstrap-base"))
        RootfsConfigurator.configureAptSources(paths, useHttps = false, arch = "arm64-v8a")
        val source = File(paths.rootfs, "etc/apt/sources.list.d/noxs.sources").readText()
        assertTrue(source.contains("URIs: http://ports.ubuntu.com/ubuntu-ports"))
        assertTrue(source.contains("Suites: noble-security"))
        assertTrue(source.contains("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"))
    }

    /**
     * PRoot process visibility is not reliable enough for shell-side apt
     * wrappers. The guest must leave apt/dpkg locking to the native tools.
     * Existing rootfs installs receive this empty guard through migration.
     */
    @Test fun `package guard does not wrap apt or use process based busy checks`() {
        val guard = RootfsConfigurator.NOXS_PKG_GUARD_SH
        assertFalse(guard.contains("noxs_pkg_busy"))
        assertFalse(Regex("(?m)^\\s*apt\\s*\\(\\)").containsMatchIn(guard))
        assertFalse(Regex("(?m)^\\s*apt-get\\s*\\(\\)").containsMatchIn(guard))
        assertTrue(guard.contains("apt/dpkg own their locks"))
    }
}
