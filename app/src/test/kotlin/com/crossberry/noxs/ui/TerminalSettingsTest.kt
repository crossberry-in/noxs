/*
 * Noxs — original implementation.
 * JVM tests for terminal settings persistence: defaults, clamping at the
 * spec bounds, round-trips, single-key updates and the reset contract
 * (terminal.* keys only — nothing else in the shared settings file).
 */
package com.crossberry.noxs.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory key-value store mirroring SharedPreferences semantics. */
private class InMemoryTerminalPrefs : TerminalPrefs {
    val ints = HashMap<String, Int>()
    val booleans = HashMap<String, Boolean>()
    val strings = HashMap<String, String>()

    override fun getInt(key: String, def: Int): Int = ints[key] ?: def
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun getBoolean(key: String, def: Boolean): Boolean = booleans[key] ?: def
    override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    override fun getString(key: String, def: String): String = strings[key] ?: def
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun remove(key: String) { ints.remove(key); booleans.remove(key); strings.remove(key) }
    override fun allKeys(): Set<String> = ints.keys + booleans.keys + strings.keys
}

class TerminalSettingsTest {

    // ---- defaults ----

    @Test
    fun `defaults match the spec`() {
        val s = TerminalSettings()
        assertEquals(14, s.fontSizeSp)                       // default 14sp
        assertEquals(10000, s.scrollbackLines)               // default 10,000 lines
        assertEquals(ScrollModePref.SMART, s.scrollMode)     // default Smart
        assertTrue(s.followLiveOutput)
        assertTrue(s.showNewOutputIndicator)
        assertTrue(s.pinchZoom)
        assertFalse(s.twoFingerScroll)                       // optional gesture
        assertTrue(s.showToolbar)
        assertTrue(s.showExtraKeys)                       // shortcut row visible by default
        assertTrue(s.keepAlive)
        assertFalse(s.confirmExit)                           // no unneeded dialogs
        assertEquals(TerminalThemePref.NOXS_DARK, s.theme)
    }

    // ---- clamping on load ----

    @Test
    fun `invalid stored values are clamped to the spec bounds on load`() {
        val prefs = InMemoryTerminalPrefs().apply {
            putInt("terminal.fontSize", 3)                   // below 10
            putInt("terminal.scrollbackLines", 999_999)      // above 50,000
            putInt("terminal.lineSpacing", 40)               // below 100%
            putInt("terminal.opacity", 200)                  // above 100%
            putInt("terminal.cursorBlinkPeriod", 1)          // below 200 ms
            putInt("terminal.cursorWidth", 99)               // above 4
            putInt("terminal.padding", -20)                  // below 0
            putString("terminal.scrollMode", "warp")         // unknown value
            putString("terminal.cursorStyle", "ghost")
        }
        val s = TerminalSettingsStore.load(prefs)
        assertEquals(10, s.fontSizeSp)
        assertEquals(50000, s.scrollbackLines)
        assertEquals(100, s.lineSpacingPercent)
        assertEquals(100, s.opacityPercent)
        assertEquals(200, s.cursorBlinkPeriodMs)
        assertEquals(4, s.cursorWidth)
        assertEquals(0, s.paddingDp)
        assertEquals(ScrollModePref.SMART, s.scrollMode)
        assertEquals(CursorStylePref.BLOCK, s.cursorStyle)
    }

    @Test
    fun `every scroll mode round-trips`() {
        for (mode in ScrollModePref.entries) {
            val prefs = InMemoryTerminalPrefs()
            TerminalSettingsStore.putString(prefs, "terminal.scrollMode", mode.key)
            assertEquals(mode, TerminalSettingsStore.load(prefs).scrollMode)
        }
    }

    @Test
    fun `every cursor style round-trips`() {
        for (style in CursorStylePref.entries) {
            val prefs = InMemoryTerminalPrefs()
            TerminalSettingsStore.putString(prefs, "terminal.cursorStyle", style.key)
            assertEquals(style, TerminalSettingsStore.load(prefs).cursorStyle)
        }
    }

    @Test
    fun `save then load is an identity round-trip`() {
        val original = TerminalSettings(
            fontSizeSp = 22,
            fontFamily = FontFamilyPref.SERIF,
            lineSpacingPercent = 150,
            letterSpacingPercent = 4,
            cursorStyle = CursorStylePref.UNDERLINE,
            cursorBlink = false,
            cursorBlinkPeriodMs = 900,
            cursorWidth = 3,
            paddingDp = 12,
            opacityPercent = 85,
            theme = TerminalThemePref.PAPER,
            pinchZoom = false,
            twoFingerScroll = true,
            hapticFeedback = true,
            scrollMode = ScrollModePref.HISTORY,
            scrollbackLines = 30000,
            confirmExit = true,
            keepAlive = false
        )
        val prefs = InMemoryTerminalPrefs()
        TerminalSettingsStore.save(prefs, original)
        assertEquals(original, TerminalSettingsStore.load(prefs))
    }

