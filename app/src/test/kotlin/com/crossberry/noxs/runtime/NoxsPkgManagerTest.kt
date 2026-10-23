package com.crossberry.noxs.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `nx pkg` Package Manager invariants: the distro-aware front end must
 * ship with the package system (migration version 13), route every
 * Debian/Ubuntu install through the NATIVE apt engine with the signed
 * trust chain intact (print-uris hashes verified, never bypassed), use
 * the Noxs mirror only as an accelerator with an official fallback, and
 * expose stable error codes + single-line progress UI.
 */
class NoxsPkgManagerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var paths: NoxsPaths

    @Before
    fun setUp() {
        paths = NoxsPaths(temporary.newFolder("rootfs"))
        RootfsConfigurator.ensureNxPackageSystem(paths)
    }

    private fun installed(rel: String): File = File(paths.rootfs, rel)

    private val pkgUi get() = installed("usr/local/lib/noxs-pkg/pkg-ui.sh").readText()
    private val pkgManager get() = installed("usr/local/lib/noxs-pkg/pkg-manager.sh").readText()
    private val cli get() = installed("usr/local/bin/nx").readText()
    private val pkgInstall get() = installed("usr/local/lib/noxs-pkg/pkg-install.sh").readText()

    @Test
    fun `package manager libs are installed executable next to the other nx libs`() {
        val ui = installed("usr/local/lib/noxs-pkg/pkg-ui.sh")
        val manager = installed("usr/local/lib/noxs-pkg/pkg-manager.sh")
        assertTrue(ui.isFile)
        assertTrue(ui.canExecute())
        assertTrue(manager.isFile)
        assertTrue(manager.canExecute())
    }

    @Test
    fun `all eleven error codes have stable exit codes`() {
        val codes = listOf(
            "PACKAGE_NOT_FOUND",
            "UNSUPPORTED_ARCHITECTURE",
            "NETWORK_ERROR",
            "DOWNLOAD_FAILED",
            "CHECKSUM_MISMATCH",
            "SIGNATURE_VERIFICATION_FAILED",
            "DEPENDENCY_RESOLUTION_FAILED",
            "INSUFFICIENT_DISK_SPACE",
            "INSTALL_PERMISSION_DENIED",
            "PACKAGE_MANAGER_BUSY",
            "MIRROR_UNAVAILABLE",
        )
        for (code in codes) {
            assertTrue("$code must map to a numeric exit code", pkgManager.contains(code))
        }
        for (rc in 3..13) {
            assertTrue("exit code $rc must be defined", pkgManager.contains("echo $rc"))
        }
    }

    @Test
    fun `apt print-uris output is parsed as data, never eval'd`() {
        assertFalse(
            "apt index data must never reach eval (injection safety)",
            pkgManager.contains("""eval "set --"""),
        )
        assertTrue(pkgManager.contains("pkgm_parse_uri"))
        assertTrue("SHA256 values must be stripped from the apt prefix", pkgManager.contains("SHA256:"))
    }

    @Test
    fun `single-flight package lock guards installs`() {
        assertTrue(pkgManager.contains("pkg-tx"))
        assertTrue(pkgManager.contains("pkgm_busy_check"))
        assertTrue(pkgManager.contains("PACKAGE_MANAGER_BUSY"))
        assertTrue("lock must be armed and released", pkgManager.contains("pkgm_lock_arm"))
        assertTrue(pkgManager.contains("pkgm_lock_release"))
    }

    @Test
    fun `downloads are verified against signed apt metadata before install`() {
        assertTrue("per-file sha256 verification step must exist", pkgManager.contains("Verifying SHA256"))
        assertTrue(pkgManager.contains("CHECKSUM_MISMATCH"))
        assertTrue("mismatched files must be removed", pkgManager.contains("rm -f"))
    }

    @Test
    fun `mirror is an accelerator with an official fallback, never a requirement`() {
        assertTrue("mirror miss must fall back to the official URL", pkgManager.contains("official fallback"))
        assertTrue(pkgManager.contains("NOXS_PKG_MIRROR"))
        assertTrue("mirror can be disabled with off", pkgManager.contains("\"off\""))
        assertTrue("require-mirror mode raises MIRROR_UNAVAILABLE", pkgManager.contains("MIRROR_UNAVAILABLE"))
        assertFalse(
            "the mirror must never replace the official apt sources wholesale",
            pkgManager.contains("deb.debian.org/debian bookworm"),
        )
    }

    @Test
    fun `single-line progress refreshes via carriage return with a non-interactive fallback`() {
        assertTrue("live line must use \\r", pkgUi.contains("\\r"))
        assertTrue("interactive tty detection", pkgUi.contains("[ -t 1 ]"))
        assertTrue("NO_COLOR must be respected", pkgUi.contains("NO_COLOR"))
        assertTrue("dumb terminals get the plain fallback", pkgUi.contains("dumb"))
        assertTrue("progress must clamp to the terminal width", pkgUi.contains("PKG_UI_COLS"))
        assertTrue("stale characters must be cleared", pkgUi.contains("PKG_UI_LASTLEN"))
        assertTrue("exactly one final newline after live progress", pkgUi.contains("ui_progress_done"))
        assertTrue("JSON mode must silence the UI", pkgUi.contains("NOXS_PKG_JSON"))
    }

    @Test
    fun `progress math never invents numbers`() {
        assertTrue("percentage requires a known total", pkgUi.contains("if (t+0 > 0"))
        assertTrue("ETA needs 2s of samples", pkgUi.contains("e+0 >= 2"))
        assertTrue("speed comes from real bytes", pkgUi.contains("spd = d/e"))
    }

    @Test
    fun `search walks the full priority chain and stays metadata-only`() {
        assertTrue(pkgManager.contains("pkgm_search_cmd"))
        assertTrue("local cache source tag", pkgManager.contains("local-cache"))
        assertTrue("Noxs API source tag", pkgManager.contains("noxs-mirror"))
        assertTrue("distro metadata source tag", pkgManager.contains("debian-official"))
        assertTrue("registry source tag", pkgManager.contains("noxs-registry"))
        assertTrue(pkgManager.contains("PACKAGE_NOT_FOUND"))
        assertTrue("mirror index is cached with a TTL", pkgManager.contains("NOXS_PKG_CACHE_TTL"))
    }

    @Test
    fun `no example versions leak into production output`() {
        val ui = pkgUi + pkgManager
        for (marker in listOf("67%", "8.x", "4.8/7.2", "1.2.3")) {
            assertFalse("hardcoded example '$marker' must never ship in the CLI", ui.contains(marker))
        }
    }

    @Test
    fun `dispatcher routes the new pkg subcommands and the install fallback`() {
        for (sub in listOf("search", "install", "update", "list", "remove", "doctor")) {
            assertTrue("nx pkg $sub must be wired", cli.contains("pkgm_${sub}_cmd"))
        }
        assertTrue("nx install falls back on rc 77", cli.contains("-eq 77"))
        assertTrue("fallback can be disabled", cli.contains("NOXS_INSTALL_FALLBACK"))
        assertTrue(
            "the Git installer reports not-found as rc 77",
            pkgInstall.contains("return 77"),
        )
    }

    @Test
    fun `shell templates keep the section-sign expansion contract`() {
        for ((name, text) in listOf("pkg-ui" to pkgUi, "pkg-manager" to pkgManager)) {
            assertFalse("unexpanded § leaked into $name", text.contains('§'))
        }
    }

    @Test
    fun `os detection covers debian and ubuntu without hardcoded paths`() {
        assertTrue(pkgManager.contains("ID_LIKE"))
        assertTrue(pkgManager.contains("NOXS_OS_RELEASE"))
        assertTrue("apt-get preferred when present", pkgManager.indexOf("apt-get") < pkgManager.indexOf("elif command -v apt "))
    }
}
