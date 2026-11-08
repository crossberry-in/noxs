/*
 * Noxs — original implementation.
 * RFB (VNC) socket client for the NoxOS emulator display bridge (M6).
 *
 * Connects to 127.0.0.1 ONLY (QEMU -vnc is loopback-bound; spec forbids
 * exposing the display). Speaks RFB 3.8 with the None security type over
 * the shared codec (com.crossberry.noxs.shared.emulator.Rfb) and the RAW
 * encoding only — the NoxOS framebuffer is a single fixed 720×1280 layer,
 * so damage rects decode straight into ARGB for Bitmap.setPixels.
 *
 * Threading: one daemon reader thread owns the socket and the framebuffer
 * state; UI callbacks are invoked through [Listener] with a per-rect
 * snapshot (no shared mutable bitmap), and input methods are safe to call
 * from any thread.
 */
package com.crossberry.noxs.emulator

import com.crossberry.noxs.shared.emulator.Keysym
import com.crossberry.noxs.shared.emulator.Rfb
import com.crossberry.noxs.shared.NoxsLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class VncClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener
) {

    interface Listener {
        /** ServerInit parsed — allocate the framebuffer bitmap here. */
        fun onConnected(width: Int, height: Int, name: String)

        /** Desktop size changed (QEMU resize pseudo-encoding). */
        fun onResized(width: Int, height: Int)

        /** Raw rect decoded — [pixels] is a private copy, safe to keep. */
        fun onRect(x: Int, y: Int, w: Int, h: Int, pixels: IntArray)

        /** Socket closed; [error] is null on clean close. */
        fun onClosed(error: String?)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var dataOut: DataOutputStream? = null
    @Volatile private var closedByUs: Boolean = false

    /** The reader thread (update requests) and the UI thread (input) both
     *  write to the same stream — every write must hold this lock, or the
     *  RFB message stream can interleave mid-message. */
    private val writeLock = Any()

    fun start() {
        if (readerThread?.isAlive == true) return
        closedByUs = false
        val t = Thread({ runLoop() }, "noxos-vnc-$port")
        t.isDaemon = true
        readerThread = t
        t.start()
    }

    fun close() {
        closedByUs = true
        runCatching { socket?.close() }
    }

    // ------------------------------------------------------------- input

    fun sendKey(keysym: Int, down: Boolean) {
        send(Rfb.keyEvent(keysym, down))
    }

    fun sendChar(c: Char, down: Boolean) {
        Keysym.forChar(c)?.let { send(Rfb.keyEvent(it, down)) }
    }

    fun sendKeyByName(name: String, down: Boolean) {
        Keysym.forName(name)?.let { send(Rfb.keyEvent(it, down)) }
    }

    /** buttonMask: bit 0 = left button (the only one the touch path uses). */
    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        send(Rfb.pointerEvent(x, y, buttonMask))
    }

    fun requestIncrementalUpdate(width: Int, height: Int) {
        send(Rfb.framebufferUpdateRequest(true, 0, 0, width, height))
    }

    private fun send(bytes: ByteArray) {
        try {
            synchronized(writeLock) {
                dataOut?.write(bytes)
                dataOut?.flush()
            }
        } catch (e: Exception) {
            // send failures surface through the reader thread's onClosed
        }
    }

    // ------------------------------------------------------------- reader

    private fun runLoop() {
        var error: String? = null
        try {
            val s = Socket()
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
            val out = DataOutputStream(s.getOutputStream())
            dataOut = out

            // 1–3. ProtocolVersion + Security + ClientInit/ServerInit.
            // The byte-level contract lives in VncHandshake (unit tested
            // against a spec-accurate synthetic QEMU server stream).
            val init = VncHandshake.performHandshake(input, out)
            listener.onConnected(init.width, init.height, init.name)

            // 4. Our pixel format + raw encoding + first full update request.
            synchronized(writeLock) {
                out.write(Rfb.setPixelFormatMessage())
                out.write(Rfb.setEncodingsRawMessage())
                out.write(Rfb.framebufferUpdateRequest(false, 0, 0, init.width, init.height))
                out.flush()
            }

            // 5. Message loop: receive an update → request the next one.
            var width = init.width
            var height = init.height
            while (true) {
                val type = input.readUnsignedByte()
                when (type) {
                    Rfb.MSG_FRAMEBUFFER_UPDATE -> {
                        input.readUnsignedByte() // padding
                        val rects = input.readUnsignedShort()
                        repeat(rects) {
                            val x = input.readUnsignedShort()
                            val y = input.readUnsignedShort()
                            val w = input.readUnsignedShort()
                            val h = input.readUnsignedShort()
                            val encoding = readU32(input).toInt()
                            when (encoding) {
                                Rfb.ENC_RAW -> {
                                    val byteCount = w * h * 4
                                    if (byteCount <= 0 || w <= 0 || h <= 0) return@repeat
                                    require(byteCount <= MAX_RECT_BYTES) {
                                        "raw rect too large: $w×$h"
                                    }
                                    val bytes = ByteArray(byteCount)
                                    input.readFully(bytes)
                                    listener.onRect(x, y, w, h, Rfb.decodeRawRect(w, h, bytes, 0))
                                }
                                Rfb.ENC_PSEUDO_DESKTOP_RESIZE -> {
                                    width = w
                                    height = h
                                    listener.onResized(w, h)
                                    synchronized(writeLock) {
                                        out.write(Rfb.framebufferUpdateRequest(false, 0, 0, w, h))
                                        out.flush()
                                    }
                                }
                                else -> throw IllegalStateException(
                                    "server sent unsupported encoding $encoding"
                                )
                            }
                        }
                        synchronized(writeLock) {
                            out.write(Rfb.framebufferUpdateRequest(true, 0, 0, width, height))
                            out.flush()
                        }
                    }
                    Rfb.MSG_SET_COLOUR_MAP_ENTRIES -> {
                        input.readUnsignedByte() // padding
                        input.readUnsignedShort() // first colour
                        val n = input.readUnsignedShort()
                        if (n > 0) {
                            val skip = ByteArray(n * 6)
                            input.readFully(skip)
                        }
                    }
                    Rfb.MSG_BELL -> Unit
                    Rfb.MSG_SERVER_CUT_TEXT -> {
                        // Clipboard is not bridged in M2 scope, but the text
                        // MUST be consumed to keep the message stream in sync.
                        val len = input.readInt().toLong() and 0xFFFFFFFFL
                        require(len <= MAX_RECT_BYTES) { "cut text too large: $len" }
                        if (len > 0) {
                            val skip = ByteArray(len.toInt())
                            input.readFully(skip)
                        }
                    }
                    else -> throw IllegalStateException("unknown server message type $type")
                }
            }
        } catch (e: InterruptedException) {
            // close() during handshake
        } catch (e: Exception) {
            if (!closedByUs) {
                NoxsLog.w("VncClient", "connection ended: ${e.message}")
                error = e.message ?: e.javaClass.simpleName
            }
        } finally {
            runCatching { socket?.close() }
            dataOut = null
            listener.onClosed(error)
        }
    }

    private fun readU32(input: DataInputStream): Long =
        input.readInt().toLong() and 0xFFFFFFFFL

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Refuse absurd rects (a 720×1280 full frame is ~3.7 MB). */
        private const val MAX_RECT_BYTES = 16 * 1024 * 1024
    }
}
