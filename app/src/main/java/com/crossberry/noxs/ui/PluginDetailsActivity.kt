/*
 * Noxs — original implementation.
 * PluginDetailsActivity: full metadata page for one plugin — logo, name,
 * version, description, author, license, category, permissions, commands,
 * minimum Noxs version, repository, the rendered README.md and the primary
 * action (Install / Update / Open / Enable / Disable / Uninstall).
 *
 * README rendering is hardened twice: PluginReadmeRenderer escapes and
 * sanitizes the markdown, and the WebView that shows the result runs with
 * JavaScript disabled.
 */
package com.crossberry.noxs.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.crossberry.noxs.R
import com.crossberry.noxs.runtime.NoxsUrlGuard
import com.crossberry.noxs.runtime.plugins.InstalledPlugin
import com.crossberry.noxs.runtime.plugins.NoxsPluginManager
import com.crossberry.noxs.runtime.plugins.NoxsPluginRuntime
import com.crossberry.noxs.runtime.plugins.PluginHttp
import com.crossberry.noxs.runtime.plugins.PluginReadmeRenderer
import com.crossberry.noxs.runtime.plugins.RegistryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PluginDetailsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: NoxsPluginManager? = null
    private var pluginId: String = ""

    private lateinit var metaColumn: LinearLayout
    private lateinit var logoBox: FrameLayout
    private lateinit var logoView: ImageView
    private lateinit var logoLetter: TextView
    private var cloudLogoView: WebView? = null
    private var cloudIcon: ByteArray? = null
    private lateinit var actionButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var dangerButton: Button
    private lateinit var permissionsBox: LinearLayout
    private lateinit var readmeView: WebView
    private var current: InstalledPlugin? = null
    private var entry: RegistryEntry? = null
    private var compat: com.crossberry.noxs.runtime.plugins.PluginCompatInfo? = null
    private var appVersion: String = "0.0.0"
    private var busy = false

    private val dp: Float
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = intent?.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        if (pluginId.isBlank()) {
            finish()
            return
        }
        manager = runCatching {
            val app = application as com.crossberry.noxs.NoxsApplication
            val paths = app.environments.activePaths()
            appVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "0.0.0"
            NoxsPluginManager(
                rootfsHome = paths.rootfsHomeNoxs,
                cacheDir = app.cacheDir,
                appVersion = appVersion,
                guestBinDir = java.io.File(paths.rootfs, "usr/local/bin"),
                cloudCatalog = noxsPluginCloudCatalog(app, paths.rootfsHomeNoxs)
            )
        }.getOrNull()
        buildUi()
        load()
    }

    private fun load() {
        scope.launch(Dispatchers.IO) {
            var entryLoaded = manager?.catalog(refresh = true)?.firstOrNull { it.id == pluginId }
            val installedLoaded = manager?.installed(pluginId)
            manager?.seenStore?.markSeen(listOf(pluginId))
            var cloudReadme: String? = null
            var cloudIconLoaded: ByteArray? = null
            if (entryLoaded != null && entryLoaded.readmeUrl == null && entryLoaded.artifact != null) {
                // Cloud plugins publish their docs inside the .noxs-plugin
                // artifact — download once, verify the published checksum and
                // enrich the entry with the authoritative plugin.json fields.
                runCatching {
                    val artifact = manager?.cloudCatalog?.inspect(pluginId)
                    if (artifact != null) {
                        entryLoaded = manager?.cloudCatalog
                            ?.authoritativeEntry(artifact.item, artifact.meta) ?: entryLoaded
                        cloudReadme = artifact.readme
                        cloudIconLoaded = artifact.iconSvg
                    }
                }
            }
            val compatLoaded = if (entryLoaded != null) {
                val entryForCompat = entryLoaded
                runCatching {
                    com.crossberry.noxs.runtime.plugins.PluginSdkCatalog.compat(
                        entryForCompat,
                        installedLoaded,
                        appVersion,
                        manager?.sdkStore?.installedVersions()?.toSet().orEmpty(),
                        installedLoaded == null && manager?.installer?.hasInvalidRecord(pluginId) == true,
                        installedLoaded != null && manager?.installer?.updateAvailable(entryForCompat, installedLoaded) == true
                    )
                }.getOrNull()
            } else null
            scope.launch {
                entry = entryLoaded
                current = installedLoaded
                compat = compatLoaded
                cloudIcon = cloudIconLoaded
                renderMeta()
                loadReadme(entryLoaded, installedLoaded, cloudReadme)
            }
        }
    }

    // ------------------------------------------------------------------ UI

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1016.toInt())
        }
        setContentView(root)

        // Header: back + title.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_back)
            setTextColor(0xFF7CE8B8.toInt())
            textSize = 14f
            setPadding(0, 0, (12 * dp).toInt(), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_title)
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 17f
        })
        root.addView(header)

        val scroll = androidx.core.widget.NestedScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        scroll.addView(content)

        // Logo + name + version block. The logo renders as a circle centered
        // at the top: a cloud artifact's icon.svg, the registry logoUrl, or a
        // letter fallback when neither exists.
        metaColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        logoBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams((84 * dp).toInt(), (84 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF121922.toInt())
                setStroke((1 * dp).toInt(), 0xFF22303C.toInt())
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        logoView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = TextView.GONE
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        logoBox.addView(logoView)
        logoLetter = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setTextColor(0xFF7CE8B8.toInt())
            textSize = 30f
            visibility = TextView.GONE
        }
        logoBox.addView(logoLetter)
        metaColumn.addView(logoBox)
        content.addView(metaColumn)

        // Permissions section.
        permissionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                setColor(0xFF121922.toInt())
                setStroke((1 * dp).toInt(), 0xFF22303C.toInt())
            }
        }
        content.addView(permissionsBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (12 * dp).toInt(), 0, 0) })

        // README (JS disabled, sanitized HTML).
        content.addView(sectionLabel(getString(R.string.plugin_details_readme)))
        readmeView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(0xFF121922.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // README links open in the Noxs browser (http/https only).
                    val url = request.url.toString()
                    when (val decision = NoxsUrlGuard.check(url)) {
                        is NoxsUrlGuard.Decision.Allowed -> startActivity(
                            Intent(this@PluginDetailsActivity, WebWindowActivity::class.java)
                                .putExtra(WebWindowActivity.EXTRA_URL, decision.url)
                        )
                        else -> Unit
                    }
                    return true
                }
            }
        }
        content.addView(readmeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (420 * dp).toInt()
        ).apply { setMargins(0, (6 * dp).toInt(), 0, 0) })

        // Primary / secondary / danger actions — pinned to the BOTTOM of the
        // page (below the docs) so install is always the last thing on screen.
        actionButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
        }
        actionButton.setOnClickListener { primaryAction() }
        content.addView(actionButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (14 * dp).toInt(), 0, (4 * dp).toInt()) })

        secondaryButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            visibility = TextView.GONE
        }
        secondaryButton.setOnClickListener { secondaryAction() }
        content.addView(secondaryButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, (4 * dp).toInt()) })

        dangerButton = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFF28B82.toInt())
            visibility = TextView.GONE
        }
        dangerButton.setOnClickListener { confirmUninstall() }
        content.addView(dangerButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFE6EDF3.toInt())
        textSize = 15f
        setPadding(0, (14 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun metaLine(label: String, value: String?): TextView = TextView(this).apply {
        text = "$label  ${value?.takeIf { it.isNotBlank() } ?: "-"}"
        setTextColor(0xFF9AA7B4.toInt())
        textSize = 13f
        setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
    }

    // -------------------------------------------------------------- render

    private fun renderMeta() {
        val e = entry
        metaColumn.removeAllViews()
        metaColumn.addView(logoBox)
        updateLogo(e, current?.meta?.name, cloudIcon)
        when {
            e != null -> {
                metaColumn.addView(TextView(this).apply {
                    text = e.name
                    setTextColor(0xFFE6EDF3.toInt())
                    textSize = 19f
                    gravity = Gravity.CENTER
                    setPadding(0, (8 * dp).toInt(), 0, 0)
                })
                metaColumn.addView(TextView(this).apply {
                    text = "v${e.version}"
                    setTextColor(0xFF6B7A88.toInt())
                    textSize = 13f
                    gravity = Gravity.CENTER
                })
                metaColumn.addView(TextView(this).apply {
                    text = e.description
                    setTextColor(0xFF9AA7B4.toInt())
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                })
                metaColumn.addView(metaLine(getString(R.string.plugin_details_author), e.author))
                metaColumn.addView(metaLine(getString(R.string.plugin_details_license), e.license))
                metaColumn.addView(metaLine(getString(R.string.plugin_details_category), e.category))
                metaColumn.addView(metaLine(getString(R.string.plugin_details_minimum), e.minimumNoxsVersion))
                metaColumn.addView(metaLine(getString(R.string.plugin_details_sdk), e.sdkVersion ?: "0.0.1"))
                if (e.apiFeatures.isNotEmpty()) {
                    metaColumn.addView(
                        metaLine(
                            getString(R.string.plugin_details_api_features),
                            e.apiFeatures.joinToString(", ")
                        )
                    )
                }
                compat?.let { renderCompat(it) }
                metaColumn.addView(metaLine(getString(R.string.plugin_details_your_noxs), appVersion))
                current?.let {
                    metaColumn.addView(
                        metaLine(getString(R.string.plugin_details_installed_version), "v${it.meta.version}")
                    )
                }
                metaColumn.addView(metaLine(getString(R.string.plugin_details_repository), e.repository))
                renderPermissions(e.permissions)
                renderCommands()
                renderActions(e)
            }
            current != null -> {
                val meta = current!!.meta
                metaColumn.addView(TextView(this).apply {
                    text = meta.name
                    setTextColor(0xFFE6EDF3.toInt())
                    textSize = 19f
                    setPadding(0, (8 * dp).toInt(), 0, 0)
                })
                metaColumn.addView(TextView(this).apply {
                    text = "v${meta.version}"
                    setTextColor(0xFF6B7A88.toInt())
                    textSize = 13f
                })
                metaColumn.addView(TextView(this).apply {
                    text = meta.description
                    setTextColor(0xFF9AA7B4.toInt())
                    textSize = 13f
                    setPadding(0, (4 * dp).toInt(), 0, 0)
                })
                renderPermissions(meta.permissions)
                renderCommands()
                renderActions(null)
            }
            else -> {
                metaColumn.addView(TextView(this).apply {
                    text = getString(R.string.plugin_store_no_results)
                    setTextColor(0xFF8FA0AF.toInt())
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, (16 * dp).toInt(), 0, 0)
                })
                actionButton.visibility = TextView.GONE
                secondaryButton.visibility = TextView.GONE
                dangerButton.visibility = TextView.GONE
            }
        }
    }

    private fun renderCompat(info: com.crossberry.noxs.runtime.plugins.PluginCompatInfo) {
        val label = when (info.state) {
            com.crossberry.noxs.runtime.plugins.PluginCompatState.COMPATIBLE ->
                getString(R.string.plugin_compat_compatible)
            com.crossberry.noxs.runtime.plugins.PluginCompatState.SDK_MISSING ->
                getString(R.string.plugin_compat_sdk_missing)
            com.crossberry.noxs.runtime.plugins.PluginCompatState.SDK_INCOMPATIBLE ->
                getString(R.string.plugin_compat_sdk_incompatible)
            com.crossberry.noxs.runtime.plugins.PluginCompatState.APP_UPDATE_REQUIRED ->
                getString(R.string.plugin_compat_app_update_required)
            com.crossberry.noxs.runtime.plugins.PluginCompatState.PLUGIN_UPDATE_AVAILABLE ->
                getString(R.string.plugin_compat_update_available)
            com.crossberry.noxs.runtime.plugins.PluginCompatState.BLOCKED ->
                getString(R.string.plugin_compat_blocked)
            com.crossberry.noxs.runtime.plugins.PluginCompatState.ERROR ->
                getString(R.string.plugin_compat_error)
        }
        val color = when (info.state) {
            com.crossberry.noxs.runtime.plugins.PluginCompatState.COMPATIBLE -> 0xFF7CE8B8.toInt()
            com.crossberry.noxs.runtime.plugins.PluginCompatState.PLUGIN_UPDATE_AVAILABLE -> 0xFFF0C674.toInt()
            com.crossberry.noxs.runtime.plugins.PluginCompatState.SDK_MISSING -> 0xFF8AB6E8.toInt()
            else -> 0xFFF28B82.toInt()
        }
        metaColumn.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_compatibility, label)
            setTextColor(color)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
        })
        info.reason?.let { reason ->
            metaColumn.addView(TextView(this).apply {
                text = reason
                setTextColor(0xFF9AA7B4.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            })
        }
        info.requiredNoxs?.let { required ->
            metaColumn.addView(TextView(this).apply {
                text = getString(R.string.plugin_update_noxs_required, required)
                setTextColor(0xFFF28B82.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (6 * dp).toInt(), 0, 0)
            })
        }
    }

    private fun renderPermissions(permissions: List<String>) {
        permissionsBox.removeAllViews()
        permissionsBox.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_permissions)
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 14f
        })
        if (permissions.isEmpty()) {
            permissionsBox.addView(TextView(this).apply {
                text = getString(R.string.plugin_details_no_permissions)
                setTextColor(0xFF6B7A88.toInt())
                textSize = 12f
            })
            return
        }
        permissions.forEach { permission ->
            permissionsBox.addView(TextView(this).apply {
                text = getString(R.string.plugin_details_permission_row, permission)
                setTextColor(0xFF7CE8B8.toInt())
                textSize = 13f
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            })
        }
    }

    private fun renderCommands() {
        val commands = entry?.commands ?: current?.meta?.commands ?: emptyList()
        if (commands.isEmpty()) return
        permissionsBox.addView(TextView(this).apply {
            text = getString(R.string.plugin_details_commands, commands.joinToString(", "))
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 12f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
    }

    private fun renderActions(e: RegistryEntry?) {
        val local = current
        val incompatible = compat?.state == com.crossberry.noxs.runtime.plugins.PluginCompatState.APP_UPDATE_REQUIRED ||
            compat?.state == com.crossberry.noxs.runtime.plugins.PluginCompatState.SDK_INCOMPATIBLE ||
            compat?.state == com.crossberry.noxs.runtime.plugins.PluginCompatState.BLOCKED
        when {
            busy -> {
                actionButton.text = getString(R.string.plugin_action_working)
                actionButton.isEnabled = false
                secondaryButton.visibility = TextView.GONE
                dangerButton.visibility = TextView.GONE
            }
            local == null -> {
                actionButton.text = getString(R.string.plugin_action_install)
                actionButton.isEnabled = e?.artifact != null && !incompatible
                secondaryButton.visibility = TextView.GONE
                dangerButton.visibility = TextView.GONE
            }
            else -> {
                val update = e != null && manager?.installer?.updateAvailable(e, local) == true && !incompatible
                if (update) {
                    actionButton.text = getString(R.string.plugin_action_update)
                    actionButton.isEnabled = true
                } else {
                    actionButton.text = getString(R.string.plugin_action_open)
                    actionButton.isEnabled = !incompatible
                }
                secondaryButton.text =
                    getString(if (local.enabled) R.string.plugin_action_disable else R.string.plugin_action_enable)
                secondaryButton.visibility = TextView.VISIBLE
                dangerButton.text = getString(R.string.plugin_action_uninstall)
                dangerButton.visibility = TextView.VISIBLE
            }
        }
    }

    // ------------------------------------------------------------- actions

    private fun primaryAction() {
        val e = entry
        val local = current
        val mgr = manager ?: return
        when {
            busy -> return
            local == null && e != null -> install(mgr)
            local != null && e != null && mgr.installer.updateAvailable(e, local) -> install(mgr)
            local != null -> openWithSdk(mgr, local)
        }
    }

    /** Opens (activates) an installed plugin through its verified SDK. */
    private fun openWithSdk(mgr: NoxsPluginManager, local: InstalledPlugin) {
        if (NoxsPluginRuntime.isActive(local.meta.id)) return
        busy = true
        renderActions(entry)
        scope.launch(Dispatchers.IO) {
            val runtime = mgr.sdkRuntimeJs(local)
            scope.launch {
                busy = false
                if (runtime.js != null) {
                    NoxsPluginRuntime.activate(applicationContext, local, runtime.js)
                    Toast.makeText(this@PluginDetailsActivity, getString(R.string.plugin_opened_toast), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@PluginDetailsActivity,
                        runtime.message ?: getString(R.string.plugin_action_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
                load()
            }
        }
    }

    private fun secondaryAction() {
        val local = current ?: return
        val mgr = manager ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (local.enabled) mgr.disable(pluginId) else mgr.enable(pluginId)
            }
            scope.launch { load() }
        }
    }

    private fun confirmUninstall() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plugin_uninstall_title, current?.meta?.name ?: pluginId))
            .setMessage(getString(R.string.plugin_uninstall_message))
            .setPositiveButton(R.string.plugin_action_uninstall) { _, _ -> uninstall() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun uninstall() {
        val mgr = manager ?: return
        busy = true
        renderActions(entry)
        scope.launch(Dispatchers.IO) {
            runCatching { mgr.uninstall(pluginId) }
            scope.launch {
                busy = false
                NoxsPluginRuntime.deactivate(pluginId)
                load()
            }
        }
    }

    private fun install(mgr: NoxsPluginManager) {
        busy = true
        renderActions(entry)
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { mgr.install(pluginId) { /* progress */ } }
            val runtime = outcome.getOrNull()?.let { mgr.sdkRuntimeJs(it) }
            scope.launch {
                busy = false
                outcome
                    .onSuccess { installed ->
                        if (runtime?.js != null) {
                            NoxsPluginRuntime.activate(applicationContext, installed, runtime.js)
                        }
                        Toast.makeText(
                            this@PluginDetailsActivity,
                            runtime?.message
                                ?: getString(R.string.plugin_installed_toast, installed.meta.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .onFailure { failure ->
                        Toast.makeText(
                            this@PluginDetailsActivity,
                            failure.message ?: getString(R.string.plugin_install_failed_toast),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                load()
            }
        }
    }

    private fun metaLine(label: String, value: String?): TextView = TextView(this).apply {
        text = "$label  ${value?.takeIf { it.isNotBlank() } ?: "-"}"
        setTextColor(0xFF9AA7B4.toInt())
        textSize = 13f
        setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
    }

    // ---------------------------------------------------------------- logo

    /**
     * Logo priority: a cloud artifact's icon.svg (rendered in a JS-disabled,
     * network-blocked WebView — no SVG rasterizer ships with Android), the
     * registry logoUrl bitmap, or a letter fallback in the circular frame.
     */
    private fun updateLogo(entry: RegistryEntry?, localName: String?, iconSvg: ByteArray?) {
        cloudLogoView?.let { existing ->
            logoBox.removeView(existing)
            existing.destroy()
            cloudLogoView = null
        }
        logoView.visibility = TextView.GONE
        logoLetter.visibility = TextView.GONE
        when {
            iconSvg != null -> renderSvgLogo(iconSvg)
            entry?.logoUrl?.startsWith("https://") == true -> loadLogoBitmap(entry.logoUrl!!)
            else -> {
                val source = localName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: entry?.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: entry?.id?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "?"
                logoLetter.text = source.substring(0, 1).uppercase()
                logoLetter.visibility = TextView.VISIBLE
            }
        }
    }

    private fun renderSvgLogo(svg: ByteArray) {
        val web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            setBackgroundColor(0x00000000)
        }
        val encoded = android.util.Base64.encodeToString(svg, android.util.Base64.NO_WRAP)
        web.loadDataWithBaseURL(
            null,
            "<html><body style='margin:0;height:100%;display:flex;" +
                "align-items:center;justify-content:center'>" +
                "<img style='max-width:100%;max-height:100%' " +
                "src=\"data:image/svg+xml;base64,$encoded\"></body></html>",
            "text/html", "utf-8", null
        )
        logoBox.addView(web)
        cloudLogoView = web
    }

    /** Fetches the registry logoUrl into the circular frame (bounded, off-thread). */
    private fun loadLogoBitmap(url: String) {
        Thread {
            val bitmap = runCatching {
                val bytes = PluginHttp.get(url, 10_000, 1L * 1024L * 1024L)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull() ?: return@Thread
            runCatching {
                logoView.post {
                    if (!isDestroyed && !isFinishing && this::logoView.isInitialized) {
                        logoView.setImageBitmap(bitmap)
                        logoView.visibility = TextView.VISIBLE
                    }
                }
            }
        }.start()
    }

    private fun loadReadme(e: RegistryEntry?, local: InstalledPlugin?, cloudReadme: String? = null) {
        scope.launch(Dispatchers.IO) {
            val markdown: String? = when {
                // Cloud plugins ship their README.md inside the artifact —
                // already extracted and verified by the inspect step.
                cloudReadme != null -> cloudReadme
                local != null && e == null ->
                    runCatching {
                        java.io.File(local.dir, local.meta.readme ?: "README.md")
                            .takeIf { it.isFile }?.readText(Charsets.UTF_8)
                    }.getOrNull()
                else -> e?.readmeUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                    runCatching {
                        String(PluginHttp.get(url, 12_000, 2L * 1024L * 1024L), Charsets.UTF_8)
                    }.getOrNull()
                }
            }
            val html = markdown?.let { PluginReadmeRenderer.render(it) }
            scope.launch {
                if (html.isNullOrBlank()) {
                    readmeView.loadData(
                        "<html><body style='color:#8FA0AF;background:#121922;font-family:sans-serif;padding:16px'>${getString(R.string.plugin_details_readme_missing)}</body></html>",
                        "text/html", "utf-8"
                    )
                } else {
                    readmeView.loadDataWithBaseURL(
                        null,
                        readmeShell(html),
                        "text/html", "utf-8", null
                    )
                }
            }
        }
    }

    private fun readmeShell(body: String): String = """
        |<!DOCTYPE html><html><head><meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<style>
        |  body { background:#121922; color:#D5DEE7; font-family:sans-serif;
        |         padding:14px; font-size:14px; line-height:1.5; }
        |  h1,h2,h3 { color:#7CE8B8; }
        |  pre { background:#0B1016; border:1px solid #22303C; padding:10px;
        |        overflow-x:auto; border-radius:6px; }
        |  code { font-family:monospace; color:#9FE8C8; }
        |  table { border-collapse:collapse; }
        |  th,td { border:1px solid #22303C; padding:5px 9px; }
        |  a { color:#6CB6FF; }
        |</style></head><body>$body</body></html>
    """.trimMargin()

    override fun onDestroy() {
        scope.cancel()
        if (this::readmeView.isInitialized) {
            readmeView.destroy()
        }
        cloudLogoView?.destroy()
        cloudLogoView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "noxs.extra.PLUGIN_ID"
    }
}
