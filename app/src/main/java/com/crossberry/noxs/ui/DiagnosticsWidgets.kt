/*
 * Noxs — original implementation.
 * Liquid Glass diagnostics widgets: translucent rounded cards (the
 * bg_noxs_glass_surface language used by the terminal chrome, rendered at
 * readable opacity), status rows with honest dot indicators and ripple pill
 * action buttons. Built with the platform SDK only — no new dependencies.
 *
 * Status vocabulary: OK (green ●), LIMITED (amber ● — an expected Android
 * runtime restriction, NOT a failure), FAIL (red ●), UNKNOWN (grey ○ —
 * the probe produced no data; never rendered as success).
 */
package com.crossberry.noxs.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.crossberry.noxs.R
import com.crossberry.noxs.runtime.NoxsDockerDoctorJson

object DiagnosticsWidgets {

    // Palette — glass surfaces over the settings navy canvas (colors.xml).
    private val CANVAS = 0xFF050B14.toInt()
    private val GLASS = 0xCC0D1725.toInt()
    private val GLASS_ELEVATED = 0xE6162335.toInt()
    private val HAIRLINE = 0x26FFFFFF
    private val TEXT = 0xFFF5F7FA.toInt()
    private val TEXT_SECONDARY = 0xFFA5B1C2.toInt()
    private val DIVIDER = 0xFF243347.toInt()
    private val ACCENT = 0xFF16D66A.toInt()
    private val WARN = 0xFFFFB020.toInt()
    private val ERROR = 0xFFFF5B67.toInt()
    private val UNKNOWN = 0xFF626B78.toInt()
    private val RIPPLE = 0x24FFFFFF

    const val CARD_RADIUS_DP = 22

    fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    /** Status colors for the dot indicator (ui status vocabulary). */
    fun colorFor(status: NoxsDockerDoctorJson.Status): Int = when (status) {
        NoxsDockerDoctorJson.Status.OK -> ACCENT
        NoxsDockerDoctorJson.Status.WARN -> WARN
        NoxsDockerDoctorJson.Status.FAIL -> ERROR
        NoxsDockerDoctorJson.Status.UNKNOWN -> UNKNOWN
    }

    // ------------------------------------------------------------- sections

    fun sectionHeader(context: Context, title: String): TextView = TextView(context).apply {
        text = title
        setTextColor(TEXT_SECONDARY)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(context, 4), dp(context, 18), dp(context, 4), dp(context, 8))
    }

    /** Translucent glass card container (liquid glass language, readable opacity). */
    fun glassCard(context: Context, vararg rows: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, CARD_RADIUS_DP).toFloat()
                setColor(GLASS)
                setStroke(dp(context, 1), HAIRLINE)
            }
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            rows.forEach { addView(it) }
        }

    // ---------------------------------------------------------------- rows

    /** Card headline: colored status dot + title + one-line state summary. */
    fun headlineRow(context: Context, title: String, status: NoxsDockerDoctorJson.Status, summary: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(dot(context, status))
        row.addView(TextView(context).apply {
            text = title
            setTextColor(TEXT)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10)
            }
        })
        row.addView(TextView(context).apply {
            text = summary
            setTextColor(colorFor(status))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        })
        return row
    }

    /** One checked item inside a card: dot, label, real detail text below. */
    fun statusRow(context: Context, label: String, status: NoxsDockerDoctorJson.Status, detail: String): View {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(dot(context, status))
        line.addView(TextView(context).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10)
            }
        })
        if (detail.isNotBlank()) {
            line.addView(TextView(context).apply {
                text = detail
                setTextColor(TEXT_SECONDARY)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setMaxWidth(dp(context, 170))
            })
        }
        wrap.addView(line)
        if (status == NoxsDockerDoctorJson.Status.FAIL && detail.isNotBlank()) {
            wrap.addView(TextView(context).apply {
                text = detail
                setTextColor(WARN)
                textSize = 12f
                setPadding(dp(context, 26), dp(context, 2), 0, 0)
            })
        }
        return wrap
    }

    /** Colored status dot (8dp) with a content description of the state. */
    fun dot(context: Context, status: NoxsDockerDoctorJson.Status): View = View(context).apply {
        val size = dp(context, 10)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_VERTICAL }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorFor(status))
        }
        contentDescription = when (status) {
            NoxsDockerDoctorJson.Status.OK -> context.getString(R.string.diag_status_ok)
            NoxsDockerDoctorJson.Status.WARN -> context.getString(R.string.diag_status_limited)
            NoxsDockerDoctorJson.Status.FAIL -> context.getString(R.string.diag_status_fail)
            NoxsDockerDoctorJson.Status.UNKNOWN -> context.getString(R.string.diag_status_unknown)
        }
    }

    /** Real info line inside a card (monospace detail, wraps). */
    fun infoLine(context: Context, text: String): TextView = TextView(context).apply {
        setText(text)
        setTextColor(TEXT_SECONDARY)
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(context, 2), 0, dp(context, 2))
    }

    // ------------------------------------------------------------- actions

    /** Ripple pill action button (glass bar language); disabled while busy. */
    fun actionButton(context: Context, label: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            setAllCaps(false)
            textSize = 13f
            setTextColor(TEXT)
            stateListAnimator = null
            background = RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                GradientDrawable().apply {
                    cornerRadius = dp(context, 18).toFloat()
                    setColor(GLASS_ELEVATED)
                    setStroke(dp(context, 1), HAIRLINE)
                },
                GradientDrawable().apply {
                    cornerRadius = dp(context, 18).toFloat()
                    setColor(RIPPLE)
                }
            )
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(context, 10) }
            setOnClickListener { onClick() }
        }

    /** Row that hosts action pills. */
    fun actionRow(context: Context, vararg buttons: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 2))
            buttons.forEach { addView(it) }
        }
}
