/*
 * Noxs — original implementation.
 * JVM tests for the setup live-operation tracker: elapsed formatting,
 * monotonic clock behavior, spinner cadence, completion/failure capture,
 * long-operation hint and background/foreground resilience.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupOpTrackerTest {

    private class Clock {
        var ms: Long = 0L
        fun now(): Long = ms
    }

    private fun tracker(clock: Clock) = SetupOpTracker { clock.now() }

    // ---- HH:MM:SS formatting (00:04 / 00:59 / 01:02:14) ----

    @Test
    fun `formatElapsed renders MM_SS below one hour`() {
        assertEquals("00:04", SetupOpTracker.formatElapsed(4_000L))
        assertEquals("00:59", SetupOpTracker.formatElapsed(59_000L))
        assertEquals("18:35", SetupOpTracker.formatElapsed(18 * 60_000L + 35_000L))
    }

    @Test
    fun `formatElapsed renders H_MM_SS at and above one hour`() {
        assertEquals("01:02:14", SetupOpTracker.formatElapsed(3_734_000L))
        assertEquals("01:00:00", SetupOpTracker.formatElapsed(3_600_000L))
        assertEquals("12:00:00", SetupOpTracker.formatElapsed(12 * 3_600_000L))
    }

    @Test
    fun `formatElapsed clamps negative values`() {
        assertEquals("00:00", SetupOpTracker.formatElapsed(-5_000L))
    }

    // ---- lifecycle: start → elapsed → finish ----

    @Test
    fun `start records the operation and elapsed grows monotonically`() {
        val clock = Clock()
        val tracker = tracker(clock)
        assertNull(tracker.current)

        tracker.start("Checking for interrupted dpkg configuration")
        assertEquals("Checking for interrupted dpkg configuration", tracker.current?.name)

        clock.ms += 1_000L
        assertEquals(1_000L, tracker.elapsedMs())
        clock.ms += 17_000L
        assertEquals(18_000L, tracker.elapsedMs())
    }

    @Test
    fun `finish captures the real elapsed and clears the operation`() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start("Preparing workspace")
        clock.ms += 42_000L

        val done = tracker.finish(success = true)
        assertEquals(42_000L, done?.finalMs)
        assertFalse(done?.failed ?: true)
        assertNull(tracker.current)
    }

    @Test
    fun `failure keeps the final elapsed and marks failed`() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start("Refreshing signed Ubuntu package metadata")
        clock.ms += 7_500L

        val done = tracker.finish(success = false)
        assertEquals(7_500L, done?.finalMs)
        assertTrue(done?.failed ?: false)
    }

    @Test
    fun `finish without a running operation is a no-op`() {
        val tracker = tracker(Clock())
        assertNull(tracker.finish(success = true))
    }

    // ---- spinner cadence (100 ms per frame, 10 frames) ----

    @Test
    fun `spinner frames advance every 100 ms and cycle`() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start("Preparing environment")

        // Frames: ⠋(0) ⠙(1) ⠹(2) ⠸(3) ⠼(4) ⠴(5) ⠦(6) ⠧(7) ⠇(8) ⠏(9)
        assertEquals('⠋', tracker.spinnerFrame()) // t=0
        clock.ms += 100L
        assertEquals('⠙', tracker.spinnerFrame()) // t=100
        clock.ms += 100L
        assertEquals('⠹', tracker.spinnerFrame()) // t=200
        clock.ms += 100L
        assertEquals('⠸', tracker.spinnerFrame()) // t=300

        // After a full cycle the frames repeat.
        clock.ms += 700L
        assertEquals('⠋', tracker.spinnerFrame()) // t=1000 → wraps to frame 0
        clock.ms += 900L
        assertEquals('⠏', tracker.spinnerFrame()) // t=1900 → frame 19 % 10 = 9
    }

    @Test
    fun `spinner is static when idle`() {
        val tracker = tracker(Clock())
        assertEquals('⠋', tracker.spinnerFrame())
        assertEquals('⠋', tracker.spinnerFrame())
    }

    // ---- long-running hint ----

    @Test
    fun `long operation hint fires once at the threshold`() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start("Installing required packages")

        clock.ms += 59_000L
        assertFalse(tracker.consumeLongOpHint())

        clock.ms += 2_000L // 61 s
        assertTrue(tracker.consumeLongOpHint())
        assertFalse(tracker.consumeLongOpHint()) // one-shot per operation
        assertFalse(tracker.consumeLongOpHint())
    }

    @Test
    fun `starting a new operation re-arms the hint`() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start("Preparing environment")
        clock.ms += 61_000L
        assertTrue(tracker.consumeLongOpHint())

        tracker.finish(true)
        tracker.start("Creating Linux account")
        assertFalse(tracker.consumeLongOpHint())
        clock.ms += 60_000L
        assertTrue(tracker.consumeLongOpHint())
    }

    // ---- background / foreground: monotonic clock ----

    @Test
    fun `elapsed survives a simulated background pause`() {
        // The clock is monotonic: an app going to background can only advance
        // it, never rewind — so the timer never resets when UI is recreated.
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start("Verifying filesystem")
        clock.ms += 3_000L

        val elapsedBeforeBackground = tracker.elapsedMs()
        // App goes to background for 30 s (no ticks happen, UI is gone).
        clock.ms += 30_000L
        // App returns: elapsed reflects real monotonic time, not zero.
        assertEquals(elapsedBeforeBackground + 30_000L, tracker.elapsedMs())
    }

    @Test
    fun `operation change resets elapsed for the new operation`() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start("Preparing workspace")
        clock.ms += 25_000L
        tracker.finish(true)

        tracker.start("Preparing secure connections")
        assertEquals(0L, tracker.elapsedMs())
    }
}
