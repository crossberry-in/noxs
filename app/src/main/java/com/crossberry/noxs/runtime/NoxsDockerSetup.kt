/*
 * Noxs — original implementation.
 * Docker setup pipeline: package install, honest capability probing, the
 * Noxs-compatible daemon mode and the real container test.
 *
 * Hard guarantees (spec):
 *  - Docker is OPTIONAL: no failure here ever fails Linux setup.
 *  - The daemon is a MANAGED BACKGROUND PROCESS owned by the Noxs app
 *    (its own proot session); it never blocks the UI and never touches
 *    privileged Android operations.
 *  - Android/proot restrictions (cgroups, iptables/nftables, mounts,
 *    namespaces) are classified as Noxs RUNTIME LIMITATIONS — the Docker
 *    installation is not treated as corrupted because of them.
 *  - Normal Docker networking is attempted at most once; the compatible
 *    mode (dockerd --iptables=false --bridge=none) is remembered.
 *  - No modprobe, no kernel changes, no device root, no credential storage.
 *  - Recovery: every step checks live state first and continues from the
 *    last successful step instead of reinstalling everything.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Owns the dockerd child process for the whole app lifetime. */
object NoxsDockerRuntime {

    @Volatile
    private var process: Process? = null

    @Volatile
    private var logFile: File? = null

    val isDaemonProcessAlive: Boolean
        get() = process?.isAlive == true

    /**
     * Spawns dockerd inside its own long-lived proot session. Returns the
     * process handle; callers verify readiness with the real `docker info`.
     */
    fun spawn(launcher: ProotLauncher, flags: List<String>, logPath: File): Process? {
        stop()
        return try {
            val argv = launcher.buildArgv(
                ProotLauncher.Options(loginAsRoot = true),
                oneShotCmd = listOf("exec", "/usr/bin/dockerd") + flags
            )
            logPath.parentFile?.mkdirs()
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1"))
            logFile = logPath
            val proc = pb.start()
            // Stream the real daemon log to disk for honest diagnostics.
            Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        logPath.bufferedWriter().use { writer ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // The process exit code carries the useful signal.
                }
            }, "noxs-dockerd-log").apply { isDaemon = true }.start()
            process = proc
            proc
        } catch (e: Exception) {
            NoxsLog.e("DockerRuntime", "dockerd spawn failed", e)
            null
        }
    }

    /** Graceful stop: SIGTERM, wait, then force. */
    fun stop() {
        val proc = process ?: return
        process = null
        try {
            proc.destroy()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(5, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            runCatching { proc.destroyForcibly() }
        }
    }

    /** Tail of the current daemon log (bounded, for honest failure reasons). */
    fun recentDaemonLog(lines: Int = 12): String {
        val file = logFile ?: return ""
        return runCatching {
            file.readLines().takeLast(lines).joinToString("\n")
        }.getOrDefault("")
    }
}

/**
 * Runs the whole Docker section of setup. Streams REAL progress through
 * [NoxsDockerSetup.Events]; every check reflects a command that actually ran.
 */
