/*
 * Noxs — original implementation.
 * RFB (VNC) protocol codec — pure byte-level logic for the NoxOS emulator
 * display bridge (Noxs × NoxOS M6). Binds to 127.0.0.1 only; RFB 3.8 with
 * the None security type (localhost-only, no password on the wire).
 *
 * This object is JVM-pure (no Android imports) so the message layouts are
 * unit tested in noxs-shared and mirrored 1:1 by the on-device socket
 * client (app module VncClient) and the QEMU verification harness
 * (tools/emulator/verify_vnc_path.py).
 */
package com.crossberry.noxs.shared.emulator

import java.io.ByteArrayOutputStream

object Rfb {

    /** Highest protocol version this client speaks (RFB 3.8). */
    const val PROTOCOL_REPLY: String = "RFB 003.008\n"

    // Server → client message types
    const val MSG_FRAMEBUFFER_UPDATE: Int = 0
    const val MSG_SET_COLOUR_MAP_ENTRIES: Int = 1
    const val MSG_BELL: Int = 2
    const val MSG_SERVER_CUT_TEXT: Int = 3

    // Client → server message types
    const val SET_PIXEL_FORMAT: Int = 0
    const val SET_ENCODINGS: Int = 2
    const val FRAMEBUFFER_UPDATE_REQUEST: Int = 3
    const val KEY_EVENT: Int = 4
    const val POINTER_EVENT: Int = 5

    // Encodings
    const val ENC_RAW: Int = 0
    const val ENC_PSEUDO_DESKTOP_RESIZE: Int = -223

    const val SECURITY_TYPE_NONE: Int = 1

    data class ServerInit(
        val width: Int,
        val height: Int,
        val name: String
    )

    /**
     * True when the server's 12-byte ProtocolVersion handshake is parseable
     * RFB 3.x. We negotiate down from 003.008; the server picks the version.
     */
    fun isSupportedVersion(handshake: ByteArray): Boolean {
        if (handshake.size < 12) return false
        val text = String(handshake, 0, 12, Charsets.US_ASCII)
        if (!text.startsWith("RFB 003.")) return false
        val minor = text.substring(8, 11).toIntOrNull() ?: return false
        return minor in 3..8
    }

    /** Server version minor (3..8) parsed from the 12-byte handshake, or null. */
    fun versionMinor(handshake: ByteArray): Int? {
        if (handshake.size < 12) return null
        val text = String(handshake, 0, 12, Charsets.US_ASCII)
        if (!text.startsWith("RFB 003.")) return null
        return text.substring(8, 11).trim().toIntOrNull()
    }

    /**
     * The pixel format we request: 32 bits-per-pixel, little-endian
     * (QEMU on ARM hosts is LE), true-colour 24-bit depth, R:G:B in the
     * high/low bytes such that a raw pixel equals the Android ARGB_8888
     * layout shifted by an alpha fix. Raw pixel bytes arrive as B,G,R,x
     * (x = alpha filler).
     */
    fun pixelFormatBytes(): ByteArray {
        val b = ByteArray(16)
        b[0] = 32            // bits-per-pixel
        b[1] = 24            // depth
        b[2] = 0             // big-endian flag: 0 = little-endian
        b[3] = 1             // true-colour flag
        putU16(b, 4, 255)    // red-max
        putU16(b, 6, 255)    // green-max
        putU16(b, 8, 255)    // blue-max
        b[10] = 16           // red-shift
        b[11] = 8            // green-shift
        b[12] = 0            // blue-shift
        b[13] = 0; b[14] = 0; b[15] = 0 // padding
        return b
    }

    /** SetPixelFormat message (type 0, 3 pad bytes, 16-byte pixel format). */
    fun setPixelFormatMessage(): ByteArray {
        val out = ByteArrayOutputStream(20)
        out.write(SET_PIXEL_FORMAT)
        out.write(0); out.write(0); out.write(0)
        out.write(pixelFormatBytes())
        return out.toByteArray()
    }

    /** SetEncodings message requesting only the raw encoding (type 2). */
    fun setEncodingsRawMessage(): ByteArray {
        val out = ByteArrayOutputStream(8)
        out.write(SET_ENCODINGS)
        out.write(0)          // padding
        putU16(out, 1)        // number of encodings
        putS32(out, ENC_RAW.toLong())
        return out.toByteArray()
    }

