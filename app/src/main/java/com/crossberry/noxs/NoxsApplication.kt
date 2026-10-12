/*
 * Noxs — original implementation.
 * Application class: global paths, main-loop dispatcher wiring, and the
 * app-scoped NoxsActivityCenter (single source of truth for background
 * activity — intentionally OUTSIDE any Activity/Service so it survives
 * Activity recreation, configuration changes and service restarts).
 */
package com.crossberry.noxs

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.crossberry.noxs.runtime.AndroidSignaller
import com.crossberry.noxs.runtime.NoxsActivityCenter
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.runtime.NoxsPermissionCenter
import com.crossberry.noxs.runtime.NoxsSetupSession
import com.crossberry.noxs.environments.NoxsEnvironmentManager
import com.crossberry.noxs.shared.NoxsLog
import java.io.File

class NoxsApplication : Application() {

    lateinit var paths: NoxsPaths
        private set

    /**
     * Multi-environment manager (spec §2, §63): provider registry, per-
     * environment storage, active-environment switching and the legacy Debian
     * import. Application-scoped so setup tasks survive Activity recreation.
     */
    val environments: NoxsEnvironmentManager by lazy { NoxsEnvironmentManager(this) }

    lateinit var activityCenter: NoxsActivityCenter
        private set

    /**
     * The setup engine + console session. Application-scoped on purpose:
     * the REAL setup process must survive Activity recreation and background
     * transitions, and the console reconnects to it from any Activity.
     */
    val setupSession: NoxsSetupSession by lazy { NoxsSetupSession(this, paths, activityCenter) }

    /**
     * Noxs Permission Center (Noxs API spec §6-§10): the single authority
     * behind every privileged API call. Application-scoped so decisions are
     * consistent across activities, services and the API bridge.
     */
    val permissionCenter: NoxsPermissionCenter by lazy {
        NoxsPermissionCenter(
            stateDir = File(filesDir, "noxs-settings"),
            androidProbe = NoxsPermissionCenter.defaultProbe(this),
            featureProbe = { permissionId ->
                when (permissionId) {
                    "background.keepawake" -> getSharedPreferences("noxs_settings", MODE_PRIVATE)
                        .getBoolean("keep_awake", true)
                    else -> false
                }
            }
        )
    }

    /**
     * Code-server (VS Code Server) lifecycle manager for the Editor screen.
     * Application-scoped: the managed server process must survive Activity
     * recreation, and duplicate-start prevention is process-wide.
     */
    val codeServer: com.crossberry.noxs.runtime.CodeServerManager by lazy {
        com.crossberry.noxs.runtime.CodeServerManager(
            pathsProvider = { environments.activePaths() },
            launcherProvider = {
                val p = environments.activePaths()
                if (p.isInstalled()) {
                    com.crossberry.noxs.runtime.NoxsRuntimeFactory.launcher(
                        p, com.crossberry.noxs.runtime.NoxsResources(p)
                    )
                } else {
                    null
                }
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        paths = NoxsPaths(this)

        // Route emulator callbacks to the Android main thread.
        val handler = Handler(Looper.getMainLooper())
        MainLoop.handler = handler

        // Activity metadata persists in app-private storage; it holds no
        // terminal output history and no secrets.
        activityCenter = NoxsActivityCenter(AndroidSignaller())
        activityCenter.attachStorage(File(filesDir, "activity/history.tsv"))

        NoxsLog.i("Noxs", "Noxs ${BuildConfig.VERSION_NAME} started (arch=${android.os.Build.SUPPORTED_ABIS.firstOrNull()})")
    }
}

/** Bridges JVM-pure modules (terminal-emulator) to the Android main loop. */
object MainLoop {
    var handler: Handler? = null

    fun post(r: Runnable) {
        val h = handler
        if (h != null) h.post(r) else r.run()
    }
}
