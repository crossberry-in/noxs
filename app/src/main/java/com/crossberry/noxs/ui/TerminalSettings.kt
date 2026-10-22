/*
 * Noxs — original implementation.
 * Terminal settings model + persistence.
 *
 * Stored in the EXISTING "noxs_settings" SharedPreferences file under the
 * "terminal." key namespace — no duplicate settings system. All numeric
 * values are clamped at load and save, so an invalid stored value can never
 * reach the terminal. Reset removes ONLY terminal.* keys: the Linux
 * filesystem, installed packages, shell history, sessions and user files are
 * never touched.
 *
 * The TerminalPrefs interface keeps this class JVM-testable (in-memory map).
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.content.SharedPreferences

enum class ScrollModePref(val key: String, val label: String) {
    NORMAL("normal", "Normal"),
    SMART("smart", "Smart"),
    HISTORY("history", "History Mirror");

    companion object {
        fun byKey(key: String): ScrollModePref = entries.firstOrNull { it.key == key } ?: SMART
    }
}

enum class CursorStylePref(val key: String, val label: String) {
    BLOCK("block", "Block"),
    UNDERLINE("underline", "Underline"),
    BAR("bar", "Bar");

    companion object {
        fun byKey(key: String): CursorStylePref = entries.firstOrNull { it.key == key } ?: BLOCK
    }
}

enum class FontFamilyPref(val key: String, val label: String) {
    MONOSPACE("monospace", "Monospace"),
    SANS("sans", "System sans"),
    SERIF("serif", "Serif");

    companion object {
        fun byKey(key: String): FontFamilyPref = entries.firstOrNull { it.key == key } ?: MONOSPACE
    }
}

enum class TerminalThemePref(val key: String, val label: String) {
    NOXS_DARK("noxs_dark", "Noxs Dark"),
    BLACK("black", "Black"),
    HIGH_CONTRAST("high_contrast", "High contrast"),
    PAPER("paper", "Paper");

    companion object {
        fun byKey(key: String): TerminalThemePref = entries.firstOrNull { it.key == key } ?: NOXS_DARK
    }
}

data class TerminalSettings(
    // Appearance
    val fontSizeSp: Int = FONT_DEFAULT_SP,
    val fontFamily: FontFamilyPref = FontFamilyPref.MONOSPACE,
    /** 100..200 → line spacing multiplier /100. */
    val lineSpacingPercent: Int = 100,
    /** 0..20 → letter spacing em /100. */
    val letterSpacingPercent: Int = 0,
    val cursorStyle: CursorStylePref = CursorStylePref.BLOCK,
    val cursorBlink: Boolean = true,
    /** 200..1500 ms blink period. */
    val cursorBlinkPeriodMs: Int = 550,
    /** 1..4 — underline/bar thickness. */
    val cursorWidth: Int = 2,
    /** 0..24 dp content padding. */
    val paddingDp: Int = 4,
    /** 50..100 % whole-view opacity. */
    val opacityPercent: Int = 100,
    val theme: TerminalThemePref = TerminalThemePref.NOXS_DARK,
    // Interaction
    val pinchZoom: Boolean = true,
    val twoFingerScroll: Boolean = false,
    val selectionEnabled: Boolean = true,
    val autoScrollSelection: Boolean = true,
    val hapticFeedback: Boolean = false,
    val showToolbar: Boolean = true,
    /** Bottom shortcut-key row (ESC/TAB/CTRL/arrows…): user-toggleable. */
    val showExtraKeys: Boolean = true,
    // Scrolling
    val scrollMode: ScrollModePref = ScrollModePref.SMART,
    val followLiveOutput: Boolean = true,
    val showNewOutputIndicator: Boolean = true,
    val autoFollowWhileRunning: Boolean = true,
    val scrollbackLines: Int = SCROLLBACK_DEFAULT_LINES,
    // Behavior
    val confirmExit: Boolean = false,
    val keepAlive: Boolean = true,
    val restoreSessions: Boolean = true,
    val autoFocusTerminal: Boolean = true,
    val preserveScrollPosition: Boolean = true,
    /** Open the terminal with the chrome hidden (Ctrl+F toggles any time). */
    val startInFullscreen: Boolean = false,
    // Advanced
    val reduceAnimations: Boolean = false,
    val textAntialias: Boolean = true,
    val debugOverlay: Boolean = false
) {
    companion object {
        const val FONT_MIN_SP = 10
        const val FONT_MAX_SP = 28
        const val FONT_DEFAULT_SP = 14
        const val SCROLLBACK_MIN_LINES = 1000
        const val SCROLLBACK_MAX_LINES = 50000
        const val SCROLLBACK_DEFAULT_LINES = 10000
        const val LINE_SPACING_MIN_PCT = 100
        const val LINE_SPACING_MAX_PCT = 200
        const val LETTER_SPACING_MIN_PCT = 0
        const val LETTER_SPACING_MAX_PCT = 20
        const val CURSOR_BLINK_MIN_MS = 200
        const val CURSOR_BLINK_MAX_MS = 1500
        const val CURSOR_WIDTH_MIN = 1
        const val CURSOR_WIDTH_MAX = 4
        const val PADDING_MIN_DP = 0
        const val PADDING_MAX_DP = 24
        const val OPACITY_MIN_PCT = 50
        const val OPACITY_MAX_PCT = 100
    }
}

