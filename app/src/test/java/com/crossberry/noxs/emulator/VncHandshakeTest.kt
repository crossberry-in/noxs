package com.crossberry.noxs.emulator

import com.crossberry.noxs.shared.emulator.Rfb
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the RFB 3.8 client handshake.
 *
 * The inline handshake in VncClient used to read the ServerInit name length
 * TWICE: once inside the 24-byte fixed block (correct) and once more from
 * the stream (wrong) — so the first four bytes of the desktop name were
 * parsed as the length. Against a real QEMU (default name "QEMU" =
 * 0x51454D55 ≈ 1.3e9) every connection died with "bad server name length"
 * and the VM screen never appeared (device regression, 2026-11-08).
 * These tests speak to a spec-accurate synthetic QEMU server stream.
 */
class VncHandshakeTest {

    // ------------------------------------------------------------- helpers

    /** ServerInit fixed block: width, height, 16-byte pixel format, name length. */
    private fun serverFixed(width: Int, height: Int, nameLen: Long): ByteArray {
        val b = ByteArray(24)
        Rfb.putU16(b, 0, width)
        Rfb.putU16(b, 2, height)
        // pixel format bytes 4..19 stay zero — serverInit does not interpret them
        Rfb.putU32(b, 20, nameLen)
        return b
    }

    /**
     * A spec-accurate synthetic RFB 3.8 server stream (what QEMU sends on
     * 127.0.0.1) plus a buffer capturing what the client writes back.
     */
    private fun qemuServer(
        name: String = "QEMU",
        trailing: ByteArray = byteArrayOf(0x00) // next message type would follow
    ): Pair<DataInputStream, ByteArrayOutputStream> {
        val wire = ByteArrayOutputStream()
        wire.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        wire.write(1)               // number-of-security-types
        wire.write(Rfb.SECURITY_TYPE_NONE)
        wire.write(0); wire.write(0); wire.write(0); wire.write(0) // SecurityResult = 0
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        wire.write(serverFixed(720, 1280, nameBytes.size.toLong()))
        wire.write(nameBytes)
        wire.write(trailing)
        val input = DataInputStream(ByteArrayInputStream(wire.toByteArray()))
        return Pair(input, ByteArrayOutputStream())
    }

    private fun securityFailureServer(nameLen: Long): DataInputStream {
        val wire = ByteArrayOutputStream()
        wire.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        wire.write(1)
        wire.write(Rfb.SECURITY_TYPE_NONE)
        wire.write(0); wire.write(0); wire.write(0); wire.write(0)
        wire.write(serverFixed(720, 1280, nameLen))
        return DataInputStream(ByteArrayInputStream(wire.toByteArray()))
    }

    // --------------------------------------------------------------- tests

    @Test
    fun `real qemu server init with default name parses without eating the name`() {
        val (stream, clientOut) = qemuServer()

        val init = VncHandshake.performHandshake(stream, clientOut)

        assertEquals(720, init.width)
        assertEquals(1280, init.height)
        assertEquals("QEMU", init.name)
    }

    @Test
    fun `bytes after the name stay in the stream for the message loop`() {
        val trailing = byteArrayOf(0x00, 0x00, 0x00, 0x2A)
        val (stream, clientOut) = qemuServer(trailing = trailing)

        VncHandshake.performHandshake(stream, clientOut)

        // exactly the 4 trailing bytes must remain — no over-read into them
        val rest = ByteArray(trailing.size)
        stream.readFully(rest)
        assertTrue(trailing.contentEquals(rest))
    }

    @Test
    fun `client writes protocol reply, security choice and client init`() {
        val (stream, clientOut) = qemuServer()

        VncHandshake.performHandshake(stream, clientOut)

        val expected = "RFB 003.008\n".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(Rfb.SECURITY_TYPE_NONE.toByte()) + // choose None
            byteArrayOf(1)                                  // ClientInit: shared
        assertTrue(expected.contentEquals(clientOut.toByteArray()))
    }

    @Test
    fun `absurd name length field is rejected before reading the name`() {
        // the exact u32 the old double-read produced from QEMU's name "QEMU"
        val qemuAsU32 = 0x51454D55L

        val ex = assertThrows(IllegalArgumentException::class.java) {
            VncHandshake.performHandshake(
                securityFailureServer(qemuAsU32),
                ByteArrayOutputStream()
            )
        }
        assertEquals("bad server name length", ex.message)
    }

    @Test
    fun `codec rejects name bytes that contradict the length field`() {
        val fixed = serverFixed(720, 1280, 4L)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            Rfb.serverInit(fixed, "QEMUX".toByteArray(Charsets.ISO_8859_1))
        }
        assertTrue(ex.message!!.contains("name mismatch"))
    }

    @Test
    fun `rfb 3-3 single security type path still completes`() {
        val wire = ByteArrayOutputStream()
        wire.write("RFB 003.003\n".toByteArray(Charsets.US_ASCII))
        // 3.3: server sends the chosen type as a single u32 (None = 1)
        wire.write(0); wire.write(0); wire.write(0); wire.write(Rfb.SECURITY_TYPE_NONE)
        val nameBytes = "QEMU".toByteArray(Charsets.ISO_8859_1)
        wire.write(serverFixed(720, 1280, nameBytes.size.toLong()))
        wire.write(nameBytes)

        val init = VncHandshake.performHandshake(
            DataInputStream(ByteArrayInputStream(wire.toByteArray())),
            ByteArrayOutputStream()
        )

        assertEquals("QEMU", init.name)
    }
}
