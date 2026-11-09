package com.crossberry.noxs.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.crossberry.noxs.NoxsApplication
import com.crossberry.noxs.devstudio.ProjectGenerator
import com.crossberry.noxs.devstudio.ProjectLanguage
import com.crossberry.noxs.devstudio.ToolchainDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DeveloperStudioActivity : AppCompatActivity() {
    private var selectedLanguage = ProjectLanguage.KOTLIN
    private lateinit var projectName: EditText
    private lateinit var status: TextView
    private lateinit var languageButtons: LinearLayout
    private val ink = Color.rgb(242, 245, 250)
    private val muted = Color.rgb(157, 169, 190)
    private val panel = Color.rgb(27, 34, 48)
    private val accent = Color.rgb(82, 135, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        refreshToolchains()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(20))
            setBackgroundColor(Color.rgb(12, 16, 25))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("‹", 30, ink).apply {
            setPadding(0, 0, dp(14), 0)
            contentDescription = "Back"
            setOnClickListener { finish() }
        })
        header.addView(text("Developer Studio", 23, ink, true), LinearLayout.LayoutParams(0, dp(54), 1f))
        header.addView(text("NX", 14, accent, true))
        root.addView(header)
        root.addView(text("Create projects inside your Noxs Linux workspace.", 13, muted).apply {
            setPadding(0, 0, 0, dp(18))
        })

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        content.addView(sectionTitle("1  ·  CHOOSE A LANGUAGE"))
        languageButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(languageButtons)
        renderLanguageButtons()

        content.addView(sectionTitle("2  ·  PROJECT DETAILS"))
        projectName = EditText(this).apply {
            hint = "Project name (example: HelloNoxs)"
            singleLine = true
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(panel, 14)
        }
        content.addView(projectName, LinearLayout.LayoutParams(-1, dp(52)).apply {
            bottomMargin = dp(12)
        })
        content.addView(text("Workspace", 13, muted, true))
        content.addView(text("/home/noxs/Projects/", 14, ink).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(panel, 12)
        })

        val create = actionButton("Create project", accent)
        content.addView(create, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(18)
            bottomMargin = dp(10)
        })
        create.setOnClickListener { createProject() }
        val terminal = actionButton("Open Noxs terminal", Color.rgb(42, 53, 72))
        content.addView(terminal, LinearLayout.LayoutParams(-1, dp(48)))
        terminal.setOnClickListener { startActivity(Intent(this, TerminalActivity::class.java)) }

        content.addView(sectionTitle("3  ·  TOOLCHAIN STATUS"))
        status = text("Checking installed tools…", 13, muted).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(panel, 14)
        }
        content.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun renderLanguageButtons() {
        languageButtons.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ProjectLanguage.entries.forEachIndexed { index, language ->
            val button = TextView(this).apply {
                text = language.title
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (selectedLanguage == language) Color.WHITE else muted)
                background = rounded(if (selectedLanguage == language) Color.rgb(54, 91, 157) else panel, 13)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener {
                    selectedLanguage = language
                    renderLanguageButtons()
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = if (index == ProjectLanguage.entries.lastIndex) 0 else dp(7)
            })
        }
        languageButtons.addView(row)
        val detail = when (selectedLanguage) {
            ProjectLanguage.KOTLIN -> "Gradle JVM application · Main.kt"
            ProjectLanguage.JAVA -> "Gradle JVM application · Main.java"
            ProjectLanguage.MOZHI -> "Mozhi source project · src/main.mz"
            ProjectLanguage.RUST -> "Cargo application · src/main.rs"
        }
        languageButtons.addView(text(detail, 12, muted).apply {
            setPadding(dp(2), dp(8), 0, dp(16))
        })
    }

    private fun createProject() {
        val name = projectName.text?.toString()?.trim().orEmpty()
        val paths = runCatching {
            (application as NoxsApplication).environments.activePaths()
        }.getOrElse {
            showError(it.message ?: "Could not access the active Noxs environment.")
            return
        }
        val language = selectedLanguage
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ProjectGenerator(paths.rootfsHomeNoxs).create(name, language) }
            }
            result.onSuccess { project ->
                AlertDialog.Builder(this@DeveloperStudioActivity)
                    .setTitle("Project created")
                    .setMessage(
                        "\${project.name} is ready.\n\n\${project.guestPath}\n\nOpen it in the Noxs code editor?"
                    )
                    .setPositiveButton("Open editor") { _, _ ->
                        startActivity(
                            Intent(this@DeveloperStudioActivity, EditorActivity::class.java)
                                .putExtra("noxs.extra.EDITOR_WORKSPACE", project.guestPath)
                        )
                    }
                    .setNeutralButton("Open terminal") { _, _ ->
                        startActivity(TerminalActivity.openAt(this@DeveloperStudioActivity, project.guestPath))
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }.onFailure { showError(it.message ?: "Project creation failed.") }
        }
    }

    private fun refreshToolchains() {
        val paths = runCatching {
            (application as NoxsApplication).environments.activePaths()
        }.getOrNull()
        if (paths == null) {
            status.text = "Noxs environment is not ready."
            return
        }
        lifecycleScope.launch {
            val tools = withContext(Dispatchers.IO) { ToolchainDetector(paths.rootfs).detect() }
            status.text = tools.joinToString("\n") {
                "\${if (it.installed) "●" else "○"}  \${it.command}: \${if (it.installed) "detected" else "not found"}"
            } + "\n\nInstall missing tools from the Noxs terminal when needed."
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Could not create project")
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun sectionTitle(value: String) = text(value, 11, muted, true).apply {
        setPadding(dp(2), dp(18), 0, dp(10))
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun actionButton(value: String, color: Int) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = rounded(color, 15)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
