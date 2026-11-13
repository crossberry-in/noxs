/*
 * Noxs — original implementation.
 * FloatingStatusPanel: a compact, information-dense developer control window
 * rendered inside the terminal screen. In-app overlay only — no system alert
 * window permission. Animations are a single subtle fade/slide on show.
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.crossberry.noxs.runtime.NoxsActivityRecord
import com.crossberry.noxs.runtime.NoxsActivityStatus
import com.crossberry.noxs.runtime.NoxsTreeUsage

class FloatingStatusPanel(context: Context) : LinearLayout(context) {

    var onTerminal: (() -> Unit)? = null
    var onLogs: (() -> Unit)? = null
    var onDetails: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    private val statusChip = chip(context)
    private val values = HashMap<String, TextView>()
    private val outputLine = TextView(context)

    init {
        val dp = resources.displayMetrics.density
        background = GradientDrawable().apply {
            cornerRadius = 26f * dp
            setColor(0xE6111418.toInt())
            setStroke((1 * dp).toInt(), 0x55FFFFFF)
        }
        elevation = 8f * dp
        val pad = (10 * dp).toInt()
        setPadding(pad, pad, pad, pad)

        orientation = VERTICAL
        // Placement inside the terminal FrameLayout overlay: docked bottom card.
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { setMargins((12 * dp).toInt(), 0, (12 * dp).toInt(), (12 * dp).toInt()) }

        // Header: Noxs + status
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(context).apply {
            text = "Noxs"
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Color.WHITE)
            setStyleBold()
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(statusChip)
        addView(header)

        addView(separator(context))

        // Info grid
        INFO_ROWS.forEach { (key, label) ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            val l = TextView(context).apply {
                text = label
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(0xFF8A94A0.toInt())
            }
            val v = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setTextColor(0xFFE6E6E6.toInt())
                maxLines = 1
            }
            row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f))
            row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.66f))
            values[key] = v
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * dp).toInt() })
        }

        addView(separator(context))

        outputLine.typeface = Typeface.MONOSPACE
        outputLine.textSize = 11f
        outputLine.setTextColor(0xFF9AA5B1.toInt())
        outputLine.maxLines = 2
        addView(outputLine)

        // Buttons
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
        fun button(label: String, danger: Boolean = false, onClick: () -> Unit): View =
            TextView(context).apply {
                text = label
                typeface = Typeface.MONOSPACE
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                setTextColor(if (danger) 0xFFFF8A80.toInt() else 0xFF3DDC84.toInt())
                minHeight = (48 * dp).toInt()
                isFocusable = true
                isClickable = true
                contentDescription = label
                val shape = GradientDrawable().apply {
                    cornerRadius = 16f * dp
                    setColor(0xCC20262D.toInt())
                    setStroke((1 * dp).toInt(), 0x55FFFFFF)
                }
                val mask = GradientDrawable().apply {
                    cornerRadius = 16f * dp
                    setColor(Color.WHITE)
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(0x44FFFFFF),
                    shape,
                    mask
                )
                setOnClickListener { onClick() }
            }
        listOf(
            button(context.getString(com.crossberry.noxs.R.string.float_terminal)) { onTerminal?.invoke() },
            button(context.getString(com.crossberry.noxs.R.string.float_logs)) { onLogs?.invoke() },
            button(context.getString(com.crossberry.noxs.R.string.float_details)) { onDetails?.invoke() },
            button(context.getString(com.crossberry.noxs.R.string.float_stop), danger = true) { onStop?.invoke() }
        ).forEachIndexed { index, b ->
            buttons.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = if (index < 3) (6 * dp).toInt() else 0
            })
        }
        addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * dp).toInt() })

        visibility = GONE
    }

    /** Renders one activity snapshot; usage may be null between samples. */
    fun render(record: NoxsActivityRecord, usage: NoxsTreeUsage?, nowMs: Long) {
        statusChip.text = statusText(record.status)
        statusChip.background = (statusChip.background as? GradientDrawable)?.apply {
            setStroke((1 * resources.displayMetrics.density).toInt(), statusColor(record.status))
        }
        statusChip.setTextColor(statusColor(record.status))

        values[KEY_SESSION]?.text = record.sessionId.ifBlank { "—" }
        values[KEY_PROCESS]?.text = record.command.ifBlank { record.title }
        values[KEY_CPU]?.text = usage?.let { "%.0f%%".format(it.cpuPercent) } ?: "—"
        values[KEY_RAM]?.text = usage?.let { "${it.rssKb / 1024} MB" } ?: "—"
        values[KEY_UPTIME]?.text = record.uptimeText(nowMs)
        values[KEY_DIR]?.text = record.workingDirectory.ifBlank { "—" }
        values[KEY_PID]?.text = record.pid?.toString() ?: "—"
        outputLine.text = record.outputSummary.ifBlank { " " }
    }

    fun showAnimated() {
        alpha = 0f
        translationY = 40f * resources.displayMetrics.density
        visibility = VISIBLE
        animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    fun hideAnimated(onDone: () -> Unit = {}) {
        animate().alpha(0f).translationY(40f * resources.displayMetrics.density)
            .setDuration(140).withEndAction { visibility = GONE; onDone() }.start()
    }

    private fun TextView.setStyleBold() { paint.isFakeBoldText = true }

    private fun separator(context: Context): View = View(context).apply {
        setBackgroundColor(0x44FFFFFF.toInt())
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt()).apply {
            setMargins(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
        }
    }

    private fun chip(context: Context): TextView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setPadding(
            (8 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(),
            (8 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt()
        )
        background = GradientDrawable().apply {
            cornerRadius = 8f * resources.displayMetrics.density
            setColor(0xFF1C232C.toInt())
            setStroke((1 * resources.displayMetrics.density).toInt(), 0xFF3DDC84.toInt())
        }
    }

    companion object {
        private const val KEY_SESSION = "session"
        private const val KEY_PROCESS = "process"
        private const val KEY_CPU = "cpu"
        private const val KEY_RAM = "ram"
        private const val KEY_UPTIME = "uptime"
        private const val KEY_DIR = "dir"
        private const val KEY_PID = "pid"
        private val INFO_ROWS = listOf(
            KEY_SESSION to "Session",
            KEY_PROCESS to "Process",
            KEY_CPU to "CPU",
            KEY_RAM to "RAM",
            KEY_UPTIME to "Uptime",
            KEY_DIR to "Directory",
            KEY_PID to "PID"
        )

        fun statusText(status: NoxsActivityStatus): String = when (status) {
            NoxsActivityStatus.QUEUED -> "○ QUEUED"
            NoxsActivityStatus.STARTING -> "● STARTING"
            NoxsActivityStatus.RUNNING -> "● RUNNING"
            NoxsActivityStatus.COMPLETED -> "✓ COMPLETED"
            NoxsActivityStatus.FAILED -> "✕ FAILED"
            NoxsActivityStatus.STOPPING -> "■ STOPPING"
            NoxsActivityStatus.STOPPED -> "■ STOPPED"
        }

        fun statusColor(status: NoxsActivityStatus): Int = when (status) {
            NoxsActivityStatus.RUNNING, NoxsActivityStatus.STARTING -> 0xFF3DDC84.toInt()
            NoxsActivityStatus.STOPPING, NoxsActivityStatus.STOPPED -> 0xFFFFD166.toInt()
            NoxsActivityStatus.FAILED -> 0xFFFF8A80.toInt()
            NoxsActivityStatus.COMPLETED -> 0xFF8A94A0.toInt()
            NoxsActivityStatus.QUEUED -> 0xFF9AA5B1.toInt()
        }
    }
}
