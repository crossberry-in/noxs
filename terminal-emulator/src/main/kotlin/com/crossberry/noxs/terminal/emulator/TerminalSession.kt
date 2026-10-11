/*
 * Noxs terminal-emulator — original implementation.
 * TerminalSession: binds a process (native PTY via JNI, or pipe fallback) to
 * the emulator, with reader/waiter threads and coalesced change notifications.
 */
package com.crossberry.noxs.terminal.emulator

import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Native PTY bridge. Loaded lazily; availability drives capability reporting. */
object NativePty {
    private val loaded = AtomicBoolean(false)
    private var available = false

    @Volatile
    var libName: String = "noxs-pty"

    fun isAvailable(): Boolean {
        if (loaded.get()) return available
        synchronized(this) {
            if (!loaded.get()) {
                available = try {
                    System.loadLibrary(libName)
                    true
                } catch (e: Throwable) {
                    NoxsLog.w("NativePty", "lib$libName unavailable: ${e.javaClass.simpleName}")
                    false
                } finally {
                    loaded.set(true)
                }
            }
            return available
        }
    }

    // Implemented in app/src/main/cpp/noxs-pty.c
    external fun create(cmd: Array<String>, env: Array<String>, cwd: String, rows: Int, cols: Int): IntArray?
    external fun setSize(fd: Int, rows: Int, cols: Int)
    external fun waitFor(pid: Int): Int
    external fun sendSignal(pid: Int, signal: Int)
    external fun requestRedraw(pid: Int, masterFd: Int)
    external fun closeFd(fd: Int)
    external fun readBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int
    external fun writeBytes(fd: Int, data: ByteArray): Int
}

interface TerminalSessionClient {
    fun onTextChanged(session: TerminalSession)
    fun onTitleChanged(session: TerminalSession)
    fun onBell(session: TerminalSession)
    fun onSessionFinished(session: TerminalSession)

    /**
     * OSC 7: the shell reported its working directory. Default no-op so
     * existing implementors keep compiling; the app layer overrides it.
     */
    fun onWorkingDirectoryChanged(session: TerminalSession, dir: String) {}
}

