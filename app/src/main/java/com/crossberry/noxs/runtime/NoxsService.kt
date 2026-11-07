/*
 * Noxs — original implementation.
 * Foreground service: keeps the Noxs runtime (sessions + control socket) alive
 * while used, owns the dedicated activity notification, and routes
 * notification actions (Open / Stop / Stop Noxs) to the Activity Center.
 */
package com.crossberry.noxs.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.ui.PluginStoreActivity
import com.crossberry.noxs.ui.PluginWindowActivity
import com.crossberry.noxs.ui.WebWindowActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class NoxsService : Service() {

    private lateinit var paths: NoxsPaths
    private lateinit var resources: NoxsResources
    private lateinit var launcher: ProotLauncher
    private lateinit var sessions: NoxsSessionManager
    private lateinit var socketServer: NoxsSocketServer
    private lateinit var center: NoxsActivityCenter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L
    private var notifyPending = false
    private var stoppingAll = false

    // Keep-awake: servers started inside a session must stay reachable while
    // the user reads them in a browser (device-global localhost access).
    // Without these locks the CPU/Wi-Fi can doze mid-request or Android can
    // treat the runtime as idle, and "localhost:8080" stops loading even
    // though the server process is alive. Gated by settings.keep_awake.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Guest→Android `nx ow` bridge: watches /var/run/noxs/host/web/requests
    // while the service is alive, so `nx ow <url>` works device-globally —
    // not only while a terminal screen is open.
    private var webWindowBridge: NoxsWebWindowBridge? = null
    private var webObserver: FileObserver? = null
    private var webObservedPath: String? = null

    // Guest→Android `nx plug` bridge (Noxs Plugin Store CLI): requests for
    // open/install/uninstall/enable/disable/update plus catalog snapshots.
    private var pluginBridge: com.crossberry.noxs.runtime.plugins.NoxsPluginBridge? = null
    private var pluginObserver: FileObserver? = null
    private var pluginObservedPath: String? = null
    private var pluginManager: com.crossberry.noxs.runtime.plugins.NoxsPluginManager? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as com.crossberry.noxs.NoxsApplication
        // Multi-environment runtime (spec §32, §62): sessions, the control
        // socket and proot all operate on the ACTIVE environment's storage.
        paths = app.environments.activePaths()
        center = app.activityCenter
        // Security bootstrap (APT/CA/TLS repair) runs here in the service
        // background: it must never gate shell creation. The terminal always
        // clears straight to an active shell while this runs. Only apt-family
        // environments get the Ubuntu APT bootstrap (spec §62 — no hard-coded
        // distro backend).
        resources = NoxsResources(paths)
        launcher = ProotLauncher(paths, resources)
        sessions = NoxsSessionManager(paths, launcher, resources, center)
        socketServer = NoxsSocketServer(paths, sessions)
        RuntimeHolder.set(this, sessions, socketServer)
        if (app.environments.activeFamilyIsApt()) {
            NoxsAptSetup.startIfInstalled(paths, launcher, scope)
        }

        center.stopHandler = { record -> performSafeStop(record) }

        createChannel()
        running = this
        startForegroundCompat(1, buildLiveNotification())
        socketServer.start()
        refreshKeepAwake()
        startWebWindowBridge()
        startPluginBridge()

        // Migration: environments installed by earlier Noxs versions gain the
        // `nx` package system (CLI + templates) without a reinstall. Cheap,
        // idempotent, version-marker guarded — and it never gates the shell.
        scope.launch(Dispatchers.IO) {
            runCatching { RootfsConfigurator.ensureNxPackageSystem(paths) }
                .onFailure { NoxsLog.w("NoxsService", "nx package system not installed: ${it.message}") }
        }

        // Single source of truth: the notification mirrors the Activity Center.
        scope.launch {
            center.records.collect {
                refreshNotificationThrottled()
                refreshKeepAwake()
            }
        }
        NoxsLog.i("NoxsService", "foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ACTIVITY -> {
                val id = intent.getStringExtra(EXTRA_ACTIVITY_ID)
                if (id != null) scope.launch(Dispatchers.IO) { center.requestStop(id) }
            }
            ACTION_STOP_ALL -> stopEverythingGracefully()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * `nx ow` support (Noxs platform spec §15): watch the web bridge request
     * directory for the whole service lifetime. Requests are validated twice
     * (guest CLI + NoxsUrlGuard) before a window opens.
     */
    private fun startWebWindowBridge() {
        val bridge = NoxsWebWindowBridge(paths)
        webWindowBridge = bridge
        bridge.onOpenWebWindow = { _, url ->
            mainHandler.post { openWebWindow(url) }
        }
        runCatching { bridge.ensureControlDirectories() }
            .onFailure { NoxsLog.w("NoxsService", "web bridge unavailable: ${it.javaClass.simpleName}") }
        armWebObserver(force = true)
        scope.launch(Dispatchers.IO) {
            runCatching { bridge.processPendingRequests() }
        }
    }

    private fun armWebObserver(force: Boolean) {
        val path = paths.webRequests.absolutePath
        if (!force && webObserver != null && webObservedPath == path) return
        webObserver?.stopWatching()
        webObserver = null
        webObservedPath = null
        val requestMask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val invalidatedMask = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val observer = object : FileObserver(path, requestMask or invalidatedMask) {
            override fun onEvent(event: Int, name: String?) {
                if ((event and invalidatedMask) != 0) {
                    mainHandler.post {
                        webObserver?.stopWatching()
                        webObserver = null
                        webObservedPath = null
                        runCatching { startWebWindowBridge() }
                    }
                    return
                }
                if (name == null || (event and requestMask) == 0) return
                // Bridge file IO is tiny; a fresh thread keeps the main loop free.
                scope.launch(Dispatchers.IO) {
                    runCatching { webWindowBridge?.processPendingRequests() }
                }
            }
        }
        runCatching { observer.startWatching() }.onSuccess {
            webObserver = observer
            webObservedPath = path
        }.onFailure {
            NoxsLog.w("NoxsService", "cannot watch web bridge: ${it.javaClass.simpleName}")
        }
    }

    private fun openWebWindow(url: String) {
        val intent = Intent(this, WebWindowActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(WebWindowActivity.EXTRA_URL, url)
        runCatching { startActivity(intent) }
            .onFailure { NoxsLog.w("NoxsService", "web window launch failed: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------ nx plug bridge (Plugin Store)

    /**
     * `nx plug` support (Noxs Plugin Store): watch the plugin bridge request
     * directory for the whole service lifetime, publish catalog/installed
     * snapshots for the CLI list/search/info commands, and route mutations
     * through the single NoxsPluginManager used by the store UI.
     */
    private fun startPluginBridge() {
        val app = application as com.crossberry.noxs.NoxsApplication
        val manager = com.crossberry.noxs.runtime.plugins.NoxsPluginManager(
            rootfsHome = paths.rootfsHomeNoxs,
            cacheDir = app.cacheDir,
            appVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "0.0.0",
            guestBinDir = java.io.File(paths.rootfs, "usr/local/bin")
        )
        pluginManager = manager
        val bridge = com.crossberry.noxs.runtime.plugins.NoxsPluginBridge(paths)
        pluginBridge = bridge
        bridge.onOpenStore = {
            mainHandler.post { openPluginStore() }
        }
        bridge.onInstall = { _, pluginId -> handlePluginMutation { manager.install(pluginId); null } }
        bridge.onUninstall = { _, pluginId -> handlePluginMutation { manager.uninstall(pluginId); null } }
        bridge.onEnable = { _, pluginId -> handlePluginMutation { manager.enable(pluginId); null } }
        bridge.onDisable = { _, pluginId -> handlePluginMutation { manager.disable(pluginId); null } }
        bridge.onUpdate = { _, pluginId -> handlePluginMutation { manager.update(pluginId); null } }
        runCatching { bridge.ensureControlDirectories() }
            .onFailure { NoxsLog.w("NoxsService", "plugin bridge unavailable: ${it.javaClass.simpleName}") }
        armPluginObserver(force = true)
        scope.launch(Dispatchers.IO) {
            runCatching { bridge.processPendingRequests() }
            runCatching { refreshPluginSnapshots() }
        }
        // The plugin runtime needs a window launcher and the guest runner.
        com.crossberry.noxs.runtime.plugins.NoxsPluginRuntime.windowLauncher =
            { windowId, pluginId, title, width, height ->
                mainHandler.post { openPluginWindow(windowId, pluginId, title, width, height) }
            }
        com.crossberry.noxs.runtime.plugins.NoxsPluginRuntime.guestRunner = { command ->
            runBlockingGuestCommand(command)
        }
    }

    private fun armPluginObserver(force: Boolean) {
        val path = paths.pluginRequests.absolutePath
        if (!force && pluginObserver != null && pluginObservedPath == path) return
        pluginObserver?.stopWatching()
        pluginObserver = null
        pluginObservedPath = null
        val requestMask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        val invalidatedMask = FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
        val observer = object : FileObserver(path, requestMask or invalidatedMask) {
            override fun onEvent(event: Int, name: String?) {
                if ((event and invalidatedMask) != 0) {
                    mainHandler.post {
                        pluginObserver?.stopWatching()
                        pluginObserver = null
                        pluginObservedPath = null
                        runCatching { startPluginBridge() }
                    }
                    return
                }
                if (name == null || (event and requestMask) == 0) return
                scope.launch(Dispatchers.IO) {
                    runCatching { pluginBridge?.processPendingRequests() }
                }
            }
        }
        runCatching { observer.startWatching() }.onSuccess {
            pluginObserver = observer
            pluginObservedPath = path
        }.onFailure {
            NoxsLog.w("NoxsService", "cannot watch plugin bridge: ${it.javaClass.simpleName}")
        }
    }

    /** Runs one plugin mutation and turns failures into stable messages. */
    private fun handlePluginMutation(
        block: () -> Unit?
    ): Pair<Boolean, String> = try {
        block()
        scope.launch(Dispatchers.IO) { runCatching { refreshPluginSnapshots() } }
        true to "done"
    } catch (e: com.crossberry.noxs.runtime.plugins.NoxsPluginInstaller.InstallException) {
        false to (e.message ?: "The request could not be completed")
    } catch (e: com.crossberry.noxs.runtime.plugins.NoxsPluginManager.ManagerException) {
        false to (e.message ?: "The request could not be completed")
    } catch (t: Throwable) {
        NoxsLog.w("NoxsService", "plugin mutation failed: ${t.javaClass.simpleName}")
        false to "The request could not be completed"
    }

    /** Catalog + installed snapshots for the CLI (list/search/info). */
    private fun refreshPluginSnapshots() {
        val manager = pluginManager ?: return
        val bridge = pluginBridge ?: return
        runCatching {
            bridge.writeSnapshots(manager.storeCards(refresh = false), manager.installed())
        }
    }

    private fun openPluginStore() {
        val intent = Intent(this, PluginStoreActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { NoxsLog.w("NoxsService", "plugin store launch failed: ${it.javaClass.simpleName}") }
    }

    private fun openPluginWindow(windowId: String, pluginId: String, title: String, width: Int, height: Int) {
        val intent = Intent(this, PluginWindowActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PluginWindowActivity.EXTRA_WINDOW_ID, windowId)
            .putExtra(PluginWindowActivity.EXTRA_PLUGIN_ID, pluginId)
            .putExtra(PluginWindowActivity.EXTRA_TITLE, title)
            .putExtra(PluginWindowActivity.EXTRA_WIDTH, width)
            .putExtra(PluginWindowActivity.EXTRA_HEIGHT, height)
        runCatching { startActivity(intent) }
            .onFailure { NoxsLog.w("NoxsService", "plugin window launch failed: ${it.javaClass.simpleName}") }
    }

    /** Blocking guest command runner for noxs.terminal.exec (worker thread). */
    private fun runBlockingGuestCommand(command: String): com.crossberry.noxs.runtime.plugins.PluginExecResult {
        return try {
            val argv = launcher.oneShotArgv(
                listOf("/bin/bash", "-lc", command),
                asRoot = false
            )
            val pb = ProcessBuilder(argv).redirectErrorStream(false)
            launcher.applyEnvTo(pb)
            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (finished) {
                com.crossberry.noxs.runtime.plugins.PluginExecResult(process.exitValue(), stdout, stderr)
            } else {
                process.destroyForcibly()
                com.crossberry.noxs.runtime.plugins.PluginExecResult(124, stdout, "timeout")
            }
        } catch (e: Exception) {
            NoxsLog.w("NoxsService", "plugin exec failed: ${e.javaClass.simpleName}")
            com.crossberry.noxs.runtime.plugins.PluginExecResult(126, "", "command failed")
        }
    }

    override fun onDestroy() {
        releaseKeepAwake()
        webObserver?.stopWatching()
        webObserver = null
        webWindowBridge = null
        pluginObserver?.stopWatching()
        pluginObserver = null
        pluginBridge = null
        pluginManager = null
        com.crossberry.noxs.runtime.plugins.NoxsPluginRuntime.shutdown()
        socketServer.stop()
        sessions.closeAll()
        // The service is going away: reconcile records so the Activity Center
        // never shows RUNNING work that no longer exists.
        center.active().forEach { if (it.kind != NoxsActivityKind.SESSION) center.markStopped(it.activityId) }
        center.stopHandler = null
        RuntimeHolder.clear()
        running = null
        scope.cancel()
        NoxsLog.i("NoxsService", "foreground service destroyed")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ stop

    /** Safe per-activity stop. Sessions go through the session manager. */
    private fun performSafeStop(record: NoxsActivityRecord) {
        when (record.kind) {
            NoxsActivityKind.SESSION -> {
                val entry = sessions.sessions.value.firstOrNull { it.label == record.sessionId }
                if (entry != null) {
                    sessions.closeSession(entry)
                } else {
                    center.markStopped(record.activityId)
                }
            }
            // The setup engine is cooperative: request a cancel and let the
            // session itself settle the record (COMPLETED/FAILED/CANCELLED).
            NoxsActivityKind.SETUP -> {
                (application as com.crossberry.noxs.NoxsApplication).setupSession.cancel()
            }
            else -> {
                record.pid?.let { center.stopProcessTree(it) }
                center.markStopped(record.activityId)
            }
        }
    }

    /**
     * Stop Noxs: sessions first (their exit flow settles the records), then
     * every other tracked activity with SIGTERM→wait→SIGKILL, then shutdown.
     */
    private fun stopEverythingGracefully() {
        if (stoppingAll) return
        stoppingAll = true
        refreshKeepAwake()
        NoxsLog.i("NoxsService", "graceful stop requested")
        scope.launch(Dispatchers.IO) {
            sessions.closeAll()
            center.active().forEach { record ->
                if (record.kind != NoxsActivityKind.SESSION) {
                    center.requestStop(record.activityId)
                }
            }
            delay(400)
            withMain { stopSelf() }
        }
    }

    private fun withMain(block: () -> Unit) = mainHandler.post(block)

    // ----------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_activity), NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows what Noxs is running in the background" }
        )
    }

    /** Throttle so bursts of terminal output never spam notification updates. */
    private fun refreshNotificationThrottled() {
        val now = System.currentTimeMillis()
        val since = now - lastNotifyAt
        if (since >= MIN_NOTIFY_INTERVAL_MS) {
            lastNotifyAt = now
            postNotification()
        } else if (!notifyPending) {
            notifyPending = true
            mainHandler.postDelayed({
                notifyPending = false
                lastNotifyAt = System.currentTimeMillis()
                postNotification()
            }, MIN_NOTIFY_INTERVAL_MS - since)
        }
    }

    private fun postNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildLiveNotification())
    }

    internal fun buildLiveNotification(): Notification {
        val active = center.active()
        val top = active.maxByOrNull { it.startedAt }
        val sessionCount = if (this::sessions.isInitialized) sessions.sessions.value.size else 0

        val text = when {
            stoppingAll -> getString(R.string.noxs_shutting_down)
            top == null ->
                if (sessionCount > 0) getString(R.string.notif_sessions, sessionCount)
                else getString(R.string.notif_idle)
            top.status == NoxsActivityStatus.STARTING -> getString(R.string.activity_status_starting) + " " + top.title
            top.status == NoxsActivityStatus.STOPPING -> getString(R.string.activity_status_stopping) + " " + top.title
            active.size == 1 -> getString(R.string.notif_running_one, top.title)
            else -> getString(R.string.notif_running_many, top.title, active.size - 1)
        }

        val focusIntent = Intent(this, com.crossberry.noxs.ui.TerminalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            top?.let { putExtra(EXTRA_FOCUS_ACTIVITY, it.activityId) }
        }
        val open = PendingIntent.getActivity(
            this, 1, focusIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_noxs)
            .setContentTitle(getString(R.string.notif_short_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        top?.progress?.let { p ->
            builder.setProgress(100, p, false)
        } ?: builder.setProgress(0, 0, top != null)

        if (top != null && !stoppingAll) {
            val stopIntent = Intent(this, NoxsService::class.java).apply {
                action = ACTION_STOP_ACTIVITY
                putExtra(EXTRA_ACTIVITY_ID, top.activityId)
            }
            val stopPi = PendingIntent.getService(
                this, 2, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.notif_action_stop), stopPi)
        }
        return builder.build()
    }

    // ------------------------------------------------------------- keep awake

    private fun keepAwakeEnabled(): Boolean =
        getSharedPreferences("noxs_settings", Context.MODE_PRIVATE).getBoolean("keep_awake", true)

    /** Hold while any session or tracked activity is running, release when idle. */
    internal fun refreshKeepAwake() {
        val shouldHold = keepAwakeEnabled() && !stoppingAll &&
            ((this::sessions.isInitialized && sessions.sessions.value.isNotEmpty()) ||
                (this::center.isInitialized && center.active().isNotEmpty()))
        if (!shouldHold) {
            releaseKeepAwake()
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noxs:runtime")
                .apply { setReferenceCounted(false) }
        }
        // Long cap: re-armed on every activity/notification change; a lock that
        // lapses is re-acquired on the next refresh, never silently forgotten.
        runCatching { wakeLock?.acquire(6 * 60 * 60 * 1000L) }
            .onFailure { NoxsLog.w("NoxsService", "wake lock unavailable: ${it.message}") }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "noxs:runtime")
                .apply { setReferenceCounted(false) }
        }
        runCatching { wifiLock?.acquire() }
            .onFailure { NoxsLog.w("NoxsService", "wifi lock unavailable: ${it.message}") }
    }

    private fun releaseKeepAwake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "noxs_activity"
        private const val NOTIFICATION_ID = 1
        private const val MIN_NOTIFY_INTERVAL_MS = 800L

        const val ACTION_STOP_ALL = "com.crossberry.noxs.STOP_ALL"
        const val ACTION_STOP_ACTIVITY = "com.crossberry.noxs.STOP_ACTIVITY"
        const val EXTRA_ACTIVITY_ID = "activity_id"
        const val EXTRA_FOCUS_ACTIVITY = "focus_activity_id"

        @Volatile
        private var running: NoxsService? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NoxsService::class.java))
        }

        /**
         * Restart so the runtime rebuilds against the newly active environment
         * (spec §32). Sessions are closed by onDestroy — the confirm dialog in
         * the environment manager warns the user before switching.
         */
        fun restart(context: Context) {
            context.stopService(Intent(context, NoxsService::class.java))
            context.startForegroundService(Intent(context, NoxsService::class.java))
        }

        /**
         * Graceful "Stop Noxs": routed through the running service so sessions,
         * tracked processes, the service and the notification shut down in the
         * correct order. Never touches unrelated Android apps.
         */
        fun requestStopAll(context: Context) {
            val intent = Intent(context, NoxsService::class.java).setAction(ACTION_STOP_ALL)
            if (running != null) {
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
        }

        /** Called by the session manager to refresh the notification. */
        fun notifySessionsChanged(count: Int) {
            running?.let {
                it.refreshNotificationThrottled()
                it.refreshKeepAwake()
            }
        }

        /** Focus payload for notification Open taps. */
        fun focusIntent(context: Context, activityId: String?): Intent =
            Intent(context, com.crossberry.noxs.ui.TerminalActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                activityId?.let { putExtra(EXTRA_FOCUS_ACTIVITY, it) }
            }
    }
}

/** Process-wide access to the active runtime (used by manager screens). */
object RuntimeHolder {
    var sessions: NoxsSessionManager? = null
        private set
    var socketServer: NoxsSocketServer? = null
        private set
    private var service: NoxsService? = null

    fun set(service: NoxsService, sessions: NoxsSessionManager, socketServer: NoxsSocketServer) {
        this.service = service
        this.sessions = sessions
        this.socketServer = socketServer
    }

    fun clear() {
        service = null
        sessions = null
        socketServer = null
    }

    /** Re-evaluate the keep-awake locks (e.g. after the user flips the setting). */
    fun refreshKeepAwake() {
        service?.refreshKeepAwake()
    }
}
