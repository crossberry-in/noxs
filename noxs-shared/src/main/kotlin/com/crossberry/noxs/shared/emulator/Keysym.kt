/*
 * Noxs — original implementation.
 * X11 keysym table for the NoxOS emulator keyboard bridge (M7). VNC sends
 * keysyms, Android sends KeyEvent key codes: the app maps Android codes to
 * the logical names below (all-caps) and this table maps names → keysyms.
 * Pure JVM, unit tested.
 */
package com.crossberry.noxs.shared.emulator

object Keysym {

    val ENTER: Int = 0xFF0D
    val CTRL_L: Int = 0xFFE3

    private val NAMED: Map<String, Int> = buildMap {
        put("ENTER", 0xFF0D)
        put("BACKSPACE", 0xFF08)
        put("TAB", 0xFF09)
        put("ESC", 0xFF1B)
        put("SPACE", 0x0020)
        put("DELETE", 0xFFFF)
        put("INSERT", 0xFF63)
        put("HOME", 0xFF50)
        put("END", 0xFF57)
        put("PAGEUP", 0xFF55)
        put("PAGEDOWN", 0xFF56)
        put("LEFT", 0xFF51)
        put("UP", 0xFF52)
        put("RIGHT", 0xFF53)
        put("DOWN", 0xFF54)
        put("SHIFT_L", 0xFFE1)
        put("SHIFT_R", 0xFFE2)
        put("CTRL_L", 0xFFE3)
        put("CTRL_R", 0xFFE4)
        put("CAPS_LOCK", 0xFFE5)
        put("META_L", 0xFFE7)
        put("META_R", 0xFFE8)
        put("ALT_L", 0xFFE9)
        put("ALT_R", 0xFFEA)
        // X11 function keys F1..F12 are contiguous.
        for (i in 1..12) {
            put("F$i", 0xFFBD + i) // F1 = 0xFFBE
        }
    }

    /** Keysym for a logical name (ENTER, LEFT, F5, ...), or null. */
    fun forName(name: String): Int? = NAMED[name.uppercase()]

    /**
     * Latin-1 printable characters map identity to their Unicode code point
     * (the X11 Latin-1 keysyms are exactly the ISO-8859-1 values). Control
     * characters and anything outside Latin-1 printable range return null
     * (the caller decides to drop them).
     */
    fun forChar(c: Char): Int? =
        if (c.code in 0x20..0x7E || c.code in 0xA0..0xFF) c.code else null

    /** True when [name] is a modifier key (sticky handling in the UI). */
    fun isModifier(name: String): Boolean = when (name.uppercase()) {
        "SHIFT_L", "SHIFT_R", "CTRL_L", "CTRL_R", "ALT_L", "ALT_R", "META_L", "META_R" -> true
        else -> false
    }
}
