package com.crossberry.noxs.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R

/** Searchable catalogue of Noxs host tools. Guest packages remain in NX. */
class AppCenterActivity : AppCompatActivity() {
    private data class Entry(
        val title: String, val description: String, val category: String,
        val status: String, val icon: String, val target: Class<*>? = null
    )

    private val entries by lazy {
        listOf(
            Entry("Terminal", "Linux shell and session workspace", "System", "Available", "›", TerminalActivity::class.java),
            Entry("File Manager", "Browse files inside the Noxs Linux environment", "System", "Available", "▤", FileBrowserActivity::class.java),
            Entry("Code Editor", "Open the Noxs code-server editor", "Developer", "Available", "⌘", EditorActivity::class.java),
            Entry("Web Browser", "Open the guarded Noxs browser", "Productivity", "Available", "◎", WebWindowActivity::class.java),
            Entry("Plugin Manager", "Browse plugins and manage permissions", "Integrations", "Available", "◇", PluginStoreActivity::class.java),
            Entry("NX Package Manager", "Install and manage verified Linux guest packages", "Developer", "Available", "□", PackageManagerActivity::class.java),
            Entry("Settings", "Configure Noxs and terminal behavior", "System", "Available", "⚙", SettingsActivity::class.java),
            Entry("App Manager", "Manage Noxs-owned modules", "System", "Planned", "▦"),
            Entry("Login & Profile Manager", "Manage accounts inside the Linux guest only", "System", "Planned", "♙"),
            Entry("System Monitor", "View guest resource usage and health", "System", "Planned", "⌁"),
            Entry("Image Viewer", "View PNG, JPEG, WebP and supported SVG files", "Media", "Planned", "▧"),
            Entry("Video Player", "Play supported local media", "Media", "Planned", "▶"),
            Entry("Music Player", "Play audio from selected files", "Media", "Planned", "♫"),
            Entry("Archive Manager", "Inspect and extract supported archives", "Productivity", "Planned", "▱"),
            Entry("AI Assistant", "Use the Noxs AI agent with approval controls", "Integrations", "Planned", "✦")
        )
    }
    private val categories = listOf("All", "System", "Productivity", "Media", "Developer", "Integrations")
    private var selected = "All"
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var chips: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            setBackgroundColor(Color.rgb(12, 15, 23))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("‹", 30, Color.WHITE).apply {
            setPadding(dp(4), 0, dp(12), 0)
            setOnClickListener { finish() }
            contentDescription = "Back"
        })
        header.addView(label("App Center", 24, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(label("N", 20, Color.rgb(115, 160, 255), true))
        root.addView(header)

        root.addView(label("Discover tools for your Noxs environment", 13, Color.LTGRAY).apply {
            setPadding(0, dp(2), 0, dp(14))
        })
        search = EditText(this).apply {
            hint = "Search apps and tools"
            singleLine = true
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(29, 35, 49), dp(16))
        }
        root.addView(search, LinearParams(MATCH, dp(48)))
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        root.addView(chipScroll, LinearParams(MATCH, dp(54)))
        root.addView(label("BUILT-IN TOOLS", 11, Color.rgb(140, 158, 190), true).apply {
            setPadding(2, dp(8), 0, dp(8))
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearParams(MATCH, 0, 1f))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(s: Editable?) {}
        })
        render()
    }

    private fun render() {
        chips.removeAllViews()
        categories.forEach { category ->
            val active = selected == category
            val chip = label(category, 12, if (active) Color.WHITE else Color.LTGRAY, active).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = rounded(if (active) Color.rgb(55, 91, 166) else Color.rgb(29, 35, 49), dp(18))
                setOnClickListener { selected = category; render() }
            }
            chips.addView(chip, LinearParams(WRAP, dp(34)).apply { marginEnd = dp(8); topMargin = dp(10) })
        }
        list.removeAllViews()
        val query = search.text?.toString()?.trim().orEmpty()
        val filtered = entries.filter {
            (selected == "All" || it.category == selected) &&
                (query.isEmpty() || "${it.title} ${it.description} ${it.category}".contains(query, true))
        }
        if (filtered.isEmpty()) {
            list.addView(label("No matching apps", 15, Color.LTGRAY).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(36), 0, dp(36))
            })
        }
        filtered.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(12), dp(12))
                background = rounded(Color.rgb(25, 31, 44), dp(18))
                isClickable = true
                isFocusable = true
                setOnClickListener { openEntry(entry) }
            }
            card.addView(label(entry.icon, 23, Color.rgb(133, 169, 255), true).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(43, 54, 78), dp(14))
            }, LinearParams(dp(48), dp(48)))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            texts.addView(label(entry.title, 15, Color.WHITE, true))
            texts.addView(label(entry.description, 12, Color.LTGRAY).apply { setPadding(0, dp(4), 0, dp(5)) })
            texts.addView(label("${entry.category} · ${entry.status}", 11,
                if (entry.status == "Available") Color.rgb(110, 205, 155) else Color.rgb(235, 190, 105)))
            card.addView(texts, LinearParams(0, WRAP, 1f))
            card.addView(label("›", 23, Color.LTGRAY))
            list.addView(card, LinearParams(MATCH, WRAP).apply { bottomMargin = dp(9) })
        }
    }

    private fun openEntry(entry: Entry) {
        val target = entry.target
        if (target != null) {
            startActivity(Intent(this, target))
        } else {
            AlertDialog.Builder(this)
                .setTitle(entry.title)
                .setMessage("This feature is planned and is not available in this build yet.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun LinearParams(width: Int, height: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(width, height, weight)
    companion object { private const val MATCH = -1; private const val WRAP = -2 }
}
