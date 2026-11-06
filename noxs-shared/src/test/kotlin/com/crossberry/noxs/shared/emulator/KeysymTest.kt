/*
 * Noxs — original implementation.
 * Unit tests for the keysym table and guest memory policy.
 */
package com.crossberry.noxs.shared.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysymTest {

    @Test
    fun `named keys map to X11 keysyms`() {
        assertEquals(0xFF0D, Keysym.forName("ENTER"))
        assertEquals(0xFF08, Keysym.forName("BACKSPACE"))
        assertEquals(0xFF09, Keysym.forName("TAB"))
        assertEquals(0xFF1B, Keysym.forName("ESC"))
        assertEquals(0xFF50, Keysym.forName("HOME"))
        assertEquals(0xFF51, Keysym.forName("LEFT"))
        assertEquals(0xFF52, Keysym.forName("UP"))
        assertEquals(0xFF53, Keysym.forName("RIGHT"))
        assertEquals(0xFF54, Keysym.forName("DOWN"))
        assertEquals(0xFFFF.toInt(), Keysym.forName("DELETE"))
    }

    @Test
    fun `function keys are contiguous F1 to F12`() {
        assertEquals(0xFFBE, Keysym.forName("F1"))
        assertEquals(0xFFC9, Keysym.forName("F12"))
    }

    @Test
    fun `modifiers identified for sticky handling`() {
        assertTrue(Keysym.isModifier("CTRL_L"))
        assertTrue(Keysym.isModifier("shift_l"))
        assertFalse(Keysym.isModifier("ENTER"))
    }

    @Test
    fun `latin1 printable maps identity`() {
        assertEquals(0x61, Keysym.forChar('a'))
        assertEquals(0x41, Keysym.forChar('A'))
        assertEquals(0x20, Keysym.forChar(' '))
        assertEquals(0x7E, Keysym.forChar('~'))
        assertNull(Keysym.forChar('\n'))
        assertNull(Keysym.forChar('\u0000'))
    }

    @Test
    fun `unknown name returns null`() {
        assertNull(Keysym.forName("NOT_A_KEY"))
        assertNull(Keysym.forName(""))
    }
}

class VmMemoryPolicyTest {

    @Test
    fun `tiers degrade gracefully on small devices`() {
        assertEquals(256, VmMemoryPolicy.chooseMemoryMb(1_000_000L))  // ~1 GiB
        assertEquals(384, VmMemoryPolicy.chooseMemoryMb(2_000_000L))  // ~2 GiB
        assertEquals(512, VmMemoryPolicy.chooseMemoryMb(6_000_000L))  // ~6 GiB
        assertEquals(512, VmMemoryPolicy.chooseMemoryMb(0L))
        assertEquals(512, VmMemoryPolicy.chooseMemoryMb(-1L))
    }

    @Test
    fun `meminfo parser extracts MemTotal`() {
        val meminfo = """
            MemTotal:       6028412 kB
            MemFree:         353980 kB
            Buffers:         124688 kB
        """.trimIndent()
        assertEquals(6028412L, VmMemoryPolicy.parseMemTotalKb(meminfo))
        assertNull(VmMemoryPolicy.parseMemTotalKb("no data here"))
    }
}