/** Minimal key-value abstraction so the store is unit-testable on the JVM. */
interface TerminalPrefs {
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, def: String): String
    fun putString(key: String, value: String)
    fun remove(key: String)
    /** All keys currently stored (reset scans these). */
    fun allKeys(): Set<String>
}

class AndroidTerminalPrefs(private val prefs: SharedPreferences) : TerminalPrefs {
    override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getBoolean(key: String, def: Boolean): Boolean = prefs.getBoolean(key, def)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getString(key: String, def: String): String = prefs.getString(key, def) ?: def
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun allKeys(): Set<String> = prefs.all.keys

    companion object {
        /** The single shared settings file this project already uses. */
        const val PREFS_NAME = "noxs_settings"
        fun from(context: Context): AndroidTerminalPrefs =
            AndroidTerminalPrefs(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

object TerminalSettingsStore {

    const val KEY_PREFIX = "terminal."

    // Spec key names — stable, persisted exactly as documented.
    private const val K_FONT_SIZE = "terminal.fontSize"
    private const val K_FONT_FAMILY = "terminal.fontFamily"
    private const val K_LINE_SPACING = "terminal.lineSpacing"
    private const val K_LETTER_SPACING = "terminal.letterSpacing"
    private const val K_CURSOR_STYLE = "terminal.cursorStyle"
    private const val K_CURSOR_BLINK = "terminal.cursorBlink"
    private const val K_CURSOR_BLINK_PERIOD = "terminal.cursorBlinkPeriod"
    private const val K_CURSOR_WIDTH = "terminal.cursorWidth"
    private const val K_PADDING = "terminal.padding"
    private const val K_OPACITY = "terminal.opacity"
    private const val K_THEME = "terminal.theme"
    private const val K_PINCH_ZOOM = "terminal.pinchZoom"
    private const val K_TWO_FINGER_SCROLL = "terminal.twoFingerScroll"
    private const val K_SELECTION_ENABLED = "terminal.selectionEnabled"
    private const val K_AUTO_SCROLL_SELECTION = "terminal.autoScrollSelection"
    private const val K_HAPTIC_FEEDBACK = "terminal.hapticFeedback"
    private const val K_SHOW_TOOLBAR = "terminal.showToolbar"
    private const val K_SHOW_EXTRA_KEYS = "terminal.showExtraKeys"
    private const val K_SCROLL_MODE = "terminal.scrollMode"
    private const val K_FOLLOW_LIVE_OUTPUT = "terminal.followLiveOutput"
    private const val K_SHOW_NEW_OUTPUT_INDICATOR = "terminal.showNewOutputIndicator"
    private const val K_AUTO_FOLLOW_WHILE_RUNNING = "terminal.autoFollowWhileRunning"
    private const val K_SCROLLBACK_LINES = "terminal.scrollbackLines"
    private const val K_CONFIRM_EXIT = "terminal.confirmExit"
    private const val K_KEEP_ALIVE = "terminal.keepAlive"
    private const val K_RESTORE_SESSIONS = "terminal.restoreSessions"
    private const val K_AUTO_FOCUS_TERMINAL = "terminal.autoFocusTerminal"
    private const val K_PRESERVE_SCROLL_POSITION = "terminal.preserveScrollPosition"
    private const val K_START_FULLSCREEN = "terminal.startFullscreen"
    private const val K_REDUCE_ANIMATIONS = "terminal.reduceAnimations"
    private const val K_TEXT_ANTIALIAS = "terminal.textAntialias"
    private const val K_DEBUG_OVERLAY = "terminal.debugOverlay"

    fun load(prefs: TerminalPrefs): TerminalSettings {
        val d = TerminalSettings()
        return TerminalSettings(
            fontSizeSp = prefs.getInt(K_FONT_SIZE, d.fontSizeSp)
                .coerceIn(TerminalSettings.FONT_MIN_SP, TerminalSettings.FONT_MAX_SP),
            fontFamily = FontFamilyPref.byKey(prefs.getString(K_FONT_FAMILY, d.fontFamily.key)),
            lineSpacingPercent = prefs.getInt(K_LINE_SPACING, d.lineSpacingPercent)
                .coerceIn(TerminalSettings.LINE_SPACING_MIN_PCT, TerminalSettings.LINE_SPACING_MAX_PCT),
            letterSpacingPercent = prefs.getInt(K_LETTER_SPACING, d.letterSpacingPercent)
                .coerceIn(TerminalSettings.LETTER_SPACING_MIN_PCT, TerminalSettings.LETTER_SPACING_MAX_PCT),
            cursorStyle = CursorStylePref.byKey(prefs.getString(K_CURSOR_STYLE, d.cursorStyle.key)),
            cursorBlink = prefs.getBoolean(K_CURSOR_BLINK, d.cursorBlink),
            cursorBlinkPeriodMs = prefs.getInt(K_CURSOR_BLINK_PERIOD, d.cursorBlinkPeriodMs)
                .coerceIn(TerminalSettings.CURSOR_BLINK_MIN_MS, TerminalSettings.CURSOR_BLINK_MAX_MS),
            cursorWidth = prefs.getInt(K_CURSOR_WIDTH, d.cursorWidth)
                .coerceIn(TerminalSettings.CURSOR_WIDTH_MIN, TerminalSettings.CURSOR_WIDTH_MAX),
            paddingDp = prefs.getInt(K_PADDING, d.paddingDp)
                .coerceIn(TerminalSettings.PADDING_MIN_DP, TerminalSettings.PADDING_MAX_DP),
            opacityPercent = prefs.getInt(K_OPACITY, d.opacityPercent)
                .coerceIn(TerminalSettings.OPACITY_MIN_PCT, TerminalSettings.OPACITY_MAX_PCT),
            theme = TerminalThemePref.byKey(prefs.getString(K_THEME, d.theme.key)),
            pinchZoom = prefs.getBoolean(K_PINCH_ZOOM, d.pinchZoom),
            twoFingerScroll = prefs.getBoolean(K_TWO_FINGER_SCROLL, d.twoFingerScroll),
            selectionEnabled = prefs.getBoolean(K_SELECTION_ENABLED, d.selectionEnabled),
            autoScrollSelection = prefs.getBoolean(K_AUTO_SCROLL_SELECTION, d.autoScrollSelection),
            hapticFeedback = prefs.getBoolean(K_HAPTIC_FEEDBACK, d.hapticFeedback),
            showToolbar = prefs.getBoolean(K_SHOW_TOOLBAR, d.showToolbar),
            showExtraKeys = prefs.getBoolean(K_SHOW_EXTRA_KEYS, d.showExtraKeys),
            scrollMode = ScrollModePref.byKey(prefs.getString(K_SCROLL_MODE, d.scrollMode.key)),
            followLiveOutput = prefs.getBoolean(K_FOLLOW_LIVE_OUTPUT, d.followLiveOutput),
            showNewOutputIndicator = prefs.getBoolean(K_SHOW_NEW_OUTPUT_INDICATOR, d.showNewOutputIndicator),
            autoFollowWhileRunning = prefs.getBoolean(K_AUTO_FOLLOW_WHILE_RUNNING, d.autoFollowWhileRunning),
            scrollbackLines = prefs.getInt(K_SCROLLBACK_LINES, d.scrollbackLines)
                .coerceIn(TerminalSettings.SCROLLBACK_MIN_LINES, TerminalSettings.SCROLLBACK_MAX_LINES),
            confirmExit = prefs.getBoolean(K_CONFIRM_EXIT, d.confirmExit),
            keepAlive = prefs.getBoolean(K_KEEP_ALIVE, d.keepAlive),
            restoreSessions = prefs.getBoolean(K_RESTORE_SESSIONS, d.restoreSessions),
            autoFocusTerminal = prefs.getBoolean(K_AUTO_FOCUS_TERMINAL, d.autoFocusTerminal),
            preserveScrollPosition = prefs.getBoolean(K_PRESERVE_SCROLL_POSITION, d.preserveScrollPosition),
            startInFullscreen = prefs.getBoolean(K_START_FULLSCREEN, d.startInFullscreen),
            reduceAnimations = prefs.getBoolean(K_REDUCE_ANIMATIONS, d.reduceAnimations),
            textAntialias = prefs.getBoolean(K_TEXT_ANTIALIAS, d.textAntialias),
            debugOverlay = prefs.getBoolean(K_DEBUG_OVERLAY, d.debugOverlay)
        )
    }

    fun save(prefs: TerminalPrefs, s: TerminalSettings) {
        prefs.putInt(K_FONT_SIZE, s.fontSizeSp.coerceIn(TerminalSettings.FONT_MIN_SP, TerminalSettings.FONT_MAX_SP))
        prefs.putString(K_FONT_FAMILY, s.fontFamily.key)
        prefs.putInt(K_LINE_SPACING, s.lineSpacingPercent.coerceIn(TerminalSettings.LINE_SPACING_MIN_PCT, TerminalSettings.LINE_SPACING_MAX_PCT))
        prefs.putInt(K_LETTER_SPACING, s.letterSpacingPercent.coerceIn(TerminalSettings.LETTER_SPACING_MIN_PCT, TerminalSettings.LETTER_SPACING_MAX_PCT))
        prefs.putString(K_CURSOR_STYLE, s.cursorStyle.key)
        prefs.putBoolean(K_CURSOR_BLINK, s.cursorBlink)
        prefs.putInt(K_CURSOR_BLINK_PERIOD, s.cursorBlinkPeriodMs.coerceIn(TerminalSettings.CURSOR_BLINK_MIN_MS, TerminalSettings.CURSOR_BLINK_MAX_MS))
        prefs.putInt(K_CURSOR_WIDTH, s.cursorWidth.coerceIn(TerminalSettings.CURSOR_WIDTH_MIN, TerminalSettings.CURSOR_WIDTH_MAX))
        prefs.putInt(K_PADDING, s.paddingDp.coerceIn(TerminalSettings.PADDING_MIN_DP, TerminalSettings.PADDING_MAX_DP))
        prefs.putInt(K_OPACITY, s.opacityPercent.coerceIn(TerminalSettings.OPACITY_MIN_PCT, TerminalSettings.OPACITY_MAX_PCT))
        prefs.putString(K_THEME, s.theme.key)
        prefs.putBoolean(K_PINCH_ZOOM, s.pinchZoom)
        prefs.putBoolean(K_TWO_FINGER_SCROLL, s.twoFingerScroll)
        prefs.putBoolean(K_SELECTION_ENABLED, s.selectionEnabled)
        prefs.putBoolean(K_AUTO_SCROLL_SELECTION, s.autoScrollSelection)
        prefs.putBoolean(K_HAPTIC_FEEDBACK, s.hapticFeedback)
        prefs.putBoolean(K_SHOW_TOOLBAR, s.showToolbar)
        prefs.putBoolean(K_SHOW_EXTRA_KEYS, s.showExtraKeys)
        prefs.putString(K_SCROLL_MODE, s.scrollMode.key)
        prefs.putBoolean(K_FOLLOW_LIVE_OUTPUT, s.followLiveOutput)
        prefs.putBoolean(K_SHOW_NEW_OUTPUT_INDICATOR, s.showNewOutputIndicator)
        prefs.putBoolean(K_AUTO_FOLLOW_WHILE_RUNNING, s.autoFollowWhileRunning)
        prefs.putInt(K_SCROLLBACK_LINES, s.scrollbackLines.coerceIn(TerminalSettings.SCROLLBACK_MIN_LINES, TerminalSettings.SCROLLBACK_MAX_LINES))
        prefs.putBoolean(K_CONFIRM_EXIT, s.confirmExit)
        prefs.putBoolean(K_KEEP_ALIVE, s.keepAlive)
        prefs.putBoolean(K_RESTORE_SESSIONS, s.restoreSessions)
        prefs.putBoolean(K_AUTO_FOCUS_TERMINAL, s.autoFocusTerminal)
        prefs.putBoolean(K_PRESERVE_SCROLL_POSITION, s.preserveScrollPosition)
        prefs.putBoolean(K_START_FULLSCREEN, s.startInFullscreen)
        prefs.putBoolean(K_REDUCE_ANIMATIONS, s.reduceAnimations)
        prefs.putBoolean(K_TEXT_ANTIALIAS, s.textAntialias)
        prefs.putBoolean(K_DEBUG_OVERLAY, s.debugOverlay)
    }

    /**
     * Updates a single int setting (slider path) with clamp-on-write, then
     * returns the reloaded settings object.
     */
    fun putInt(prefs: TerminalPrefs, key: String, value: Int): TerminalSettings {
        prefs.putInt(key, clampForKey(key, value))
        return load(prefs)
    }

    /** Clamps a value to the documented bounds of its setting key. */
    private fun clampForKey(key: String, value: Int): Int = when (key) {
        "terminal.fontSize" -> value.coerceIn(TerminalSettings.FONT_MIN_SP, TerminalSettings.FONT_MAX_SP)
        "terminal.scrollbackLines" -> value.coerceIn(TerminalSettings.SCROLLBACK_MIN_LINES, TerminalSettings.SCROLLBACK_MAX_LINES)
        "terminal.lineSpacing" -> value.coerceIn(TerminalSettings.LINE_SPACING_MIN_PCT, TerminalSettings.LINE_SPACING_MAX_PCT)
        "terminal.letterSpacing" -> value.coerceIn(TerminalSettings.LETTER_SPACING_MIN_PCT, TerminalSettings.LETTER_SPACING_MAX_PCT)
        "terminal.cursorBlinkPeriod" -> value.coerceIn(TerminalSettings.CURSOR_BLINK_MIN_MS, TerminalSettings.CURSOR_BLINK_MAX_MS)
        "terminal.cursorWidth" -> value.coerceIn(TerminalSettings.CURSOR_WIDTH_MIN, TerminalSettings.CURSOR_WIDTH_MAX)
        "terminal.padding" -> value.coerceIn(TerminalSettings.PADDING_MIN_DP, TerminalSettings.PADDING_MAX_DP)
        "terminal.opacity" -> value.coerceIn(TerminalSettings.OPACITY_MIN_PCT, TerminalSettings.OPACITY_MAX_PCT)
        else -> value
    }

    fun putBoolean(prefs: TerminalPrefs, key: String, value: Boolean): TerminalSettings {
        prefs.putBoolean(key, value)
        return load(prefs)
    }

    fun putString(prefs: TerminalPrefs, key: String, value: String): TerminalSettings {
        prefs.putString(key, value)
        return load(prefs)
    }

    /**
     * Removes ONLY terminal.* keys. Quotas, rootfs URL, DNS, bootstrap data
     * and every other preference namespace stay untouched.
     */
    fun reset(prefs: TerminalPrefs) {
        prefs.allKeys().filter { it.startsWith(KEY_PREFIX) }.forEach { prefs.remove(it) }
    }
}
