/*
 * Noxs — original implementation.
 * JVM tests for the guest welcome dashboard: installation/wiring invariants
 * (migration installs /usr/local/lib/noxs-welcome.sh, the bashrc snippet
 * sources it under the interactive once-guard) and real renderer behavior
 * executed by bash — facts, palette fallbacks (NO_COLOR / TERM=dumb), width
 * clamping, the narrow-screen fallback and the interactive-session guard.
 */
package com.crossberry.noxs.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoxsWelcomeTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var paths: NoxsPaths

    private val lib: File get() = File(paths.rootfs, "usr/local/lib/noxs-welcome.sh")

    @Before
    fun setUp() {
        paths = NoxsPaths(temporary.newFolder("rootfs"))
        // refreshShellIntegration only rewrites rc files that already exist,
        // mirroring "user edits above the marker are kept".
        File(paths.rootfs, "etc").mkdirs()
        File(paths.rootfs, "etc/bash.bashrc").writeText("# test bashrc\n")
        RootfsConfigurator.ensureNxPackageSystem(paths)
    }

    // ------------------------------------------------------------ wiring

    @Test
    fun `welcome lib is installed executable by the package-system migration`() {
        assertTrue(lib.isFile)
        assertTrue(lib.canExecute())
        assertTrue(lib.readText().contains("noxs_welcome()"))
        assertEquals("12", RootfsConfigurator.NX_PACKAGE_SYSTEM_VERSION)
    }

    @Test
    fun `bashrc snippet sources the renderer under the interactive once-guard`() {
        val bashrc = File(paths.rootfs, "etc/bash.bashrc").readText()
        assertTrue(bashrc.contains("[ -t 1 ]"))
        assertTrue(bashrc.contains("NOXS_BANNER_SHOWN"))
        assertTrue(bashrc.contains("/usr/local/lib/noxs-welcome.sh"))
        // guard first, then source, then render — and nothing renders for
        // one-shot/non-interactive shells because the guard never fires
        val guard = bashrc.indexOf("[ -t 1 ]")
        val source = bashrc.indexOf(". /usr/local/lib/noxs-welcome.sh")
        val call = bashrc.indexOf("noxs_welcome\n")
        assertTrue(guard in 0 until source)
        assertTrue(source < call)
        // the old plain-text banner is gone from shell integration
        assertFalse(bashrc.contains("Welcome to Noxs"))
    }

    // ---------------------------------------------------- renderer (bash)

    private fun runRenderer(
        vararg envPairs: Pair<String, String>,
        osRelease: File? = null
    ): String {
        val call = if (osRelease != null) "noxs_welcome '${osRelease.absolutePath}'" else "noxs_welcome"
        val script = "set -e; . '${lib.absolutePath}'; $call"
        val pb = ProcessBuilder("bash", "-c", script)
        pb.environment().apply {
            // CI runners may export TERM=dumb; pin a color-capable default so
            // each test controls the palette deterministically.
            put("TERM", "xterm-256color")
            envPairs.forEach { (k, v) -> put(k, v) }
        }
        pb.redirectErrorStream(false)
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue("renderer failed: ${String(proc.errorStream.readBytes())}", proc.waitFor(20, TimeUnit.SECONDS))
        assertEquals(0, proc.exitValue())
        return out
    }

    private fun stubOsRelease(pretty: String = "Debian GNU/Linux 12 (bookworm)"): File =
        temporary.newFile("os-release").apply {
            writeText("PRETTY_NAME=\"$pretty\"\nID=debian\nVERSION_ID=\"12\"\n")
        }

    private fun visibleLines(out: String): List<String> =
        out.replace("\u001B]0;[^\u0007]*\u0007".toRegex(), "")
            .split("\n")
            .filter { it.isNotBlank() }
            .map { it.replace("\u001B\\[[0-9;]*m".toRegex(), "") }

    private fun realUname(arg: String): String =
        ProcessBuilder("uname", arg).start().inputStream.readBytes().toString(Charsets.UTF_8).trim()

    @Test
    fun `default render shows branding, real facts and quick start in a unicode box`() {
        val out = runRenderer("COLUMNS" to "56", osRelease = stubOsRelease())
        assertTrue(out.contains("Noxs Linux"))
        assertTrue(out.contains("Debian GNU/Linux 12 (bookworm)"))
        assertTrue(out.contains(realUname("-m")))
        assertTrue(out.contains("kernel ${realUname("-r")}"))
        assertTrue(out.contains("Quick Start"))
        // valid, existing commands only
        listOf("noxs help", "nx plug", "noxs code status", "nx cert-fix", "sudo apt update")
            .forEach { assertTrue("missing QS command: $it", out.contains(it)) }
        assertTrue(out.contains("crossberry.vercel.app"))
        assertTrue(out.contains("CrossberryWeb"))
        // unicode borders + green branding ANSI
        assertTrue(out.contains("┌"))
        assertTrue(out.contains("\u001B[1;32m"))
    }

    @Test
    fun `NO_COLOR strips every ANSI sequence but keeps facts and the box`() {
        val out = runRenderer("COLUMNS" to "56", "NO_COLOR" to "1", osRelease = stubOsRelease())
        assertFalse(out.contains("\u001B"))
        assertTrue(out.contains("Noxs Linux"))
        assertTrue(out.contains("┌"))
        assertTrue(out.contains("Quick Start"))
    }

    @Test
    fun `dumb terminals get ASCII borders and no color`() {
        val out = runRenderer("COLUMNS" to "56", "TERM" to "dumb", osRelease = stubOsRelease())
        assertFalse(out.contains("\u001B"))
        assertFalse(out.contains("┌"))
        assertTrue(out.contains("+"))
        assertTrue(out.contains("|"))
        assertTrue(out.contains("Noxs Linux"))
    }

    @Test
    fun `narrow columns fall back to a compact two-line banner`() {
        val out = runRenderer("COLUMNS" to "24", osRelease = stubOsRelease())
        assertFalse(out.contains("┌"))
        assertFalse(out.contains("Quick Start"))
        assertTrue(out.contains("Noxs Linux"))
        assertTrue(out.contains("noxs help"))
        assertTrue(out.contains("nx cert-fix"))
    }

    @Test
    fun `huge columns are clamped so borders never wrap`() {
        val out = runRenderer("COLUMNS" to "300", osRelease = stubOsRelease())
        visibleLines(out).forEach { line -> assertTrue("line too wide: ${line.length}", line.length <= 68) }
        // every bordered row has exactly the clamped width
        val boxLines = visibleLines(out).filter { it.startsWith("┌") || it.startsWith("└") || it.startsWith("├") }
        assertTrue(boxLines.isNotEmpty())
        boxLines.forEach { assertEquals(68, it.length) }
    }

    @Test
    fun `all panel rows align to the same visible width`() {
        val out = runRenderer("COLUMNS" to "56", osRelease = stubOsRelease())
        val rows = visibleLines(out).filter { it.startsWith("│") }
        assertTrue(rows.size >= 10)
        rows.forEach { assertEquals("misaligned: $it", 56, it.length) }
    }

    @Test
    fun `missing os-release falls back to plain Linux without failing`() {
        val out = runRenderer("COLUMNS" to "56", osRelease = File(temporary.root, "absent-release"))
        assertTrue(out.contains("Noxs Linux"))
        assertTrue(out.contains("| Linux"))
    }

    // ------------------------------------------- startup guard (real pty)

    private fun scriptBinary(): String? =
        listOf("/usr/bin/script", "/bin/script").firstOrNull { File(it).canExecute() }

    private fun runInteractiveShell(extraEnv: String): String {
        val bashrc = File(paths.rootfs, "etc/bash.bashrc").readText()
            .replace("/usr/local/lib/noxs-welcome.sh", lib.absolutePath)
        val rc = temporary.newFile("bashrc").apply { writeText(bashrc) }
        val home = temporary.newFolder("home")
        val cmd = "env -i PATH=/usr/bin:/bin HOME=${home.absolutePath} $extraEnv " +
            "bash --rcfile '${rc.absolutePath}' -i"
        val pb = ProcessBuilder(scriptBinary(), "-qec", cmd, "/dev/null")
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        proc.waitFor(30, TimeUnit.SECONDS)
        return out
    }

    @Test
    fun `a new interactive session renders the panel exactly once`() {
        assumeTrue(scriptBinary() != null)
        val out = runInteractiveShell("")
        assertTrue("panel missing in interactive session", out.contains("Quick Start"))
        assertEquals(1, Regex("Quick Start").findAll(out).count())
    }

    @Test
    fun `NOXS_BANNER_SHOWN suppresses the panel for nested shells`() {
        assumeTrue(scriptBinary() != null)
        val out = runInteractiveShell("NOXS_BANNER_SHOWN=1")
        assertFalse("panel must not repeat", out.contains("Quick Start"))
    }

    @Test
    fun `non-interactive shells never render the panel`() {
        // bash -i -c DOES read the rcfile, but stdout is a pipe here — so the
        // [ -t 1 ] guard (not bash's own interactivity check) must block it.
        val bashrc = File(paths.rootfs, "etc/bash.bashrc").readText()
            .replace("/usr/local/lib/noxs-welcome.sh", lib.absolutePath)
        val rc = temporary.newFile("bashrc").apply { writeText(bashrc) }
        val home = temporary.newFolder("home")
        val pb = ProcessBuilder(
            "bash", "--rcfile", rc.absolutePath, "-i", "-c", "echo done-marker"
        )
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("PATH", "/usr/bin:/bin")
            put("HOME", home.absolutePath)
            remove("NO_COLOR")
        }
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        proc.waitFor(30, TimeUnit.SECONDS)
        assertTrue(out.contains("done-marker"))
        assertFalse("guard must block non-tty output", out.contains("Quick Start"))
    }
}
