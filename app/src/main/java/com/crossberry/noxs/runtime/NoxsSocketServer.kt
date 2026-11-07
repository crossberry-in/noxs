/*
 * Noxs — original implementation.
 * Android↔Ubuntu unix-socket bridge.
 *
 * Two directions (docs/UNIX-SOCKETS.md):
 *  1. Control socket (this file): an Android LocalServerSocket on the abstract
 *     namespace ("noxs.control") serving JSON status queries to the app UI.
 *  2. Filesystem sockets: processes inside the Ubuntu userspace create real
 *     AF_UNIX sockets under /var/run/noxs/ (physically inside the app-private
 *     rootfs). Android apps can connect to them through the physical path —
 *     no privilege escalation involved, everything stays in the sandbox.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.BuildConfig
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

class NoxsSocketServer(
    private val paths: NoxsPaths,
    private val sessions: NoxsSessionManager?
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var server: android.net.LocalServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                val s = android.net.LocalServerSocket(CONTROL_SOCKET_ABSTRACT)
                server = s
                NoxsLog.i("SocketServer", "control socket live: @${CONTROL_SOCKET_ABSTRACT}")
                while (running.get()) {
                    val conn = s.accept() ?: break
                    handle(conn)
                }
            } catch (e: Exception) {
                if (running.get()) NoxsLog.w("SocketServer", "control loop ended: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun handle(conn: android.net.LocalSocket) {
        scope.launch {
            try {
                conn.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.inputStream))
                    val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))
                    val request = reader.readLine()?.trim().orEmpty()
                    writer.write(responseFor(request))
                    writer.newLine()
                    writer.flush()
                }
            } catch (e: Exception) {
                NoxsLog.w("SocketServer", "request failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Minimal JSON protocol: "status" | "sessions" | "ping" → JSON line. */
    fun responseFor(request: String): String = when {
        request == "ping" -> """{"ok":true,"service":"noxs","version":"${BuildConfig.VERSION_NAME}"}"""
        request == "status" -> {
            val list = sessions?.sessions?.value.orEmpty()
            """{"ok":true,"running":true,"sessions":${list.size},"arch":"${firstAbi()}"}"""
        }
        request == "sessions" -> {
            val list = sessions?.sessions?.value.orEmpty()
            val items = list.joinToString(",") {
                """"${it.label.replace("\"", "")}(${if (it.session.isRunning) "running" else "exited"})""""
            }
            """{"ok":true,"items":[$items]}"""
        }
        request.isBlank() -> """{"ok":false,"error":"empty request"}"""
        else -> """{"ok":false,"error":"unknown request '${
            request.replace("\"", "").take(64)
        }'"}"""
    }

    private fun firstAbi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    fun stop() {
        running.set(false)
        try { server?.close() } catch (ignored: Exception) {}
        scope.cancel()
    }

    companion object {
        const val CONTROL_SOCKET_ABSTRACT = "noxs.control"

        /**
         * Physical path for a virtual socket path inside the rootfs — lets
         * Android clients connect to Ubuntu-side AF_UNIX sockets.
         */
        fun physicalSocketPath(paths: NoxsPaths, virtualPath: String): String =
            com.crossberry.noxs.shared.SocketPathValidator.physicalPath(
                paths.rootfs.absolutePath, virtualPath
            )
    }
}