class TerminalSession(
    val label: String,
    private val client: TerminalSessionClient,
    scrollbackLines: Int = NoxsConstants.DEFAULT_SCROLLBACK
) : TerminalEmulator.Client {

    val emulator = TerminalEmulator(this, 80, 24, scrollbackLines)

    @Volatile
    var pid: Int = -1
        private set
    @Volatile
    var isRunning: Boolean = false
        private set
    @Volatile
    var exitCode: Int = 0
        private set
    @Volatile
    var isPty: Boolean = false
        private set
    @Volatile
    var receivedBytes: Long = 0L
        private set

    /** System.currentTimeMillis of the last PTY output chunk (session status). */
    @Volatile
    var lastOutputAtMs: Long = 0L
        private set

    private var masterFd: Int = -1
    private var stdinStream: OutputStream? = null
    private var process: Process? = null
    private val started = AtomicBoolean(false)

    private var cols = 80
    private var rows = 24

    /**
     * Starts the child. [cmd] is an argv array (no shell), [env] entries use
     * KEY=VALUE form, [cwd] must exist and be accessible.
     */
    @Synchronized
    fun start(cmd: Array<String>, env: Array<String>, cwd: String, preferPty: Boolean = true) {
        check(started.compareAndSet(false, true)) { "Session already started" }
        val usePty = preferPty && NativePty.isAvailable()
        isPty = usePty
        if (usePty) {
            val res = NativePty.create(cmd, env, cwd, rows, cols)
            if (res != null && res.size >= 2 && res[0] > 0 && res[1] >= 0) {
                pid = res[0]
                masterFd = res[1]
                isRunning = true
                startReader(FdInputStream(masterFd))
                startWaiter()
            } else {
                // Native creation failed — degrade to pipe mode.
                isPty = false
                startPipe(cmd, env, cwd)
            }
        } else {
            startPipe(cmd, env, cwd)
        }
    }

    private fun startPipe(cmd: Array<String>, env: Array<String>, cwd: String) {
        try {
            val dir = File(cwd).takeIf { it.isDirectory } ?: File(cwd).parentFile?.takeIf { it.isDirectory } ?: File("/")
            val pb = ProcessBuilder(*cmd).directory(dir)
            pb.redirectErrorStream(true)
            val newEnv = pb.environment()
            for (kv in env) {
                val idx = kv.indexOf('=')
                if (idx > 0) newEnv[kv.substring(0, idx)] = kv.substring(idx + 1)
            }
            val p = pb.start()
            process = p
            pid = try { p.pid().toInt() } catch (e: Throwable) { -1 }
            stdinStream = p.outputStream
            isRunning = true
            startReader(p.inputStream)
            startWaiter()
        } catch (e: Exception) {
            NoxsLog.e("TerminalSession", "pipe start failed", e)
            val errMsg = "\r\n\u001b[1;31m[Noxs] Failed to start session: ${e.message}\u001b[0m\r\n"
            emulator.write(errMsg.toByteArray(Charsets.UTF_8))
            isRunning = false
            exitCode = 127
            client.onSessionFinished(this)
        }
    }

    private fun startReader(stream: InputStream) {
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        receivedBytes += n
                        lastOutputAtMs = System.currentTimeMillis()
                        val promptWasActive = emulator.promptActive
                        emulator.write(buf, n)
                        if (promptWasActive && emulator.promptActive && isPty && isRunning && masterFd >= 0 &&
                            containsLineBreak(buf, n)) {
                            requestPromptRedraw()
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) NoxsLog.w("TerminalSession", "reader ended: ${e.javaClass.simpleName}")
            }
        }, "noxs-reader-$pid").apply { isDaemon = true; start() }
    }

    private fun containsLineBreak(data: ByteArray, length: Int): Boolean {
        for (i in 0 until length) {
            if (data[i] == '\n'.code.toByte() || data[i] == '\r'.code.toByte()) return true
        }
        return false
    }

    private var lastPromptRedrawNanos = 0L

    private fun requestPromptRedraw() {
        val now = System.nanoTime()
        if (now - lastPromptRedrawNanos < 80_000_000L) return
        lastPromptRedrawNanos = now
        runCatching { NativePty.requestRedraw(pid, masterFd) }
    }

    private fun startWaiter() {
        Thread({
            val code: Int = if (isPty) {
                NativePty.waitFor(pid)
            } else {
                try { process?.waitFor() ?: -1 } catch (e: InterruptedException) { -1 }
            }
            // Brief grace period for reader thread to drain final buffered bytes from PTY
            try { Thread.sleep(40) } catch (_: InterruptedException) {}
            exitCode = code
            isRunning = false
            cleanup()
            client.onSessionFinished(this)
        }, "noxs-waiter-$pid").apply { isDaemon = true; start() }
    }

    private class FdInputStream(private val fd: Int) : InputStream() {
        // Blocking reads performed by the native bridge (no reflection needed).
        override fun read(): Int {
            val b = ByteArray(1)
            return if (NativePty.readBytes(fd, b, 0, 1) == 1) b[0].toInt() and 0xff else -1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = NativePty.readBytes(fd, b, off, len)
    }

    /** Write input bytes (keyboard/paste) to the child. */
    @Synchronized
    fun write(data: ByteArray) {
        try {
            if (isPty) NativePty.writeBytes(masterFd, data) else stdinStream?.write(data)
            stdinStream?.flush()
        } catch (e: Exception) {
            NoxsLog.w("TerminalSession", "write failed: ${e.message}")
        }
    }

    fun sendSignal(signal: Int) {
        if (isPty) NativePty.sendSignal(pid, signal)
        else process?.destroy()
    }

    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0) return
        rows = newRows
        cols = newCols
        emulator.resize(newCols, newRows)
        if (isPty && masterFd >= 0) NativePty.setSize(masterFd, newRows, newCols)
    }

    fun kill() {
        if (isRunning) {
            if (isPty) {
                NativePty.sendSignal(pid, 15) // SIGTERM
                Thread.sleep(150)
                if (isRunning) NativePty.sendSignal(pid, 9)
            } else process?.destroy()
        }
    }

    private fun cleanup() {
        if (isPty && masterFd >= 0) {
            try { NativePty.closeFd(masterFd) } catch (ignored: Throwable) {}
            masterFd = -1
        }
        try { stdinStream?.close() } catch (ignored: Exception) {}
    }

    // ---- emulator client (dispatched to session client by the app layer) ----

    private val pendingChange = AtomicBoolean(false)

    /** Pluggable main-loop dispatcher — set by the Android layer; defaults to inline. */
    var mainThreadDispatcher: ((Runnable) -> Unit)? = null

    private fun dispatch(r: Runnable) {
        val d = mainThreadDispatcher
        if (d != null) d(r) else r.run()
    }

    override fun onScreenChanged() {
        // Coalesce bursts of updates into one UI notification.
        if (!pendingChange.compareAndSet(false, true)) return
        dispatch {
            pendingChange.set(false)
            client.onTextChanged(this@TerminalSession)
        }
    }

    override fun onTitleChanged(title: String) {
        dispatch { client.onTitleChanged(this@TerminalSession) }
    }

    override fun onWorkingDirectoryChanged(dir: String) {
        dispatch { client.onWorkingDirectoryChanged(this@TerminalSession, dir) }
    }

    override fun onBell() {
        dispatch { client.onBell(this@TerminalSession) }
    }

    override fun onResize(cols: Int, rows: Int) { /* TIOCSWINSZ handled in resize() */ }

    override fun onReply(data: ByteArray) = write(data)
}
