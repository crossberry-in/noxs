/*
 * Noxs — original implementation.
 * RFB 3.8 client handshake for the NoxOS emulator display bridge.
 *
 * Pure stream logic, separated from the socket reader thread so the
 * byte-level contract is unit-testable on the JVM against a spec-accurate
 * synthetic server (and was NOT before: the inline copy in VncClient read
 * the ServerInit name length twice and every real QEMU connection died
 * with "bad server name length").
 *
 * Order per RFC 6143 (RFB 3.8):
 *   ProtocolVersion (12 bytes)
 *   → number-of-security-types + types (3.7+; 3.3 sends a single u32)
 *   → client chooses None → SecurityResult u32 (3.8+)
 *   → ClientInit u8 (shared flag)
 *   → ServerInit: width u16, height u16, 16-byte pixel format,
 *     name-length u32, name bytes.
 */
package com.crossberry.noxs.emulator

import com.crossberry.noxs.shared.emulator.Rfb
import java.io.DataInputStream
import java.io.OutputStream

object VncHandshake {

    /** Sanity bound for the desktop name coming from a loopback QEMU. */
    const val MAX_NAME_LENGTH: Int = 64_000

    /**
     * Runs the full RFB 3.8 handshake and returns the parsed [Rfb.ServerInit].
     * Throws with a precise reason on any spec violation.
     */
    fun performHandshake(input: DataInputStream, out: OutputStream): Rfb.ServerInit {
        // 1. ProtocolVersion — negotiate down to the server's version.
        val handshake = ByteArray(12)
        input.readFully(handshake)
        if (!Rfb.isSupportedVersion(handshake)) {
            throw IllegalStateException(
                "unsupported RFB version: ${String(handshake, Charsets.US_ASCII)}"
            )
        }
        val minor = Rfb.versionMinor(handshake) ?: 3
        out.write(Rfb.PROTOCOL_REPLY.toByteArray(Charsets.US_ASCII))
        out.flush()

        // 2. Security: RFB 3.3 servers pick the type; 3.7+ send a list.
        if (minor == 3) {
            val chosen = readU32(input).toInt()
            if (chosen != Rfb.SECURITY_TYPE_NONE) {
                throw IllegalStateException("server requires auth type $chosen")
            }
        } else {
            val count = input.readUnsignedByte()
            if (count == 0) {
                val reasonLen = readU32(input).toInt()
                val reason = ByteArray(reasonLen)
                input.readFully(reason)
                throw IllegalStateException("server refused: ${String(reason, Charsets.UTF_8)}")
            }
            val types = ByteArray(count)
            input.readFully(types)
            if (!types.any { it.toInt() == Rfb.SECURITY_TYPE_NONE }) {
                throw IllegalStateException("no None security type offered")
            }
            out.write(Rfb.SECURITY_TYPE_NONE)
            out.flush()
            if (minor >= 8) {
                val result = readU32(input).toInt()
                if (result != 0) {
                    throw IllegalStateException("security handshake failed ($result)")
                }
            }
        }

        // 3. ClientInit (shared) + ServerInit.
        out.write(1) // share the desktop
        out.flush()
        // The ServerInit fixed part is exactly 24 bytes: width(2) + height(2)
        // + pixel format(16) + NAME-LENGTH(4). The name length lives IN this
        // block at offset 20 — reading another u32 from the stream instead
        // would consume the first 4 name bytes as a length (QEMU's default
        // desktop name "QEMU" = 0x51454D55 ≈ 1.3e9) and fail with
        // "bad server name length" on every real server (device regression).
        val fixed = ByteArray(24)
        input.readFully(fixed)
        val nameLen = Rfb.getU32(fixed, 20).toInt()
        require(nameLen in 0..MAX_NAME_LENGTH) { "bad server name length" }
        val nameBytes = ByteArray(nameLen)
        if (nameLen > 0) input.readFully(nameBytes)
        return Rfb.serverInit(fixed, nameBytes)
    }

    private fun readU32(input: DataInputStream): Long =
        input.readInt().toLong() and 0xFFFFFFFFL
}