    /** FramebufferUpdateRequest (type 3): incremental flag + rectangle. */
    fun framebufferUpdateRequest(
        incremental: Boolean,
        x: Int, y: Int, w: Int, h: Int
    ): ByteArray {
        val out = ByteArrayOutputStream(10)
        out.write(FRAMEBUFFER_UPDATE_REQUEST)
        out.write(if (incremental) 1 else 0)
        putU16(out, x); putU16(out, y); putU16(out, w); putU16(out, h)
        return out.toByteArray()
    }

    /** KeyEvent (type 4): down flag + X11 keysym. */
    fun keyEvent(keysym: Int, down: Boolean): ByteArray {
        val out = ByteArrayOutputStream(8)
        out.write(KEY_EVENT)
        out.write(if (down) 1 else 0)
        out.write(0); out.write(0) // padding
        putU32(out, keysym.toLong() and 0xFFFFFFFFL)
        return out.toByteArray()
    }

    /** PointerEvent (type 5): button bit-mask + position. */
    fun pointerEvent(x: Int, y: Int, buttonMask: Int): ByteArray {
        val out = ByteArrayOutputStream(6)
        out.write(POINTER_EVENT)
        out.write(buttonMask and 0xFF)
        putU16(out, x); putU16(out, y)
        return out.toByteArray()
    }

    /**
     * Parses the 24-byte fixed part of ServerInit (after ClientInit):
     * u16 width, u16 height, 16-byte pixel format, u32 name length,
     * followed by the name bytes ([nameBytes]). The name-length field is
     * authoritative — [nameBytes] must carry exactly that many bytes, or
     * the stream is out of sync and must not be trusted further.
     */
    fun serverInit(fixed: ByteArray, nameBytes: ByteArray): ServerInit {
        require(fixed.size >= 24) { "ServerInit fixed part must be 24 bytes" }
        val width = getU16(fixed, 0)
        val height = getU16(fixed, 2)
        val nameLen = getU32(fixed, 20).toInt()
        require(nameBytes.size == nameLen) {
            "ServerInit name mismatch: field says $nameLen, got ${nameBytes.size} bytes"
        }
        val name = String(nameBytes, Charsets.ISO_8859_1)
        return ServerInit(width, height, name)
    }

    /**
     * Decodes one raw-encoded rectangle (w*h pixels, 4 bytes each in the
     * pixel format we set: little-endian B,G,R,x) into an ARGB int array
     * ready for Bitmap.setPixels. The alpha byte QEMU sends is a filler —
     * force it opaque so the view does not render transparent black.
     */
    fun decodeRawRect(w: Int, h: Int, bytes: ByteArray, offset: Int): IntArray {
        val count = w * h
        require(offset + count * 4 <= bytes.size) { "raw rect truncated" }
        val pixels = IntArray(count)
        var p = offset
        for (i in 0 until count) {
            val blue = bytes[p].toInt() and 0xFF
            val green = bytes[p + 1].toInt() and 0xFF
            val red = bytes[p + 2].toInt() and 0xFF
            pixels[i] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            p += 4
        }
        return pixels
    }

    // ------------------------------------------------------------- helpers

    fun putU16(b: ByteArray, at: Int, v: Int) {
        b[at] = ((v ushr 8) and 0xFF).toByte()
        b[at + 1] = (v and 0xFF).toByte()
    }

    /** ByteArray variant used by tests to synthesize server messages. */
    fun putU32(b: ByteArray, at: Int, v: Long) {
        b[at] = ((v ushr 24) and 0xFFL).toByte()
        b[at + 1] = ((v ushr 16) and 0xFFL).toByte()
        b[at + 2] = ((v ushr 8) and 0xFFL).toByte()
        b[at + 3] = (v and 0xFFL).toByte()
    }

    fun putU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    fun putU32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v ushr 24) and 0xFFL).toInt())
        out.write(((v ushr 16) and 0xFFL).toInt())
        out.write(((v ushr 8) and 0xFFL).toInt())
        out.write((v and 0xFFL).toInt())
    }

    fun putS32(out: ByteArrayOutputStream, v: Long) = putU32(out, v)

    fun getU16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    fun getU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)
}