class NoxsDockerSetup(
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher,
    private val isCancelled: () -> Boolean = { false }
) {

    interface Events {
        /** A long operation starts (spinner + elapsed in the console). */
        fun onOp(name: String)
        fun onOpFinish(success: Boolean)
        /** One checklist row reached its final ✓/✗/! state. */
        fun onCheck(row: NoxsDockerCompat.CheckRow)
        /** One real output line from apt/dockerd/docker (already bounded). */
        fun onLog(line: String)
    }

    data class Outcome(
        val state: NoxsDockerCompat.State,
        val mode: String,
        val rows: List<NoxsDockerCompat.CheckRow>,
        val summary: List<String>,
        val detail: String
    )

    private val rows = mutableListOf<NoxsDockerCompat.CheckRow>()
    private val executor = OneShotExecutor(launcher)

    /** Tracks the open console op so a new one always closes the previous. */
    private var opOpen = false

    private fun beginOp(events: Events, name: String) {
        if (opOpen) events.onOpFinish(true)
        opOpen = true
        events.onOp(name)
    }

    private fun endOp(events: Events, success: Boolean) {
        if (!opOpen) return
        opOpen = false
        events.onOpFinish(success)
    }

    private fun checkCancelled() {
        if (isCancelled()) throw SetupCancelledException()
    }

    private fun addRow(label: String, ok: Boolean?, detail: String = ""): NoxsDockerCompat.CheckRow {
        val row = NoxsDockerCompat.CheckRow(label, ok, detail)
        rows += row
        return row
    }

    private fun addRow(row: NoxsDockerCompat.CheckRow): NoxsDockerCompat.CheckRow {
        rows += row
        return row
    }

    // -------------------------------------------------------------- entry

    suspend fun run(aptReady: Boolean, events: Events): Outcome = withContext(Dispatchers.IO) {
        rows.clear()
        try {
            runInternal(aptReady, events)
        } catch (e: SetupCancelledException) {
            throw e
        } catch (e: Exception) {
            NoxsLog.e("DockerSetup", "docker setup crashed", e)
            Outcome(
                NoxsDockerCompat.State.INSTALL_FAILED, "", rows.toList(),
                listOf("Docker setup failed unexpectedly."),
                e.message ?: e.javaClass.simpleName
            )
        }
    }

    private suspend fun runInternal(aptReady: Boolean, events: Events): Outcome {
        // --- recovery check: what already exists? (spec §12) -------------
        beginOp(events, "Checking Docker installation")
        val packageResult = executor.run(
            listOf("/usr/bin/dpkg", "-s", "docker.io"), asRoot = true, timeoutSec = 60
        )
        val packageInstalled = packageResult.success &&
            packageResult.stdout.contains("Status: install ok installed")
        if (packageInstalled) {
            events.onCheck(addRow("Docker package", true, "already installed"))
        }
        checkCancelled()

        // --- package installation (spec §1) ------------------------------
        if (!packageInstalled) {
            if (!aptReady) {
                endOp(events, true)
                events.onCheck(
                    addRow(
                        "Docker package", false,
                        "The dpkg package database is not ready yet. Run it later with: noxs docker install"
                    )
                )
                return finish(
                    NoxsDockerCompat.State.NOT_INSTALLED, "",
                    "Docker setup skipped: the package database is not ready yet."
                )
            }
            beginOp(events, "Installing Docker")
            val installOk = installDockerPackage(events)
            endOp(events, installOk)
            if (!installOk) {
                events.onCheck(addRow("Docker package", false, lastInstallFailure))
                return finish(
                    NoxsDockerCompat.State.INSTALL_FAILED, "",
                    lastInstallFailure.ifBlank { "docker.io could not be installed." }
                )
            }
            events.onCheck(addRow("Docker package", true))
        }
        checkCancelled()

        // --- CLI + containerd (real commands, real versions) --------------
        val version = executor.run(listOf("/usr/bin/docker", "--version"), asRoot = false, timeoutSec = 30)
        val cliOk = version.success && version.stdout.contains("Docker version", ignoreCase = true)
        events.onCheck(
            addRow("Docker CLI", cliOk, if (cliOk) version.stdout.trim() else realReason(version))
        )
        val containerd = executor.run(
            listOf("/bin/bash", "-c", "/usr/bin/containerd --version || /usr/bin/dpkg -s containerd"),
            asRoot = true, timeoutSec = 60
        )
        events.onCheck(
            addRow(
                "containerd", containerd.success,
                if (containerd.success) containerd.stdout.lineSequence().firstOrNull()?.trim().orEmpty()
                else realReason(containerd)
            )
        )
        checkCancelled()

        // --- daemon probe → Noxs compatibility mode (spec §2/§4) ----------
        beginOp(events, "Starting Docker daemon")
        val probeBefore = dockerInfo()
        val daemonAlreadyUp = probeBefore.first
        var daemonOk = daemonAlreadyUp
        var normalNetworkingOk = false
        var lastDaemonReason = if (daemonAlreadyUp) "" else probeBefore.second
        var mode = if (daemonAlreadyUp) "normal" else ""

        if (daemonAlreadyUp) {
            normalNetworkingOk = true
            events.onCheck(addRow("Docker daemon", true, "already running"))
        } else {
            val persisted = persistedMode()
            if (persisted != "compatibility") {
                // One honest normal-mode attempt (never repeatedly retried).
                events.onLog("Starting dockerd with default settings…")
                val normalStart = startDaemonAndAwait(emptyList())
                lastDaemonReason = normalStart.second
                daemonOk = normalStart.first
                if (daemonOk) {
                    mode = "normal"
                    normalNetworkingOk = true
                }
            }
            if (!daemonOk) {
                val verdict = NoxsDockerCompat.classifyFailure(
                    lastDaemonReason.ifBlank { NoxsDockerRuntime.recentDaemonLog() }
                )
                if (verdict.limitation == NoxsDockerCompat.Limitation.NETWORKING ||
                    verdict.limitation == NoxsDockerCompat.Limitation.UNKNOWN) {
                    events.onLog("Docker networking is limited by Android — retrying in Noxs compatibility mode…")
                }
                val flags = NoxsDockerCompat.compatDaemonFlags().toMutableList()
                if (verdict.limitation == NoxsDockerCompat.Limitation.STORAGE) {
                    NoxsDockerCompat.storageFallbackFlag("overlay2")?.let { flags += it }
                }
                val compatStart = startDaemonAndAwait(flags)
                daemonOk = compatStart.first
                lastDaemonReason = compatStart.second
                // Compat mode is the only path from here: normal networking
                // was already attempted at most once (spec §4).
                mode = "compatibility"
            }
            if (daemonOk) {
                events.onCheck(addRow("Docker daemon", true, if (mode == "compatibility") "Noxs compatibility mode" else ""))
            } else {
                events.onCheck(addRow("Docker daemon", false, realReasonText(lastDaemonReason)))
            }
        }
        endOp(events, daemonOk)

        if (!daemonOk) {
            return finish(
                NoxsDockerCompat.State.INSTALLED_DAEMON_UNAVAILABLE, mode,
                "Docker is installed but the daemon could not start. " +
                    "This is a Noxs runtime limitation, not a broken installation.",
                extraReason = realReasonText(lastDaemonReason)
            )
        }
        checkCancelled()

        // --- storage + networking classification (real docker info) -------
        val info = executor.run(listOf("/usr/bin/docker", "info"), asRoot = true, timeoutSec = 45)
        val driver = NoxsDockerCompat.parseStorageDriver(info.stdout)
        val networkingAvailable = normalNetworkingOk

        // --- container execution test (spec §6) ---------------------------
        beginOp(events, "Testing container execution")
        val hello = executor.run(
            listOf("/usr/bin/docker", "run", "--rm", "hello-world"),
            asRoot = true, timeoutSec = 240
        )
        val containersOk = hello.success
        endOp(events, true)
        events.onCheck(
            addRow(
                "Container runtime", containersOk,
                if (containersOk) "hello-world ran successfully"
                else "hello-world failed: " + realReason(hello)
            )
        )
        if (driver != null) {
            events.onCheck(addRow("Docker storage", true, driver))
        } else {
            events.onCheck(addRow("Docker storage", false, realReason(info)))
        }
        events.onCheck(
            addRow(
                NoxsDockerCompat.CheckRow(
                    "Docker networking", networkingAvailable,
                    if (networkingAvailable) "" else "Android/proot restricts kernel networking controls",
                    limited = !networkingAvailable
                )
            )
        )

        val state = if (mode == "compatibility") {
            NoxsDockerCompat.State.COMPATIBILITY
        } else {
            NoxsDockerCompat.State.READY
        }
        return finish(state, mode, "")
    }

    // ------------------------------------------------------------ helpers

    private var lastInstallFailure: String = ""

    /**
     * Real apt update + install with live output streaming (never frozen UI).
     * Holds the package-manager transaction so Noxs never overlaps apt/dpkg.
     */
    private suspend fun installDockerPackage(events: Events): Boolean {
        // Single-flight policy: never overlap the APT bootstrapper or another
        // Noxs-managed apt/dpkg transaction (app-level guard + in-sandbox flag).
        if (NoxsPkgTransaction.currentOwner() != null ||
            File(paths.rootfsNoxsRun, NoxsPkgTransaction.FLAG_NAME).isFile
        ) {
            lastInstallFailure = NoxsPkgTransaction.BUSY_MESSAGE
            return false
        }
        if (!NoxsPkgTransaction.acquire("docker-setup")) {
            lastInstallFailure = NoxsPkgTransaction.BUSY_MESSAGE
            return false
        }
        NoxsPkgTransaction.armFlag(paths, "docker-setup")
        try {
            RootfsConfigurator.repairDpkgPermissions(paths)
            val update = runStreaming(listOf("/usr/bin/apt-get", "update"), 240, events)
            if (!update.success) {
                lastInstallFailure = "apt update failed (exit ${update.exitCode}): " + realReason(update)
                NoxsLog.e("DockerSetup", lastInstallFailure)
                return false
            }
            checkCancelled()
            val install = runStreaming(
                listOf("/usr/bin/apt-get", "install", "--yes", "--no-install-recommends", "docker.io"),
                600, events
            )
            if (!install.success) {
                lastInstallFailure = "apt install docker.io failed (exit ${install.exitCode}): " + realReason(install)
                NoxsLog.e("DockerSetup", lastInstallFailure)
                return false
            }
            checkCancelled()
            val verify = executor.run(listOf("/usr/bin/docker", "--version"), asRoot = false, timeoutSec = 30)
            if (!verify.success) {
                lastInstallFailure = "docker --version failed after install: " + realReason(verify)
            }
            return verify.success
        } finally {
            NoxsPkgTransaction.clearFlag(paths)
            NoxsPkgTransaction.release("docker-setup")
        }
    }

    /**
     * Streams every real output line while the command runs; each line also
     * updates the console so the user sees genuine apt progress.
     */
    private suspend fun runStreaming(
        command: List<String>,
        timeoutSec: Long,
        events: Events
    ): OneShotExecutor.Result = withContext(Dispatchers.IO) {
        val argv = launcher.oneShotArgv(command, asRoot = true)
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        launcher.applyEnvTo(
            pb,
            mapOf(
                "NOXS_ROOT_LOGIN" to "1",
                "DEBIAN_FRONTEND" to "noninteractive",
                "DEBIAN_PRIORITY" to "critical"
            )
        )
        val proc = pb.start()
        val captured = StringBuilder()
        val lock = Any()
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { stream ->
                    while (true) {
                        val line = stream.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) events.onLog(trimmed.take(200))
                        synchronized(lock) {
                            captured.append(line).append('\n')
                            if (captured.length > 32_000) captured.delete(0, captured.length - 16_000)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "noxs-docker-install").apply { isDaemon = true }
        reader.start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        reader.join(5_000)
        OneShotExecutor.Result(
            exitCode = if (finished) proc.exitValue() else 124,
            stdout = synchronized(lock) { captured.toString() },
            stderr = if (finished) "" else "timeout after ${timeoutSec}s"
        )
    }

    /** Runs `docker info` — the one real daemon liveness check. */
    private suspend fun dockerInfo(): Pair<Boolean, String> {
        val result = executor.run(listOf("/usr/bin/docker", "info"), asRoot = true, timeoutSec = 30)
        return result.success to realReason(result)
    }

    private suspend fun persistedMode(): String {
        val file = File(paths.rootfsNoxsRun, STATE_FILE)
        if (!file.isFile) return ""
        return runCatching { NoxsDockerCompat.parseState(file.readText()).mode }.getOrDefault("")
    }

    /**
     * Starts dockerd with [flags] and waits for the REAL readiness signal
     * (`docker info` succeeding through the shared unix socket).
     */
    private suspend fun startDaemonAndAwait(flags: List<String>): Pair<Boolean, String> {
        val logPath = File(paths.logs, "dockerd.log")
        val proc = NoxsDockerRuntime.spawn(launcher, flags, logPath)
            ?: return false to "dockerd could not be launched inside the Noxs sandbox."
        repeat(READINESS_POLLS) {
            checkCancelled()
            if (!proc.isAlive) {
                // Exited immediately: capture the real reason from its log.
                return false to NoxsDockerRuntime.recentDaemonLog()
                    .ifBlank { "dockerd exited during startup (exit ${proc.exitValue()})." }
            }
            val (ok, _) = dockerInfo()
            if (ok) return true to ""
            kotlinx.coroutines.delay(2_000)
        }
        // Never leave a crash-looping daemon running.
        NoxsDockerRuntime.stop()
        return false to NoxsDockerRuntime.recentDaemonLog().ifBlank { "dockerd did not become ready in time." }
    }

    private fun realReason(result: OneShotExecutor.Result): String =
        (result.stdout + "\n" + result.stderr)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString("\n")
            .take(400)

    private fun realReasonText(raw: String): String =
        NoxsDockerCompat.classifyFailure(raw).let { verdict ->
            buildString {
                append(verdict.reason)
                val real = raw.lineSequence().map { it.trim() }
                    .filter { it.isNotBlank() }.take(3).joinToString("\n")
                if (real.isNotBlank()) {
                    append("\nDocker reported: ")
                    append(real.take(300))
                }
            }
        }.take(600)

    /** Persists ONLY safe runtime facts (state/mode) and returns the outcome. */
    private fun finish(
        state: NoxsDockerCompat.State,
        mode: String,
        headline: String,
        extraReason: String = ""
    ): Outcome {
        writeStateFile(state, mode, daemonRunning = state == NoxsDockerCompat.State.READY ||
            state == NoxsDockerCompat.State.COMPATIBILITY)
        val summary = NoxsDockerCompat.summaryLines(
            daemonAvailable = state == NoxsDockerCompat.State.READY ||
                state == NoxsDockerCompat.State.COMPATIBILITY,
            networkingAvailable = state == NoxsDockerCompat.State.READY,
            storageDriver = rows.firstOrNull { it.label == "Docker storage" && it.ok == true }?.detail,
            containersSupported = rows.firstOrNull { it.label == "Container runtime" }?.ok
        )
        return Outcome(
            state = state,
            mode = mode,
            rows = rows.toList(),
            summary = summary,
            detail = listOf(headline, extraReason).filter { it.isNotBlank() }.joinToString("\n")
        )
    }

    private fun writeStateFile(state: NoxsDockerCompat.State, mode: String, daemonRunning: Boolean) {
        runCatching {
            val dir = paths.rootfsNoxsRun
            dir.mkdirs()
            val temp = File(dir, "$STATE_FILE.tmp")
            temp.writeText(NoxsDockerCompat.serializeState(state, mode, daemonRunning))
            val target = File(dir, STATE_FILE)
            if (target.exists() && !target.delete()) return
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    companion object {
        /** Shared state file (also read by the in-sandbox `noxs docker` CLI). */
        const val STATE_FILE = "docker-state"
        private const val READINESS_POLLS = 20
    }
}
