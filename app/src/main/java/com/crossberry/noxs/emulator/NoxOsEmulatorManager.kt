/*
 * Noxs — original implementation.
 * NoxOS VM lifecycle manager (Noxs × NoxOS M2: EmulatorManager).
 *
 * Modeled on CodeServerManager: the manager owns ONE dedicated long-lived
 * proot process per VM (one-shot execs die with --kill-on-exit, so a VM
 * needs its own process). The QEMU process runs INSIDE the Ubuntu proot
 * environment — exactly the M1-verified execution path — and exposes:
 *   - VNC display bound to 127.0.0.1 only (spec: localhost, no password;
 *     proot does not create a network namespace, so the app can connect)
 *   - serial boot log → logs/serial.log
 *
 * Boot flow (auto-boot from the sidebar entry):
 *   ensure VM dirs + vm.json + kernel asset
 *   → ensure qemu-system-aarch64 exists in the rootfs (apt install once)
 *   → pick a free loopback VNC display (1..9)
 *   → spawn qemu via ProotLauncher (root login, VM dir bound at /noxs-vm)
 *   → wait for the VNC port to answer → state Running(port)
 *
 * The whole pipeline reports LIVE setup progress: [setupProgress] carries a
 * monotonic 0..100 percent + sub-phase (parsed from the apt/dpkg stream by
 * [SetupProgressTracker]), and [installLines] carries the raw log tail shown
 * live in the emulator window overlay.
 *
 * HONEST STATUS (M2): implemented with limitations — compiled and device-
 * tested via the GitHub Actions APK pipeline only when pushed; the QEMU
 * command set and the VNC protocol path are verified in the M1 gate and
 * tools/emulator/verify_vnc_path.py on real QEMU 7.2.22 (M1 gate); the
 * guest runtime apt-installs QEMU 8.2 from Ubuntu noble. The VM
 * dies with the app process (M8 adds background lifecycle); snapshots and
 * SAF disk import are M3/M4.
 */
package com.crossberry.noxs.emulator

import com.crossberry.noxs.BuildConfig
import com.crossberry.noxs.runtime.AndroidSignaller
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.ProotLauncher
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.emulator.QemuCommand
import com.crossberry.noxs.shared.emulator.VmMemoryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

sealed class VmState {
    /** VM not started (fresh app or after a clean stop). */
    object Idle : VmState()

    /** Short setup phase: dirs, config, kernel provisioning. */
    data class Preparing(val detail: String) : VmState()

    /** qemu-system-aarch64 is being installed into the Ubuntu rootfs via apt. */
    data class InstallingRuntime(val lines: List<String>) : VmState()

    /** QEMU spawned, waiting for the VNC port to answer. */
    data class Booting(val vncDisplay: Int) : VmState()

    /** VNC answers on 127.0.0.1:<port> — the display bridge can connect. */
    data class Running(val vncDisplay: Int) : VmState()

    /** VM stopped (user action or process exit observed). */
    data class Stopped(val detail: String?) : VmState()

    /** Boot or runtime failure with a human-readable reason. */
    data class Failed(val message: String) : VmState()
}

