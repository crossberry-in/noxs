/*
 * Noxs — original implementation.
 * Color scheme for the sora-editor surface (the engine Squircle CE embeds):
 * chrome colors follow the Noxs palette (black + panel + accent); syntax
 * token colors come from the TextMate theme loaded from assets by
 * EditorActivity, so this class only paints the editor chrome.
 */
package com.crossberry.noxs.ui

import android.graphics.Color
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

class NoxsEditorScheme : EditorColorScheme() {

    override fun applyDefault() {
        super.applyDefault()
        setColor(WHOLE_BACKGROUND, 0xFF111111.toInt())
        setColor(LINE_NUMBER_BACKGROUND, 0xFF111111.toInt())
        setColor(LINE_NUMBER, 0xFF5A5A5A.toInt())
        setColor(LINE_NUMBER_CURRENT, 0xFF20D866.toInt())
        setColor(LINE_DIVIDER, 0xFF303030.toInt())
        setColor(TEXT_NORMAL, 0xFFE6E6E6.toInt())
        setColor(CURRENT_LINE, 0xFF1A1A1A.toInt())
        setColor(SELECTION_INSERT, 0xFF20D866.toInt())
        setColor(SELECTION_HANDLE, 0xFF20D866.toInt())
        setColor(SELECTED_TEXT_BACKGROUND, 0xFF264D38.toInt())
        setColor(HIGHLIGHTED_DELIMITERS_BACKGROUND, 0xFF2A2A2A.toInt())
        setColor(HIGHLIGHTED_DELIMITERS_FOREGROUND, 0xFFFFFFFF.toInt())
        setColor(MATCHED_TEXT_BACKGROUND, 0xFF2E5A40.toInt())
        setColor(BLOCK_LINE, 0xFF303030.toInt())
        setColor(BLOCK_LINE_CURRENT, 0xFF20D866.toInt())
        setColor(NON_PRINTABLE_CHAR, 0xFF3A3A3A.toInt())
        setColor(COMPLETION_WND_BACKGROUND, 0xFF1E1E1E.toInt())
        setColor(COMPLETION_WND_TEXT_PRIMARY, 0xFFE6E6E6.toInt())
        setColor(COMPLETION_WND_TEXT_SECONDARY, 0xFF9A9A9A.toInt())
        setColor(SCROLL_BAR_THUMB, 0xFF3A3A3A.toInt())
        setColor(SCROLL_BAR_THUMB_PRESSED, 0xFF20D866.toInt())
        // Unused by the TextMate path but kept dark so nothing flashes white.
        setColor(BACKGROUND_COLOR_DARK, 0xFF111111.toInt())
        setColor(BACKGROUND_COLOR_LIGHT, 0xFF111111.toInt())
        setColor(STICKY_SCROLL_DIVIDER, 0xFF303030.toInt())
    }
}
