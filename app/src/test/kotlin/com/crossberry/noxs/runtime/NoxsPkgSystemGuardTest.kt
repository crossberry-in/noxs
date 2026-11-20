/*
 * Noxs — original implementation.
 * Protected-system-path guard tests for the NX package system.
 *
 * Root cause covered here: packages extracted with tar -C / could
 * overwrite Ubuntu system executables (usr/bin/clear, /bin, the loader
 * chain) and `nx remove` could DELETE them with no restore path — the
 * reported "No such file or directory" corruption for /usr/bin/docker,
 * /usr/local/bin/sudo and /usr/bin/clear. The guard now refuses those
 * paths at both the tarball-member check and the removal scope check.
 */
package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoxsPkgSystemGuardTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var paths: NoxsPaths

    @Before
    fun setUp() {
        paths = NoxsPaths(temporary.newFolder("rootfs"))
        RootfsConfigurator.ensureNxPackageSystem(paths)
    }

    private fun installed(rel: String): File = File(paths.rootfs, rel)
    private val pkgLib get() = installed("usr/local/lib/noxs-pkg/pkg-lib.sh").readText()
    private val pkgInstall get() = installed("usr/local/lib/noxs-pkg/pkg-install.sh").readText()
    private val dockerLib get() = installed("usr/local/lib/noxs-pkg/docker-lib.sh").readText()

    @Test
    fun `docker diagnostics lib is installed executable`() {
        val lib = installed("usr/local/lib/noxs-pkg/docker-lib.sh")
        assertTrue(lib.isFile)
        assertTrue(lib.canExecute())
        assertTrue(dockerLib.contains("nx_docker_doctor_cmd"))
        assertTrue(dockerLib.contains("nx_docker_repair_cmd"))
        assertTrue(dockerLib.contains("nx_docker_status_cmd"))
    }

    @Test
    fun `tarball member check refuses protected system paths`() {
        // The guard pattern covers: bin, sbin, usr/bin, usr/sbin, lib, lib64,
        // usr/lib, usr/lib64 — the dirs whose loss produced the ENOENT reports.
        assertTrue(pkgLib.contains("bin/*|sbin/*|usr/bin/*|usr/sbin/*|lib/*|lib64/*|usr/lib/*|usr/lib64/*"))
        assertTrue(pkgLib.contains("protected system path"))
    }

    @Test
    fun `symlink targets to protected system paths are refused too`() {
        // A symlink whose TARGET lands in usr/bin must be caught, not only
        // the member path itself (the historical usr/bin overwrite vector).
        val guardBlock = pkgLib.substringAfter("check_tarball_members() {").substringBefore("\n}")
        assertTrue(guardBlock.contains("Package archive symlink targets a protected system path"))
    }

    @Test
    fun `removal scope no longer allows deleting usr binaries`() {
        // The old scope check was `usr/*|opt/*` allowed; it must now be
        // `usr/local/*|opt/*` with explicit refusal of system dirs.
        val removeBlock = pkgInstall.substringAfter("pkg_remove_cmd() {").substringBefore("# Prune directories")
        assertTrue(removeBlock.contains("usr/local/*|opt/*"))
        assertFalse(removeBlock.contains("usr/*|opt/*) : ;;"))
        assertTrue(removeBlock.contains("protected system path"))
    }

    @Test
    fun `docker doctor probes run against a fixture prefix in tests`() {
        // Guest-absolute paths go through dd_path so the sandboxed functional
        // suite can point them at a fixture tree (NX_DOCKER_PREFIX).
        assertTrue(dockerLib.contains("NX_DOCKER_PREFIX"))
        assertTrue(dockerLib.contains("dd_path() {"))
    }

    @Test
    fun `docker repair exposes no storage-reset path`() {
        // Destructive surface: only --check and --yes exist. Any storage reset
        // would violate the never-delete-user-data rule.
        val repairBlock = dockerLib.substringAfter("nx_docker_repair_cmd() {").substringBefore("nx_docker_cmd() {")
        assertFalse(repairBlock.contains("reset-storage"))
        assertFalse(repairBlock.contains("rm -rf /var/lib/docker"))
        assertTrue(repairBlock.contains("Docker storage was not modified"))
    }

    @Test
    fun `nx dispatch registers docker before the noxs forwarder`() {
        val cli = installed("usr/local/bin/nx").readText()
        val dockerCase = cli.substringAfter("    docker)").substringBefore("    cert-fix)")
        assertTrue(dockerCase.contains("docker-lib.sh"))
        assertTrue(dockerCase.contains("nx_docker_cmd"))
        // the lifecycle subcommands are forwarded, not re-implemented
        assertTrue(dockerLib.contains("exec noxs docker"))
    }
}