class NoxOsEmulatorManager(
    private val pathsProvider: () -> NoxsPaths,
    private val launcherProvider: () -> ProotLauncher?,
    private val assetsProvider: () -> android.content.res.AssetManager?
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    val state: StateFlow<VmState> get() = _state

    /** Live apt output while qemu is being installed into the rootfs. */
    private val _installLines = MutableStateFlow<List<String>>(emptyList())
    val installLines: StateFlow<List<String>> get() = _installLines

    /** Live setup percent + sub-phase for the whole boot pipeline (0..100). */
    private val _setupProgress = MutableStateFlow(SetupProgress(0, SetupSubPhase.IDLE))
    val setupProgress: StateFlow<SetupProgress> get() = _setupProgress

    private val tracker = SetupProgressTracker()

    private fun publishSetup() {
        val snapshot = tracker.snapshot()
        if (snapshot != _setupProgress.value) {
            _setupProgress.value = snapshot
        }
    }

    /** Appends stage headers / boot ticks to the live log (capped tail). */
    private fun pushLogLine(line: String) {
        _installLines.value = (_installLines.value + line).takeLast(LOG_TAIL_MAX)
    }

    @Volatile private var managedProcess: Process? = null

    @Volatile private var activeDisplay: Int? = null

    private fun paths(): NoxsPaths = pathsProvider()

    fun storage(): VmStorage = VmStorage(paths())

    // ------------------------------------------------------------- probes

    /** True when the rootfs actually contains the M1-closed qemu binary. */
    fun qemuInstalled(): Boolean {
        val p = paths()
        if (!p.isInstalled()) return false
        return File(p.rootfs, QEMU_GUEST_PATH).isFile
    }

    /** PID of the running VM, verified against /proc/<pid>/cmdline.
     *
     *  The pid comes from QEMU's own -pidfile (written inside the VM dir)
     *  — java.lang.Process.pid() needs API 26 while minSdk is 24, so the
     *  process object is never used as the pid source. */
    fun vmPid(): Long? {
        val storage = storage()
        val pid = try {
            storage.qemuPidFile.readText().trim().toLongOrNull()
        } catch (e: Exception) {
            null
        } ?: return null
        val cmdline = try {
            File("/proc/$pid/cmdline").readBytes().decodeToString()
        } catch (e: Exception) {
            return null
        }
        return if (cmdline.contains("qemu-system-aarch64")) pid else null
    }

    fun isRunning(): Boolean = vmPid() != null

    /**
     * Port probe: true when something already listens on the loopback port.
     * Same technique as CodeServerManager.portBusy.
     */
    private fun portBusy(port: Int): Boolean = try {
        ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress("127.0.0.1", port))
            false
        }
    } catch (e: Exception) {
        true
    }

    /** True when a TCP listener answers (connect + immediate close). */
    private fun vncAnswers(port: Int): Boolean {
        if (port !in 1..65535) return false
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.connect(InetSocketAddress("127.0.0.1", port), 1200)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { sock?.close() }
        }
    }

    // --------------------------------------------------------------- boot

    /**
     * Ensures runtime + kernel, then boots the VM. Returns the VNC port
     * once QEMU actually answers on the loopback. Idempotent: a healthy
     * running VM is reused instead of duplicated.
     */
    suspend fun boot(): Result<Int> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val launcher = launcherProvider()
                ?: return@withContext Result.failure(
                    IllegalStateException("Noxs runtime is not ready — set up the Linux environment first")
                )
            val storage = storage()
            _state.value = VmState.Preparing("Creating VM storage")
            tracker.beginPrepare(); publishSetup()
            pushLogLine("── preparing VM storage ──")

            // Healthy VM already up? Reuse it (prevents duplicate starts).
            if (vmPid() != null) {
                val display = activeDisplay
                if (display != null && vncAnswers(QemuCommand.vncPort(display))) {
                    _state.value = VmState.Running(display)
                    return@withContext Result.success(QemuCommand.vncPort(display))
                }
            }

            if (!storage.ensureDirs()) {
                return@withContext Result.failure(
                    IllegalStateException("cannot create VM storage directories")
                )
            }

            // Kernel: provision from APK assets (M1-verified release build).
            val assets = assetsProvider()
                ?: return@withContext Result.failure(
                    IllegalStateException("APK assets unavailable")
                )
            _state.value = VmState.Preparing("Provisioning NoxOS kernel")
            tracker.beginKernel(); publishSetup()
            pushLogLine("── provisioning NoxOS kernel ──")
            val assetBytes = try {
                assets.open(VmStorage.KERNEL_ASSET_PATH).use { it.readBytes() }
            } catch (e: Exception) {
                return@withContext Result.failure(
                    IllegalStateException("bundled kernel missing: ${VmStorage.KERNEL_ASSET_PATH}")
                )
            }
            if (!storage.provisionKernel(assetBytes, VmStorage.KERNEL_ASSET_VERSION)) {
                return@withContext Result.failure(
                    IllegalStateException("cannot provision the guest kernel into VM storage")
                )
            }

            // Config: spec §5 config.json (memory policy picks guest RAM).
            val memTotalKb = VmMemoryPolicy.parseMemTotalKb(readMemInfo())
            val memoryMb = VmMemoryPolicy.chooseMemoryMb(memTotalKb ?: 0L)
            writeVmConfig(storage, memoryMb)

            // Runtime: install qemu-system-arm (M1 closure seed) via apt once.
            if (!qemuInstalled()) {
                _state.value = VmState.InstallingRuntime(emptyList())
                _installLines.value = emptyList()
                val installResult = installQemuRuntime(launcher)
                if (installResult != null) {
                    return@withContext Result.failure(installResult)
                }
                if (!qemuInstalled()) {
                    return@withContext Result.failure(
                        IllegalStateException("qemu-system-aarch64 still missing after apt install")
                    )
                }
            }

            // Free loopback VNC display in the probed range.
            val display = pickDisplay()
                ?: return@withContext Result.failure(
                    IllegalStateException("no free local VNC display (${QemuCommand.VNC_DISPLAY_MIN}..${QemuCommand.VNC_DISPLAY_MAX})")
                )

            // Spawn the dedicated VM process (one-shot argv, root login).
            val qemuArgv = QemuCommand.build(
                QemuCommand.Config(
                    kernelPath = storage.guestKernelPath,
                    serialLogPath = storage.guestSerialLogPath,
                    pidFilePath = storage.guestQemuPidPath,
                    vncDisplay = display,
                    memoryMb = memoryMb,
                    displayWidth = 720,
                    displayHeight = 1280
                )
            )
            val argv = try {
                launcher.buildArgv(
                    ProotLauncher.Options(
                        loginAsRoot = true,
                        workDir = "/root",
                        extraBinds = listOf(storage.noxosDir.absolutePath to VmStorage.GUEST_VM_BIND)
                    ),
                    oneShotCmd = qemuArgv
                )
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            val process = try {
                val pb = ProcessBuilder(argv).redirectErrorStream(true)
                launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1"))
                pb.start()
            } catch (e: Exception) {
                NoxsLog.e(TAG, "VM spawn failed", e)
                return@withContext Result.failure(e)
            }
            process.outputStream.close()
            managedProcess = process
            activeDisplay = display
            // Stale pidfile from a previous VM would poison vmPid() until the
            // guest overwrites it — remove it before boot (QEMU rewrites it).
            runCatching { storage.qemuPidFile.delete() }

            // QEMU output → logs/qemu.log (the failure-detail source).
            val qemuLog = storage.qemuLogFile
            runCatching {
                FileOutputStream(qemuLog, true).use { out ->
                    out.write("\n--- boot ${BuildConfig.VERSION_NAME} (${System.currentTimeMillis()}) ---\n".toByteArray())
                }
            }
            val pump = Thread {
                try {
                    FileOutputStream(qemuLog, true).use { out ->
                        process.inputStream.copyTo(out)
                    }
                } catch (e: Exception) {
                    // stream closed on stop
                }
            }
            pump.isDaemon = true
            pump.start()

            // Wait for real VNC readiness on the loopback — the percent
            // crawls 88 → 99 while the log shows elapsed seconds.
            val port = QemuCommand.vncPort(display)
            _state.value = VmState.Booting(display)
            tracker.beginBoot(); publishSetup()
            pushLogLine("── booting NoxOS (waiting for guest display) ──")
            val bootStarted = System.currentTimeMillis()
            val deadline = bootStarted + BOOT_TIMEOUT_MS
            var lastLoggedSecond = -1L
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    val detail = storage.logTail(qemuLog, 6).lastOrNull { it.isNotBlank() }
                        ?.takeLast(200) ?: "QEMU exited during startup"
                    _state.value = VmState.Failed("QEMU exited during startup: $detail")
                    managedProcess = null
                    return@withContext Result.failure(IllegalStateException(detail))
                }
                if (vncAnswers(port)) {
                    tracker.markRunning(); publishSetup()
                    _state.value = VmState.Running(display)
                    NoxsLog.i(TAG, "NoxOS VM running — VNC 127.0.0.1:$port (pid=${vmPid()})")
                    return@withContext Result.success(port)
                }
                val elapsed = System.currentTimeMillis() - bootStarted
                tracker.tickBoot(elapsed, BOOT_TIMEOUT_MS); publishSetup()
                val second = elapsed / 1000
                if (second != lastLoggedSecond && second % 2 == 0L) {
                    lastLoggedSecond = second
                    pushLogLine("waiting for guest display… ${second}s")
                }
                delay(500)
            }
            _state.value = VmState.Failed(
                "VM did not open its display within ${BOOT_TIMEOUT_MS / 1000}s — check the VM logs"
            )
            Result.failure(IllegalStateException("VNC timeout"))
        }
    }

    /**
     * Streams `apt-get update && apt-get install -y --no-install-recommends
     * qemu-system-arm` (the M1 closure seed package) through the proot
     * environment. Returns null on success, the failure reason otherwise.
     *
     * The FULL PATH (/usr/bin/apt-get) is deliberate and matches every other
     * apt caller (bootstrap, docker setup, cert-fix): login shells source
     * /etc/profile.d/noxs-pkg-guard.sh, which defines apt/apt-get wrapper
     * FUNCTIONS — a bare "apt-get" would resolve to the function, and a
     * broken generated function once passed a literal `$@` token to apt
     * ("E: Invalid operation $@"). The absolute path bypasses functions.
     */
    private suspend fun installQemuRuntime(launcher: ProotLauncher): IllegalStateException? {
        tracker.beginUpdate(); publishSetup()
        pushLogLine("── apt-get update ──")
        runAptStep(launcher, listOf("/usr/bin/apt-get", "update")) { line ->
            tracker.onLine(line); publishSetup()
        }?.let { return it }

        tracker.beginInstall(); publishSetup()
        pushLogLine("── apt-get install qemu-system-arm ──")
        // Dpkg::Use-Pty=0 forces dpkg's "Progress: [ NN %]" lines onto stdout
        // so SetupProgressTracker can parse real unpack/configure percentages.
        runAptStep(
            launcher,
            listOf(
                "/usr/bin/apt-get", "-o", "Dpkg::Use-Pty=0",
                "install", "-y", "--no-install-recommends", "qemu-system-arm"
            )
        ) { line ->
            tracker.onLine(line); publishSetup()
        }?.let { return it }
        return null
    }

    /**
     * Runs one apt command through the proot environment, streaming every
     * output line live into [installLines] and the [onLine] callback (which
     * feeds the percent tracker). Returns null on success, the failure
     * reason otherwise.
     *
     * The FULL PATH (/usr/bin/apt-get) is deliberate and matches every other
     * apt caller (bootstrap, docker setup, cert-fix): login shells source
     * /etc/profile.d/noxs-pkg-guard.sh, which defines apt/apt-get wrapper
     * FUNCTIONS — a bare "apt-get" would resolve to the function, and a
     * broken generated function once passed a literal `$@` token to apt
     * ("E: Invalid operation $@"). The absolute path bypasses functions.
     */
    private suspend fun runAptStep(
        launcher: ProotLauncher,
        cmd: List<String>,
        onLine: (String) -> Unit
    ): IllegalStateException? {
        val argv = try {
            launcher.oneShotArgv(cmd, asRoot = true)
        } catch (e: Exception) {
            return IllegalStateException("runtime install spawn failed: ${e.message}")
        }
        val process = try {
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1", "DEBIAN_FRONTEND" to "noninteractive"))
            pb.start()
        } catch (e: Exception) {
            return IllegalStateException("runtime install spawn failed: ${e.message}")
        }
        process.outputStream.close()
        val lines = mutableListOf<String>()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { synchronized(lines) { lines.add(it) } }
            } catch (e: Exception) {
                // stream closed on cancellation
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            var cursor = 0
            while (process.isAlive) {
                cursor = publishNewLines(lines, cursor, onLine)
                delay(200)
            }
            val code = process.waitFor()
            reader.join(2000)
            publishNewLines(lines, cursor, onLine)
            if (code != 0) {
                val joined = synchronized(lines) { lines.toList() }
                // dpkg/apt lock contention (terminal setup runs in parallel)
                if (joined.any { it.contains("Could not get lock", ignoreCase = true) }) {
                    return IllegalStateException(
                        "APT is busy — the terminal may be finishing setup; try again in a minute"
                    )
                }
                return IllegalStateException(
                    "qemu runtime install failed (exit $code): " +
                        joined.lastOrNull { it.isNotBlank() }?.takeLast(200)
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { process.destroyForcibly() }
            throw e
        } catch (e: Exception) {
            runCatching { process.destroyForcibly() }
            return IllegalStateException("runtime install failed: ${e.message}")
        }
        return null
    }

    /** Drains lines[cursor..] into the live log + tracker; returns the new
     *  cursor. Called on a 200 ms cadence from [runAptStep]. */
    private fun publishNewLines(
        lines: List<String>,
        cursor: Int,
        onLine: (String) -> Unit
    ): Int {
        if (cursor >= lines.size) return cursor
        for (i in cursor until lines.size) {
            val line = synchronized(lines) { lines[i] }
            pushLogLine(line)
            onLine(line)
        }
        return lines.size
    }

    private fun pickDisplay(): Int? {
        for (d in QemuCommand.VNC_DISPLAY_MIN..QemuCommand.VNC_DISPLAY_MAX) {
            if (!portBusy(QemuCommand.vncPort(d))) return d
        }
        return null
    }

    private fun readMemInfo(): String = try {
        File("/proc/meminfo").readText()
    } catch (e: Exception) {
        ""
    }

    private fun writeVmConfig(storage: VmStorage, memoryMb: Int) {
        runCatching {
            val existing = if (storage.configFile.isFile) {
                VmConfigJson.parse(storage.configFile.readText())
            } else null
            val config = VmConfig(
                memoryMb = memoryMb,
                kernelAssetVersion = VmStorage.KERNEL_ASSET_VERSION,
                createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                lastBootAtMs = System.currentTimeMillis()
            )
            storage.configFile.writeText(VmConfigJson.write(config))
        }
    }

    // --------------------------------------------------------------- stop

    suspend fun stop(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val qemuPid = vmPid()
            val process = managedProcess
            if (qemuPid == null && process == null) {
                _state.value = VmState.Idle
                return@withContext Result.success(Unit)
            }
            // 1. Graceful: SIGTERM the real QEMU pid (same-uid signal via
            //    the app's process-ownership layer). QEMU flushes and exits.
            if (qemuPid != null) {
                AndroidSignaller().terminate(qemuPid.toInt())
            }
            // 2. Then the proot wrapper (falls through when QEMU already
            //    exited; --kill-on-exit cleans the tree).
            process?.let { p ->
                runCatching {
                    if (p.isAlive) {
                        p.destroy()
                        if (!p.waitFor(10, TimeUnit.SECONDS)) {
                            p.destroyForcibly()
                        }
                    }
                }
            }
            managedProcess = null
            activeDisplay = null
            runCatching { storage().qemuPidFile.delete() }
            _state.value = VmState.Stopped(null)
            Result.success(Unit)
        }
    }

    /** Called when the managed process exited on its own (crash or guest). */
    fun pollForUnexpectedExit() {
        val p = managedProcess ?: return
        if (!p.isAlive) {
            managedProcess = null
            if (_state.value is VmState.Running || _state.value is VmState.Booting) {
                _state.value = VmState.Stopped(
                    storage().logTail(storage().qemuLogFile, 4).lastOrNull { it.isNotBlank() }?.takeLast(200)
                )
            }
        }
    }

    companion object {
        private const val TAG = "NoxOsEmulator"

        /** qemu-system-aarch64 path inside the Ubuntu rootfs (M1 manifest). */
        const val QEMU_GUEST_PATH: String = "usr/bin/qemu-system-aarch64"

        /** Cap on the live log tail published to the UI. */
        const val LOG_TAIL_MAX: Int = 200

        /** TCG boot on a phone is not instant; the M1 gate uses 90s too. */
        const val BOOT_TIMEOUT_MS: Long = 90_000L
    }
}
