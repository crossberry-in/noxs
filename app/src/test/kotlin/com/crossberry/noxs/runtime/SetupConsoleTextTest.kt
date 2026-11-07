/*
 * Noxs — original implementation.
 * JVM tests for the setup console's width-aware live lines. These pin the
 * root-cause fix for the "stuck repeating [ Noxs ] Installing or repairing
 * ca-certificate" flood: a live line rewritten every 100 ms must NEVER
 * exceed the terminal's real column count — an overflowing line wraps, and
 * each rewrite then spills a new screen row carrying the same status.
 */
package com.crossberry.noxs.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupConsoleTextTest {

    private val realOpNames = listOf(
        "Preparing environment",
        "Verifying filesystem",
        "Preparing workspace",
        "Downloading Ubuntu 24.04 LTS",
        "Extracting rootfs",
        "Creating Linux account",
        "Preparing secure connections",
        "Checking repositories",
        "Checking for interrupted dpkg configuration",
        "Refreshing signed Ubuntu package metadata",
        "Installing ca-certificates",
        "Updating certificate bundle",
        "Verifying certificates",
        "Installing Ubuntu archive keyring",
        "Repairing pending dpkg configuration",
        "Installing required packages",
        // The pre-fix name that produced the reported flood:
        "Installing or repairing ca-certificates"
    )

    private fun visibleLength(line: Pair<String, String>): Int =
        line.first.length + if (line.first.isEmpty()) line.second.length else 1 + line.second.length

    @Test
    fun `op line fits every realistic phone width`() {
        val widths = listOf(24, 34, 40, 48, 50, 57, 60, 72, 80)
        for (width in widths) {
            for (name in realOpNames) {
                val line = SetupConsoleText.opLine(name, '⠋', "00:05", width)
                assertTrue(
                    "width=$width name='$name' visible=${visibleLength(line)}",
                    visibleLength(line) <= width
                )
            }
        }
    }

    @Test
    fun `op line keeps the prefix and full name when they fit`() {
        val line = SetupConsoleText.opLine("Installing ca-certificates", '⠋', "00:07", 80)
        assertEquals("[ Noxs ]", line.first)
        assertEquals("⠋ Installing ca-certificates  00:07", line.second)
    }

    @Test
    fun `op line truncates with ellipsis instead of wrapping`() {
        val line = SetupConsoleText.opLine("Installing or repairing ca-certificates", '⠸', "01:02", 40)
        assertTrue(visibleLength(line) <= 40)
        assertTrue(line.second.contains("…"))
        // Elapsed time always survives truncation.
        assertTrue(line.second.endsWith("01:02"))
    }

    @Test
    fun `very narrow terminal falls back to the compact frame plus elapsed form`() {
        val line = SetupConsoleText.opLine("Installing ca-certificates", '⠹', "00:59", 20)
        assertEquals("", line.first)
        assertEquals("⠹ 00:59", line.second)
        assertTrue(line.second.length <= 20)
    }

    @Test
    fun `degenerate widths never crash and never overflow`() {
        for (width in intArrayOf(0, 1, 4, 8, 12, 23)) {
            val line = SetupConsoleText.opLine("Any operation name here", '⠇', "10:00", width)
            assertTrue(visibleLength(line) <= maxOf(width, SetupConsoleText.compactLine('⠇', "10:00", width).length))
        }
    }

    @Test
    fun `safeWidth falls back to 80 outside the plausible range`() {
        assertEquals(80, SetupConsoleText.safeWidth(0))
        assertEquals(80, SetupConsoleText.safeWidth(7))
        assertEquals(80, SetupConsoleText.safeWidth(501))
        assertEquals(42, SetupConsoleText.safeWidth(42))
    }

    @Test
    fun `live padding never pushes a rewrite past the width`() {
        val plain = "[ Noxs ] 12.3 / 48.0 MB (25%)"
        for (width in listOf(20, 30, 40, 50, 80)) {
            val pad = SetupConsoleText.livePadding(plain.length, width)
            assertTrue(plain.length + pad < width || pad == 0)
        }
        assertEquals(0, SetupConsoleText.livePadding(60, 40))
    }

    @Test
    fun `the reported flooding name is clamped on narrow consoles`() {
        // Pre-fix visible width of the full line: 57 columns — wider than
        // most portrait phone terminals, which made every tick wrap.
        val name = "Installing or repairing ca-certificates"
        for (width in listOf(34, 40, 48, 50, 56)) {
            val line = SetupConsoleText.opLine(name, '⠋', "00:05", width)
            assertFalse(
                "the old unclamped line must never come back (width=$width)",
                visibleLength(line) > width
            )
        }
        assertTrue(SetupConsoleText.OP_LINE_FIXED_OVERHEAD + name.length > 56)
    }
}
