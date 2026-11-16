/*
 * Noxs — original implementation.
 * Main terminal screen: multiple sessions (tabs + drawer), visible action bar,
 * direct Android IME-to-PTY input, terminal canvas, extra keys, and live status.
 */
package com.crossberry.noxs.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.os.Bundle
import android.os.FileObserver
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.crossberry.noxs.NoxsApplication
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.databinding.ActivityTerminalBinding
import com.crossberry.noxs.runtime.AndroidProcSource
import com.crossberry.noxs.runtime.NoxsActivityCenter
import com.crossberry.noxs.runtime.NoxsActivityRecord
import com.crossberry.noxs.runtime.NoxsAptSetup
import com.crossberry.noxs.runtime.NoxsAndroidBridge
import com.crossberry.noxs.runtime.NoxsAndroidBridgeHost
import com.crossberry.noxs.runtime.NoxsAndroidPermissions
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsProcSampler
import com.crossberry.noxs.runtime.NoxsResources
import com.crossberry.noxs.runtime.NoxsService
import com.crossberry.noxs.runtime.NoxsSessionManager
import com.crossberry.noxs.runtime.NoxsStorageBridge
import com.crossberry.noxs.runtime.NoxsStorageMount
import com.crossberry.noxs.runtime.NoxsTreeUsage
import com.crossberry.noxs.runtime.RuntimeHolder
import com.crossberry.noxs.terminal.emulator.TerminalSession
import com.crossberry.noxs.terminal.emulator.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class TerminalActivity : AppCompatActivity(), TerminalSessionClient {

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var paths: NoxsPaths
    private var resources: NoxsResources? = null
    private var sessionManager: NoxsSessionManager? = null
    private lateinit var storageBridge: NoxsStorageBridge
    private lateinit var storagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var bridgeSystemUiLauncher: ActivityResultLauncher<Intent>
    private lateinit var bridgePhotoLauncher: ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>
    private lateinit var bridgePermLauncher: ActivityResultLauncher<Array<String>>
    private var storageObserver: FileObserver? = null
    private var storageObservedPath: String? = null
    private var pendingStorageAction: PendingStorageAction? = null

    // NX Android Bridge (document/sms/device/notification/... commands)
    private lateinit var androidBridge: NoxsAndroidBridgeHost
    private var bridgeObserver: FileObserver? = null
    private var bridgeObservedPath: String? = null
    private var pendingBridgeUi: PendingBridgeUi? = null
    private var bridgeCameraOutput: File? = null
    private var bridgeCancelRequested = false

    private data class PendingBridgeUi(
        val requestId: String,
        val kind: String,
        val args: List<String>,
        val text: String = ""
    )
    /** User-defined order for session chips; labels are unique per session. */
    private val sessionDisplayOrder = mutableListOf<String>()

    private data class PendingStorageAction(
        val requestId: String,
        val category: NoxsStorageBridge.Category
    )

    private var current: NoxsSessionManager.Entry? = null

    // Terminal settings (terminal.* keys) — applied live, no shell restart
    private var terminalSettings: TerminalSettings = TerminalSettings()
    private val scrollOffsets = mutableMapOf<String, Int>()
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val searchRunnable = Runnable {
        binding.terminal.setSearchQuery(binding.etSearch.text?.toString().orEmpty())
    }

    // One-shot guard: the background APT repair failure toast is shown once
    // per resumed session, never per state re-emission.
    private var aptFailureAnnounced = false

    // Nullable (not lateinit): adoptRuntimeNow() collects the sessions StateFlow,
    // which can emit synchronously during onCreate before/around adapter binding.
    private var sessionAdapter: TwoLineAdapter? = null

    // Background Activity Center integration
    private val activityCenter: NoxsActivityCenter
        get() = (application as NoxsApplication).activityCenter
    private val procSampler by lazy { NoxsProcSampler(AndroidProcSource()) }
    private var floatingPanel: FloatingStatusPanel? = null
    private var panelJob: Job? = null
    private var attachedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onStoragePickerResult(result)
        }
        bridgeSystemUiLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onBridgeSystemUiResult(result)
        }
        bridgePhotoLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            onBridgePhotoPicked(uri)
        }
        bridgePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            onBridgePermissionsResult(grants)
        }
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as com.crossberry.noxs.NoxsApplication
        paths = app.environments.activePaths()
        storageBridge = NoxsStorageBridge(this, paths)
        androidBridge = NoxsAndroidBridgeHost(this, paths)
        val savedStorageRequest = savedInstanceState?.getString(STATE_STORAGE_REQUEST_ID)
        val savedStorageCategory = savedInstanceState?.getString(STATE_STORAGE_CATEGORY)?.let { NoxsStorageBridge.Category.fromKey(it) }
        if (savedStorageRequest != null && savedStorageCategory != null) {
            pendingStorageAction = PendingStorageAction(savedStorageRequest, savedStorageCategory)
        }

        if (!paths.isInstalled()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // Bind the session list adapter BEFORE adopting the runtime
        sessionAdapter = TwoLineAdapter.bind(binding.sessionList, emptyList())

        NoxsService.start(this)
        adoptRuntime()

        // Top bar: Drawer menu button
        binding.btnDrawer.setOnClickListener {
            if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
                binding.drawer.closeDrawer(GravityCompat.START)
            } else {
                binding.drawer.openDrawer(GravityCompat.START)
            }
        }

        // Drawer: sessions
        binding.btnNewSession.setOnClickListener { newSession(root = false) }
        binding.btnNewRoot.setOnClickListener { newSession(root = true) }

        // Visible action bar: primary actions; everything else lives in the
        // overflow menus so buttons never clip on narrow screens.
        binding.btnActionNewShell.setOnClickListener { newSession(root = false) }
        binding.btnActionNewRoot.setOnClickListener { newSession(root = true) }
        binding.btnActionActivity.setOnClickListener { startActivity(Intent(this, ActivityCenterActivity::class.java)) }
        binding.btnActionActivity.setOnLongClickListener {
            val id = current?.activityId
            if (id != null) toggleFloatingPanel(id) else {
                Toast.makeText(this, R.string.activity_none, Toast.LENGTH_SHORT).show()
            }
            true
        }
        binding.btnActionCopy.setOnClickListener { copyTerminalText() }
        binding.btnActionPaste.setOnClickListener { pasteIntoShell() }
        binding.btnActionMore.setOnClickListener { showActionsMenu(it) }
        binding.btnHeaderMore.setOnClickListener { showHeaderMenu(it) }
        applyHeaderStatsVisibility()

        // Drawer: stay on the terminal
        binding.navTerminal.setOnClickListener {
            binding.drawer.closeDrawer(GravityCompat.START)
        }

        // Drawer: managers. nav_noxos opens the NoxOS emulator window, which
        // AUTO-BOOTS the VM on open (Noxs × NoxOS M2 integration contract).
        val routes = mapOf(
            binding.navMirrorAccount to NoxsMirrorAccountActivity::class.java,
            binding.navNoxos to com.crossberry.noxs.emulator.EmulatorActivity::class.java,
            binding.navFiles to FileBrowserActivity::class.java,
            binding.navEditor to EditorActivity::class.java,
            binding.navPlugins to PluginStoreActivity::class.java,
            binding.navPackages to PackageManagerActivity::class.java,
            binding.navUsers to UserManagerActivity::class.java,
            binding.navActivity to ActivityCenterActivity::class.java,
            binding.navProcesses to ProcessManagerActivity::class.java,
            binding.navServices to ServiceManagerActivity::class.java,
            binding.navEnv to EnvVarsActivity::class.java,
            binding.navStorage to StorageActivity::class.java,
            binding.navSecurity to SecurityActivity::class.java,
            binding.navSettings to SettingsActivity::class.java,
            binding.navDiagnostics to DiagnosticsActivity::class.java,
            binding.navAbout to AboutActivity::class.java
        )
        routes.forEach { (view, activity) ->
            view.setOnClickListener {
                binding.drawer.closeDrawer(GravityCompat.START)
                startActivity(Intent(this, activity))
            }
        }

        binding.navGit.setOnClickListener {
            binding.drawer.closeDrawer(GravityCompat.START)
            WebWindowActivity.open(this, "https://nxmirror.netlify.app/")
        }

        // Terminal view config + extra keys wiring
        binding.terminal.volumeKeysEnabled = true
        binding.terminal.onTerminalLinkClick = ::openTerminalLink
        binding.terminal.onScrollStateChanged = { atLiveBottom ->
            binding.btnTerminalLatest.visibility = if (atLiveBottom) View.GONE else View.VISIBLE
        }
        binding.btnTerminalLatest.setOnClickListener { binding.terminal.scrollToBottom() }
        binding.extraKeys.terminalView = binding.terminal

        // Terminal UX upgrade: persisted settings, search, indicator
        applyTerminalSettings()
        wireSearchBar()
        binding.terminal.onToggleFullscreen = { toggleFullscreen() }
        binding.terminal.onIndicatorChanged = { label ->
            binding.btnTerminalLatest.text = label?.let { "↓ $it" } ?: getString(R.string.terminal_latest)
        }
        // Settings → Terminal → Behavior → Start in full screen: launch with
        // the top options hidden. Ctrl+F still toggles at any time.
        if (terminalSettings.startInFullscreen) {
            fullscreenMode = true
            applyFullscreenUi()
        }

        // Status widget ticker
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatus()
                    renderSessionStatus()
                    delay(5000)
                }
            }
        }

        val existing = RuntimeHolder.sessions?.sessions?.value
        if (!existing.isNullOrEmpty()) {
            current = existing.first()
            attachCurrent()
        } else {
            autoCreatePending = true
            fulfillAutoCreateIfReady()
        }
        // APT security setup runs in the service background (NoxsAptSetup).
        // It never gates the shell: observe it only for a failure notice.
        observeAptSetup()
        onNewIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Notification "Open" lands here: focus the tapped activity and raise
        // the floating status panel over the terminal.
        intent?.getStringExtra(NoxsService.EXTRA_FOCUS_ACTIVITY)?.let { activityId ->
            val record = activityCenter.find(activityId)
            val session = record?.sessionId?.let { label ->
                sessionManager?.sessions?.value?.firstOrNull { it.label == label }
            }
            if (session != null) {
                current = session
                attachCurrent()
            }
            showFloatingPanel(activityId)
        }
        // NXEditor "Open in Terminal": a NEW session at the editor workspace;
        // existing sessions are untouched (singleTop routes repeats here).
        intent?.getStringExtra(EXTRA_NEW_SESSION_CWD)?.let { cwd ->
            newSessionAt(cwd)
        }
    }

    /**
     * Creates a fresh user shell whose proot -w starts at [cwd] (validated by
     * NoxsSessionManager) and attaches it. Never closes other sessions.
     */
    private fun newSessionAt(cwd: String) {
        val mgr = sessionManager ?: RuntimeHolder.sessions ?: run {
            Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show()
            return
        }
        sessionManager = mgr
        mgr.createSession("editor", loginAsRoot = false, cwd = cwd)
            .onSuccess { entry ->
                current = entry
                attachCurrent()
                binding.drawer.closeDrawer(GravityCompat.START)
            }
            .onFailure {
                Toast.makeText(this, it.message ?: getString(R.string.err_generic), Toast.LENGTH_LONG).show()
            }
    }

    private var autoCreatePending = false

    private fun fulfillAutoCreateIfReady() {
        if (!autoCreatePending) return
        val mgr = sessionManager ?: RuntimeHolder.sessions ?: return
        sessionManager = mgr
        autoCreatePending = false
        val list = mgr.sessions.value
        if (list.isEmpty()) {
            newSession(root = false)
        } else if (current == null) {
            current = list.first()
            attachCurrent()
        }
    }

    /**
     * Observes the service-side APT bootstrap purely for diagnostics. The
     * shell is never blocked on it; a failed repair is announced once and
     * retried automatically on the next launch.
     */
    private fun observeAptSetup() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NoxsAptSetup.state.collect { state ->
                    if (state === NoxsAptSetup.State.FAILED && !aptFailureAnnounced) {
                        aptFailureAnnounced = true
                        Toast.makeText(
                            this@TerminalActivity,
                            getString(R.string.apt_background_failed, NoxsAptSetup.detail.take(160)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun adoptRuntime() {
        if (RuntimeHolder.sessions == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                var tries = 0
                while (RuntimeHolder.sessions == null && tries < 20) {
                    delay(250); tries++
                }
                withContext(Dispatchers.Main) {
                    adoptRuntimeNow()
                }
            }
        } else {
            adoptRuntimeNow()
        }
    }

    private fun adoptRuntimeNow() {
        sessionManager = RuntimeHolder.sessions
        resources = NoxsResources(paths)
        sessionManager?.client = this
        lifecycleScope.launch {
            sessionManager?.sessions?.collect { list ->
                if (current == null && list.isNotEmpty()) {
                    current = list.last()
                    attachCurrent()
                } else if (current != null) {
                    val replacement = list.firstOrNull { it.label == current?.label && it.session !== current?.session }
                    if (replacement != null) {
                        current = replacement
                        attachCurrent()
                    }
                }
                renderSessions(list)
            }
        }
        fulfillAutoCreateIfReady()
    }

    private fun newSession(root: Boolean) {
        // No setup gates here by design: the terminal clears straight to an
        // active shell while the APT security layer repairs in the background.
        val mgr = sessionManager ?: RuntimeHolder.sessions ?: run {
            Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show()
            return
        }
        sessionManager = mgr
        mgr.createSession("", loginAsRoot = root)
            .onSuccess { entry ->
                current = entry
                attachCurrent()
                binding.drawer.closeDrawer(GravityCompat.START)
            }
            .onFailure {
                Toast.makeText(this, it.message ?: getString(R.string.err_generic), Toast.LENGTH_LONG).show()
            }
    }

    private fun openTerminalLink(uri: String): Boolean {
        // Real printed URLs (including localhost servers such as
        // http://127.0.0.1:8080 or http://localhost:3000) open in the browser.
        // Servers usually bind 0.0.0.0/:: — those hosts are normalized to the
        // device loopback so the link works globally, not only in-terminal.
        if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TerminalLinks.normalize(uri))))
                true
            } catch (_: Exception) {
                Toast.makeText(this, "No browser is available to open this link.", Toast.LENGTH_SHORT).show()
                true
            }
        }
        val intent = when (uri) {
            WEBSITE_URL -> Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))
            SUPPORT_EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse(SUPPORT_EMAIL))
            else -> return false
        }
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            Toast.makeText(this, "No compatible app is available to open this link.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    // ------------------------------------------------------------- full screen

    /** Ctrl+F toggles between the normal chrome and an immersive terminal. */
    private var fullscreenMode = false

    /** Secondary statistics row expanded via the header overflow menu. */
    private var statsRowExpanded = false

    private fun toggleFullscreen() {
        fullscreenMode = !fullscreenMode
        applyFullscreenUi()
        // Persist the live toggle so activity recreation (rotation, process
        // death) restores the user's last chrome state; Ctrl+F "Start in full
        // screen" setting and this toggle share the same key by design.
        terminalSettings = TerminalSettingsStore.putBoolean(
            AndroidTerminalPrefs.from(this), "terminal.startFullscreen", fullscreenMode
        )
        Toast.makeText(
            this,
            if (fullscreenMode) R.string.terminal_fullscreen_enter else R.string.terminal_fullscreen_exit,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyFullscreenUi() {
        val chrome = if (fullscreenMode) View.GONE else View.VISIBLE
        binding.statusBar.visibility = chrome
        binding.statsExpanded.visibility =
            if (!fullscreenMode && statsRowExpanded) View.VISIBLE else View.GONE
        binding.sessionTabsScroll.visibility = chrome
        // Full screen always hides the action toolbar and the shortcut-key
        // row; normal mode restores both exactly as the user set them in
        // settings (terminal.showToolbar / terminal.showExtraKeys).
        binding.actionsScroll.visibility =
            if (!fullscreenMode && terminalSettings.showToolbar) View.VISIBLE else View.GONE
        binding.extraKeys.visibility =
            if (!fullscreenMode && terminalSettings.showExtraKeys) View.VISIBLE else View.GONE
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreenMode)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (fullscreenMode) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun attachCurrent() {
        val entry = current ?: return
        binding.terminal.attach(entry.session)
        binding.statusSession.text = entry.label + (if (entry.loginAsRoot) " ▸ ${getString(R.string.session_root_badge)}" else "")
        attachedAtMs = System.currentTimeMillis()
        // Live settings: scrollback capacity + preserved reading position.
        binding.terminal.applyAppearance(terminalSettings.toAppearance())
        runCatching { entry.session.emulator.setScrollbackLimit(terminalSettings.scrollbackLines) }
        binding.terminal.post {
            if (terminalSettings.preserveScrollPosition) {
                scrollOffsets[entry.label]?.let { binding.terminal.restoreScrollOffset(it) }
            }
            if (terminalSettings.autoFocusTerminal) {
                binding.terminal.requestFocus()
                binding.terminal.showSoftInput()
            }
        }
        renderSessionStatus()
    }

    /** Loads terminal.* settings and applies them to the live view. */
    private fun applyTerminalSettings() {
        terminalSettings = TerminalSettingsStore.load(AndroidTerminalPrefs.from(this))
        binding.terminal.applyAppearance(terminalSettings.toAppearance())
        binding.terminal.onFontSizeChanged = { sp ->
            TerminalSettingsStore.putInt(AndroidTerminalPrefs.from(this), "terminal.fontSize", sp.toInt())
        }
        binding.actionsScroll.visibility =
            if (terminalSettings.showToolbar) View.VISIBLE else View.GONE
        binding.extraKeys.visibility =
            if (terminalSettings.showExtraKeys) View.VISIBLE else View.GONE
        current?.session?.let { runCatching { it.emulator.setScrollbackLimit(terminalSettings.scrollbackLines) } }
    }

    /** Performance setting: hide live CPU/RAM from the header when disabled. */
    private fun applyHeaderStatsVisibility() {
        val show = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(PREF_HEADER_STATS, true)
        binding.statusCpu.visibility = if (show) View.VISIBLE else View.GONE
        binding.statusMem.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            statsRowExpanded = false
            binding.statsExpanded.visibility = View.GONE
        }
    }

    /** Toolbar overflow: secondary terminal actions + remaining destinations. */
    private fun showActionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MORE_CLEAR_TERMINAL, 0, getString(R.string.action_clear))
        popup.menu.add(0, MORE_CLEAR_SCROLLBACK, 1, getString(R.string.terminal_menu_clear_scrollback))
        popup.menu.add(0, MORE_SEARCH, 2, getString(R.string.action_search))
        popup.menu.add(0, MORE_SCROLL_TOP, 3, getString(R.string.terminal_scroll_top))
        popup.menu.add(0, MORE_SCROLL_BOTTOM, 4, getString(R.string.terminal_scroll_bottom))
        popup.menu.add(0, MORE_PACKAGES, 5, getString(R.string.nav_packages))
        popup.menu.add(0, MORE_FILES, 6, getString(R.string.nav_files))
        popup.menu.add(0, MORE_FONT_INFO, 7, getString(R.string.terminal_font_size_value, terminalSettings.fontSizeSp))
            .isEnabled = false
        popup.menu.add(0, MORE_FONT_INCR, 8, getString(R.string.terminal_font_increase))
        popup.menu.add(0, MORE_FONT_DECR, 9, getString(R.string.terminal_font_decrease))
        popup.menu.add(0, MORE_RESET_FONT, 10, getString(R.string.terminal_menu_reset_font))
        popup.menu.add(0, MORE_SAVE_OUTPUT, 11, getString(R.string.terminal_menu_save_output))
        popup.menu.add(
            0, MORE_TOGGLE_FULLSCREEN, 12,
            getString(
                if (fullscreenMode) R.string.terminal_menu_fullscreen_exit
                else R.string.terminal_menu_fullscreen
            )
        )
        popup.menu.add(0, MORE_SESSION_SETTINGS, 13, getString(R.string.terminal_menu_session_settings))
        popup.menu.add(0, MORE_SERVICES, 14, getString(R.string.nav_services))
        popup.menu.add(0, MORE_PROCESSES, 15, getString(R.string.nav_processes))
        popup.menu.add(0, MORE_DIAGNOSTICS, 16, getString(R.string.nav_diagnostics))
        popup.menu.add(0, MORE_APP_SETTINGS, 17, getString(R.string.nav_settings))
        popup.setOnMenuItemClickListener { item -> onActionMenuItem(item.itemId) }
        popup.show()
    }

    private fun onActionMenuItem(id: Int): Boolean = when (id) {
        MORE_CLEAR_TERMINAL -> { clearTerminal(); true }
        MORE_CLEAR_SCROLLBACK -> { binding.terminal.clearScrollback(); true }
        MORE_SEARCH -> { openSearchBar(); true }
        MORE_SCROLL_TOP -> { binding.terminal.scrollToTop(); true }
        MORE_SCROLL_BOTTOM -> { binding.terminal.scrollToBottom(); true }
        MORE_PACKAGES -> { startActivity(Intent(this, PackageManagerActivity::class.java)); true }
        MORE_FILES -> { startActivity(Intent(this, FileBrowserActivity::class.java)); true }
        MORE_FONT_INCR -> { binding.terminal.nudgeFontSize(1f); true }
        MORE_FONT_DECR -> { binding.terminal.nudgeFontSize(-1f); true }
        MORE_RESET_FONT -> { binding.terminal.resetFontSize(); true }
        MORE_SAVE_OUTPUT -> { saveTerminalOutput(); true }
        MORE_TOGGLE_FULLSCREEN -> { toggleFullscreen(); true }
        MORE_SESSION_SETTINGS -> { startActivity(Intent(this, TerminalSettingsActivity::class.java)); true }
        MORE_SERVICES -> { startActivity(Intent(this, ServiceManagerActivity::class.java)); true }
        MORE_PROCESSES -> { startActivity(Intent(this, ProcessManagerActivity::class.java)); true }
        MORE_DIAGNOSTICS -> { startActivity(Intent(this, DiagnosticsActivity::class.java)); true }
        MORE_APP_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> false
    }

    /** Header ⋮: expandable statistics + session options. */
    private fun showHeaderMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val expanded = binding.statsExpanded.visibility == View.VISIBLE
        popup.menu.add(
            0, HEADER_TOGGLE_STATS, 0,
            getString(if (expanded) R.string.header_hide_stats else R.string.header_show_stats)
        )
        popup.menu.add(0, HEADER_CLOSE_SESSION, 1, getString(R.string.drawer_close_session))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                HEADER_TOGGLE_STATS -> {
                    statsRowExpanded = !expanded
                    binding.statsExpanded.visibility = if (statsRowExpanded) View.VISIBLE else View.GONE
                    true
                }
                HEADER_CLOSE_SESSION -> {
                    current?.let { confirmCloseSession(it) }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun wireSearchBar() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Debounced: large scrollbacks must not be re-scanned per keystroke.
                searchHandler.removeCallbacks(searchRunnable)
                searchHandler.postDelayed(searchRunnable, 250)
            }
        })
        binding.btnSearchPrev.setOnClickListener { binding.terminal.searchPreviousMatch() }
        binding.btnSearchNext.setOnClickListener { binding.terminal.searchNextMatch() }
        binding.btnSearchClose.setOnClickListener { closeSearchBar() }
        binding.terminal.onSearchResult = { count, index ->
            binding.tvSearchCount.text = if (count <= 0) "" else "${index + 1}/$count"
        }
    }

    private fun toggleSearchBar() {
        if (binding.searchBar.visibility == View.VISIBLE) closeSearchBar() else openSearchBar()
    }

    private fun openSearchBar() {
        binding.searchBar.visibility = View.VISIBLE
        binding.etSearch.requestFocus()
    }

    private fun closeSearchBar() {
        searchHandler.removeCallbacks(searchRunnable)
        binding.searchBar.visibility = View.GONE
        binding.tvSearchCount.text = ""
        binding.terminal.clearSearch()
    }

    /** Shares the full transcript through the system share sheet. */
    private fun saveTerminalOutput() {
        val text = binding.terminal.selectionOrTranscriptText()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.terminal_menu_save_output))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, null)) }
            .onFailure { Toast.makeText(this, R.string.err_generic, Toast.LENGTH_SHORT).show() }
    }

    /** Subtle session/process status: state chip + optional detail line. */
    private fun renderSessionStatus() {
        val session = current?.session
        when {
            session == null -> {
                binding.statusState.visibility = View.GONE
                binding.statusProc.text = ""
            }
            session.isRunning && session.lastOutputAtMs > 0 &&
                System.currentTimeMillis() - session.lastOutputAtMs < 10_000L -> {
                setStateChip(getString(R.string.state_running), R.color.noxs_accent)
                binding.statusProc.text = getString(R.string.status_proc_running, session.label, session.pid)
            }
            session.isRunning -> {
                setStateChip(getString(R.string.state_idle), R.color.noxs_text_dim)
                binding.statusProc.text = getString(R.string.status_proc_idle, session.label, session.pid)
            }
            session.exitCode == 0 -> {
                setStateChip(getString(R.string.state_exited), R.color.noxs_warning)
                binding.statusProc.text = getString(R.string.status_proc_exited, session.exitCode)
            }
            else -> {
                setStateChip(getString(R.string.state_failed), R.color.noxs_error)
                binding.statusProc.text = getString(R.string.status_proc_failed, session.exitCode)
            }
        }
    }

    private fun setStateChip(label: String, colorRes: Int) {
        binding.statusState.text = label
        binding.statusState.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.statusState.visibility = View.VISIBLE
        binding.statusDot.setBackgroundResource(colorRes)
    }

    /** Confirmation before terminating a shell (running or not). */
    private fun confirmCloseSession(entry: NoxsSessionManager.Entry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.session_close_confirm_title)
            .setMessage(getString(R.string.session_close_confirm_msg, entry.label))
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val wasCurrent = current === entry
                sessionManager?.closeSession(entry)
                if (wasCurrent) {
                    current = sessionManager?.sessions?.value?.firstOrNull { it !== entry }
                    attachCurrent()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun copyTerminalText() {
        val text = binding.terminal.selectionOrTranscriptText()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("noxs-terminal", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun pasteIntoShell() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val session = current?.session ?: return
        session.write(session.emulator.paste(clip))
    }

    private fun clearTerminal() {
        val session = current?.session ?: return
        session.emulator.clearScreen()
        binding.terminal.onSessionOutputChanged(session)
        session.write("clear\n".toByteArray(Charsets.UTF_8))
        binding.terminal.invalidate()
    }

    override fun onStart() {
        super.onStart()
        if (::storageBridge.isInitialized && paths.isInstalled()) {
            startStorageObserver()
            startBridgeObserver()
        }
    }

    override fun onStop() {
        // Preserve the reading position across backgrounding/recreation.
        current?.let { scrollOffsets[it.label] = binding.terminal.currentScrollOffset() }
        storageObserver?.stopWatching()
        storageObserver = null
        storageObservedPath = null
        bridgeObserver?.stopWatching()
        bridgeObserver = null
        bridgeObservedPath = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingStorageAction?.let { pending ->
            outState.putString(STATE_STORAGE_REQUEST_ID, pending.requestId)
            outState.putString(STATE_STORAGE_CATEGORY, pending.category.key)
        }
        super.onSaveInstanceState(outState)
    }

    private fun startStorageObserver() {
        val recreated = runCatching { storageBridge.ensureControlDirectories() }.getOrElse {
            Toast.makeText(this, "Unable to prepare Noxs storage commands", Toast.LENGTH_LONG).show()
            return
        }
        cleanupStaleStorageTransfers()
        armStorageObserver(force = recreated)
        processPendingStorageRequests()
    }

    private fun armStorageObserver(force: Boolean) {
        val path = paths.storageRequests.absolutePath
        if (!force && storageObserver != null && storageObservedPath == path) return
        storageObserver?.stopWatching()
        storageObserver = null
        storageObservedPath = null
        val requestMask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val directoryInvalidatedMask = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val watchMask = requestMask or directoryInvalidatedMask
        val observer = object : FileObserver(path, watchMask) {
            override fun onEvent(event: Int, path: String?) {
                if ((event and directoryInvalidatedMask) != 0) {
                    runOnUiThread {
                        storageObserver?.stopWatching()
                        storageObserver = null
                        storageObservedPath = null
                        startStorageObserver()
                    }
                    return
                }
                if (path == null || (event and requestMask) == 0) return
                runOnUiThread { processPendingStorageRequests() }
            }
        }
        runCatching { observer.startWatching() }.onSuccess {
            storageObserver = observer
            storageObservedPath = path
        }.onFailure {
            Toast.makeText(this, "Unable to watch Noxs storage requests", Toast.LENGTH_LONG).show()
        }
    }

    private fun cleanupStaleStorageTransfers() {
        val cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L
        listOf(paths.storageRequests, paths.storageResponses, paths.storagePayloads, paths.storageResponsePayloads)
            .forEach { directory ->
                directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            }
    }

    private fun processPendingStorageRequests() {
        val recreated = runCatching { storageBridge.ensureControlDirectories() }.getOrElse { return }
        if (recreated) {
            cleanupStaleStorageTransfers()
            armStorageObserver(force = true)
        }
        if (pendingStorageAction != null) return
        val pendingFiles = paths.storageRequests.listFiles().orEmpty()
        pendingFiles.filter(::isSymbolicLink).forEach { it.delete() }
        val request = pendingFiles
            .filter { it.isFile && !isSymbolicLink(it) }
            .sortedBy { it.lastModified() }
            .firstOrNull() ?: return
        val requestId = request.name
        if (!requestId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) {
            request.delete()
            return
        }
        val lines = runCatching { request.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        request.delete()
        if (lines.isEmpty()) {
            completeStorageRequest(requestId, NoxsStorageBridge.CommandResult(false, "Empty storage request"))
            return
        }
        val operation = lines[0].trim().lowercase()
        val categoryKey = lines.getOrNull(1)?.trim().orEmpty()
        val path = lines.getOrNull(2).orEmpty()
        val argument = lines.getOrNull(3).orEmpty()

        when (operation) {
            "setup" -> confirmStoragePicker(requestId, NoxsStorageBridge.Category.SHARED)
            "map" -> {
                val category = NoxsStorageBridge.Category.fromKey(categoryKey)
                if (category == null) {
                    completeStorageRequest(requestId, NoxsStorageBridge.CommandResult(false, "Unknown storage category: $categoryKey"))
                } else confirmStoragePicker(requestId, category)
            }
            "reset" -> confirmStorageReset(requestId)
            else -> lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    storageBridge.execute(requestId, operation, categoryKey, path, argument)
                }
                completeStorageRequest(requestId, result)
            }
        }
    }

    private fun confirmStoragePicker(requestId: String, category: NoxsStorageBridge.Category) {
        pendingStorageAction = PendingStorageAction(requestId, category)
        AlertDialog.Builder(this)
            .setTitle("Noxs Storage Access")
            .setMessage(
                "Choose a folder for ${category.label}. Android will grant Noxs access only to the folder you select. " +
                    "SAF access is available through Noxs storage commands, not as a Linux POSIX path."
            )
            .setPositiveButton("Choose folder") { _, _ -> launchStoragePicker() }
            .setNegativeButton("Cancel") { _, _ ->
                val pending = pendingStorageAction
                pendingStorageAction = null
                if (pending != null) completeStorageRequest(
                    pending.requestId,
                    NoxsStorageBridge.CommandResult(false, "Permission not granted; storage mappings were not changed")
                )
            }
            .setOnCancelListener {
                val pending = pendingStorageAction
                pendingStorageAction = null
                if (pending != null) completeStorageRequest(
                    pending.requestId,
                    NoxsStorageBridge.CommandResult(false, "Permission not granted; storage mappings were not changed")
                )
            }
            .show()
    }

    private fun launchStoragePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        storagePickerLauncher.launch(intent)
    }

    private fun onStoragePickerResult(result: ActivityResult) {
        val pending = pendingStorageAction ?: return
        pendingStorageAction = null
        val uri: Uri? = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            completeStorageRequest(
                pending.requestId,
                NoxsStorageBridge.CommandResult(false, "Folder access was cancelled or denied; no mapping was saved")
            )
            return
        }
        val response = runCatching {
            storageBridge.grantTree(pending.category, uri, result.data?.flags ?: 0)
            NoxsStorageBridge.CommandResult(
                true,
                "${pending.category.label} folder permission saved.\n${storageBridge.statusText()}"
            )
        }.getOrElse {
            NoxsStorageBridge.CommandResult(false, it.message ?: "Could not retain access to the selected folder")
        }
        completeStorageRequest(pending.requestId, response)
    }

    private fun confirmStorageReset(requestId: String) {
        pendingStorageAction = PendingStorageAction(requestId, NoxsStorageBridge.Category.SHARED)
        AlertDialog.Builder(this)
            .setTitle("Reset Noxs storage mappings?")
            .setMessage(
                "This releases Noxs' saved folder permissions and clears its mappings. " +
                    "It will not delete, move, or modify files in Android storage."
            )
            .setPositiveButton("Reset mappings") { _, _ ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { storageBridge.resetMappings() }
                            .fold(
                                onSuccess = { NoxsStorageBridge.CommandResult(true, "Noxs storage mappings reset. Android files were not deleted.") },
                                onFailure = { NoxsStorageBridge.CommandResult(false, it.message ?: "Storage reset failed") }
                            )
                    }
                    pendingStorageAction = null
                    completeStorageRequest(requestId, result)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingStorageAction = null
                completeStorageRequest(requestId, NoxsStorageBridge.CommandResult(false, "Reset cancelled; no mappings changed"))
            }
            .setOnCancelListener {
                pendingStorageAction = null
                completeStorageRequest(requestId, NoxsStorageBridge.CommandResult(false, "Reset cancelled; no mappings changed"))
            }
            .show()
    }

    private fun completeStorageRequest(requestId: String, result: NoxsStorageBridge.CommandResult) {
        if (!requestId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) return
        if (runCatching { storageBridge.ensureControlDirectories() }.isFailure) return
        val response = File(paths.storageResponses, requestId)
        val temporary = File(paths.storageResponses, "$requestId.${java.util.UUID.randomUUID()}.tmp")
        runCatching {
            paths.storageResponses.mkdirs()
            temporary.writeText("${if (result.ok) "OK" else "ERR"}\n${result.output.trimEnd()}\n", Charsets.UTF_8)
            if (isSymbolicLink(response)) response.delete()
            if (response.exists() && !response.delete()) {
                temporary.delete()
                return@runCatching
            }
            if (!temporary.renameTo(response)) {
                temporary.delete()
                return@runCatching
            }
        }
        if (pendingStorageAction?.requestId == requestId) pendingStorageAction = null
        if (storageObserver != null) processPendingStorageRequests()
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        val mode = android.system.Os.lstat(file.absolutePath).st_mode
        (mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
    }.getOrDefault(false)

    // ======================================================== NX Android Bridge
    // Serves /var/run/noxs/host/android requests written by the guest CLI
    // (noxs document | sms | device | notification | share | camera |
    // clipboard | network | files | storage mount ...). UI operations run
    // here in the foreground activity; everything else goes to the bridge host.

    private fun startBridgeObserver() {
        val recreated = runCatching { androidBridge.ensureControlDirectories() }.getOrElse { return }
        androidBridge.cleanupStaleTransfers()
        armBridgeObserver(force = recreated)
        processPendingBridgeRequests()
    }

    private fun armBridgeObserver(force: Boolean) {
        val path = paths.bridgeRequests.absolutePath
        if (!force && bridgeObserver != null && bridgeObservedPath == path) return
        bridgeObserver?.stopWatching()
        bridgeObserver = null
        bridgeObservedPath = null
        val requestMask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val dirInvalid = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val observer = object : FileObserver(path, requestMask or dirInvalid) {
            override fun onEvent(event: Int, name: String?) {
                if ((event and dirInvalid) != 0) {
                    runOnUiThread {
                        bridgeObserver?.stopWatching()
                        bridgeObserver = null
                        bridgeObservedPath = null
                        startBridgeObserver()
                    }
                    return
                }
                if (name == null || (event and requestMask) == 0) return
                runOnUiThread { processPendingBridgeRequests() }
            }
        }
        runCatching { observer.startWatching() }.onSuccess {
            bridgeObserver = observer
            bridgeObservedPath = path
        }
    }

    private fun processPendingBridgeRequests() {
        runCatching { androidBridge.ensureControlDirectories() }.getOrElse { return }
        if (pendingBridgeUi != null) return
        val pendingFiles = paths.bridgeRequests.listFiles().orEmpty()
        pendingFiles.filter(::isSymbolicLink).forEach { it.delete() }
        val request = pendingFiles
            .filter { it.isFile && !isSymbolicLink(it) }
            .sortedBy { it.lastModified() }
            .firstOrNull() ?: return
        val requestId = request.name
        if (!requestId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) {
            request.delete()
            return
        }
        val lines = runCatching { request.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        request.delete()
        val parsed = NoxsAndroidBridge.parseRequest(requestId, lines)

        if (NoxsAndroidBridge.needsUi(parsed.operation)) {
            handleBridgeUi(parsed)
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { androidBridge.execute(parsed) }
                    .getOrElse { NoxsAndroidBridge.CommandResult(false, it.message ?: "bridge failure") }
            }
            completeBridgeRequest(requestId, result)
        }
    }

    private fun completeBridgeRequest(requestId: String, result: NoxsAndroidBridge.CommandResult) {
        if (!requestId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) return
        runCatching { androidBridge.ensureControlDirectories() }.getOrElse { return }
        val response = File(paths.bridgeResponses, requestId)
        val temporary = File(paths.bridgeResponses, "$requestId.${java.util.UUID.randomUUID()}.tmp")
        runCatching {
            paths.bridgeResponses.mkdirs()
            temporary.writeText("${if (result.ok) "OK" else "ERR"}\n${result.output.trimEnd()}\n", Charsets.UTF_8)
            if (isSymbolicLink(response)) response.delete()
            if (response.exists() && !response.delete()) { temporary.delete(); return@runCatching }
            if (!temporary.renameTo(response)) temporary.delete()
        }
        if (pendingBridgeUi?.requestId == requestId) pendingBridgeUi = null
        if (bridgeObserver != null) processPendingBridgeRequests()
    }

    // ------------------------------------------------------- UI operations

    private fun handleBridgeUi(request: NoxsAndroidBridge.Request) {
        val message = if (
            request.operation == "sms-send" || request.operation == "sms-compose" ||
            request.operation == "share-text" || request.operation == "notify-post"
        ) androidBridge.payloadText(request) else ""
        pendingBridgeUi = PendingBridgeUi(request.id, request.operation, request.args, message)
        when (request.operation) {
            "ui-files" -> {
                pendingBridgeUi = null
                startActivity(Intent(this, FileBrowserActivity::class.java))
                completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(true, "Noxs file browser opened"))
            }
            "ui-storage" -> {
                pendingBridgeUi = null
                startActivity(Intent(this, StorageActivity::class.java))
                completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(true, "Noxs storage dashboard opened"))
            }
            "doc-open", "doc-pick" -> launchBridgeDocumentPick(request, multi = request.operation == "doc-pick")
            "doc-import" -> launchBridgeDocumentPick(request, multi = true)
            "doc-export" -> launchBridgeExport(request)
            "doc-gallery" -> {
                runCatching {
                    bridgePhotoLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }.getOrElse {
                    pendingBridgeUi = null
                    completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Photo picker unavailable on this device"))
                }
            }
            "camera" -> launchBridgeCamera(request)
            "share-text" -> {
                pendingBridgeUi = null
                val text = message.ifEmpty { request.arg(1) }
                if (text.isEmpty()) {
                    completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "No text to share"))
                } else {
                    runCatching { startActivity(androidBridge.shareTextIntent(text, null)) }
                        .onSuccess {
                            completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(true, "Sharesheet opened"))
                        }
                        .onFailure {
                            completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Could not open the sharesheet"))
                        }
                }
            }
            "share-file" -> launchBridgeShareFile(request)
            "sms-compose" -> {
                pendingBridgeUi = null
                val number = request.arg(1)
                runCatching { startActivity(androidBridge.smsComposeIntent(number, message)) }
                    .onSuccess {
                        completeBridgeRequest(
                            request.id,
                            NoxsAndroidBridge.CommandResult(true, "System SMS app opened — send from there for final approval")
                        )
                    }
                    .onFailure {
                        completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "No SMS app available on this device"))
                    }
            }
            "sms-contacts" -> {
                runCatching {
                    bridgeSystemUiLauncher.launch(
                        Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI)
                    )
                }.getOrElse {
                    pendingBridgeUi = null
                    completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Contacts app unavailable"))
                }
            }
            "sms-send" -> confirmBridgeSmsSend(request, message)
            "notify-permission" -> requestBridgePermissions(
                request, NoxsAndroidPermissions.Perm.NOTIFICATIONS
            )
            "perm-request" -> {
                val perm = NoxsAndroidPermissions.Perm.values().firstOrNull { it.id == request.arg(1) }
                if (perm == null) {
                    pendingBridgeUi = null
                    completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Unknown capability: ${request.arg(1)}"))
                } else requestBridgePermissions(request, perm)
            }
            "mount-grant" -> {
                runCatching {
                    bridgeSystemUiLauncher.launch(
                        Intent(NoxsAndroidPermissions.ALL_FILES_ACTION, Uri.parse("package:$packageName"))
                    )
                }.getOrElse {
                    pendingBridgeUi = null
                    completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "All-files-access screen unavailable"))
                }
            }
            else -> {
                pendingBridgeUi = null
                completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Unknown bridge operation"))
            }
        }
    }

    /** Requests [perm] runtime permissions, then answers the bridge request. */
    private fun requestBridgePermissions(request: NoxsAndroidBridge.Request, perm: NoxsAndroidPermissions.Perm) {
        val toRequest = NoxsAndroidPermissions.requestList(perm)
        if (toRequest.isEmpty() || NoxsAndroidPermissions.isGranted(this, perm)) {
            pendingBridgeUi = null
            completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(true, NoxsAndroidPermissions.statusText(this, perm)))
            return
        }
        // Keep the pending request; the result handler completes it.
        runCatching { bridgePermLauncher.launch(toRequest.toTypedArray()) }.getOrElse {
            pendingBridgeUi = null
            completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Permission dialog unavailable"))
        }
    }

    private fun onBridgePermissionsResult(grants: Map<String, Boolean>) {
        val pending = pendingBridgeUi ?: return
        pendingBridgeUi = null
        if (!grants.values.all { it }) {
            completeBridgeRequest(
                pending.requestId,
                NoxsAndroidBridge.CommandResult(false, "Permission denied — nothing was sent or changed")
            )
            return
        }
        when (pending.kind) {
            "camera" -> {
                // The grant was collected first — now actually open the camera.
                launchBridgeCamera(
                    NoxsAndroidBridge.parseRequest(pending.requestId, listOf("camera") + pending.args)
                )
            }
            else -> completeBridgeRequest(
                pending.requestId,
                NoxsAndroidBridge.CommandResult(true, "Permission granted")
            )
        }
    }

    /** Explicit, user-approved SMS send: recipient + message shown, one message. */
    private fun confirmBridgeSmsSend(request: NoxsAndroidBridge.Request, message: String) {
        val number = request.arg(1)
        if (number.isBlank() || message.isEmpty()) {
            pendingBridgeUi = null
            completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Usage: noxs sms send <number> (message via payload)"))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send SMS via Noxs")
            .setMessage("To: $number\n\n${message.take(1200)}")
            .setPositiveButton("Send now") { _, _ ->
                pendingBridgeUi = null
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { androidBridge.sendSms(number, message) }
                    completeBridgeRequest(request.id, result)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingBridgeUi = null
                completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Cancelled — no SMS was sent"))
            }
            .setOnCancelListener {
                if (pendingBridgeUi?.requestId == request.id) {
                    pendingBridgeUi = null
                    completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Cancelled — no SMS was sent"))
                }
            }
            .show()
    }

    private fun launchBridgeDocumentPick(request: NoxsAndroidBridge.Request, multi: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.arg(2).ifEmpty { "*/*" }
            if (multi) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { bridgeSystemUiLauncher.launch(intent) }.getOrElse {
            pendingBridgeUi = null
            completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Document picker unavailable"))
        }
    }

    private fun launchBridgeExport(request: NoxsAndroidBridge.Request) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.arg(2).ifEmpty { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, request.arg(1).ifEmpty { "noxs-export" })
        }
        runCatching { bridgeSystemUiLauncher.launch(intent) }.getOrElse {
            pendingBridgeUi = null
            completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Document creator unavailable"))
        }
    }

    private fun launchBridgeCamera(request: NoxsAndroidBridge.Request) {
        val perm = NoxsAndroidPermissions.Perm.CAMERA
        if (!NoxsAndroidPermissions.isGranted(this, perm)) {
            // Keep pendingBridgeUi(kind=camera); the grant result relaunches capture.
            runCatching {
                bridgePermLauncher.launch(NoxsAndroidPermissions.requestList(perm).toTypedArray())
            }.getOrElse {
                pendingBridgeUi = null
                completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "Permission dialog unavailable"))
            }
            return
        }
        val output = File(cacheDir, "bridge-capture-${System.currentTimeMillis()}.jpg")
        bridgeCameraOutput = output
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", output)
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { bridgeSystemUiLauncher.launch(intent) }.getOrElse {
            pendingBridgeUi = null
            bridgeCameraOutput = null
            completeBridgeRequest(request.id, NoxsAndroidBridge.CommandResult(false, "No camera app available"))
        }
    }

    private fun launchBridgeShareFile(request: NoxsAndroidBridge.Request) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = androidBridge.payloadFile(request)
                    require(payload.isFile && payload.length() > 0) { "No staged file to share" }
                    val shared = androidBridge.stageForSharing(payload)
                    startActivity(androidBridge.shareFileIntent(shared, request.arg(1).ifEmpty { shared.name }, request.arg(2)))
                    NoxsAndroidBridge.CommandResult(true, "Sharesheet opened")
                }.getOrElse { NoxsAndroidBridge.CommandResult(false, it.message ?: "Could not share the file") }
            }
            pendingBridgeUi = null
            completeBridgeRequest(request.id, result)
        }
    }

    private fun onBridgePhotoPicked(uri: Uri?) {
        val pending = pendingBridgeUi ?: return
        pendingBridgeUi = null
        if (uri == null) {
            completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "Gallery selection cancelled"))
            return
        }
        bridgeTransfer("gallery import", pending.requestId) { output ->
            contentResolver.openInputStream(uri)?.use { it.copyTo(output) }
                ?: throw IllegalStateException("Could not open the selected photo")
        }
    }

    private fun onBridgeSystemUiResult(result: ActivityResult) {
        val pending = pendingBridgeUi ?: return
        pendingBridgeUi = null
        when (pending.kind) {
            "doc-open" -> {
                val uri = result.data?.data
                if (result.resultCode == RESULT_OK && uri != null) {
                    openBridgeDocumentExternally(pending, uri)
                } else {
                    completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "Document selection cancelled"))
                }
            }
            "doc-pick", "doc-import" -> {
                if (result.resultCode != RESULT_OK) {
                    completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "Document selection cancelled"))
                    return
                }
                if (pending.kind == "doc-import") {
                    importBridgeDocuments(pending, result)
                } else {
                    val uri = result.data?.data
                    if (uri == null) {
                        completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "No document selected"))
                    } else {
                        bridgeTransfer("document transfer", pending.requestId) { output ->
                            contentResolver.openInputStream(uri)?.use { it.copyTo(output) }
                                ?: throw IllegalStateException("Could not read the selected document")
                        }
                    }
                }
            }
            "doc-export" -> {
                val uri = result.data?.data
                if (result.resultCode != RESULT_OK || uri == null) {
                    completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "Export cancelled"))
                    return
                }
                bridgeTransfer("export", pending.requestId) { output ->
                    androidBridge.payloadFile(
                        NoxsAndroidBridge.parseRequest(pending.requestId, listOf("doc-export") + pending.args)
                    ).takeIf { it.isFile }
                        ?.inputStream()?.use { it.copyTo(output) }
                        ?: throw IllegalStateException("Staged export payload is missing")
                }
            }
            "camera" -> {
                val file = bridgeCameraOutput
                bridgeCameraOutput = null
                if (result.resultCode != RESULT_OK || file == null || !file.isFile || file.length() == 0L) {
                    file?.delete()
                    completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "Camera capture cancelled"))
                    return
                }
                bridgeTransfer("camera transfer", pending.requestId) { output ->
                    file.inputStream().use { it.copyTo(output) }
                    file.delete()
                }
            }
            "mount-grant" -> {
                completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(true, NoxsStorageMount.statusText(this)))
            }
            "sms-contacts" -> {
                val uri = result.data?.data
                if (result.resultCode != RESULT_OK || uri == null) {
                    completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "Contact selection cancelled"))
                    return
                }
                lifecycleScope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.query(
                                uri,
                                arrayOf(
                                    android.provider.ContactsContract.Contacts._ID,
                                    android.provider.ContactsContract.Contacts.DISPLAY_NAME
                                ),
                                null, null, null
                            )?.use { c ->
                                if (c.moveToFirst()) {
                                    val name = c.getString(1) ?: "contact"
                                    NoxsAndroidBridge.CommandResult(true, "Selected contact: $name\nPass the number to noxs sms send or noxs sms compose")
                                } else NoxsAndroidBridge.CommandResult(false, "Contact has no details")
                            } ?: NoxsAndroidBridge.CommandResult(false, "Could not read the contact")
                        }.getOrElse { NoxsAndroidBridge.CommandResult(false, "Could not read the contact") }
                    }
                    completeBridgeRequest(pending.requestId, outcome)
                }
            }
            else -> completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "Bridge operation cancelled"))
        }
    }

    private fun openBridgeDocumentExternally(pending: PendingBridgeUi, uri: Uri) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val type = contentResolver.getType(uri) ?: "*/*"
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, type)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                    startActivity(view)
                    NoxsAndroidBridge.CommandResult(true, "Opened in an Android app")
                }.getOrElse { NoxsAndroidBridge.CommandResult(false, "No Android app can open this document") }
            }
            completeBridgeRequest(pending.requestId, outcome)
        }
    }

    /** Copies selected documents into the guest home (relative destination). */
    private fun importBridgeDocuments(pending: PendingBridgeUi, result: ActivityResult) {
        val uris = mutableListOf<Uri>()
        result.data?.data?.let { uris.add(it) }
        result.data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) } }
        if (uris.isEmpty()) {
            completeBridgeRequest(pending.requestId, NoxsAndroidBridge.CommandResult(false, "No document selected"))
            return
        }
        val destRel = pending.args.getOrNull(0).orEmpty().trim().ifEmpty { "imports" }
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(paths.rootfs, "home/noxs/" + destRel.trimStart('/').replace("..", "_").ifEmpty { "imports" })
                    if (!dest.isDirectory && !dest.mkdirs()) throw IllegalStateException("Cannot create destination folder")
                    var imported = 0
                    var bytes = 0L
                    for (uri in uris) {
                        val name = queryBridgeDisplayName(uri) ?: continue
                        val target = File(dest, com.crossberry.noxs.runtime.NoxsDocumentsPolicy.sanitizeDisplayName(name))
                        contentResolver.openInputStream(uri)?.use { input ->
                            java.io.FileOutputStream(target).use { output -> bytes += input.copyTo(output) }
                        } ?: continue
                        imported++
                    }
                    NoxsAndroidBridge.CommandResult(
                        true,
                        "Imported $imported document(s) (${bytes / 1024} KB) into ~/$destRel"
                    )
                }.getOrElse { NoxsAndroidBridge.CommandResult(false, it.message ?: "Import failed") }
            }
            completeBridgeRequest(pending.requestId, outcome)
        }
    }

    private fun queryBridgeDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.let { it.ifBlank { null } }

    /** Streams into the response-payload slot with a cancelable progress dialog. */
    private fun bridgeTransfer(label: String, requestId: String, producer: (OutputStream) -> Unit) {
        bridgeCancelRequested = false
        val progressDialog = runCatching {
            AlertDialog.Builder(this)
                .setTitle("Noxs Android Bridge")
                .setMessage("$label…\n0 KB transferred")
                .setNegativeButton("Cancel") { _, _ -> bridgeCancelRequested = true }
                .setCancelable(false)
                .show()
        }.getOrNull()
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                var transferred = 0L
                var lastUpdate = 0L
                val temporary = androidBridge.tempResponsePayloadFile(requestId)
                try {
                    java.io.FileOutputStream(temporary).use { raw ->
                        val sink = object : OutputStream() {
                            override fun write(b: Int) {
                                throw UnsupportedOperationException("single-byte writes are not used")
                            }

                            override fun write(b: ByteArray, off: Int, len: Int) {
                                if (bridgeCancelRequested) throw java.io.InterruptedIOException("cancelled")
                                raw.write(b, off, len)
                                transferred += len
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 250) {
                                    lastUpdate = now
                                    runOnUiThread {
                                        progressDialog?.setMessage("$label…\n${transferred / 1024} KB transferred")
                                    }
                                }
                            }
                        }
                        producer(sink)
                    }
                    if (bridgeCancelRequested) throw java.io.InterruptedIOException("cancelled")
                    val bytes = androidBridge.finishResponsePayload(requestId, temporary)
                    NoxsAndroidBridge.CommandResult(true, "$label complete: ${bytes / 1024} KB")
                } catch (e: java.io.InterruptedIOException) {
                    temporary.delete()
                    NoxsAndroidBridge.CommandResult(false, "$label cancelled — no partial data was kept")
                } catch (e: Exception) {
                    temporary.delete()
                    NoxsAndroidBridge.CommandResult(false, "$label failed: ${e.message?.take(200) ?: "unknown error"}")
                }
            }
            progressDialog?.dismiss()
            completeBridgeRequest(requestId, outcome)
        }
    }

    private fun renderSessions(list: List<NoxsSessionManager.Entry>) {
        val adapter = sessionAdapter ?: return
        adapter.submit(list.map { entry ->
            TwoLineRow(
                title = entry.label + (if (entry.loginAsRoot) " [ROOT]" else ""),
                subtitle = if (entry.session.isRunning) "running" else "exited (${entry.session.exitCode})",
                onClick = {
                    current = entry
                    attachCurrent()
                    binding.drawer.closeDrawer(GravityCompat.START)
                },
                onLongClick = {
                    confirmCloseSession(entry)
                }
            )
        })
        renderSessionTabs(list)
    }

    private fun renderSessionTabs(list: List<NoxsSessionManager.Entry>) {
        val row = binding.sessionTabsRow
        row.removeAllViews()
        val dp = getResources().displayMetrics.density

        // Keep the user's custom order while removing sessions that no longer exist.
        val liveLabels = list.map { it.label }.toSet()
        sessionDisplayOrder.removeAll { it !in liveLabels }
        list.forEach { if (it.label !in sessionDisplayOrder) sessionDisplayOrder.add(it.label) }
        val orderedSessions = list.sortedBy { sessionDisplayOrder.indexOf(it.label) }

        // Session chips: root sandbox sessions carry a warning outline + ⌗.
        orderedSessions.forEach { entry ->
            val isSelected = entry === current
            val running = entry.session.isRunning
            val chip = TextView(this).apply {
                tag = entry
                val dot = if (running) "● " else "○ "
                val rootTag = if (entry.loginAsRoot) " ⌗" else ""
                text = "$dot${entry.label}$rootTag"
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                isSingleLine = true
                contentDescription = entry.label +
                    (if (entry.loginAsRoot) " (${getString(R.string.session_root_badge)})" else "") +
                    (if (running) "" else " · ${getString(R.string.state_exited)}")
                setTextColor(
                    when {
                        isSelected -> ON_ACCENT
                        !running -> DIM_TEXT
                        entry.loginAsRoot -> WARN_TEXT
                        else -> TEXT_WHITE
                    }
                )
                minHeight = (48 * dp).toInt()
                isFocusable = true
                isClickable = true
                this.isSelected = isSelected
                val chipShape = GradientDrawable().apply {
                    cornerRadius = 16f * dp
                    setColor(if (isSelected) ACCENT else ELEVATED)
                    setStroke(
                        (1 * dp).toInt(),
                        when {
                            isSelected -> ACCENT
                            entry.loginAsRoot -> WARN_BORDER
                            else -> BORDER
                        }
                    )
                }
                val chipMask = GradientDrawable().apply {
                    cornerRadius = 16f * dp
                    setColor(0xFFFFFFFF.toInt())
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(0x44FFFFFF),
                    chipShape,
                    chipMask
                )
                setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, entry.label + " · Long press for session actions")
                setOnHoverListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_HOVER_ENTER -> {
                            view.animate().scaleX(1.025f).scaleY(1.025f).translationZ(6f * dp).setDuration(120L).start()
                            view.pointerIcon = android.view.PointerIcon.getSystemIcon(this@TerminalActivity, android.view.PointerIcon.TYPE_HAND)
                        }
                        MotionEvent.ACTION_HOVER_EXIT -> {
                            view.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120L).start()
                            view.pointerIcon = android.view.PointerIcon.getSystemIcon(this@TerminalActivity, android.view.PointerIcon.TYPE_ARROW)
                        }
                    }
                    false
                }
                setOnContextClickListener {
                    showSessionActionsMenu(this, entry)
                    true
                }
                setOnTouchListener { view, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80L).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
                    }
                    false
                }
                setOnClickListener {
                    if (current !== entry) {
                        current = entry
                        attachCurrent()
                        renderSessionTabs(list)
                    }
                }
                setOnDragListener { target, event -> handleSessionDragEvent(target, event) }
                setOnLongClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    startSessionDrag(this, entry)
                    true
                }
            }
            chip.alpha = 0f
            chip.translationY = (5 * dp)
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * dp).toInt() })
            chip.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((orderedSessions.indexOf(entry) * 24L).coerceAtMost(120L))
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        // Compact [+] launcher: shell or root sandbox via overflow, so the
        // two creation options never clip on narrow screens.
        val add = TextView(this).apply {
            text = "+"
            typeface = Typeface.MONOSPACE
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = (24 * dp).toInt()
            setTextColor(TEXT_WHITE)
            contentDescription = getString(R.string.action_new_shell)
            background = GradientDrawable().apply {
                cornerRadius = 8f * dp
                setColor(ELEVATED)
                setStroke((1 * dp).toInt(), BORDER)
            }
            setPadding((11 * dp).toInt(), 0, (11 * dp).toInt(), 0)
            setOnClickListener { showNewSessionMenu(it) }
        }
        row.addView(add, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (6 * dp).toInt() })

        row.setOnDragListener { target, event -> handleSessionDragEvent(target, event) }

        // Keep the active chip in view after switching or creating sessions.
        binding.sessionTabsScroll.post {
            val index = list.indexOfFirst { it === current }
            val target = if (index >= 0) row.getChildAt(index) else add
            target?.let { binding.sessionTabsScroll.smoothScrollTo(it.left.coerceAtLeast(0), 0) }
        }
    }

    /** Context actions for a terminal session, exposed on long press. */
    private fun showSessionActionsMenu(anchor: View, entry: NoxsSessionManager.Entry) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Switch to session")
        popup.menu.add(0, 5, 1, "Drag to reorder")
        popup.menu.add(0, 3, 2, "Move left")
        popup.menu.add(0, 4, 3, "Move right")
        popup.menu.add(0, 2, 4, "Close session")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    current = entry
                    attachCurrent()
                    binding.drawer.closeDrawer(GravityCompat.START)
                }
                2 -> confirmCloseSession(entry)
                5 -> startSessionDrag(anchor, entry)
                3 -> moveSession(entry, -1)
                4 -> moveSession(entry, 1)
            }
            true
        }
        popup.show()
    }

    /** Starts a native Android drag operation for a terminal session tab. */
    private fun startSessionDrag(anchor: View, entry: NoxsSessionManager.Entry) {
        val clip = android.content.ClipData.newPlainText("noxs-session", entry.label)
        anchor.startDragAndDrop(clip, View.DragShadowBuilder(anchor), entry, 0)
    }

    /** Handles drag targets on chips and the session strip; runtime sessions are never moved. */
    private fun handleSessionDragEvent(target: View, event: android.view.DragEvent): Boolean {
        fun resetTarget() {
            target.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationZ(0f)
                .setDuration(120L)
                .start()
        }

        when (event.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED ->
                return event.clipDescription?.hasMimeType("text/plain") == true
            android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                target.animate()
                    .alpha(0.88f)
                    .scaleX(1.045f)
                    .scaleY(1.045f)
                    .translationZ(8f * target.resources.displayMetrics.density)
                    .setDuration(140L)
                    .start()
                return true
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                resetTarget()
                return true
            }
            android.view.DragEvent.ACTION_DROP -> {
                resetTarget()
                val source = event.localState as? NoxsSessionManager.Entry ?: return false
                val from = sessionDisplayOrder.indexOf(source.label)
                val destination = (target.tag as? NoxsSessionManager.Entry)?.let { sessionDisplayOrder.indexOf(it.label) } ?: sessionDisplayOrder.lastIndex
                if (from >= 0 && destination >= 0 && from != destination) {
                    val moved = sessionDisplayOrder.removeAt(from)
                    sessionDisplayOrder.add(destination.coerceIn(0, sessionDisplayOrder.size), moved)
                    RuntimeHolder.sessions?.sessions?.value?.let(::renderSessionTabs)
                }
                target.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                return true
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                resetTarget()
                return true
            }
        }
        return true
    }

    /** Reorders the visible session strip without changing runtime session state. */
    private fun moveSession(entry: NoxsSessionManager.Entry, direction: Int) {
        val from = sessionDisplayOrder.indexOf(entry.label)
        val to = (from + direction).coerceIn(0, sessionDisplayOrder.lastIndex)
        if (from < 0 || from == to) return
        val moved = sessionDisplayOrder.removeAt(from)
        sessionDisplayOrder.add(to, moved)
        renderSessionTabs(RuntimeHolder.sessions?.sessions?.value ?: return)
        val row = binding.sessionTabsRow
        row.getChildAt(to)?.let { binding.sessionTabsScroll.smoothScrollTo(it.left.coerceAtLeast(0), 0) }
    }

    /** [+] overflow: shell and root sandbox creation on any screen width. */
    private fun showNewSessionMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, NEW_SHELL, 0, getString(R.string.action_new_shell))
        popup.menu.add(0, NEW_ROOT, 1, getString(R.string.drawer_new_root_session))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                NEW_SHELL -> newSession(root = false)
                NEW_ROOT -> newSession(root = true)
            }
            true
        }
        popup.show()
    }

    private suspend fun refreshStatus() {
        val r = resources ?: return
        // A usage probe must never take the terminal down (the field crash
        // came from exactly this path): degrade the widget instead.
        val usage = try {
            withContext(Dispatchers.IO) { r.currentUsage() }
        } catch (e: Throwable) {
            NoxsLog.w("Terminal", "resource refresh failed — widget keeps last values (${e.javaClass.simpleName})")
            return
        }
        binding.statusCpu.text = getString(R.string.status_cpu, "%.0f".format(usage.cpuPercent))
        binding.statusMem.text = getString(R.string.status_mem, usage.memMb.toString(), usage.totalMemMb.toString())
        binding.statusStorage.text = getString(R.string.status_storage, usage.storageUsedMb.toString())
    }

    // ---- Background Activity Center: floating status panel ----

    private fun toggleFloatingPanel(activityId: String) {
        if (floatingPanel != null && floatingPanel?.visibility == View.VISIBLE) {
            hideFloatingPanel()
        } else {
            showFloatingPanel(activityId)
        }
    }

    private fun showFloatingPanel(activityId: String) {
        val record = activityCenter.find(activityId) ?: run {
            Toast.makeText(this, R.string.activity_none, Toast.LENGTH_SHORT).show()
            return
        }
        val existing = floatingPanel
        val panel = existing ?: FloatingStatusPanel(this).also { created ->
            floatingPanel = created
            binding.terminalContainer.addView(created)
            created.onTerminal = {
                val rec = activityCenter.find(activityId)
                val entry = rec?.sessionId?.let { label ->
                    sessionManager?.sessions?.value?.firstOrNull { it.label == label }
                }
                if (entry != null) {
                    current = entry
                    attachCurrent()
                }
                hideFloatingPanel()
            }
            created.onLogs = { showPanelLogs(activityId) }
            created.onDetails = { showPanelDetails(activityId) }
            created.onStop = { confirmPanelStop(activityId) }
        }
        panel.render(record, null, System.currentTimeMillis())
        panel.showAnimated()
        startPanelLoop(activityId)
    }

    private fun hideFloatingPanel() {
        panelJob?.cancel()
        panelJob = null
        floatingPanel?.hideAnimated()
    }

    /** Lightweight polling only while the panel is visible (spec: never hot). */
    private fun startPanelLoop(activityId: String) {
        panelJob?.cancel()
        panelJob = lifecycleScope.launch {
            while (isActive) {
                renderPanel(activityId)
                delay(2_000L)
            }
        }
    }

    private fun renderPanel(activityId: String) {
        val panel = floatingPanel ?: return
        val record = activityCenter.find(activityId)
        if (record == null || record.status.isTerminal) {
            hideFloatingPanel()
            return
        }
        val rootPids = buildSet {
            record.pid?.takeIf { it > 0 }?.let { add(it) }
            // Attribute the whole process tree of the owning session.
            sessionManager?.sessions?.value
                ?.firstOrNull { it.label == record.sessionId }
                ?.let { entry -> entry.session.pid.takeIf { it > 0 }?.let(::add) }
        }
        val usage: NoxsTreeUsage? = if (rootPids.isEmpty()) null
        else runCatching { procSampler.sampleTree(rootPids) }.getOrNull()
        panel.render(record, usage, System.currentTimeMillis())
    }

    private fun showPanelLogs(activityId: String) {
        val lines = activityCenter.recentOutput(activityId)
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_logs_title)
            .setMessage(if (lines.isEmpty()) "No recent output." else lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPanelDetails(activityId: String) {
        val record = activityCenter.find(activityId) ?: return
        val usage = procSampler.sampleTree(setOfNotNull(record.pid?.takeIf { it > 0 }))
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_details_title)
            .setMessage(
                FloatingStatusPanel.statusText(record.status) + "\n" +
                    "Session: " + record.sessionId.ifBlank { "—" } + "\n" +
                    "Process: " + record.command.ifBlank { record.title } + "\n" +
                    "PID: " + (record.pid?.toString() ?: "—") + "\n" +
                    "CPU: " + (usage?.let { "%.0f%%".format(it.cpuPercent) } ?: "—") + "\n" +
                    "RAM: " + (usage?.let { "${it.rssKb / 1024} MB" } ?: "—") + "\n" +
                    "Processes: " + (usage?.processCount ?: 0) + "\n" +
                    "Directory: " + record.workingDirectory.ifBlank { "—" }
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmPanelStop(activityId: String) {
        val record = activityCenter.find(activityId) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_stop_confirm_title)
            .setMessage(getString(R.string.activity_stop_confirm_msg, record.title))
            .setPositiveButton(R.string.activity_stop) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { activityCenter.requestStop(activityId) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- TerminalSessionClient forwarding to the terminal views ----

    override fun onTextChanged(session: TerminalSession) {
        if (current?.session === session) binding.terminal.onSessionOutputChanged(session)
    }

    override fun onTitleChanged(session: TerminalSession) {
        if (current?.session === session) binding.statusSession.text = session.label
    }

    override fun onBell(session: TerminalSession) {
        Toast.makeText(this, "🔔 ${session.label}", Toast.LENGTH_SHORT).show()
    }

    override fun onSessionFinished(session: TerminalSession) {
        val wasCurrent = current?.session === session
        if (wasCurrent) {
            binding.terminal.invalidate()
            binding.statusSession.text = "${session.label} (exited ${session.exitCode})"
        }
        current?.activityId?.let { id ->
            if (floatingPanel?.visibility == View.VISIBLE) hideFloatingPanel()
        }

        // Smart exit: only the finished shell closes. Noxs stays alive while
        // other sessions or background activity remain, and shuts down cleanly
        // only when the LAST shell exits with nothing else running (spec 8-10).
        val mgr = sessionManager ?: RuntimeHolder.sessions
        val remaining = mgr?.sessions?.value?.size ?: 0
        val center = (application as NoxsApplication).activityCenter
        when {
            remaining > 0 ->
                Toast.makeText(this, getString(R.string.session_closed_remaining, remaining), Toast.LENGTH_LONG).show()
            center.hasPersistentWork() ->
                Toast.makeText(this, getString(R.string.session_closed_background), Toast.LENGTH_LONG).show()
            wasCurrent && System.currentTimeMillis() - attachedAtMs < SHUTDOWN_GRACE_MS ->
                // A shell that died immediately is a bootstrap failure, not a
                // user "exit" — keep the app open so the user can diagnose.
                Toast.makeText(this, getString(R.string.session_closed_background), Toast.LENGTH_LONG).show()
            else -> {
                Toast.makeText(this, getString(R.string.noxs_shutting_down), Toast.LENGTH_LONG).show()
                NoxsService.requestStopAll(this)
                finishAffinity()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreenMode) {
            // First back press leaves full screen instead of leaving the app.
            toggleFullscreen()
            return
        }
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawer(GravityCompat.START)
        } else if (terminalSettings.confirmExit &&
            sessionManager?.sessions?.value?.any { it.session.isRunning } == true
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.terminal_exit_title)
                .setMessage(R.string.terminal_exit_msg)
                .setPositiveButton(R.string.action_confirm) { _, _ -> finishAffinity() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            super.onBackPressed()
        }
    }

    internal companion object {
        const val STATE_STORAGE_REQUEST_ID = "storage_request_id"
        const val STATE_STORAGE_CATEGORY = "storage_category"
        const val PREFS_SETTINGS = "noxs_settings"
        const val PREF_HEADER_STATS = "header_stats"
        const val WEBSITE_URL = "https://crossberry.vercel.app"
        const val SUPPORT_EMAIL = "mailto:crossberryweb@gmail.com"
        const val SHUTDOWN_GRACE_MS = 3_000L

        /** NXEditor → TerminalActivity: open a NEW session at this guest cwd. */
        const val EXTRA_NEW_SESSION_CWD = "noxs.extra.NEW_SESSION_CWD"

        /** Terminal tab the NXEditor workspace can jump to (validated cwd). */
        fun openAt(context: Context, cwd: String): Intent =
            Intent(context, TerminalActivity::class.java)
                .putExtra(EXTRA_NEW_SESSION_CWD, cwd)

        // Noxs design-system colors (mirror res/values/colors.xml).
        private val ACCENT = 0xFF20D866.toInt()
        private val ON_ACCENT = 0xFF04150A.toInt()
        private val ELEVATED = 0xFF222222.toInt()
        private val BORDER = 0xFF303030.toInt()
        private val WARN_TEXT = 0xFFFFB020.toInt()
        private val WARN_BORDER = 0xFF7A5613.toInt()
        private val TEXT_WHITE = 0xFFFFFFFF.toInt()
        private val DIM_TEXT = 0xFFAAAAAA.toInt()

        const val NEW_SHELL = 300
        const val NEW_ROOT = 301
        const val MORE_CLEAR_TERMINAL = 302
        const val MORE_CLEAR_SCROLLBACK = 303
        const val MORE_SEARCH = 304
        const val MORE_SCROLL_TOP = 305
        const val MORE_SCROLL_BOTTOM = 306
        const val MORE_PACKAGES = 307
        const val MORE_FILES = 308
        const val MORE_FONT_INFO = 309
        const val MORE_FONT_INCR = 310
        const val MORE_FONT_DECR = 311
        const val MORE_RESET_FONT = 312
        const val MORE_SAVE_OUTPUT = 313
        const val MORE_TOGGLE_FULLSCREEN = 314
        const val MORE_SESSION_SETTINGS = 315
        const val MORE_SERVICES = 316
        const val MORE_PROCESSES = 317
        const val MORE_DIAGNOSTICS = 318
        const val MORE_APP_SETTINGS = 319
        const val HEADER_TOGGLE_STATS = 320
        const val HEADER_CLOSE_SESSION = 321
    }
}
