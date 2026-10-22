/*
 * Noxs — original implementation.
 * Settings → Terminal → {Appearance | Interaction | Scrolling | Behavior}.
 * One activity renders the chosen section as grouped rows (switches, range
 * sliders with exact values and reset, single-choice selectors). Changes
 * persist immediately and take effect live.
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityTerminalSettingsBinding
import java.util.Locale

class TerminalSettingsSubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalSettingsBinding
    private val prefs by lazy { AndroidTerminalPrefs.from(this) }
    private var section: String = SECTION_APPEARANCE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        section = intent.getStringExtra(EXTRA_SECTION) ?: SECTION_APPEARANCE
        binding.screenTitle.text = when (section) {
            SECTION_APPEARANCE -> getString(R.string.settings_appearance)
            SECTION_INTERACTION -> getString(R.string.settings_interaction)
            SECTION_SCROLLING -> getString(R.string.settings_scrolling)
            else -> getString(R.string.settings_behavior)
        }
        render()
    }

    private fun load(): TerminalSettings = TerminalSettingsStore.load(prefs)

    private fun render() {
        val s = load()
        val stack = binding.stack
        stack.removeAllViews()
        when (section) {
            SECTION_APPEARANCE -> renderAppearance(stack, s)
            SECTION_INTERACTION -> renderInteraction(stack, s)
            SECTION_SCROLLING -> renderScrolling(stack, s)
            else -> renderBehavior(stack, s)
        }
    }

    private fun renderAppearance(stack: LinearLayout, s: TerminalSettings) {
        val w = SettingsWidgets
        stack.addView(w.sliderRow(
            this,
            getString(R.string.settings_font_size),
            getString(R.string.settings_font_size_desc),
            TerminalSettings.FONT_MIN_SP, TerminalSettings.FONT_MAX_SP, 1, s.fontSizeSp,
            format = { "$it sp" },
            onChange = { value ->
                val next = if (value < 0) TerminalSettings.FONT_DEFAULT_SP else value
                TerminalSettingsStore.putInt(prefs, "terminal.fontSize", next)
            }
        ))
        stack.addView(w.selectorRow(
            this,
            getString(R.string.settings_font_family),
            getString(R.string.settings_font_family_desc),
            FontFamilyPref.entries.map { it.label },
            s.fontFamily.ordinal,
            onPick = { index ->
                val next = FontFamilyPref.entries[index]
                TerminalSettingsStore.putString(prefs, "terminal.fontFamily", next.key)
                render()
            }
        ))
        stack.addView(w.sliderRow(
            this,
            getString(R.string.settings_line_spacing),
            getString(R.string.settings_line_spacing_desc),
            TerminalSettings.LINE_SPACING_MIN_PCT, TerminalSettings.LINE_SPACING_MAX_PCT, 5, s.lineSpacingPercent,
            format = { String.format(Locale.US, "%.2f×", it / 100f) },
            onChange = { value ->
                val next = if (value < 0) 100 else value
                TerminalSettingsStore.putInt(prefs, "terminal.lineSpacing", next)
            }
        ))
        stack.addView(w.sliderRow(
            this,
            getString(R.string.settings_letter_spacing),
            getString(R.string.settings_letter_spacing_desc),
            TerminalSettings.LETTER_SPACING_MIN_PCT, TerminalSettings.LETTER_SPACING_MAX_PCT, 1, s.letterSpacingPercent,
            format = { String.format(Locale.US, "%.02f em", it / 100f) },
            onChange = { value ->
                val next = if (value < 0) 0 else value
                TerminalSettingsStore.putInt(prefs, "terminal.letterSpacing", next)
            }
        ))
        stack.addView(w.sectionHeader(this, getString(R.string.settings_cursor)))
        stack.addView(w.selectorRow(
            this,
            getString(R.string.settings_cursor_style),
            getString(R.string.settings_cursor_style_desc),
            CursorStylePref.entries.map { it.label },
            s.cursorStyle.ordinal,
            onPick = { index ->
                val next = CursorStylePref.entries[index]
                TerminalSettingsStore.putString(prefs, "terminal.cursorStyle", next.key)
                render()
            }
        ))
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_cursor_blink),
            getString(R.string.settings_cursor_blink_desc),
            s.cursorBlink
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.cursorBlink", checked) })
        stack.addView(w.sliderRow(
            this,
            getString(R.string.settings_cursor_blink_period),
            getString(R.string.settings_cursor_blink_period_desc),
            TerminalSettings.CURSOR_BLINK_MIN_MS, TerminalSettings.CURSOR_BLINK_MAX_MS, 50, s.cursorBlinkPeriodMs,
            format = { "$it ms" },
            onChange = { value ->
                val next = if (value < 0) 550 else value
                TerminalSettingsStore.putInt(prefs, "terminal.cursorBlinkPeriod", next)
            }
        ))
        stack.addView(w.sliderRow(
            this,
            getString(R.string.settings_cursor_width),
            getString(R.string.settings_cursor_width_desc),
            TerminalSettings.CURSOR_WIDTH_MIN, TerminalSettings.CURSOR_WIDTH_MAX, 1, s.cursorWidth,
            format = { "$it" },
            onChange = { value ->
                val next = if (value < 0) 2 else value
                TerminalSettingsStore.putInt(prefs, "terminal.cursorWidth", next)
            }
        ))
        stack.addView(w.sectionHeader(this, getString(R.string.settings_surface)))
        stack.addView(w.sliderRow(
            this,
            getString(R.string.settings_terminal_padding),
            getString(R.string.settings_terminal_padding_desc),
            TerminalSettings.PADDING_MIN_DP, TerminalSettings.PADDING_MAX_DP, 2, s.paddingDp,
            format = { "$it dp" },
            onChange = { value ->
                val next = if (value < 0) 4 else value
                TerminalSettingsStore.putInt(prefs, "terminal.padding", next)
            }
        ))
        stack.addView(w.sliderRow(
            this,
            getString(R.string.settings_opacity),
            getString(R.string.settings_opacity_desc),
            TerminalSettings.OPACITY_MIN_PCT, TerminalSettings.OPACITY_MAX_PCT, 5, s.opacityPercent,
            format = { "$it %" },
            onChange = { value ->
                val next = if (value < 0) 100 else value
                TerminalSettingsStore.putInt(prefs, "terminal.opacity", next)
            }
        ))
        stack.addView(w.selectorRow(
            this,
            getString(R.string.settings_theme),
            getString(R.string.settings_theme_desc),
            TerminalThemePref.entries.map { it.label },
            s.theme.ordinal,
            onPick = { index ->
                val next = TerminalThemePref.entries[index]
                TerminalSettingsStore.putString(prefs, "terminal.theme", next.key)
                render()
            }
        ))
    }

    private fun renderInteraction(stack: LinearLayout, s: TerminalSettings) {
        val w = SettingsWidgets
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_pinch_zoom),
            getString(R.string.settings_pinch_zoom_desc),
            s.pinchZoom
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.pinchZoom", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_two_finger_scroll),
            getString(R.string.settings_two_finger_scroll_desc),
            s.twoFingerScroll
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.twoFingerScroll", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_long_press_selection),
            getString(R.string.settings_long_press_selection_desc),
            s.selectionEnabled
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.selectionEnabled", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_auto_scroll_selection),
            getString(R.string.settings_auto_scroll_selection_desc),
            s.autoScrollSelection
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.autoScrollSelection", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_haptic_feedback),
            getString(R.string.settings_haptic_feedback_desc),
            s.hapticFeedback
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.hapticFeedback", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_show_toolbar),
            getString(R.string.settings_show_toolbar_desc),
            s.showToolbar
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.showToolbar", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_show_shortcut_keys),
            getString(R.string.settings_show_shortcut_keys_desc),
            s.showExtraKeys
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.showExtraKeys", checked) })
    }

    private fun renderScrolling(stack: LinearLayout, s: TerminalSettings) {
        val w = SettingsWidgets
        stack.addView(w.selectorRow(
            this,
            getString(R.string.settings_scroll_mode),
            getString(R.string.settings_scroll_mode_desc),
            ScrollModePref.entries.map { it.label },
            s.scrollMode.ordinal,
            onPick = { index ->
                val next = ScrollModePref.entries[index]
                TerminalSettingsStore.putString(prefs, "terminal.scrollMode", next.key)
                render()
            }
        ))
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_follow_live_output),
            getString(R.string.settings_follow_live_output_desc),
            s.followLiveOutput
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.followLiveOutput", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_show_new_output_indicator),
            getString(R.string.settings_show_new_output_indicator_desc),
            s.showNewOutputIndicator
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.showNewOutputIndicator", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_auto_follow_running),
            getString(R.string.settings_auto_follow_running_desc),
            s.autoFollowWhileRunning
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.autoFollowWhileRunning", checked) })
        stack.addView(w.sliderRow(
            this,
            getString(R.string.settings_scrollback),
            getString(R.string.settings_scrollback_desc),
            TerminalSettings.SCROLLBACK_MIN_LINES, TerminalSettings.SCROLLBACK_MAX_LINES, 500, s.scrollbackLines,
            format = { String.format(Locale.US, "%,d lines", it) },
            onChange = { value ->
                val next = if (value < 0) TerminalSettings.SCROLLBACK_DEFAULT_LINES else value
                TerminalSettingsStore.putInt(prefs, "terminal.scrollbackLines", next)
            }
        ))
    }

    private fun renderBehavior(stack: LinearLayout, s: TerminalSettings) {
        val w = SettingsWidgets
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_confirm_exit),
            getString(R.string.settings_confirm_exit_desc),
            s.confirmExit
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.confirmExit", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_keep_alive),
            getString(R.string.settings_keep_alive_desc),
            s.keepAlive
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.keepAlive", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_restore_sessions),
            getString(R.string.settings_restore_sessions_desc),
            s.restoreSessions
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.restoreSessions", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_auto_focus),
            getString(R.string.settings_auto_focus_desc),
            s.autoFocusTerminal
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.autoFocusTerminal", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_preserve_scroll),
            getString(R.string.settings_preserve_scroll_desc),
            s.preserveScrollPosition
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.preserveScrollPosition", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_start_fullscreen),
            getString(R.string.settings_start_fullscreen_desc),
            s.startInFullscreen
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.startFullscreen", checked) })
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_INTERACTION = "interaction"
        const val SECTION_SCROLLING = "scrolling"
        const val SECTION_BEHAVIOR = "behavior"

        fun intent(context: Context, section: String): Intent =
            Intent(context, TerminalSettingsSubActivity::class.java).apply { putExtra(EXTRA_SECTION, section) }
    }
}