    // ---- single-key updates ----

    @Test
    fun `putInt clamps on write and returns reloaded settings`() {
        val prefs = InMemoryTerminalPrefs()
        val s = TerminalSettingsStore.putInt(prefs, "terminal.fontSize", 99)
        assertEquals(28, s.fontSizeSp)
        assertEquals(28, prefs.getInt("terminal.fontSize", -1))
    }

    @Test
    fun `putBoolean flips only the requested key`() {
        val prefs = InMemoryTerminalPrefs()
        TerminalSettingsStore.putBoolean(prefs, "terminal.pinchZoom", false)
        val s = TerminalSettingsStore.load(prefs)
        assertFalse(s.pinchZoom)
        assertTrue(s.followLiveOutput)
    }

    @Test
    fun `start in fullscreen defaults off and round-trips`() {
        val fresh = InMemoryTerminalPrefs()
        assertFalse(TerminalSettingsStore.load(fresh).startInFullscreen)
        val prefs = InMemoryTerminalPrefs()
        val s = TerminalSettingsStore.putBoolean(prefs, "terminal.startFullscreen", true)
        assertTrue(s.startInFullscreen)
        assertEquals(true, prefs.booleans["terminal.startFullscreen"])
        // Reset wipes it like every other terminal.* key.
        TerminalSettingsStore.reset(prefs)
        assertFalse(TerminalSettingsStore.load(prefs).startInFullscreen)
    }

    // ---- reset contract (spec 25) ----

    @Test
    fun `show shortcut keys defaults on and round-trips`() {
        val prefs = InMemoryTerminalPrefs()
        val saved = TerminalSettingsStore.putBoolean(prefs, "terminal.showExtraKeys", false)
        assertFalse(saved.showExtraKeys)
        assertFalse(TerminalSettingsStore.load(prefs).showExtraKeys)
        assertTrue(TerminalSettingsStore.load(prefs).showToolbar)   // independent key
        // A second save must not resurrect or leak it.
        TerminalSettingsStore.save(prefs, saved)
        assertFalse(TerminalSettingsStore.load(prefs).showExtraKeys)
        // Reset restores the default (visible).
        TerminalSettingsStore.reset(prefs)
        assertTrue(TerminalSettingsStore.load(prefs).showExtraKeys)
    }

    @Test
    fun `reset removes only terminal keys`() {
        val prefs = InMemoryTerminalPrefs().apply {
            // Simulate the shared file with non-terminal settings alongside.
            putString("rootfs_url", "https://example.com/rootfs.tar.gz")
            putBoolean("extra_keys", false)
            putBoolean("volume_keys", true)
            putInt("terminal.fontSize", 18)
            putBoolean("terminal.pinchZoom", false)
            putString("terminal.theme", "black")
        }
        TerminalSettingsStore.reset(prefs)

        assertNull(prefs.strings["terminal.theme"])
        assertEquals(0, prefs.allKeys().count { it.startsWith("terminal.") })
        // Non-terminal namespaces survive untouched.
        assertEquals("https://example.com/rootfs.tar.gz", prefs.strings["rootfs_url"])
        assertEquals(false, prefs.booleans["extra_keys"])
        assertEquals(true, prefs.booleans["volume_keys"])
    }

    @Test
    fun `after reset, loading returns pure defaults`() {
        val prefs = InMemoryTerminalPrefs()
        TerminalSettingsStore.save(
            prefs,
            TerminalSettings(fontSizeSp = 26, scrollMode = ScrollModePref.HISTORY, scrollbackLines = 50000)
        )
        TerminalSettingsStore.reset(prefs)
        val s = TerminalSettingsStore.load(prefs)
        assertEquals(TerminalSettings(), s)
    }

    @Test
    fun `reset on an empty store is safe`() {
        val prefs = InMemoryTerminalPrefs()
        TerminalSettingsStore.reset(prefs)
        assertTrue(prefs.allKeys().isEmpty())
    }

    // ---- bound constants mirror the spec ----

    @Test
    fun `spec bounds are as documented`() {
        assertEquals(10, TerminalSettings.FONT_MIN_SP)
        assertEquals(28, TerminalSettings.FONT_MAX_SP)
        assertEquals(1000, TerminalSettings.SCROLLBACK_MIN_LINES)
        assertEquals(50000, TerminalSettings.SCROLLBACK_MAX_LINES)
        assertEquals(200, TerminalSettings.CURSOR_BLINK_MIN_MS)
        assertEquals(1500, TerminalSettings.CURSOR_BLINK_MAX_MS)
        assertEquals(50, TerminalSettings.OPACITY_MIN_PCT)
        assertEquals(100, TerminalSettings.OPACITY_MAX_PCT)
    }
}
