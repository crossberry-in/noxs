/*
 * Noxs — original implementation.
 * Code-server (VS Code Server) lifecycle manager for the Noxs editor.
 *
 * The manager drives the guest-side `noxs code` CLI (single source of
 * lifecycle truth for terminal users and the editor alike):
 *   - install:  noxs code install — progress lines stream in; cancelling
 *     kills the download (safe: staging dirs are swept on the next attempt)
 *   - start:    noxs code start --foreground --workspace <dir>, run under a
 *     dedicated long-lived proot process owned here. One-shot execs die with
 *     --kill-on-exit, so a managed daemon needs its own process.
 *   - stop:     noxs code stop (graceful SIGTERM from inside the guest),
 *     then destroy() as a fallback for the managed wrapper process.
 *
 * State is read back through host-visible files:
 *   - <paths.run>/code-server.pid                       (real server PID)
 *   - <rootfs>/home/noxs/.noxs/code/config.yaml         (bind-addr/auth)
 *   - <rootfs>/var/log/noxs/code-server.log             (server log)
 *
 * The credential (auth password) lives only in the guest config file
 * (mode 600, app-private storage) and in memory; it is never logged and
 * never written to preferences. All argv construction goes through
 * ProotLauncher — no shell concatenation of untrusted paths.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit

class CodeServerManager(
    private val pathsProvider: () -> NoxsPaths,
    private val launcherProvider: () -> ProotLauncher?
) {

    data class Snapshot(
        val installed: Boolean,
        val version: String?,
        val running: Boolean,
        val port: Int?,
        val httpReady: Boolean
    )

    private val mutex = Mutex()

    /** Live install output (stdout of `noxs code install`). */
    private val _installLines = MutableStateFlow<List<String>>(emptyList())
    val installLines: StateFlow<List<String>> get() = _installLines

    @Volatile private var managedProcess: Process? = null

    private fun paths(): NoxsPaths = pathsProvider()

    // ------------------------------------------- guest-side well-known files

    fun configFile(): File = File(paths().rootfsHomeNoxs, ".noxs/code/config.yaml")

    fun pidFile(): File = File(paths().run, "code-server.pid")

    fun installLogFile(): File = File(paths().run, "code-install.log")

    fun serverLogFile(): File = File(paths().rootfs, "var/log/noxs/code-server.log")

    // --------------------------------------------------------- read probes

    /** Installed code-server version, or null when not installed. */
    fun installedVersion(): String? {
        val dir = File(paths().rootfs, "opt/code-server")
        if (!File(dir, "bin/code-server").isFile) return null
        val v = File(dir, "version")
        val text = if (v.isFile) try {
            v.readText().trim()
        } catch (e: Exception) {
            ""
        } else ""
        val safe = text.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        return safe.ifEmpty { "unknown" }
    }

    fun isInstalled(): Boolean = installedVersion() != null

    fun readConfig(): CodeServerSupport.ServerConfig? = try {
        CodeServerSupport.parseConfig(configFile().readText())
    } catch (e: Exception) {
        null
    }

    /** PID of the running server, verified against /proc/<pid>/cmdline. */
    fun serverPid(): Long? {
        val pid = try {
            CodeServerSupport.parsePidFile(pidFile().readText())
        } catch (e: Exception) {
            null
        } ?: return null
        if (!procCmdlineLooksLikeCodeServer(pid)) {
            // stale metadata: crash, reboot or PID reuse — clean it up
            runCatching { pidFile().delete() }
            return null
        }
        return pid
    }

    private fun procCmdlineLooksLikeCodeServer(pid: Long): Boolean = try {
        CodeServerSupport.looksLikeCodeServerCmdline(File("/proc/$pid/cmdline").readBytes())
    } catch (e: Exception) {
        false
    }

    fun isRunning(): Boolean = serverPid() != null

    /**
     * HTTP readiness against the local loopback — proot does not create a
     * network namespace, so the guest's 127.0.0.1 is this device's. Any
     * HTTP status line counts (302 to /login means the server is up).
     */
    suspend fun probeHttp(port: Int, timeoutMs: Int = 1200): Boolean =
        withContext(Dispatchers.IO) {
            if (port !in 1..65535) return@withContext false
            var conn: HttpURLConnection? = null
            try {
                conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.responseCode in 100..599
            } catch (e: Exception) {
                false
            } finally {
                conn?.disconnect()
            }
        }

    suspend fun snapshot(): Snapshot {
        val cfg = readConfig()
        val running = isRunning()
        val port = cfg?.port
        val ready = running && port != null && probeHttp(port)
        return Snapshot(
            installed = isInstalled(),
            version = installedVersion(),
            running = running,
            port = port,
            httpReady = ready
        )
    }

    /** True when something (not necessarily us) listens on [port]. */
    fun portBusy(port: Int): Boolean = try {
        ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress("127.0.0.1", port))
            false
        }
    } catch (e: Exception) {
        true
    }

    /** Tail of the server log, credential lines stripped. */
    fun logTailLines(maxLines: Int): List<String> = try {
        val f = serverLogFile()
        if (!f.isFile) {
            emptyList()
        } else {
            val bytes = f.length()
            val start = if (bytes > LOG_TAIL_WINDOW) bytes - LOG_TAIL_WINDOW else 0
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                raf.readBytes().toString(Charsets.UTF_8)
            }
                .lineSequence()
                .filter { !it.contains("password", ignoreCase = true) }
                .toList()
                .takeLast(maxLines)
        }
    } catch (e: Exception) {
        emptyList()
    }

    // -------------------------------------------------------------- install

    /**
     * Runs `noxs code install` under a dedicated proot process. Output lines
     * stream into [installLines]; cancellation kills the guest download.
     */
    suspend fun install(force: Boolean = false): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val launcher = launcherProvider()
                ?: return@withContext Result.failure(
                    IllegalStateException("Noxs runtime is not ready — set up the Linux environment first")
                )
            _installLines.value = emptyList()
            val args = buildList {
                add("noxs")
                add("code")
                add("install")
                if (force) add("--force")
            }
            val argv = try {
                launcher.oneShotArgv(args, asRoot = false)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            val process = try {
                ProcessBuilder(argv).redirectErrorStream(true).start()
            } catch (e: Exception) {
                NoxsLog.e(TAG, "install spawn failed", e)
                return@withContext Result.failure(e)
            }
            process.outputStream.close()
            val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { lines.add(it) }
                } catch (e: Exception) {
                    // stream closed by cancellation
                }
            }
            reader.isDaemon = true
            reader.start()
            try {
                while (process.isAlive) {
                    ensureActive()
                    publish(lines)
                    delay(200)
                }
                val code = process.waitFor()
                publish(lines)
                reader.join(2000)
                if (code == 0) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException(lastMeaningfulLine(lines)))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                NoxsLog.i(TAG, "install cancelled — killing the guest download")
                runCatching { process.destroyForcibly() }
                throw e
            } catch (e: Exception) {
                runCatching { process.destroyForcibly() }
                Result.failure(e)
            }
        }
    }

    private fun publish(lines: List<String>) {
        val snapshot = synchronized(lines) { lines.toList() }
        if (snapshot.size != _installLines.value.size) {
            _installLines.value = snapshot
        }
    }

    private fun lastMeaningfulLine(lines: List<String>): String {
        val filtered = lines
            .filter { it.isNotBlank() && !it.contains('%') }
            .map { it.removePrefix("noxs: ") }
        return filtered.lastOrNull { !it.startsWith("[") }
            ?: filtered.lastOrNull()
            ?: "code-server install failed"
    }

    // ---------------------------------------------------------------- start

    /**
     * Starts (or reuses) the server bound to [workspace]. Returns the HTTP
     * port once the server actually answers on the loopback. Duplicate
     * starts are prevented by reusing an already-running server.
     */
    suspend fun start(workspace: String): Result<Int> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!CodeServerSupport.isValidGuestWorkspace(workspace)) {
                return@withContext Result.failure(IllegalArgumentException("invalid workspace path"))
            }
            // Reuse a server that is already alive (prevents duplicates).
            if (serverPid() != null) {
                val cfg = readConfig()
                val port = cfg?.port
                if (port != null && probeHttp(port)) {
                    return@withContext Result.success(port)
                }
            }
            // reap a finished managed process from a previous run
            managedProcess?.let { p -> if (!p.isAlive) managedProcess = null }

            val launcher = launcherProvider()
                ?: return@withContext Result.failure(
                    IllegalStateException("Noxs runtime is not ready — set up the Linux environment first")
                )
            val argv = try {
                launcher.oneShotArgv(
                    listOf("noxs", "code", "start", "--foreground", "--workspace", workspace),
                    asRoot = false
                )
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            val process = try {
                ProcessBuilder(argv).redirectErrorStream(true).start()
            } catch (e: Exception) {
                NoxsLog.e(TAG, "start spawn failed", e)
                return@withContext Result.failure(e)
            }
            process.outputStream.close()
            managedProcess = process

            // server output → the same guest log file `noxs code logs` reads
            val logFile = serverLogFile()
            runCatching { logFile.parentFile?.mkdirs() }
            runCatching {
                FileOutputStream(logFile, true).use { out ->
                    out.write("\n--- managed start (${System.currentTimeMillis()}) ---\n".toByteArray())
                }
            }
            val pump = Thread {
                try {
                    appendTo(logFile, process.inputStream)
                } catch (e: Exception) {
                    // stream closed on stop
                }
            }
            pump.isDaemon = true
            pump.start()

            // Wait for real HTTP readiness; re-read the config every round —
            // the CLI may move the port when a conflict is detected.
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            var exitReason: String? = null
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    // exited: either "already running" (quick) or a failure
                    if (serverPid() != null) {
                        val cfg = readConfig()
                        val port = cfg?.port
                        if (port != null && probeHttp(port)) {
                            return@withContext Result.success(port)
                        }
                    }
                    exitReason = logTailLines(6).lastOrNull { it.isNotBlank() }
                        ?.takeLast(160)
                        ?: "code-server exited during startup"
                    break
                }
                val cfg = readConfig()
                val port = cfg?.port
                if (port != null && probeHttp(port)) {
                    return@withContext Result.success(port)
                }
                delay(500)
            }
            val message = exitReason
                ?: "code-server did not become ready within ${START_TIMEOUT_MS / 1000}s — inspect: noxs code logs"
            NoxsLog.w(TAG, "start failed: $message")
            Result.failure(IllegalStateException(message))
        }
    }

    // ----------------------------------------------------------------- stop

    suspend fun stop(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            // graceful stop from inside the guest (SIGTERM to the real pid)
            val launcher = launcherProvider()
            if (launcher != null) {
                try {
                    val exec = OneShotExecutor(launcher)
                    exec.run(listOf("noxs", "code", "stop"), asRoot = false, timeoutSec = 40)
                } catch (e: Exception) {
                    NoxsLog.w(TAG, "guest stop failed: ${e.message}")
                }
            }
            // then the managed wrapper, if still around
            managedProcess?.let { p ->
                runCatching {
                    if (p.isAlive) {
                        p.destroy()
                        if (!p.waitFor(10, TimeUnit.SECONDS)) {
                            p.destroyForcibly()
                        }
                    }
                }
                managedProcess = null
            }
            if (serverPid() == null) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IllegalStateException("code-server is still running — stop it from a terminal with: noxs code stop")
                )
            }
        }
    }

    suspend fun restart(workspace: String): Result<Int> {
        stop() // tolerate a failed stop; start() reuses a healthy server anyway
        return start(workspace)
    }

    private fun appendTo(target: File, input: InputStream) {
        target.parentFile?.mkdirs()
        FileOutputStream(target, true).use { out -> input.copyTo(out) }
    }

    companion object {
        private const val TAG = "CodeServer"
        private const val START_TIMEOUT_MS = 60_000L
        private const val LOG_TAIL_WINDOW = 262_144L
    }
}
