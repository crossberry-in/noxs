/*
 * Noxs — original implementation.
 * Settings → Terminal hub. Clean grouped rows (no generic Android-settings
 * look): Appearance / Interaction / Scrolling / Behavior sections, an
 * Advanced group (performance, rendering, debug) and Reset.
 *
 * Every change persists to the existing noxs_settings store under terminal.*
 * keys and takes effect without restarting Linux. Reset removes ONLY
 * terminal.* keys — never the filesystem, packages, shell history, sessions
 * or running processes.
 */
package com.crossberry.noxs.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityTerminalSettingsBinding
import java.util.Locale

class TerminalSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalSettingsBinding
    private val prefs by lazy { AndroidTerminalPrefs.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.screenTitle.text = getString(R.string.settings_terminal_title)
        render()
    }

    private fun load(): TerminalSettings = TerminalSettingsStore.load(prefs)

    private fun render() {
        val s = load()
        val w = SettingsWidgets
        val stack = binding.stack
        stack.removeAllViews()

        stack.addView(w.sectionHeader(this, getString(R.string.settings_section_terminal)))
        stack.addView(w.valueRow(
            this,
            getString(R.string.settings_appearance),
            getString(R.string.settings_appearance_desc),
            appearanceSummary(s)
        ) { startActivity(TerminalSettingsSubActivity.intent(this, TerminalSettingsSubActivity.SECTION_APPEARANCE)) })
        stack.addView(w.valueRow(
            this,
            getString(R.string.settings_interaction),
            getString(R.string.settings_interaction_desc),
            interactionSummary(s)
        ) { startActivity(TerminalSettingsSubActivity.intent(this, TerminalSettingsSubActivity.SECTION_INTERACTION)) })
        stack.addView(w.valueRow(
            this,
            getString(R.string.settings_scrolling),
            getString(R.string.settings_scrolling_desc),
            scrollSummary(s)
        ) { startActivity(TerminalSettingsSubActivity.intent(this, TerminalSettingsSubActivity.SECTION_SCROLLING)) })
        stack.addView(w.valueRow(
            this,
            getString(R.string.settings_behavior),
            getString(R.string.settings_behavior_desc),
            behaviorSummary(s)
        ) { startActivity(TerminalSettingsSubActivity.intent(this, TerminalSettingsSubActivity.SECTION_BEHAVIOR)) })

        stack.addView(w.sectionHeader(this, getString(R.string.settings_section_advanced)))
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_reduce_animations),
            getString(R.string.settings_reduce_animations_desc),
            s.reduceAnimations
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.reduceAnimations", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_text_antialias),
            getString(R.string.settings_text_antialias_desc),
            s.textAntialias
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.textAntialias", checked) })
        stack.addView(w.switchRow(
            this,
            getString(R.string.settings_debug_overlay),
            getString(R.string.settings_debug_overlay_desc),
            s.debugOverlay
        ) { checked -> TerminalSettingsStore.putBoolean(prefs, "terminal.debugOverlay", checked) })
        stack.addView(w.actionRow(
            this,
            getString(R.string.settings_debug_info),
            getString(R.string.settings_debug_info_desc)
        ) { showDebugInfo(load()) })

        stack.addView(w.sectionHeader(this, getString(R.string.settings_section_reset)))
        stack.addView(w.actionRow(
            this,
            getString(R.string.settings_reset_terminal),
            getString(R.string.settings_reset_terminal_desc),
            color = 0xffff5252.toInt()
        ) { confirmReset() })
    }

    private fun appearanceSummary(s: TerminalSettings): String =
        "${s.fontSizeSp} sp · ${s.fontFamily.label} · ${s.theme.label}"

    private fun interactionSummary(s: TerminalSettings): String = listOf(
        getString(if (s.pinchZoom) R.string.settings_state_on else R.string.settings_state_off, "Pinch"),
        getString(if (s.showToolbar) R.string.settings_state_on else R.string.settings_state_off, "Toolbar"),
        getString(if (s.showExtraKeys) R.string.settings_state_on else R.string.settings_state_off, "Shortcuts")
    ).joinToString(" · ")

    private fun scrollSummary(s: TerminalSettings): String =
        "${s.scrollMode.label} · ${String.format(Locale.US, "%,d", s.scrollbackLines)} lines"

    private fun behaviorSummary(s: TerminalSettings): String = listOf(
        getString(if (s.keepAlive) R.string.settings_state_on else R.string.settings_state_off, "Keep alive"),
        getString(if (s.restoreSessions) R.string.settings_state_on else R.string.settings_state_off, "Restore")
    ).joinToString(" · ")

    private fun showDebugInfo(s: TerminalSettings) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_debug_info))
            .setMessage(
                "Font: ${s.fontSizeSp} sp ${s.fontFamily.label}\n" +
                    "Spacing: ${s.lineSpacingPercent}% line · ${s.letterSpacingPercent}% letter\n" +
                    "Cursor: ${s.cursorStyle.label}, width ${s.cursorWidth}, " +
                    (if (s.cursorBlink) "blink ${s.cursorBlinkPeriodMs} ms" else "steady") + "\n" +
                    "Scrollback: ${String.format(Locale.US, "%,d", s.scrollbackLines)} lines\n" +
                    "Scroll mode: ${s.scrollMode.label}\n" +
                    "Theme: ${s.theme.label} · opacity ${s.opacityPercent}% · padding ${s.paddingDp} dp\n" +
                    "Gestures: pinch ${onOff(s.pinchZoom)}, two-finger scroll ${onOff(s.twoFingerScroll)}"
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_reset_confirm_title))
            .setMessage(getString(R.string.settings_reset_confirm_msg))
            .setPositiveButton(getString(R.string.settings_reset_positive)) { _, _ ->
                TerminalSettingsStore.reset(prefs)
                render()
                Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
