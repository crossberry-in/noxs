/*
 * Noxs — original implementation.
 * Unit tests for the RFB (VNC) codec: message layouts must be byte-exact
 * per RFC 6143 — these tests mirror what the QEMU verification harness
 * (tools/emulator/verify_vnc_path.py) proves against the real server.
 */
package com.crossberry.noxs.shared.emulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbTest {

    @Test
    fun `protocol version gate accepts RFB 3x and rejects garbage`() {
        assertTrue(Rfb.isSupportedVersion("RFB 003.008\n".toByteArray()))
        assertTrue(Rfb.isSupportedVersion("RFB 003.003\n".toByteArray()))
        assertFalse(Rfb.isSupportedVersion("RFB 002.000\n".toByteArray()))
        assertFalse(Rfb.isSupportedVersion("HTTP/1.1 200 OK".toByteArray()))
        assertFalse(Rfb.isSupportedVersion(ByteArray(4)))
        assertEquals(8, Rfb.versionMinor("RFB 003.008\n".toByteArray()))
        assertEquals(3, Rfb.versionMinor("RFB 003.003\n".toByteArray()))
        assertNull(Rfb.versionMinor("RFB 002.000\n".toByteArray()))
    }

    @Test
    fun `pixel format is 32bpp LE truecolor rgb shifts 16_8_0`() {
        val b = Rfb.pixelFormatBytes()
        assertEquals(16, b.size)
        assertEquals(32, b[0].toInt())
        assertEquals(24, b[1].toInt())
        assertEquals(0, b[2].toInt()) // little-endian
        assertEquals(1, b[3].toInt()) // true colour
        assertEquals(255, Rfb.getU16(b, 4))
        assertEquals(255, Rfb.getU16(b, 6))
        assertEquals(255, Rfb.getU16(b, 8))
        assertEquals(16, b[10].toInt())
        assertEquals(8, b[11].toInt())
        assertEquals(0, b[12].toInt())
    }

    @Test
    fun `set pixel format message layout`() {
        val m = Rfb.setPixelFormatMessage()
        assertEquals(20, m.size)
        assertEquals(Rfb.SET_PIXEL_FORMAT, m[0].toInt())
        assertEquals(0, m[1].toInt())
        assertEquals(0, m[2].toInt())
        assertEquals(0, m[3].toInt())
        assertEquals(32, m[4].toInt())
    }

    @Test
    fun `set encodings raw message layout`() {
        val m = Rfb.setEncodingsRawMessage()
        assertEquals(8, m.size)
        assertEquals(Rfb.SET_ENCODINGS, m[0].toInt())
        assertEquals(0, m[1].toInt())
        assertEquals(1, Rfb.getU16(m, 2)) // one encoding
        assertEquals(Rfb.ENC_RAW.toLong(), Rfb.getU32(m, 4))
    }

    @Test
    fun `framebuffer update request layout`() {
        val full = Rfb.framebufferUpdateRequest(false, 0, 0, 720, 1280)
        assertEquals(10, full.size)
        assertEquals(Rfb.FRAMEBUFFER_UPDATE_REQUEST, full[0].toInt())
        assertEquals(0, full[1].toInt())
        assertEquals(720, Rfb.getU16(full, 6))
        assertEquals(1280, Rfb.getU16(full, 8))

        val incremental = Rfb.framebufferUpdateRequest(true, 0, 0, 720, 1280)
        assertEquals(1, incremental[1].toInt())
    }

    @Test
    fun `key event layout — down and up`() {
        val down = Rfb.keyEvent(0xFF0D, true)
        assertEquals(8, down.size)
        assertEquals(Rfb.KEY_EVENT, down[0].toInt())
        assertEquals(1, down[1].toInt())
        assertEquals(0xFF0D.toLong(), Rfb.getU32(down, 4))

        val up = Rfb.keyEvent(0x61, false)
        assertEquals(0, up[1].toInt())
        assertEquals(0x61.toLong(), Rfb.getU32(up, 4))
    }

    @Test
    fun `pointer event layout`() {
        val m = Rfb.pointerEvent(360, 640, 1)
        assertEquals(6, m.size)
        assertEquals(Rfb.POINTER_EVENT, m[0].toInt())
        assertEquals(1, m[1].toInt())
        assertEquals(360, Rfb.getU16(m, 2))
        assertEquals(640, Rfb.getU16(m, 4))
    }

    @Test
    fun `server init parses width height and name`() {
        val fixed = ByteArray(24)
        Rfb.putU16(fixed, 0, 720)
        Rfb.putU16(fixed, 2, 1280)
        Rfb.putU32(fixed, 20, 5L)
        val name = "NoxOS".toByteArray(Charsets.ISO_8859_1)
        val init = Rfb.serverInit(fixed, name)
        assertEquals(720, init.width)
        assertEquals(1280, init.height)
        assertEquals("NoxOS", init.name)
    }

    @Test
    fun `raw rect decode produces opaque ARGB`() {
        // little-endian B,G,R,x per pixel
        val bytes = byteArrayOf(
            0x10, 0x20, 0x30, 0x00, // pixel 0: b=0x10 g=0x20 r=0x30
            0xFF.toByte(), 0x40, 0x00, 0x7F.toByte() // pixel 1
        )
        val pixels = Rfb.decodeRawRect(2, 1, bytes, 0)
        assertEquals(2, pixels.size)
        assertEquals(0xFF302010.toInt(), pixels[0])
        assertEquals(0xFF0040FF.toInt(), pixels[1]) // alpha filler forced opaque
    }

    @Test
    fun `u16 u32 big endian helpers round trip`() {
        val b = ByteArray(6)
        Rfb.putU16(b, 0, 0x1234)
        Rfb.putU16(b, 2, 0x00FF)
        assertEquals(0x1234, Rfb.getU16(b, 0))
        assertEquals(0x00FF, Rfb.getU16(b, 2))
        Rfb.putU32(b, 2, 0xDEADBEEFL)
        assertEquals(0xDEADBEEFL, Rfb.getU32(b, 2))
    }

    @Test
    fun `messages are byte-stream concatenation safe`() {
        // A real client writes handshake + messages back to back; ensure no
        // builder mutates shared state (each call returns a fresh array).
        val a = Rfb.setPixelFormatMessage()
        val b = Rfb.setPixelFormatMessage()
        assertArrayEquals(a, b)
    }
}
