/*
 * Noxs — original implementation.
 * Noxs Editor: the real code-server (VS Code Server) workspace rendered in
 * an Android WebView, served by the Noxs Linux guest on 127.0.0.1.
 *
 * Flow (sidebar Editor entry):
 *   1. resolve the active terminal session's working directory (OSC 7)
 *   2. check whether code-server is installed → install screen with progress
 *   3. start the server through `noxs code start --foreground` (managed by
 *      CodeServerManager under its own long-lived proot process)
 *   4. wait for actual HTTP readiness on the loopback
 *   5. attach a WebView restricted to the server origin, auto-authenticating
 *      against code-server's password login (the credential is never logged)
 *
 * This screen REPLACES the former native sora-editor/Squircle NXEditor UI.
 * Terminal sessions, files and the workspace tracking stay untouched.
 */
package com.crossberry.noxs.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityEditorBinding
import com.crossberry.noxs.runtime.CodeServerManager
import com.crossberry.noxs.runtime.CodeServerSupport
import com.crossberry.noxs.runtime.NoxsEditorFilesystem
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsService
import com.crossberry.noxs.runtime.NoxsUrlGuard
import com.crossberry.noxs.runtime.RuntimeHolder
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import android.util.Base64
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var paths: NoxsPaths
    private lateinit var fs: NoxsEditorFilesystem

    private val manager: CodeServerManager
        get() = (application as com.crossberry.noxs.NoxsApplication).codeServer

    private var workspace: String = NoxsConstants.DEFAULT_USER_HOME
    private var webView: WebView? = null
    private var serverPort: Int? = null
    private var flowJob: Job? = null
    private var authAttempts = 0
    private var submittingLogin = AtomicBoolean(false)
    private var ready = false
    private var openTerminalPending = false
    private var phaseVisible: Phase = Phase.CHECKING

    private enum class Phase { CHECKING, INSTALLING, STARTING, OPENING, READY, FAILED, STOPPED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).environments.activePaths()
        fs = NoxsEditorFilesystem(paths.rootfs)

        workspace = resolveInitialWorkspace(savedInstanceState)
        binding.workspacePath.text = workspace
        setChip(Phase.CHECKING)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnOpenTerminal.setOnClickListener { openWorkspaceInTerminal() }
        binding.btnReload.setOnClickListener { reloadEditor() }
        binding.btnMenu.setOnClickListener { showMenu(it) }
        binding.btnRetry.setOnClickListener {
            binding.btnRetry.text = getString(R.string.editor_retry)
            startFlow()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = webView
                // In-editor history first; finishing keeps the server (and VS
                // Code's hot-exit backups) alive, so nothing is discarded.
                if (ready && web != null && web.canGoBack()) {
                    web.goBack()
                } else {
                    finish()
                }
            }
        })

        observeInstallLines()

        val restoredPort = savedInstanceState?.getInt(KEY_PORT, -1) ?: -1
        if (restoredPort > 0 && manager.isRunning()) {
            // Activity recreation: reattach to the already-running server.
            serverPort = restoredPort
            attachWebView(restoredPort, autoLoad = false)
            val restored = webView?.restoreState(savedInstanceState)
            if (restored == null) {
                webView?.loadUrl("http://127.0.0.1:$restoredPort/")
            }
        } else {
            startFlow()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_WORKSPACE, workspace)
        serverPort?.let { outState.putInt(KEY_PORT, it) }
        webView?.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        flowJob?.cancel()
        detachWebView()
        super.onDestroy()
    }

    // ------------------------------------------------------- flow pipeline

    private fun startFlow(restart: Boolean = false) {
        flowJob?.cancel()
        ready = false
        detachWebView()
        showPanel(visible = true)
        binding.editorProgress.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.GONE
        setChip(Phase.CHECKING)
        setPhaseText(getString(R.string.editor_phase_checking))
        flowJob = lifecycleScope.launch {
            val installError = ensureInstalled()
            if (installError != null) {
                fail(installError)
                return@launch
            }
            setChip(Phase.STARTING)
            setPhaseText(getString(R.string.editor_phase_starting))
            val started = if (restart) manager.restart(workspace) else manager.start(workspace)
            val port = started.getOrElse { e ->
                fail(e.message)
                return@launch
            }
            serverPort = port
            setChip(Phase.OPENING)
            setPhaseText(getString(R.string.editor_phase_opening))
            attachWebView(port)
        }
    }

    /** Returns an error message when installation failed, null otherwise. */
    private suspend fun ensureInstalled(): String? {
        if (manager.isInstalled()) return null
        setChip(Phase.INSTALLING)
        setPhaseText(getString(R.string.editor_phase_installing))
        binding.statusDetail.text = getString(R.string.editor_install_hint)
        binding.progressScroll.visibility = View.VISIBLE
        val result = manager.install()
        binding.progressScroll.visibility = View.GONE
        return result.exceptionOrNull()?.message
    }

    private fun fail(message: String?) {
        setChip(Phase.FAILED)
        setPhaseText(getString(R.string.editor_phase_failed))
        binding.editorProgress.visibility = View.GONE
        binding.btnRetry.text = getString(R.string.editor_retry)
        binding.btnRetry.visibility = View.VISIBLE
        val detail = buildString {
            append(message ?: getString(R.string.err_generic))
            val log = manager.logTailLines(5)
            if (log.isNotEmpty()) {
                append("\n\n")
                append(log.joinToString("\n"))
            }
        }
        binding.statusDetail.text = detail
        binding.progressScroll.visibility = View.VISIBLE
        ready = false
    }

    // ------------------------------------------------------------ web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun attachWebView(port: Int, autoLoad: Boolean = true) {
        detachWebView()
        val cfg = manager.readConfig()
        if (cfg == null || cfg.auth != "password" || cfg.password.isNullOrBlank()) {
            fail(getString(R.string.editor_auth_unavailable))
            return
        }
        authAttempts = 0
        val web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.setBackgroundColor(Color.parseColor("#111111"))
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = EditorWebClient(port, cfg.password)
        web.webChromeClient = EditorChromeClient(port)
        CookieManager.getInstance().setAcceptCookie(true)
        binding.webContainer.addView(web)
        webView = web
        ready = true
        showPanel(visible = false)
        setChip(Phase.READY)
        if (autoLoad) {
            web.loadUrl("http://127.0.0.1:$port/")
        }
    }

    private fun detachWebView() {
        webView?.let { w ->
            binding.webContainer.removeAllViews()
            w.stopLoading()
            w.destroy()
        }
        webView = null
    }

    private fun reloadEditor() {
        val web = webView
        if (ready && web != null) {
            web.reload()
        } else {
            startFlow()
        }
    }

    private inner class EditorWebClient(
        private val port: Int,
        private val password: String
    ) : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (CodeServerSupport.isAllowedEditorUrl(url, port)) return false
            // Never navigate the editor away. Safe external links open in the
            // guarded Noxs web window; everything else is blocked.
            when (val decision = NoxsUrlGuard.check(url)) {
                is NoxsUrlGuard.Decision.Allowed -> openInWebWindow(decision.url)
                is NoxsUrlGuard.Decision.Rejected ->
                    NoxsLog.w(TAG, "blocked editor navigation (${decision.reason})")
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (CodeServerSupport.isLoginPage(url)) {
                if (authAttempts >= MAX_LOGIN_ATTEMPTS) {
                    fail(getString(R.string.editor_auth_failed))
                    return
                }
                // Rate-limited auto-login: fill + submit the password form.
                if (submittingLogin.compareAndSet(false, true)) {
                    authAttempts++
                    val b64 = Base64.encodeToString(
                        password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                    )
                    view.evaluateJavascript(CodeServerSupport.loginInjectScript(b64), null)
                    view.postDelayed({ submittingLogin.set(false) }, 1500)
                }
            } else {
                authAttempts = 0
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame && ready) {
                NoxsLog.w(TAG, "editor main-frame error: ${error.description}")
                fail(getString(R.string.editor_server_unreachable))
            }
        }
    }

    private inner class EditorChromeClient(
        private val port: Int
    ) : WebChromeClient() {

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            // target=_blank: capture the destination and route it through the
            // same main-frame policy; the editor window itself never navigates.
            val temp = WebView(view.context)
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    t: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    if (CodeServerSupport.isAllowedEditorUrl(url, port)) {
                        webView?.loadUrl(url)
                    } else {
                        val decision = NoxsUrlGuard.check(url)
                        if (decision is NoxsUrlGuard.Decision.Allowed) {
                            openInWebWindow(decision.url)
                        }
                    }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun openInWebWindow(url: String) {
        try {
            val intent = Intent(this, WebWindowActivity::class.java)
                .putExtra(WebWindowActivity.EXTRA_URL, url)
            startActivity(intent)
        } catch (e: Exception) {
            NoxsLog.w(TAG, "web window open failed: ${e.message}")
            Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(Menu.NONE, ITEM_RESTART, Menu.NONE, getString(R.string.editor_menu_restart))
        menu.menu.add(Menu.NONE, ITEM_STOP, Menu.NONE, getString(R.string.editor_menu_stop))
        menu.menu.add(Menu.NONE, ITEM_LOG, Menu.NONE, getString(R.string.editor_menu_log))
        menu.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                ITEM_RESTART -> {
                    startFlow(restart = true)
                    true
                }
                ITEM_STOP -> {
                    stopServer()
                    true
                }
                ITEM_LOG -> {
                    showLogDialog()
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun stopServer() {
        lifecycleScope.launch {
            setChip(Phase.CHECKING)
            val result = manager.stop()
            detachWebView()
            ready = false
            if (result.isSuccess) {
                showPanel(visible = true)
                setChip(Phase.STOPPED)
                setPhaseText(getString(R.string.editor_phase_stopped))
                binding.editorProgress.visibility = View.GONE
                binding.statusDetail.text = getString(R.string.editor_stopped_hint)
                binding.btnRetry.text = getString(R.string.editor_start)
                binding.btnRetry.visibility = View.VISIBLE
            } else {
                fail(result.exceptionOrNull()?.message)
            }
        }
    }

    private fun showLogDialog() {
        val lines = manager.logTailLines(60)
        val text = if (lines.isEmpty()) {
            getString(R.string.editor_log_empty)
        } else {
            lines.joinToString("\n")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_menu_log)
            .setMessage(text)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    // ------------------------------------------------------ install stream

    private fun observeInstallLines() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.installLines.collect { lines ->
                    if (phaseVisible == Phase.INSTALLING && lines.isNotEmpty()) {
                        binding.statusDetail.text =
                            lines.takeLast(INSTALL_LINES_SHOWN).joinToString("\n")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------- open in terminal

    private fun openWorkspaceInTerminal() {
        if (openTerminalPending) return // debounce double taps
        val dirOk = CodeServerSupport.isValidGuestWorkspace(workspace) &&
            runCatching { fs.isDirectory(workspace) }.getOrDefault(false)
        if (!dirOk) {
            Toast.makeText(this, R.string.editor_open_terminal_missing, Toast.LENGTH_SHORT).show()
            return
        }
        openTerminalPending = true
        binding.btnOpenTerminal.isEnabled = false
        binding.btnOpenTerminal.postDelayed({
            openTerminalPending = false
            binding.btnOpenTerminal.isEnabled = true
        }, 800)
        NoxsService.start(this)
        try {
            startActivity(TerminalActivity.openAt(this, workspace))
            Toast.makeText(
                this, getString(R.string.editor_session_created, workspace),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.editor_open_terminal_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ------------------------------------------------------------ plumbing

    private fun resolveInitialWorkspace(savedInstanceState: Bundle?): String {
        savedInstanceState?.getString(KEY_WORKSPACE)?.let { return it }
        val requested = intent.getStringExtra(EXTRA_WORKSPACE)
        val sessionCwd = RuntimeHolder.sessions?.sessions?.value?.firstOrNull()
            ?.session?.emulator?.currentWorkingDirectory
        val resolved = CodeServerSupport.resolveWorkspace(
            requested = requested,
            sessionCwd = sessionCwd,
            fallbackHome = NoxsConstants.DEFAULT_USER_HOME,
            isDirectory = { p ->
                CodeServerSupport.isValidGuestWorkspace(p) &&
                    runCatching { fs.isDirectory(p) }.getOrDefault(false)
            }
        )
        if (resolved == null) {
            Toast.makeText(this, R.string.editor_workspace_missing, Toast.LENGTH_LONG).show()
            return NoxsConstants.DEFAULT_USER_HOME
        }
        return resolved
    }

    private fun setChip(p: Phase) {
        phaseVisible = p
        val textRes = when (p) {
            Phase.CHECKING -> R.string.editor_chip_checking
            Phase.INSTALLING -> R.string.editor_chip_installing
            Phase.STARTING -> R.string.editor_chip_starting
            Phase.OPENING -> R.string.editor_chip_opening
            Phase.READY -> R.string.editor_chip_ready
            Phase.FAILED -> R.string.editor_chip_failed
            Phase.STOPPED -> R.string.editor_chip_stopped
        }
        val colorRes = when (p) {
            Phase.READY -> R.color.noxs_ok
            Phase.FAILED -> R.color.noxs_error
            Phase.INSTALLING, Phase.STARTING, Phase.OPENING -> R.color.noxs_warning
            Phase.CHECKING, Phase.STOPPED -> R.color.noxs_text_dim
        }
        binding.statusChip.text = getString(textRes)
        binding.statusChip.setTextColor(getColor(colorRes))
    }

    private fun setPhaseText(text: String) {
        binding.statusTitle.text = text
    }

    private fun showPanel(visible: Boolean) {
        binding.statusPanel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        private const val TAG = "NoxsEditor"
        private const val KEY_WORKSPACE = "noxs.state.EDITOR_WORKSPACE"
        private const val KEY_PORT = "noxs.state.EDITOR_PORT"
        private const val EXTRA_WORKSPACE = "noxs.extra.EDITOR_WORKSPACE"
        private const val MAX_LOGIN_ATTEMPTS = 5
        private const val INSTALL_LINES_SHOWN = 8
        private const val ITEM_RESTART = 1
        private const val ITEM_STOP = 2
        private const val ITEM_LOG = 3

        /** Sidebar entry point (and any caller with a pre-resolved workspace). */
        fun open(context: android.content.Context, workspace: String? = null) {
            val intent = android.content.Intent(context, EditorActivity::class.java)
            workspace?.let { intent.putExtra(EXTRA_WORKSPACE, it) }
            context.startActivity(intent)
        }
    }
}
