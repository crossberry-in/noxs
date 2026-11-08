/*
 * Noxs — original implementation.
 * ReconnectPolicy contract tests: bounded ladder, exhaustion, reset.
 * Pure JVM, no Android runtime.
 */
package com.crossberry.noxs.shared.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `ladder grows exponentially and caps at 15 seconds`() {
        val p = ReconnectPolicy()
        assertEquals(1_000L, p.nextDelayMs())
        assertEquals(2_000L, p.nextDelayMs())
        assertEquals(4_000L, p.nextDelayMs())
        assertEquals(8_000L, p.nextDelayMs())
        assertEquals(15_000L, p.nextDelayMs()) // 16s capped to 15s
    }

    @Test
    fun `default bound is five attempts then exhaustion`() {
        val p = ReconnectPolicy()
        repeat(5) { i ->
            assertTrue("attempt ${i + 1} must be allowed", p.canRetry())
            assertTrue(p.nextDelayMs() > 0)
        }
        assertFalse(p.canRetry())
        assertEquals(-1L, p.nextDelayMs())
        assertEquals(5, p.attemptsUsed())
    }

    @Test
    fun `reset restores the fresh ladder`() {
        val p = ReconnectPolicy()
        repeat(5) { p.nextDelayMs() }
        assertFalse(p.canRetry())
        p.reset()
        assertTrue(p.canRetry())
        assertEquals(0, p.attemptsUsed())
        assertEquals(1_000L, p.nextDelayMs())
    }

    @Test
    fun `custom tighter bound is honoured`() {
        val p = ReconnectPolicy(maxAttempts = 2)
        assertTrue(p.nextDelayMs() > 0)
        assertTrue(p.nextDelayMs() > 0)
        assertFalse(p.canRetry())
        assertEquals(-1L, p.nextDelayMs())
    }

    @Test
    fun `zero bound never schedules`() {
        val p = ReconnectPolicy(maxAttempts = 0)
        assertFalse(p.canRetry())
        assertEquals(-1L, p.nextDelayMs())
    }
}
