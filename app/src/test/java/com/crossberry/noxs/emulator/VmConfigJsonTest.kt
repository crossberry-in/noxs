/*
 * Noxs — original implementation.
 * Unit tests for the VM config JSON round-trip (Noxs × NoxOS M2, spec §5).
 */
package com.crossberry.noxs.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmConfigJsonTest {

    private val config = VmConfig(
        memoryMb = 512,
        kernelAssetVersion = "0.9.0",
        createdAtMs = 1_700_000_000_000L,
        lastBootAtMs = 1_700_000_100_000L
    )

    @Test
    fun `write then parse round trips every field`() {
        val text = VmConfigJson.write(config)
        val parsed = VmConfigJson.parse(text)
        assertEquals(config, parsed)
    }

    @Test
    fun `null lastBoot survives the round trip`() {
        val text = VmConfigJson.write(config.copy(lastBootAtMs = null))
        assertTrue(text.contains("\"lastBootAtMs\""))
        assertEquals(config.copy(lastBootAtMs = null), VmConfigJson.parse(text))
    }

    @Test
    fun `display block is nested`() {
        val text = VmConfigJson.write(config)
        assertTrue(text.contains("\"display\""))
        assertTrue(text.contains("\"width\": 720"))
        assertTrue(text.contains("\"height\": 1280"))
        assertTrue(text.contains("\"vncBind\": \"127.0.0.1\""))
    }

    @Test
    fun `malformed json parses to null instead of throwing`() {
        assertNull(VmConfigJson.parse("not json at all"))
        assertNull(VmConfigJson.parse("{\"memoryMb\": \"oops\"}"))
        assertNull(VmConfigJson.parse(""))
    }

    @Test
    fun `partial json keeps documented defaults`() {
        val parsed = VmConfigJson.parse("{\"memoryMb\": 384}")
        assertEquals(384, parsed?.memoryMb)
        assertEquals("noxos", parsed?.vmId)
        assertEquals(720, parsed?.displayWidth)
        assertEquals(1280, parsed?.displayHeight)
        assertEquals("127.0.0.1", parsed?.vncBind)
    }
}
