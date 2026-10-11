/*
 * Noxs terminal-emulator — original clean-room implementation of a VT100/
 * VT220/xterm-subset parser. Covers what real Debian CLI tools emit:
 * bash/less/vim-tiny/nano/top/htop/curl progress/htop box drawing.
 *
 * Supported: SGR (16/256/truecolor, attrs), CUP/CUU/D/F/B/CHA/VPA, ED/EL,
 * IL/DL/ICH/DCH/ECH, SU/SD, DECSTBM scroll regions, DECSET/DECRST (alt screen
 * 47/1047/1048/1049, cursor 25, wrap 7, origin 6, app-cursor 1, bracketed
 * paste 2004), OSC 0/2 titles, DECSC/DECRC, IND/RI/NEL, HTS/TBC/CHT/CBT,
 * DECALN, DSR/CPR/DA responses, SO/SI + DEC special graphics, UTF-8 incl.
 * wide chars, soft line wrap.
 */
package com.crossberry.noxs.terminal.emulator

class TerminalEmulator(
    private val client: Client,
    cols: Int,
    rows: Int,
    scrollbackLines: Int = NoxsScrollbackDefault
) {

    interface Client {
        fun onScreenChanged()
        fun onTitleChanged(title: String)
        fun onBell()
        fun onResize(cols: Int, rows: Int)
        /** Responses (CPR/DA/DCS replies) that must go to the child process. */
        fun onReply(data: ByteArray)

        /**
         * OSC 7: the shell reported its current working directory
         * (Noxs shell integration emits it on every prompt). Default no-op
         * so existing clients keep compiling.
         */
        fun onWorkingDirectoryChanged(dir: String) {}
    }

    val buffer = TerminalBuffer(cols, rows, scrollbackLines)

    // --- parser state ---
    private enum class State { GROUND, ESC, ESC_INTERMEDIATE, CSI_PARAM, CSI_INTERMEDIATE, OSC, CHARSET }

    private var state = State.GROUND
    private val csiParams = StringBuilder()
    private var csiPrivate = 0 // '?' '>' '<' '='
    private val csiIntermediates = StringBuilder()
    private val oscBuffer = StringBuilder()
    private var escIntermediate = 0
    private var charsetDesignate = 0

    // UTF-8 decoding state
    private var utf8BytesNeeded = 0
    private var utf8Accum = 0

    // tab stops (every 8 by default)
    private var tabStops = BooleanArray(0)

    var bracketedPaste = false
        private set
    var appCursorKeys = false
        private set
    var title = ""
        private set

    @Volatile
    var promptActive: Boolean = false
        private set

    /**
     * Absolute guest path of the shell's working directory as last reported
     * by the shell itself via OSC 7 (Noxs shell integration), or null when
     * the shell has not reported one yet. Powers the NXEditor workspace
     * detection and Activity Center records.
     */
    @Volatile
    var currentWorkingDirectory: String? = null
        private set

    private var activeHyperlink: String? = null

    // Track wide-char continuation cell
    private var pendingWide = false

    init {
        buffer.reset()
        initTabStops()
    }

    companion object {
        const val NoxsScrollbackDefault = 10000
        private const val BEL = 0x07
        private const val MAX_OSC7_LENGTH = 1024
        private const val BS = 0x08
        private const val HT = 0x09
        private const val LF = 0x0a
        private const val VT = 0x0b
        private const val FF = 0x0c
        private const val CR = 0x0d
        private const val SO = 0x0e
        private const val SI = 0x0f
        private const val ESC = 0x1b
        private val ALLOWED_TERMINAL_LINKS = setOf(
            "https://crossberry.vercel.app",
            "mailto:crossberryweb@gmail.com"
        )
    }

    private fun initTabStops() {
        tabStops = BooleanArray(buffer.cols) { it % 8 == 0 }
    }

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        buffer.resize(newCols, newRows)
        val old = tabStops
        tabStops = BooleanArray(newCols) { it < old.size && old[it] || it % 8 == 0 && it >= old.size }
        client.onResize(newCols, newRows)
        client.onScreenChanged()
    }

    /** Clear the terminal buffer while holding the same lock used by rendering and output. */
    @Synchronized
    fun clearScreen() {
        buffer.reset()
        pendingWide = false
        activeHyperlink = null
        promptActive = false
    }

    /**
     * Applies a new scrollback capacity live (settings change) without
     * touching the screen, VT state or the child process.
     */
    @Synchronized
    fun setScrollbackLimit(maxLines: Int) {
        buffer.resizeScrollback(maxLines)
        client.onScreenChanged()
    }

    // ------------------------------------------------------------------ input

    @Synchronized
    fun write(data: ByteArray, length: Int = data.size) {
        var i = 0
        while (i < length) {
            val b = data[i++].toInt() and 0xff
            when (state) {
                State.GROUND -> ground(b)
                State.ESC -> esc(b)
                State.ESC_INTERMEDIATE -> escIntermediate(b)
                State.CSI_PARAM -> csiParam(b)
                State.CSI_INTERMEDIATE -> csiIntermediate(b)
                State.OSC -> osc(b)
                State.CHARSET -> charset(b)
            }
        }
        client.onScreenChanged()
    }

    private fun ground(b: Int) {
        if (utf8BytesNeeded > 0) {
            if (b and 0xc0 == 0x80) {
                utf8Accum = (utf8Accum shl 6) or (b and 0x3f)
                utf8BytesNeeded--
                if (utf8BytesNeeded == 0) {
                    val cp = utf8Accum
                    utf8Accum = 0
                    printCodePoint(cp)
                }
            } else {
                utf8BytesNeeded = 0
                utf8Accum = 0
                printCodePoint(0xfffd)
                ground(b)
            }
            return
        }
        when {
            b < 0x20 -> control(b)
            b == 0x7f -> { /* DEL ignored */ }
            b < 0x80 -> printCodePoint(b)
            b and 0xe0 == 0xc0 -> { utf8BytesNeeded = 1; utf8Accum = b and 0x1f }
            b and 0xf0 == 0xe0 -> { utf8BytesNeeded = 2; utf8Accum = b and 0x0f }
            b and 0xf8 == 0xf0 -> { utf8BytesNeeded = 3; utf8Accum = b and 0x07 }
            else -> printCodePoint(0xfffd)
        }
    }

    private fun control(b: Int) {
        when (b) {
            BEL -> client.onBell()
            BS -> {
                if (pendingWide) pendingWide = false
                if (buffer.cursorCol > 0) buffer.cursorCol--
            }
            HT -> {
                do {
                    if (buffer.cursorCol >= buffer.cols - 1) break
                    buffer.cursorCol++
                } while (!tabStopAt(buffer.cursorCol))
            }
            LF, VT, FF -> hardLineFeed()
            CR -> { buffer.cursorCol = 0; pendingWide = false }
            SO -> buffer.charsetActive = buffer.charsetG1
            SI -> buffer.charsetActive = buffer.charsetG0
            ESC -> { state = State.ESC; csiPrivate = 0 }
            0x18, 0x1a -> { state = State.GROUND } // CAN/SUB abort
            else -> {} // NUL etc. ignored
        }
    }

    private fun hardLineFeed() {
        buffer.currentLine().lineWrap = false
        lineFeed()
    }

    private fun lineFeed() {
        pendingWide = false
        if (buffer.cursorRow == buffer.bottomMargin) buffer.scrollUp(1)
        else if (buffer.cursorRow < buffer.rows - 1) buffer.cursorRow++
    }

    private fun reverseLineFeed() {
        if (buffer.cursorRow == buffer.topMargin) buffer.scrollDown(1)
        else if (buffer.cursorRow > 0) buffer.cursorRow--
    }

    // ------------------------------------------------------------- characters

    private fun printCodePoint(cp: Int) {
        val width = WcWidth.width(cp)
        if (width == 0) return // combining: skip attaching (simple model)
        val buf = buffer

        if (buf.autoWrap && buf.cursorCol >= buf.cols) {
            buf.currentLine().lineWrap = true
            buf.cursorCol = 0
            lineFeed()
        }

        if (buf.insertMode && width == 1 && buf.cursorCol < buf.cols - 1) {
            val line = buf.currentLine()
            for (i in buf.cols - 1 downTo buf.cursorCol + 1) {
                line.chars[i] = line.chars[i - 1]
                line.styles[i] = line.styles[i - 1]
                line.hyperlinks[i] = line.hyperlinks[i - 1]
            }
        }

        if (buf.cursorCol >= buf.cols) buf.cursorCol = buf.cols - 1
        val line = buf.currentLine()
        val ch = buf.mapCharset(cp.toChar())
        line.chars[buf.cursorCol] = ch
        line.styles[buf.cursorCol] = buf.currentStyle
        line.hyperlinks[buf.cursorCol] = activeHyperlink
        if (width == 2) {
            if (buf.cursorCol + 1 < buf.cols) {
                line.chars[buf.cursorCol + 1] = ' '
                line.styles[buf.cursorCol + 1] = TextStyle.wideContOf(buf.currentStyle)
                line.hyperlinks[buf.cursorCol + 1] = activeHyperlink
                buf.cursorCol += 2
                pendingWide = false
            } else {
                line.chars[buf.cursorCol] = ' '
                line.hyperlinks[buf.cursorCol] = null
                buf.cursorCol = buf.cols
                if (buf.autoWrap) {
                    line.lineWrap = true
                    buf.cursorCol = 0
                    lineFeed()
                    printCodePoint(cp)
                }
            }
        } else {
            buf.cursorCol++
        }
    }

    // ------------------------------------------------------------------ ESC

    private fun esc(b: Int) {
        when (b) {
            0x5b -> { state = State.CSI_PARAM; csiParams.setLength(0); csiIntermediates.setLength(0); csiPrivate = 0 }
            0x5d -> { state = State.OSC; oscBuffer.setLength(0) }
            0x28 -> { charsetDesignate = 0; state = State.CHARSET }
            0x29 -> { charsetDesignate = 1; state = State.CHARSET }
            0x37 -> { buffer.saveCursor(); state = State.GROUND }
            0x38 -> { buffer.restoreCursor(); state = State.GROUND }
            0x44 -> { hardLineFeed(); state = State.GROUND }
            0x45 -> { buffer.cursorCol = 0; hardLineFeed(); state = State.GROUND }
            0x4d -> { reverseLineFeed(); state = State.GROUND }
            0x63 -> { fullReset(); state = State.GROUND }
            0x23 -> { escIntermediate = '#'.code; state = State.ESC_INTERMEDIATE }
            0x3d -> { state = State.GROUND } // keypad app mode: no-op
            0x3e -> { state = State.GROUND }
            in 0x20..0x2f -> { escIntermediate = b; state = State.ESC_INTERMEDIATE }
            else -> state = State.GROUND
        }
    }

    private fun escIntermediate(b: Int) {
        if (escIntermediate == '#'.code && b == '8'.code) {
            decAlignmentTest()
        }
        state = State.GROUND
    }

    private fun charset(b: Int) {
        val design = if (b == '0'.code) 1 else 0
        if (charsetDesignate == 0) { buffer.charsetG0 = design; buffer.charsetActive = design } else buffer.charsetG1 = design
        state = State.GROUND
    }

    private fun decAlignmentTest() {
        val buf = buffer
        for (r in 0 until buf.rows) {
            val line = buf.screen()[r]
            for (c in 0 until buf.cols) {
                line.chars[c] = 'E'
                line.styles[c] = TextStyle.defaultStyle()
                line.hyperlinks[c] = null
            }
            line.lineWrap = false
        }
    }

    private fun fullReset() {
        buffer.reset()
        initTabStops()
        bracketedPaste = false
        appCursorKeys = false
        state = State.GROUND
        title = ""
        promptActive = false
        activeHyperlink = null
        client.onTitleChanged("")
    }

    // ------------------------------------------------------------------ CSI

    private fun csiParam(b: Int) {
        when (b) {
            in '0'.code..'9'.code, ';'.code, ':'.code -> csiParams.append(b.toChar())
            '?'.code, '>'.code, '<'.code, '='.code -> csiPrivate = b
            else -> { state = State.CSI_INTERMEDIATE; csiIntermediate(b) }
        }
    }

    private fun csiIntermediate(b: Int) {
        if (b in 0x20..0x2f) {
            csiIntermediates.append(b.toChar())
        } else {
            dispatchCsi(b)
            state = State.GROUND
        }
    }

    private fun params(default: Int = 1): IntArray {
        val raw = csiParams.toString()
        if (raw.isEmpty()) return intArrayOf(default)
        // Preserve explicit zero: EL/ED and TBC use zero as a meaningful mode.
        // Commands whose movement/count parameter defaults zero to one do that
        // at the call site, rather than losing the distinction here.
        return raw.split(';').map { s ->
            val sub = s.substringBefore(':')
            sub.toIntOrNull() ?: default
        }.ifEmpty { listOf(default) }.toIntArray()
    }

    private fun paramAt(idx: Int, default: Int): Int =
        params(default).getOrElse(idx) { default }

    private fun dispatchCsi(final: Int) {
        val buf = buffer
        val n = paramAt(0, 1).coerceAtLeast(1)
        when (final) {
            0x40 -> insertChars(n)
            0x41 -> buf.cursorRow = (buf.cursorRow - n).coerceAtLeast(if (buf.originMode) buf.topMargin else 0)
            0x42, 0x65 -> buf.cursorRow = (buf.cursorRow + n).coerceAtMost(if (buf.originMode) buf.bottomMargin else buf.rows - 1)
            0x43, 0x61 -> buf.cursorCol = (buf.cursorCol + n).coerceAtMost(buf.cols - 1)
            0x44 -> buf.cursorCol = (buf.cursorCol - n).coerceAtLeast(0)
            0x45 -> { buf.cursorRow = (buf.cursorRow + n).coerceAtMost(buf.rows - 1); buf.cursorCol = 0 }
            0x46 -> { buf.cursorRow = (buf.cursorRow - n).coerceAtLeast(0); buf.cursorCol = 0 }
            0x47, 0x60 -> buf.cursorCol = (n - 1).coerceIn(0, buf.cols - 1)
            0x48, 0x66 -> {
                val row = paramAt(0, 1) - 1
                val col = paramAt(1, 1) - 1
                setCursorPosition(col, row)
            }
            0x49 -> repeat(n.coerceAtMost(16)) { // CHT → tab forward
                do {
                    if (buffer.cursorCol >= buffer.cols - 1) break
                    buffer.cursorCol++
                } while (!tabStopAt(buffer.cursorCol))
            }
            0x4a -> eraseDisplay(paramAt(0, 0))
            0x4b -> eraseLine(paramAt(0, 0))
            0x4c -> if (inScrollRegion()) buf.insertLines(n)
            0x4d -> if (inScrollRegion()) buf.deleteLines(n)
            0x50 -> deleteChars(n)
            0x53 -> buf.scrollUp(n)
            0x54 -> buf.scrollDown(n)
            0x58 -> eraseChars(n)
            0x5a -> repeat(n.coerceAtMost(16)) { // CBT → tab back
                do { if (buf.cursorCol > 0) buf.cursorCol-- } while (buf.cursorCol > 0 && !tabStopAt(buf.cursorCol))
            }
            0x62 -> { /* REP: rarely used — ignore */ }
            0x63 -> client.onReply("\u001b[?62;6c".toByteArray()) // DA → VT220-ish
            0x64 -> buf.cursorRow = (n - 1).coerceIn(0, buf.rows - 1)
            0x67 -> when (paramAt(0, 0)) {
                0 -> if (buf.cursorCol < tabStops.size) tabStops[buf.cursorCol] = false
                3 -> java.util.Arrays.fill(tabStops, false)
            }
            0x68 -> setModes(reset = false)
            0x6c -> setModes(reset = true)
            0x6d -> selectGraphicRendition()
            0x6e -> when (n) {
                5 -> client.onReply("\u001b[0n".toByteArray())
                6 -> client.onReply("\u001b[${buf.cursorRow + 1};${buf.cursorCol + 1}R".toByteArray())
            }
            0x72 -> {
                val topValue = paramAt(0, 1).let { if (it <= 0) 1 else it }
                val bottomValue = paramAt(1, buf.rows).let { if (it <= 0) buf.rows else it }
                val top = (topValue - 1).coerceIn(0, buf.rows - 1)
                val bottom = (bottomValue - 1).coerceIn(top, buf.rows - 1)
                buf.topMargin = top
                buf.bottomMargin = bottom
                setCursorPosition(0, if (buf.originMode) top else 0)
            }
            0x73 -> buf.saveCursor()
            0x75 -> buf.restoreCursor()
        }
    }

    private fun inScrollRegion(): Boolean {
        // IL/DL only apply when the cursor is inside the scroll region
        val buf = buffer
        return buf.cursorRow in buf.topMargin..buf.bottomMargin
    }

    private fun setCursorPosition(col: Int, row: Int) {
        val buf = buffer
        var r = row
        var c = col
        if (buf.originMode) {
            r = (r + buf.topMargin).coerceIn(buf.topMargin, buf.bottomMargin)
        }
        buf.cursorRow = r.coerceIn(0, buf.rows - 1)
        buf.cursorCol = c.coerceIn(0, buf.cols - 1)
        pendingWide = false
    }

    private fun eraseDisplay(mode: Int) {
        val buf = buffer
        val scr = buf.screen()
        when (mode) {
            0 -> {
                eraseLineRange(buf.cursorCol, buf.cols, scr[buf.cursorRow])
                for (r in buf.cursorRow + 1 until buf.rows) scr[r].clear()
            }
            1 -> {
                for (r in 0 until buf.cursorRow) scr[r].clear()
                eraseLineRange(0, buf.cursorCol + 1, scr[buf.cursorRow])
            }
            2 -> for (r in 0 until buf.rows) scr[r].clear()
            3 -> { buf.clearScrollback(); for (r in 0 until buf.rows) scr[r].clear() }
        }
    }

    private fun eraseLine(mode: Int) {
        val line = buffer.currentLine()
        when (mode) {
            0 -> eraseLineRange(buffer.cursorCol, buffer.cols, line)
            1 -> eraseLineRange(0, buffer.cursorCol + 1, line)
            2 -> line.clear()
        }
    }

    private fun eraseLineRange(from: Int, to: Int, line: TerminalBuffer.Line) {
        // Erase fills with blanks using the current background (BCE-style).
        val bgStyle = TextStyle.encode(TextStyle.DEFAULT_FG_INDEX, TextStyle.bg(buffer.currentStyle), 0)
        for (i in from until minOf(to, buffer.cols)) {
            line.chars[i] = ' '
            line.styles[i] = bgStyle
            line.hyperlinks[i] = null
        }
        if (from <= 0 && to >= buffer.cols) line.lineWrap = false
    }

    private fun insertChars(n: Int) {
        val line = buffer.currentLine()
        val c = buffer.cursorCol
        for (i in buffer.cols - 1 downTo c + n) {
            line.chars[i] = line.chars[i - n]
            line.styles[i] = line.styles[i - n]
            line.hyperlinks[i] = line.hyperlinks[i - n]
        }
        for (i in c until minOf(c + n, buffer.cols)) {
            line.chars[i] = ' '
            line.styles[i] = TextStyle.defaultStyle()
            line.hyperlinks[i] = null
        }
    }

    private fun deleteChars(n: Int) {
        val line = buffer.currentLine()
        val c = buffer.cursorCol
        for (i in c until buffer.cols - n) {
            line.chars[i] = line.chars[i + n]
            line.styles[i] = line.styles[i + n]
            line.hyperlinks[i] = line.hyperlinks[i + n]
        }
        for (i in maxOf(c, buffer.cols - n) until buffer.cols) {
            line.chars[i] = ' '
            line.styles[i] = TextStyle.defaultStyle()
            line.hyperlinks[i] = null
        }
    }

    private fun eraseChars(n: Int) {
        val line = buffer.currentLine()
        val bgStyle = TextStyle.encode(TextStyle.DEFAULT_FG_INDEX, TextStyle.DEFAULT_BG_INDEX, 0)
        for (i in buffer.cursorCol until minOf(buffer.cursorCol + n, buffer.cols)) {
            line.chars[i] = ' '
            line.styles[i] = bgStyle
            line.hyperlinks[i] = null
        }
    }

    private fun tabStopAt(col: Int): Boolean = col < tabStops.size && tabStops[col]

    // ---------------------------------------------------------------- modes

    private fun setModes(reset: Boolean) {
        val values = csiParams.toString().split(';').mapNotNull { it.toIntOrNull() }
        if (csiPrivate == 0x3f) {
            for (m in values) when (m) {
                1 -> appCursorKeys = !reset
                6 -> buffer.originMode = !reset
                7 -> buffer.autoWrap = !reset
                25 -> buffer.cursorVisible = !reset
                47 -> buffer.setAltScreen(!reset)
                1047 -> buffer.setAltScreen(!reset)
                1048 -> if (!reset) buffer.saveCursor() else buffer.restoreCursor()
                1049 -> {
                    if (!reset) { buffer.saveCursor(); buffer.setAltScreen(true) }
                    else { buffer.setAltScreen(false); buffer.restoreCursor() }
                }
                2004 -> bracketedPaste = !reset
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------ SGR

    private fun selectGraphicRendition() {
        val rawParams = csiParams.toString().ifEmpty { "0" }
        val parts = rawParams.split(';').map { it.ifEmpty { "0" } }
        var i = 0
        val buf = buffer
        var fg = TextStyle.fg(buf.currentStyle)
        var bg = TextStyle.bg(buf.currentStyle)
        var flags = TextStyle.flags(buf.currentStyle)
        val charset = TextStyle.charset(buf.currentStyle)

        while (i < parts.size) {
            val p = parts[i].toIntOrNull() ?: 0
            when (p) {
                0 -> { fg = TextStyle.DEFAULT_FG_INDEX; bg = TextStyle.DEFAULT_BG_INDEX; flags = 0 }
                1 -> flags = flags or TextStyle.FLAG_BOLD
                2 -> flags = flags or TextStyle.FLAG_DIM
                3 -> flags = flags or TextStyle.FLAG_ITALIC
                4 -> flags = flags or TextStyle.FLAG_UNDERLINE
                5 -> flags = flags or TextStyle.FLAG_BLINK
                7 -> flags = flags or TextStyle.FLAG_REVERSE
                8 -> flags = flags or TextStyle.FLAG_INVISIBLE
                9 -> flags = flags or TextStyle.FLAG_STRIKETHROUGH
                21 -> flags = flags or TextStyle.FLAG_UNDERLINE
                22 -> flags = flags and (TextStyle.FLAG_BOLD or TextStyle.FLAG_DIM).inv()
                23 -> flags = flags and TextStyle.FLAG_ITALIC.inv()
                24 -> flags = flags and TextStyle.FLAG_UNDERLINE.inv()
                25 -> flags = flags and TextStyle.FLAG_BLINK.inv()
                27 -> flags = flags and TextStyle.FLAG_REVERSE.inv()
                28 -> flags = flags and TextStyle.FLAG_INVISIBLE.inv()
                29 -> flags = flags and TextStyle.FLAG_STRIKETHROUGH.inv()
                in 30..37 -> fg = p - 30
                38 -> {
                    val ext = parseExtendedColor(parts, i)
                    if (ext != null) { fg = ext.color; i = ext.nextIndex } else return
                }
                39 -> fg = TextStyle.DEFAULT_FG_INDEX
                in 40..47 -> bg = p - 40
                48 -> {
                    val ext = parseExtendedColor(parts, i)
                    if (ext != null) { bg = ext.color; i = ext.nextIndex } else return
                }
                49 -> bg = TextStyle.DEFAULT_BG_INDEX
                in 90..97 -> fg = p - 90 + 8
                in 100..107 -> bg = p - 100 + 8
            }
            i++
        }
        buf.currentStyle = TextStyle.encode(fg, bg, flags, charset)
    }

    private class ExtColor(val color: Int, val nextIndex: Int)

    private fun parseExtendedColor(parts: List<String>, idx: Int): ExtColor? {
        val mode = parts.getOrNull(idx + 1)?.toIntOrNull() ?: return null
        return when (mode) {
            5 -> {
                val v = parts.getOrNull(idx + 2)?.toIntOrNull() ?: return null
                ExtColor(v.coerceIn(0, 255), idx + 2)
            }
            2 -> {
                val r = parts.getOrNull(idx + 2)?.toIntOrNull() ?: return null
                val g = parts.getOrNull(idx + 3)?.toIntOrNull() ?: return null
                val b = parts.getOrNull(idx + 4)?.toIntOrNull() ?: return null
                ExtColor(-0x1000000 or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255), idx + 4)
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ OSC

    private fun osc(b: Int) {
        when {
            b == BEL -> { finishOsc(); state = State.GROUND }
            b == ESC -> { /* expect ST (ESC \) */ oscBuffer.append('\u001b'); }
            b == 0x5c && oscBuffer.endsWith("\u001b") -> {
                oscBuffer.setLength(oscBuffer.length - 1)
                finishOsc(); state = State.GROUND
            }
            else -> oscBuffer.append(b.toChar())
        }
    }

    private fun finishOsc() {
        val text = oscBuffer.toString()
        val code = text.substringBefore(';').toIntOrNull() ?: return
        when (code) {
            0, 2 -> {
                title = text.substringAfter(';', "").ifEmpty { "Noxs" }
                client.onTitleChanged(title)
            }
            8 -> {
                val payload = text.substringAfter(';', "")
                val uri = payload.substringAfter(';', "")
                activeHyperlink = uri.takeIf { it in ALLOWED_TERMINAL_LINKS }
            }
            133 -> {
                when (text.substringAfter(';', "").substringBefore(';')) {
                    "A" -> promptActive = true // shell prompt is accepting input
                    "B", "C", "D" -> promptActive = false
                }
            }
            7 -> {
                val payload = text.substringAfter(';', "")
                parseOsc7Path(payload)?.let { dir ->
                    if (dir != currentWorkingDirectory) {
                        currentWorkingDirectory = dir
                        client.onWorkingDirectoryChanged(dir)
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Parses an OSC 7 payload into an absolute path. Accepts the forms the
     * Noxs shell integration and common shells emit:
     *   /abs/path                     (bare path — allowed extension)
     *   file:///abs/path              (empty authority)
     *   file://host/abs/path          (authority stripped)
     * Percent escapes are decoded as UTF-8 bytes; malformed sequences stay
     * literal. Returns null for anything that is not a plausible absolute
     * directory path (relative paths, control characters, oversized payloads).
     */
    internal fun parseOsc7Path(payload: String): String? {
        if (payload.isEmpty() || payload.length > MAX_OSC7_LENGTH) return null
        val path: String = when {
            payload.startsWith("file://") -> {
                val rest = payload.substring(7)
                val slash = rest.indexOf('/')
                if (slash < 0) return null
                rest.substring(slash)
            }
            payload.startsWith("file:") -> {
                // file:/abs or file:///abs both land on the last slash run
                val rest = payload.substring(5)
                val slash = rest.indexOf('/')
                if (slash < 0) return null
                rest.substring(slash)
            }
            payload.startsWith("/") -> payload
            else -> return null
        }
        if (path.length < 1) return null
        val decoded = decodePercentUtf8(path)
        if (!decoded.startsWith("/")) return null
        // Control characters (except none at all) would corrupt downstream UIs.
        if (decoded.any { it < ' ' || it == '\u007f' }) return null
        return decoded
    }

    private fun decodePercentUtf8(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        val hex = StringBuilder(2)
        var i = 0
        // First pass: expand %XX escapes into raw bytes (others pass through).
        val raw = ByteArray(s.length + s.length / 3 + 8)
        var n = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                hex.setLength(0)
                var j = i + 1
                while (j < s.length && hex.length < 2 && s[j].isHexDigit()) {
                    hex.append(s[j]); j++
                }
                if (hex.length == 2) {
                    raw[n++] = hex.toString().toInt(16).toByte()
                    i = j
                    continue
                }
            }
            // Literal byte (UTF-8 multibyte sequences pass through as chars)
            val b = c.code
            when {
                b < 0x80 -> raw[n++] = b.toByte()
                b < 0x800 -> {
                    raw[n++] = (0xC0 or (b shr 6)).toByte()
                    raw[n++] = (0x80 or (b and 0x3F)).toByte()
                }
                else -> {
                    raw[n++] = (0xE0 or (b shr 12)).toByte()
                    raw[n++] = (0x80 or ((b shr 6) and 0x3F)).toByte()
                    raw[n++] = (0x80 or (b and 0x3F)).toByte()
                }
            }
            i++
        }
        out.write(raw, 0, n)
        return out.toString("UTF-8")
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // -------------------------------------------------------------- paste in

    /** App-side paste honoring bracketed paste mode. */
    fun paste(text: String): ByteArray {
        val payload = text.replace("\r\n", "\r").replace("\n", "\r")
        return if (bracketedPaste) {
            "\u001b[200~$payload\u001b[201~".toByteArray(Charsets.UTF_8)
        } else payload.toByteArray(Charsets.UTF_8)
    }

    /** Return an explicitly allowlisted OSC-8 link at a screen cell. */
    @Synchronized
    fun hyperlinkAt(row: Int, col: Int): String? {
        val screen = buffer.screen()
        if (row !in 0 until minOf(buffer.rows, screen.size)) return null
        val line = screen.getOrNull(row) ?: return null
        if (col !in line.hyperlinks.indices) return null
        return line.hyperlinks[col]
    }

    /** Link lookup for a viewport row, including retained scrollback. */
    @Synchronized
    fun hyperlinkAtDocumentRow(documentRow: Int, col: Int): String? {
        val line = buffer.documentLineAt(documentRow) ?: return null
        return line.hyperlinks.getOrNull(col)
    }

    /** Resolve link taps against stable row identity if output arrived after hit-testing. */
    @Synchronized
    fun hyperlinkAtDocumentPosition(lineIdentity: Long, col: Int): String? {
        val row = buffer.documentRowOf(lineIdentity) ?: return null
        return buffer.documentLineAt(row)?.hyperlinks?.getOrNull(col)
    }

    /** Snapshot used by renderer & selection. */
    @Synchronized
    fun screenText(): List<String> {
        val out = ArrayList<String>(buffer.rows)
        var join = false
        for (r in 0 until buffer.rows) {
            val line = buffer.screen()[r]
            val text = line.text()
            if (join && out.isNotEmpty()) out[out.size - 1] = out.last() + text
            else out.add(text)
            join = line.lineWrap
        }
        return out
    }

    /** Full transcript (scrollback + non-empty screen lines up to cursor) for Terminal TextView / copy. */
    @Synchronized
    fun transcriptText(): String {
        val sb = StringBuilder()
        for (i in buffer.scrollbackSize - 1 downTo 0) {
            val line = buffer.scrollbackLine(i) ?: continue
            sb.append(line.text())
            if (!line.lineWrap) sb.append('\n')
        }
        val scr = buffer.screen()
        var lastRow = buffer.cursorRow.coerceIn(0, buffer.rows - 1)
        for (r in buffer.rows - 1 downTo 0) {
            if (scr[r].text().isNotEmpty()) {
                if (r > lastRow) lastRow = r
                break
            }
        }
        for (r in 0..lastRow) {
            val line = scr[r]
            val raw = line.text()
            if (r == buffer.cursorRow && buffer.cursorVisible) {
                val col = buffer.cursorCol.coerceAtLeast(0)
                if (col >= raw.length) {
                    sb.append(raw).append(" ".repeat((col - raw.length).coerceAtMost(80))).append('█')
                } else {
                    sb.append(raw.substring(0, col)).append('█').append(raw.substring(col + 1))
                }
            } else {
                sb.append(raw)
            }
            if (r < lastRow && !line.lineWrap) sb.append('\n')
        }
        return sb.toString()
    }
}
