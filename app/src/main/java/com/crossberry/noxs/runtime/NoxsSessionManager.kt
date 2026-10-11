/*
 * Noxs — original implementation.
 * Session manager: creates/holds terminal sessions bound to the Debian
 * userspace, enforces the session quota, and keeps them alive behind the
 * foreground service. Every session registers with the NoxsActivityCenter so
 * the Activity Center, floating panel and notification share one state source.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.MainLoop
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.terminal.emulator.TerminalSession
import com.crossberry.noxs.terminal.emulator.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class NoxsSessionManager(
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher,
    private val resources: NoxsResources,
    private val center: NoxsActivityCenter? = null
) : TerminalSessionClient {

    data class Entry(
        val session: TerminalSession,
        val label: String,
        val startedAt: Long,
        val loginAsRoot: Boolean,
        val isFallback: Boolean = false,
        val activityId: String? = null
    )

    private val _sessions = MutableStateFlow<List<Entry>>(emptyList())
    val sessions: StateFlow<List<Entry>> = _sessions

    private val quota get() = resources.load()

    /** Set by TerminalActivity to route emulator callbacks to the UI. */
    var client: TerminalSessionClient? = null

    private var sessionSeq = 0
    private val lastOutputPush = HashMap<String, Long>()

    fun createSession(
        label: String,
        loginAsRoot: Boolean = false,
        showFirstRunWelcome: Boolean = false,
        cwd: String? = null
    ): Result<Entry> {
        if (_sessions.value.size >= quota.maxSessions) {
            return Result.failure(IllegalStateException("Session quota reached (${quota.maxSessions})"))
        }
        if (!paths.isInstalled()) {
            return Result.failure(IllegalStateException("Noxs Linux environment is not set up"))
        }
        runCatching { RootfsConfigurator.ensureHealthyRootfs(paths) }

        sessionSeq++
        val name = label.ifBlank { if (loginAsRoot) "root-$sessionSeq" else "shell-$sessionSeq" }
        val session = TerminalSession(
            label = name,
            client = this,
            scrollbackLines = NoxsConstants.DEFAULT_SCROLLBACK
        )
        // route emulator callbacks via main loop
        session.mainThreadDispatcher = { MainLoop.post(it) }

        // Write immediate header into the emulator buffer so the terminal view
        // is never blank while proot initializes the Debian userspace.
        val userTag = if (loginAsRoot) "root@noxs" else "noxs@android"
        val header = "\u001b[1;32m● Noxs Linux\u001b[0m — session \u001b[1m$name\u001b[0m ($userTag)\r\n"
        val welcome = if (showFirstRunWelcome) firstRunWelcome() else ""
        session.emulator.write((header + welcome).toByteArray(Charsets.UTF_8))

        val sessionEnvironment = mutableMapOf("NOXS_SESSION_LABEL" to name)
        if (loginAsRoot) sessionEnvironment["NOXS_ROOT_LOGIN"] = "1"
        val env = launcher.buildEnv(sessionEnvironment)
        // NXEditor "Open in Terminal": validated guest working directory,
        // falling back to the standard home when missing or invalid.
        val workDir = resolveCwd(cwd, loginAsRoot)
        val argv = launcher.buildArgv(ProotLauncher.Options(loginAsRoot = loginAsRoot, workDir = workDir))

        session.start(argv.toTypedArray(), env, paths.tmp.absolutePath, preferPty = true)

        // Register with the Activity Center (single source of truth for state).
        val activity = center?.register(
            title = "Terminal — $name",
            command = "proot · Debian 12",
            sessionId = name,
            kind = NoxsActivityKind.SESSION,
            pid = session.pid.takeIf { it > 0 },
            workingDirectory = workDir,
            status = if (session.isRunning) NoxsActivityStatus.RUNNING else NoxsActivityStatus.STARTING
        )

        val entry = Entry(
            session, name, System.currentTimeMillis(), loginAsRoot,
            isFallback = false, activityId = activity?.activityId
        )
        _sessions.value = _sessions.value + entry
        NoxsLog.i("Sessions", "created '$name' pid=${session.pid} pty=${session.isPty}")
        updateNotification()
        return Result.success(entry)
    }

    /**
     * Validates a requested working directory against the guest rootfs and
     * falls back to the standard home when it is missing, invalid, or not a
     * directory — a session never fails to start because of a stale cwd.
     */
    private fun resolveCwd(cwd: String?, loginAsRoot: Boolean): String {
        val fallback = if (loginAsRoot) NoxsConstants.DEFAULT_ROOT_HOME else NoxsConstants.DEFAULT_USER_HOME
        if (cwd.isNullOrBlank() || cwd == fallback) return fallback
        return try {
            val fs = NoxsEditorFilesystem(paths.rootfs)
            val dir = fs.validateGuestPath(cwd)
            if (dir.isDirectory) fs.guestPathOf(dir) else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun firstRunWelcome(): String = buildString {
        append("\r\nWelcome to Noxs by CrossberryWeb!\r\n\r\n")
        append("For help, feedback, or support:\r\n")
        append("Website: \u001b]8;;https://crossberry.vercel.app\u001b\\crossberry.vercel.app\u001b]8;;\u001b\\\r\n")
        append("Email: \u001b]8;;mailto:crossberryweb@gmail.com\u001b\\crossberryweb@gmail.com\u001b]8;;\u001b\\\r\n\r\n")
        append("Thank you for using Noxs.\r\n\r\n")
    }

    private fun startFallbackSession(failedEntry: Entry, exitCode: Int) {
        val shBin = listOf("/system/bin/sh", "/bin/sh").firstOrNull { File(it).canExecute() } ?: return
        val session = TerminalSession(
            label = failedEntry.label,
            client = this,
            scrollbackLines = NoxsConstants.DEFAULT_SCROLLBACK
        )
        session.mainThreadDispatcher = { MainLoop.post(it) }

        val prevText = failedEntry.session.emulator.transcriptText().replace("█", "").trim()
        val banner = buildString {
            if (prevText.isNotEmpty()) {
                append(prevText.replace("\n", "\r\n"))
                append("\r\n")
            }
            append("\u001b[1;33m[Noxs] proot exited (code $exitCode) — attached sandbox shell in rootfs\u001b[0m\r\n")
            append("Type \u001b[1;36mls -la\u001b[0m, \u001b[1;36mpwd\u001b[0m, or \u001b[1;36muname -a\u001b[0m.\r\n\r\n")
        }
        session.emulator.write(banner.toByteArray(Charsets.UTF_8))

        val homeDir = if (failedEntry.loginAsRoot) {
            File(paths.rootfs, "root").apply { mkdirs() }
        } else {
            paths.rootfsHomeNoxs.apply { mkdirs() }
        }
        val prompt = if (failedEntry.loginAsRoot) "root@noxs:\\w# " else "noxs@android:\\w$ "
        val env = arrayOf(
            "PATH=${File(paths.rootfs, "usr/local/bin").absolutePath}:${File(paths.rootfs, "usr/bin").absolutePath}:/system/bin:/system/xbin",
            "HOME=${homeDir.absolutePath}",
            "USER=${if (failedEntry.loginAsRoot) "root" else NoxsConstants.DEFAULT_USER}",
            "LOGNAME=${if (failedEntry.loginAsRoot) "root" else NoxsConstants.DEFAULT_USER}",
            "TERM=${NoxsConstants.TERM_VALUE}",
            "PS1=$prompt",
            "TMPDIR=${paths.tmp.absolutePath}"
        )
        session.start(arrayOf(shBin, "-i"), env, homeDir.absolutePath, preferPty = true)

        // The activity stays alive — it is the same logical session, new pid.
        failedEntry.activityId?.let { id ->
            center?.markRunning(id, pid = session.pid.takeIf { it > 0 })
            center?.attachOutput(id, "[Noxs] proot exited (code $exitCode) — attached sandbox shell")
        }

        val fallbackEntry = Entry(
            session = session,
            label = failedEntry.label,
            startedAt = System.currentTimeMillis(),
            loginAsRoot = failedEntry.loginAsRoot,
            isFallback = true,
            activityId = failedEntry.activityId
        )
        _sessions.value = (_sessions.value - failedEntry) + fallbackEntry
        updateNotification()
        MainLoop.post { client?.onTextChanged(session) }
    }

    fun closeSession(entry: Entry) {
        entry.activityId?.let { center?.markStopped(it) }
        entry.session.kill()
        _sessions.value = _sessions.value - entry
        updateNotification()
    }

    fun closeAll() {
        _sessions.value.forEach { entry ->
            entry.activityId?.let { center?.markStopped(it) }
            entry.session.kill()
        }
        _sessions.value = emptyList()
        updateNotification()
    }

    private fun updateNotification() {
        NoxsService.notifySessionsChanged(_sessions.value.size)
    }

    // ---- TerminalSessionClient forwarding (session -> UI + service) ----

    override fun onTextChanged(session: TerminalSession) {
        pushOutputSummary(session)
        MainLoop.post { client?.onTextChanged(session) }
    }

    override fun onTitleChanged(session: TerminalSession) {
        MainLoop.post { client?.onTitleChanged(session) }
    }

    override fun onBell(session: TerminalSession) {
        MainLoop.post { client?.onBell(session) }
    }

    override fun onSessionFinished(session: TerminalSession) {
        val entry = _sessions.value.firstOrNull { it.session === session }
        val elapsedMs = if (entry != null) System.currentTimeMillis() - entry.startedAt else Long.MAX_VALUE
        NoxsLog.i("Sessions", "session '${session.label}' exited code=${session.exitCode} elapsed=${elapsedMs}ms")

        // If proot died immediately upon startup, automatically fall back to an
        // interactive sandbox shell so the user never gets a dead/blank terminal.
        if (entry != null && !entry.isFallback && session.exitCode != 0 && elapsedMs < 2500L) {
            startFallbackSession(entry, session.exitCode)
            return
        }

        val exitNotice = buildString {
            append("\r\n\u001b[1;33m[Process exited with code ${session.exitCode}]\u001b[0m\r\n")
            // A SIGKILL on the session child is almost always Android's memory
            // / phantom-process management, not the user's shell. Say so and
            // point at the fix instead of leaving a mysterious negative code.
            if (session.exitCode == -9) {
                append("\u001b[1;33m[The process was killed by Android (SIGKILL). " +
                    "Background servers can be terminated this way — keep the Noxs " +
                    "notification active and enable \u201cKeep CPU awake\u201d in Settings.]\u001b[0m\r\n")
            }
        }
        session.emulator.write(exitNotice.toByteArray(Charsets.UTF_8))

        // Reconcile the Activity Center record with the real outcome. A record
        // already settled (user-initiated stop) is never overwritten — manual
        // stops stay STOPPED even when the process reports a signal exit.
        entry?.activityId?.let { id ->
            val current = center?.find(id)
            if (current == null || !current.status.isTerminal) {
                when {
                    session.exitCode == 0 -> center?.markCompleted(id, 0)
                    session.exitCode < 0 -> center?.markFailed(id, session.exitCode, "Process stopped unexpectedly")
                    else -> center?.markFailed(id, session.exitCode)
                }
            }
        }

        if (entry != null) _sessions.value = _sessions.value - entry
        updateNotification()
        MainLoop.post { client?.onSessionFinished(session) }
    }

    /**
     * Feed the Activity Center a throttled last-line summary. Output history
     * itself stays in the terminal emulator; the center only keeps a summary.
     */
    private fun pushOutputSummary(session: TerminalSession) {
        val activityId = _sessions.value.firstOrNull { it.session === session }?.activityId ?: return
        val now = System.currentTimeMillis()
        if (now - (lastOutputPush[activityId] ?: 0L) < OUTPUT_SUMMARY_INTERVAL_MS) return
        lastOutputPush[activityId] = now
        val lastLine = runCatching {
            session.emulator.transcriptText().lineSequence().lastOrNull { it.isNotBlank() }
        }.getOrNull()
        if (!lastLine.isNullOrBlank()) center?.attachOutput(activityId, lastLine)
    }

    private companion object {
        const val OUTPUT_SUMMARY_INTERVAL_MS = 1_500L
    }
}
