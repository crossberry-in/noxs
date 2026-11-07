/*
 * Noxs — original implementation.
 * Setup console session: owns the REAL setup process (NoxsInstaller) at
 * application scope and streams its real events — step transitions, live
 * download bytes, live tar-entry counts, real apt/dpkg output lines and
 * real failures — into a genuine terminal session (TerminalEmulator).
 *
 * The UI (SetupActivity) is a thin viewer on top: it attaches a TerminalView
 * to the terminal session and reconnects after any recreation. The setup
 * process itself never depends on the Activity lifecycle, and stopping it
 * (Stop / Ctrl+C) is cooperative, keeps verified partial downloads, and
 * never fakes or hides output.
 */
package com.crossberry.noxs.runtime

import android.content.Context
import com.crossberry.noxs.MainLoop
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.terminal.emulator.TerminalSession
import com.crossberry.noxs.terminal.emulator.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class NoxsSetupSession(
    private val context: Context,
    private val paths: NoxsPaths,
    private val center: NoxsActivityCenter? = null
) : TerminalSessionClient {

    enum class State { IDLE, AWAITING_PASSWORD, RUNNING, REPAIRING, COMPLETED, FAILED, CANCELLED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    /** 0 = not collecting, 1 = choose password, 2 = confirm password. */
    private val _passwordPhase = MutableStateFlow(0)
    val passwordPhase: StateFlow<Int> = _passwordPhase

    @Volatile
    var failureDetail: String = ""
        private set

    @Volatile
    var failureHintRes: Int = 0
        private set

    /** Genuine terminal session (no child process; the emulator renders events). */
    val terminal = TerminalSession("setup", this, scrollbackLines = NoxsConstants.DEFAULT_SCROLLBACK).apply {
        mainThreadDispatcher = { MainLoop.post(it) }
    }

    /** Main-thread hook: the terminal buffer changed (attach from an Activity). */
    @Volatile
    var onViewChanged: (() -> Unit)? = null

    /** Current long-running operation for the slim toolbar (spinner + name + elapsed). */
    data class LiveOp(val name: String, val startedAtElapsedRealtimeMs: Long)

    private val _liveOp = MutableStateFlow<LiveOp?>(null)
    val liveOp: StateFlow<LiveOp?> = _liveOp

    /** Presentation-only tracker; never touches installer state or logs. */
    private val opTracker = SetupOpTracker()

    /** Setup engine scope: independent from any Activity/UI lifecycle. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cooperative cancel flag — checked by the installer at safe boundaries. */
    @Volatile
    private var cancelRequested = false

    /** Held only in memory until chpasswd consumes it; wiped right after. */
    @Volatile
    private var password: CharArray? = null

    private var liveLineActive = false
    private var liveLineIsSpinner = false
    private var lastSpinnerFrame = ' '
    private var lastElapsedSeconds = -1L
    private var lastLiveRenderMs = 0L
    private var lastCenterProgressMs = 0L
    private var setupActivityId: String? = null
    private var currentStep = 0
    private val startedAtMs = java.util.concurrent.atomic.AtomicLong(0L)

    private val logFile: File by lazy { File(paths.logs, "setup-console.log") }
    private val eventFile: File by lazy { File(paths.logs, "setup-events.jsonl") }

    // ------------------------------------------------------------- console

    /** Writes real setup events into the terminal buffer (main loop) + log. */
    fun console(text: String) {
        MainLoop.post { terminal.emulator.write(text.toByteArray(Charsets.UTF_8)) }
        appendLog(text)
    }

    private fun appendLog(text: String) {
        try {
            logFile.parentFile?.mkdirs()
            if (logFile.isFile && logFile.length() > MAX_LOG_BYTES) logFile.delete()
            logFile.appendText(text.replace(ANSI, ""))
        } catch (_: Exception) {
            // Console logging must never break the setup process.
        }
    }

    private fun endLiveLine(suffix: String = "") {
        if (!liveLineActive) return
        liveLineActive = false
        liveLineIsSpinner = false
        console(suffix + "\r\n")
    }

    private fun liveLine(plain: String, ansi: String) {
        // Padding capped by the real width so the rewrite can never wrap.
        val width = SetupConsoleText.safeWidth(terminal.emulator.buffer.cols)
        val pad = SetupConsoleText.livePadding(plain.length, width)
        console("\r" + ansi + " ".repeat(pad))
        liveLineActive = true
        liveLineIsSpinner = false
    }

    // ------------------------------------------------- live operation line

    private fun stepOpName(step: Int): String = when (step) {
        1 -> "Preparing environment"
        2 -> "Verifying filesystem"
        3 -> "Preparing workspace"
        4 -> "Preparing environment"
        5 -> "Downloading Ubuntu 24.04 LTS"
        6 -> "Extracting rootfs"
        7 -> "Preparing environment"
        8 -> "Creating Linux account"
        9 -> "Preparing workspace"
        10 -> "Preparing secure connections"
        11 -> "Preparing environment"
        else -> "Working"
    }

    /** Maps REAL bootstrapper phase lines onto live operations; null = plain log. */
    private fun opForLogLine(line: String): String? {
        val lower = line.lowercase(Locale.US)
        return when {
            lower.contains("checking repositories") ->
                "Checking repositories"
            lower.contains("checking for interrupted dpkg configuration") ->
                "Checking for interrupted dpkg configuration"
            lower.contains("refreshing signed ubuntu package metadata") ->
                "Refreshing signed Ubuntu package metadata"
            lower.contains("installing ca-certificates") ||
                lower.contains("installing or repairing ca-certificates") ->
                "Installing ca-certificates"
            lower.contains("updating certificate bundle") ->
                "Updating certificate bundle"
            lower.contains("verifying certificates") ->
                "Verifying certificates"
            lower.contains("archive keyring") ->
                "Installing Ubuntu archive keyring"
            lower.contains("finishing interrupted package configuration") ||
                lower.contains("retrying dpkg configuration") ->
                "Repairing pending dpkg configuration"
            lower.contains("package dependencies") ->
                "Installing required packages"
            else -> null
        }
    }

    /** Starts an operation; spinner ops rewrite one console line in place. */
    private fun startOp(name: String, withSpinnerLine: Boolean) {
        opTracker.start(name)
        _liveOp.value = LiveOp(name, android.os.SystemClock.elapsedRealtime())
        lastSpinnerFrame = ' '
        lastElapsedSeconds = -1L
        lastLiveRenderMs = 0L
        if (withSpinnerLine) {
            writeOpLine(name, opTracker.spinnerFrame(), SetupOpTracker.formatElapsed(0L))
        } else {
            // Steps 5/6 stream their own REAL progress (bytes / tar entries).
            console("\u001b[1;32m[ Noxs ]\u001b[0m $name ")
        }
    }

    private fun writeOpLine(name: String, frame: Char, elapsed: String) {
        // \r + EL: the line updates in place — no new lines are created.
        // The visible text is clamped to the terminal's REAL column count;
        // an unclamped line wraps, and every tick would then spill a new
        // screen row carrying the same status (the "stuck repeating" flood).
        val width = SetupConsoleText.safeWidth(terminal.emulator.buffer.cols)
        val (prefix, body) = SetupConsoleText.opLine(name, frame, elapsed, width)
        val prefixAnsi = if (prefix.isEmpty()) "" else "\u001b[1;32m$prefix\u001b[0m "
        console("\r$prefixAnsi$body\u001b[K")
        liveLineActive = true
        liveLineIsSpinner = true
    }

    /**
     * UI-driven tick (~8–12 Hz while the console is visible): refreshes the
     * spinner frame and the 1 Hz elapsed counter in place. When the app is
     * backgrounded nothing ticks — the monotonic clock guarantees the elapsed
     * time shown when ticking resumes is still truthful.
     */
    fun tickLiveLine() {
        val op = opTracker.current ?: return
        val hintDue = opTracker.consumeLongOpHint()
        val frame = opTracker.spinnerFrame()
        val seconds = opTracker.elapsedMs() / 1000L
        if (frame == lastSpinnerFrame && seconds == lastElapsedSeconds && !hintDue) return
        if (hintDue) {
            // Bake the current line so the hint lands below it — long duration
            // is never treated as an error.
            console("\r\n\u001b[90m[ noxs ] Still working — this operation may take a little longer.\u001b[0m\r\n")
            liveLineActive = false
            lastElapsedSeconds = -1L
        }
        lastSpinnerFrame = frame
        lastElapsedSeconds = seconds
        writeOpLine(op.name, frame, SetupOpTracker.formatElapsed(opTracker.elapsedMs()))
    }

    /** Bakes the live line as a ✓/✗ record carrying the real elapsed time. */
    private fun closeOpLine(success: Boolean) {
        val done = opTracker.finish(success)
        if (liveLineActive && liveLineIsSpinner && done != null) {
            val mark = if (success) "✓" else "✗"
            val color = if (success) "1;32" else "1;31"
            console("\r\u001b[${color}m[ Noxs ]\u001b[0m $mark ${done.name}  ${SetupOpTracker.formatElapsed(done.finalMs)}\u001b[K\r\n")
        } else if (liveLineActive) {
            console("\r\n")
        }
        liveLineActive = false
        liveLineIsSpinner = false
        _liveOp.value = null
    }

    /** Plain bake for user-cancelled operations (the ^C note explains itself). */
    private fun abandonOpLine() {
        if (liveLineActive) console("\r\n")
        liveLineActive = false
        liveLineIsSpinner = false
        opTracker.finish(false)
        _liveOp.value = null
    }

    private fun renderStep(step: Int) {
        if (currentStep in 1..11) {
            NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                NoxsSetupEventLog.STEP_COMPLETED, currentStep, System.currentTimeMillis() - startedAtMs.get()))
        }
        currentStep = step
        NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
            NoxsSetupEventLog.STEP_STARTED, step, System.currentTimeMillis() - startedAtMs.get()))
        setupActivityId?.let { center?.attachOutput(it, stepOpName(step)) }
        // Close the previous live line: a spinner op becomes a ✓ record;
        // download/extract lines already baked their own success suffix.
        if (liveLineActive && !liveLineIsSpinner) {
            endLiveLine(" \u001b[1;32mok\u001b[0m")
        } else {
            closeOpLine(success = true)
        }
        startOp(stepOpName(step), withSpinnerLine = step != 5 && step != 6)
    }

    private fun renderDownload(doneBytes: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        if (doneBytes < totalBytes && now - lastLiveRenderMs < LIVE_RENDER_INTERVAL_MS) return
        lastLiveRenderMs = now
        val pct = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).coerceIn(0L, 100L).toInt() else 0
        // Activity Center / notification: real download percentage, throttled.
        if (totalBytes > 0 && now - lastCenterProgressMs >= 1_500L) {
            lastCenterProgressMs = now
            setupActivityId?.let { center?.setProgress(it, pct) }
        }
        NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
            NoxsSetupEventLog.OUTPUT_TICK, 5, note = "download $pct%"))
        if (doneBytes >= totalBytes && totalBytes > 0) {
            // Honest wording: bytes are complete, the SHA-256 check happens in
            // the installer right after and logs its own verdict line — this
            // line must not claim "verified" before that check ran.
            endLiveLine(" \u001b[1;32mok (${mb(totalBytes)} MB, checking SHA-256)\u001b[0m")
        } else {
            // Pick the most informative progress body that still fits the
            // real terminal width — a wrapping live line would flood.
            val body = liveBody(
                "${mb(doneBytes)} / ${mb(totalBytes)} MB ($pct%)",
                "${mb(doneBytes)}/${mb(totalBytes)}MB",
                "${mb(doneBytes)}MB"
            )
            liveLine("[ Noxs ] $body", "\u001b[1;32m[ Noxs ]\u001b[0m $body")
        }
    }

    private fun renderExtract(entries: Long) {
        val now = System.currentTimeMillis()
        if (now - lastLiveRenderMs < LIVE_RENDER_INTERVAL_MS) return
        lastLiveRenderMs = now
        val count = "%,d".format(Locale.US, entries)
        val body = liveBody(
            "$count entries extracted",
            "$entries extracted",
            "$entries ex"
        )
        liveLine("[ Noxs ] $body", "\u001b[1;32m[ Noxs ]\u001b[0m $body")
    }

    /** First informative-enough body that fits the terminal's real width. */
    private fun liveBody(full: String, medium: String, short: String): String {
        val width = SetupConsoleText.safeWidth(terminal.emulator.buffer.cols)
        val prefixLen = "[ Noxs ] ".length
        return when {
            prefixLen + full.length <= width - 1 -> full
            prefixLen + medium.length <= width - 1 -> medium
            prefixLen + short.length <= width - 1 -> short
            else -> short.take((width - prefixLen - 1).coerceAtLeast(1))
        }
    }

    private fun mb(bytes: Long): String = "%.1f".format(Locale.US, bytes / (1024.0 * 1024.0))

    // ------------------------------------------------------------ lifecycle

    /**
     * Called by the setup console on every entry. Prints the banner and the
     * password prompts exactly once per fresh install; every later call is a
     * no-op reconnect (buffer already holds the live output).
     */
    fun prepareIfNeeded() {
        if (paths.isInstalled()) return
        synchronized(this) {
            if (_state.value != State.IDLE) return
            console("\u001b[1;32mNoxs Linux\u001b[0m \u001b[90m— Ubuntu 24.04 LTS (Noble Numbat) · setup console\u001b[0m\r\n")
            if (logFile.isFile && logFile.length() > 0) {
                console("\u001b[33m[ noxs ] A previous setup attempt was interrupted; verified partial downloads will be reused.\u001b[0m\r\n")
            }
            console("root@noxs:~# \u001b[1mnoxs setup\u001b[0m\r\n")
            askPassword(1)
            _state.value = State.AWAITING_PASSWORD
        }
    }

    private fun askPassword(phase: Int) {
        _passwordPhase.value = phase
        when (phase) {
            1 -> {
                console("\u001b[1mChoose a password for the 'noxs' account\u001b[0m\r\n")
                console("\u001b[90m(min 4 characters — used with sudo inside Noxs; never stored by the app)\u001b[0m\r\n")
                console("\u001b[1;36mEnter password:\u001b[0m \u001b[?25h")
            }
            else -> console("\u001b[1;36mConfirm password:\u001b[0m \u001b[?25h")
        }
    }

    /** Terminal-style password entry (echoed by the masked input row). */
    fun submitPassword(input: String) {
        if (_state.value != State.AWAITING_PASSWORD) return
        when (_passwordPhase.value) {
            1 -> {
                val chars = input.toCharArray()
                if (chars.size < 4 || chars.any { it == ':' || Character.isISOControl(it) }) {
                    chars.fill('\u0000')
                    console("\u001b[1;31mPassword must be at least 4 characters (':' and control characters are not allowed).\u001b[0m\r\n")
                    askPassword(1)
                    return
                }
                password = chars
                askPassword(2)
            }
            2 -> {
                val candidate = input.toCharArray()
                val stored = password
                if (stored == null || !candidate.contentEquals(stored)) {
                    candidate.fill('\u0000')
                    stored?.fill('\u0000')
                    password = null
                    console("\u001b[1;31mPasswords do not match. Try again.\u001b[0m\r\n")
                    askPassword(1)
                    return
                }
                candidate.fill('\u0000')
                _passwordPhase.value = 0
                startInstall()
            }
        }
    }

    // ------------------------------------------------------------- docker

    /**
     * The 🐳 Docker section of the setup console: real package install,
     * honest daemon probing, the Noxs compatibility mode and the real
     * hello-world container test — streamed live, never blocking the UI and
     * never failing the overall setup (Docker is optional/experimental).
     */
    private suspend fun runDockerSection() {
        console("\r\n\u001b[1;36m🐳 Docker Setup\u001b[0m\r\n")
        val aptReady = paths.aptReadyMarker.isFile
        val docker = NoxsDockerSetup(paths, ProotLauncher(paths, NoxsResources(paths)), { cancelRequested })
        val outcome = docker.run(aptReady, DockerEvents())
        when (outcome.state) {
            NoxsDockerCompat.State.READY -> {
                console("\r\n\u001b[1;32m🐳 Docker is ready.\u001b[0m\r\n")
                outcome.summary.forEach { console("  $it\r\n") }
            }
            NoxsDockerCompat.State.COMPATIBILITY -> {
                console("\r\n\u001b[1;33m🐳 Docker Compatibility Mode\u001b[0m\r\n")
                outcome.summary.forEach { console("  $it\r\n") }
                console("\r\nReason: Android/proot restricts kernel networking and cgroup controls.\r\n")
                console("Docker is running with Noxs compatibility mode.\r\n")
            }
            NoxsDockerCompat.State.INSTALLED_DAEMON_UNAVAILABLE -> {
                console("\r\n\u001b[1;33mDocker daemon unavailable (a Noxs runtime limitation)\u001b[0m\r\n")
                outcome.summary.forEach { console("  $it\r\n") }
                outcome.detail.lineSequence().filter { it.isNotBlank() }.take(6).forEach {
                    console("  $it\r\n")
                }
                console("Try later with: noxs docker start\r\n")
            }
            NoxsDockerCompat.State.INSTALL_FAILED -> {
                console("\r\n\u001b[1;31mDocker installation failed (setup itself is complete)\u001b[0m\r\n")
                outcome.detail.lineSequence().filter { it.isNotBlank() }.take(8).forEach {
                    console("  $it\r\n")
                }
                console("Try later with: noxs docker install\r\n")
            }
            NoxsDockerCompat.State.NOT_INSTALLED -> {
                console("\r\n\u001b[90mDocker setup skipped: \u001b[0m")
                console(outcome.detail + "\r\n")
            }
        }
    }

    /** Renders Docker setup progress into the console (spinner + checklist). */
    private inner class DockerEvents : NoxsDockerSetup.Events {
        override fun onOp(name: String) {
            setupActivityId?.let { center?.attachOutput(it, name) }
            startOp(name, withSpinnerLine = true)
        }

        override fun onOpFinish(success: Boolean) {
            closeOpLine(success)
        }

        override fun onCheck(row: NoxsDockerCompat.CheckRow) {
            // Bake the live spinner line so the checklist row is permanent,
            // then keep ticking the same operation below it.
            if (liveLineActive) {
                console("\r\n")
                liveLineActive = false
            }
            val mark = if (row.ok == true) "✓" else if (row.ok == false) "✗" else "!"
            val color = when {
                row.ok == true -> "32"
                row.ok == false && !row.limited -> "31"
                else -> "33"
            }
            val text = buildString {
                append("[").append(mark).append("] ").append(row.label)
                val detail = row.detail.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                if (detail.isNotBlank()) append(" — ").append(detail.take(140))
            }
            console("\u001b[${color}m$text\u001b[0m\r\n")
            resumeOpTick()
        }

        override fun onLog(line: String) {
            val hadSpinner = liveLineActive && liveLineIsSpinner
            if (hadSpinner) {
                console("\r\n")
                liveLineActive = false
            }
            console("\u001b[90m  $line\u001b[0m\r\n")
            resumeOpTick()
        }

        private fun resumeOpTick() {
            if (opTracker.current != null) {
                lastElapsedSeconds = -1L
                tickLiveLine()
            }
        }
    }

    private fun startInstall() {
        cancelRequested = false
        failureDetail = ""
        failureHintRes = 0
        _state.value = State.RUNNING
        startedAtMs.set(System.currentTimeMillis())
        currentStep = 0
        _liveOp.value = null
        liveLineActive = false
        liveLineIsSpinner = false
        lastSpinnerFrame = ' '
        lastElapsedSeconds = -1L
        val hasPartial = paths.cache.listFiles()
            ?.any { it.isFile && it.name.endsWith(".part") && it.length() > 1_000_000L } == true
        NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
            if (hasPartial) NoxsSetupEventLog.RESUMED else NoxsSetupEventLog.STARTED))
        setupActivityId = center?.register(
            title = "Noxs setup",
            command = "noxs setup",
            sessionId = "setup",
            kind = NoxsActivityKind.SETUP,
            status = NoxsActivityStatus.STARTING
        )?.activityId
        setupActivityId?.let { center?.markRunning(it) }
        val installer = NoxsInstaller(
            context, paths,
            ProotLauncher(paths, NoxsResources(paths)),
            isCancelled = { cancelRequested }
        )
        scope.launch {
            val result = try {
                installer.install(installerProgress)
            } catch (e: SetupCancelledException) {
                NoxsInstaller.InstallResult.Cancelled
            } catch (e: Exception) {
                NoxsLog.e("SetupSession", "setup crashed", e)
                NoxsInstaller.InstallResult.Failure(R.string.err_bootstrap, e.message ?: e.javaClass.simpleName)
            }
            when (result) {
                is NoxsInstaller.InstallResult.Success -> {
                    closeOpLine(success = true)
                    // Optional/experimental Docker section: never fails setup.
                    var cancelledDuringDocker = false
                    try {
                        runDockerSection()
                    } catch (e: SetupCancelledException) {
                        cancelledDuringDocker = true
                    }
                    if (cancelledDuringDocker) {
                        abandonOpLine()
                        NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                            NoxsSetupEventLog.CANCELLED, currentStep.takeIf { it > 0 },
                            System.currentTimeMillis() - startedAtMs.get(),
                            note = "cancelled during the Docker section"))
                        setupActivityId?.let { center?.markStopped(it) }
                        console("\u001b^C Setup stopped by user. The Linux environment is ready; Docker state is saved.\u001b\r\n")
                        console("root@noxs:~# ")
                        _state.value = State.CANCELLED
                        return@launch
                    }
                    NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                        NoxsSetupEventLog.COMPLETED, elapsedMs = System.currentTimeMillis() - startedAtMs.get()))
                    setupActivityId?.let { center?.markCompleted(it) }
                    console("\u001b[1;32mNoxs Linux environment setup completed successfully.\u001b[0m\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.COMPLETED
                }
                is NoxsInstaller.InstallResult.Failure -> {
                    failureDetail = result.detail
                    failureHintRes = result.userMessageRes
                    closeOpLine(success = false)
                    NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                        NoxsSetupEventLog.FAILED, currentStep.takeIf { it > 0 },
                        System.currentTimeMillis() - startedAtMs.get(),
                        note = NoxsSetupEventLog.sanitize(result.detail.lineSequence().firstOrNull { it.isNotBlank() } ?: "setup failed")))
                    setupActivityId?.let { center?.markFailed(it, summary = "setup failed — real reason shown in the setup console") }
                    console("\u001b[1;31mERROR: setup failed\u001b[0m\r\n")
                    console("\u001b[1;31mReason:\u001b[0m\r\n")
                    result.detail.lineSequence().filter { it.isNotBlank() }.take(12).forEach {
                        console("  $it\r\n")
                    }
                    console("Setup stopped.\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.FAILED
                }
                is NoxsInstaller.InstallResult.Cancelled -> {
                    abandonOpLine()
                    NoxsSetupEventLog.append(eventFile, NoxsSetupEventLog.Event(
                        NoxsSetupEventLog.CANCELLED, currentStep.takeIf { it > 0 },
                        System.currentTimeMillis() - startedAtMs.get()))
                    setupActivityId?.let { center?.markStopped(it) }
                    console("\u001b[1;33m^C Setup stopped by user. Verified partial downloads are kept for resume.\u001b[0m\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.CANCELLED
                }
            }
        }
    }

    /** [Retry] action: fresh password prompt, real resume of partial data. */
    fun retry() {
        val current = _state.value
        if (current != State.FAILED && current != State.CANCELLED) return
        cancelRequested = false
        failureDetail = ""
        failureHintRes = 0
        console("\r\n\u001b[1mRetrying setup\u001b[0m\r\n")
        askPassword(1)
        _state.value = State.AWAITING_PASSWORD
    }

    /**
     * [Repair] action: runs the REAL recovery pipeline — dpkg --configure -a,
     * authenticated dependency repair, CA certificates, verified HTTPS update —
     * through the signed APT bootstrapper while holding the package-manager
     * transaction. Real output streams into the console; locks are never
     * deleted or overridden.
     */
    fun repair() {
        val current = _state.value
        if (current != State.FAILED && current != State.CANCELLED) return
        cancelRequested = false
        _state.value = State.REPAIRING
        setupActivityId?.let { center?.attachOutput(it, "repairing Ubuntu package layer") }
        console("\r\n\u001b[1;32m[ Noxs ]\u001b[0m Repairing Ubuntu package layer (dpkg recovery + signed HTTPS update)…\r\n")
        scope.launch {
            val launcher = ProotLauncher(paths, NoxsResources(paths))
            val outcome: NoxsAptBootstrapper.Result? = try {
                NoxsPkgTransaction.withTransaction(paths, "setup-repair") {
                    NoxsAptBootstrapper(paths, launcher, { cancelRequested })
                        .initialize(force = true, onLog = { line ->
                            endLiveLine()
                            console("\u001b[90m[ noxs ]\u001b[0m $line\r\n")
                        })
                }
            } catch (e: SetupCancelledException) {
                null
            }
            endLiveLine()
            when {
                outcome == null && cancelRequested -> {
                    console("\u001b[1;33m^C Repair stopped. Partial state is kept.\u001b[0m\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.CANCELLED
                }
                outcome == null -> {
                    console("\u001b[1;31m${NoxsPkgTransaction.BUSY_MESSAGE}\u001b[0m\r\n")
                    console("Setup stopped.\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.FAILED
                }
                outcome.success -> {
                    console("\u001b[1;32m[ Noxs ]\u001b[0m Repair finished. Run Retry to complete setup.\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.FAILED
                }
                else -> {
                    console("\u001b[1;31mERROR: repair failed\u001b[0m\r\n")
                    outcome.detail.lineSequence().filter { it.isNotBlank() }.take(12).forEach {
                        console("  $it\r\n")
                    }
                    console("Setup stopped.\r\n")
                    console("root@noxs:~# ")
                    _state.value = State.FAILED
                }
            }
        }
    }

    /** Stop / Ctrl+C: cooperative cancel; the engine aborts at the next check. */
    fun cancel() {
        if (_state.value != State.RUNNING && _state.value != State.REPAIRING) return
        cancelRequested = true
        console("\r\n\u001b[90m^C requested — stopping after the current operation…\u001b[0m\r\n")
    }

    // ------------------------------------------------- installer progress

    private val installerProgress = object : NoxsInstaller.Progress {
        override fun onStep(step: Int, titleRes: Int, detail: String) = renderStep(step)
        override fun onProgressBytes(downloaded: Long, total: Long) = renderDownload(downloaded, total)
        override fun onExtracted(entries: Long) = renderExtract(entries)
        override fun onLog(line: String) {
            // Real phase lines become live operations (spinner + elapsed);
            // everything else streams as a dim, verbatim log line.
            val opName = opForLogLine(line)
            if (opName != null) {
                closeOpLine(success = true)
                startOp(opName, withSpinnerLine = true)
                return
            }
            val hadSpinner = liveLineActive && liveLineIsSpinner
            if (hadSpinner) {
                // Bake the spinner line so the real output lands below it.
                console("\r\n")
                liveLineActive = false
            }
            console("\u001b[90m[ noxs ]\u001b[0m $line\r\n")
            if (hadSpinner && opTracker.current != null) {
                // Continue ticking the same operation below the log line.
                lastElapsedSeconds = -1L
                tickLiveLine()
            }
        }
        override fun onPasswordRequired(): CharArray? {
            val pw = password
            password = null // consumed; the installer wipes the array after use
            return pw
        }
    }

    // ---------------------------------------------------- TerminalSessionClient

    override fun onTextChanged(session: TerminalSession) {
        onViewChanged?.invoke()
    }

    override fun onTitleChanged(session: TerminalSession) {
        onViewChanged?.invoke()
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onSessionFinished(session: TerminalSession) {
        onViewChanged?.invoke()
    }

    private companion object {
        const val LIVE_RENDER_INTERVAL_MS = 300L
        const val MAX_LOG_BYTES = 512L * 1024
        val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    }
}
