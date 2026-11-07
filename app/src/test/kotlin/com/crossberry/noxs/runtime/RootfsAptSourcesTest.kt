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

    @Test fun `single HTTPS Bookworm source replaces base image definitions`() {
        val paths = NoxsPaths(temporary.newFolder("rootfs-base"))
        val apt = File(paths.rootfs, "etc/apt")
        val sourceDir = File(apt, "sources.list.d")
        sourceDir.mkdirs()
        File(apt, "sources.list").writeText("deb http://old.example bookworm main\n")
        File(sourceDir, "debian.sources").writeText("old base image sources\n")
        File(sourceDir, "extra.list").writeText("deb http://extra.example bookworm main\n")
        File(sourceDir, "preferences").writeText("keep non-source apt configuration\n")

        RootfsConfigurator.configureAptSources(paths, useHttps = true)

        assertFalse(File(apt, "sources.list").exists())
        val definitions = sourceDir.listFiles().orEmpty().filter {
            it.name.endsWith(".list") || it.name.endsWith(".sources")
        }
        assertEquals(listOf("noxs.sources"), definitions.map { it.name })
        val source = File(sourceDir, "noxs.sources").readText()
        assertTrue(source.contains("URIs: https://deb.debian.org/debian"))
        assertTrue(source.contains("Suites: bookworm bookworm-updates"))
        assertTrue(source.contains("URIs: https://security.debian.org/debian-security"))
        assertTrue(source.contains("Suites: bookworm-security"))
        assertTrue(source.contains("Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg"))
        assertTrue(source.contains("Components: main contrib non-free non-free-firmware"))
        assertFalse(source.contains("http://"))
        assertTrue(File(sourceDir, "preferences").isFile)
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
        RootfsConfigurator.configureAptSources(paths, useHttps = false)
        val source = File(paths.rootfs, "etc/apt/sources.list.d/noxs.sources").readText()
        assertTrue(source.contains("URIs: http://deb.debian.org/debian"))
        assertTrue(source.contains("URIs: http://security.debian.org/debian-security"))
        assertTrue(source.contains("Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg"))
    }

    /**
     * Regression: the guard template once emitted `command apt-get "\$@"`
     * (backslash kept literally — Kotlin raw strings do not process escapes),
     * so the shell function passed the literal token `$@` to apt and every
     * apt call through the guard failed with "E: Invalid operation $@".
     * The generated script must forward arguments with real double-quote
     * expansion and must never contain a backslash before `$@`.
     */
    @Test fun `package guard forwards arguments with real shell expansion`() {
        val guard = RootfsConfigurator.NOXS_PKG_GUARD_SH
        assertFalse("backslash-damage before \$@ must never be generated", guard.contains("\\$@"))
        assertTrue(guard.contains("command apt \"\$@\""))
        assertTrue(guard.contains("command apt-get \"\$@\""))
        // both wrappers still serialize through the busy guard
        assertEquals(2, Regex("if noxs_pkg_busy").findAll(guard).count())
    }
}
