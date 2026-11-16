/*
 * Noxs — original implementation.
 * Opt-in direct shared-storage mount for the guest terminal.
 *
 * Noxs is SAF-first (NoxsStorageBridge): user-selected folders stay
 * command-based and are NEVER presented as POSIX paths. This module adds the
 * OTHER officially supported mode for terminal use cases: Android's
 * user-granted "All files access" (MANAGE_EXTERNAL_STORAGE), where the host
 * app genuinely owns read/write access to /storage/emulated/0. In that mode —
 * and ONLY when the user explicitly enables it — a verified host-side proot
 * bind exposes shared storage inside the guest:
 *
 *     /storage/emulated/0  ->  /sdcard        (proot -b, always read-write)
 *     ~/storage/shared     ->  /sdcard        (guest symlink, Termux layout)
 *     ~/storage/{downloads,dcim,pictures,music,movies} -> matching subdirs
 *
 * Properties:
 *  - default OFF; enabling requires TWO explicit user actions (the Noxs
 *    toggle AND Android's own Settings screen), so it can never happen
 *    silently and can be revoked from either side at any time
 *  - the bind is a real host-side bind mount of app-accessible storage —
 *    never a fake path: when the grant is missing, no bind is added and the
 *    guest sees nothing (PROOT_IGNORE_MISSING_BINDINGS aside, we simply do
 *    not lie)
 *  - the toggle persists across terminal restarts (SharedPreferences)
 */
package com.crossberry.noxs.runtime

import android.content.Context
import android.os.Build
import android.os.Environment
import com.crossberry.noxs.shared.NoxsLog

object NoxsStorageMount {

    const val PREFS = "noxs_android_bridge"
    const val KEY_ENABLED = "direct_mount"
    const val HOST_SHARED = "/storage/emulated/0"
    const val GUEST_SDCARD = "/sdcard"

    /** Process-wide context, set once from NoxsApplication.onCreate. */
    @Volatile
    var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Pure, unit-tested bind decision. */
    fun guestBinds(enabled: Boolean, directReady: Boolean): List<Pair<String, String>> =
        if (enabled && directReady) listOf(HOST_SHARED to GUEST_SDCARD) else emptyList()

    /** Binds for the next session launch (consulted by ProotLauncher). */
    fun guestBinds(): List<Pair<String, String>> {
        val context = appContext ?: return emptyList()
        return guestBinds(isEnabled(context), isDirectStorageReady())
    }

    /**
     * True when Android itself allows this app to read/write shared storage
     * directly: Android 11+ "All files access" (user-approved in Settings),
     * or the legacy READ/WRITE_EXTERNAL_STORAGE grant on Android 10-.
     */
    fun isDirectStorageReady(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            appContext?.let { ctx ->
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, "android.permission.READ_EXTERNAL_STORAGE"
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, "android.permission.WRITE_EXTERNAL_STORAGE"
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } ?: false
        }
    }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** Persists the user's explicit choice; returns the decision that was stored. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        NoxsLog.i(
            "StorageMount",
            if (enabled) "direct shared-storage mount enabled (user opt-in)"
            else "direct shared-storage mount disabled"
        )
        return enabled
    }

    /** User-facing status block (also served over the android bridge). */
    fun statusText(context: Context): String = buildString {
        val ready = isDirectStorageReady()
        val enabled = isEnabled(context)
        appendLine("Direct shared-storage mount")
        appendLine("Android grant: ${if (ready) "all-files access granted" else "not granted"}")
        appendLine("Noxs toggle:   ${if (enabled) "on" else "off"}")
        if (ready && enabled) {
            appendLine("Mount:         $HOST_SHARED -> $GUEST_SDCARD (active for new sessions)")
            appendLine("Guest paths:   ~/storage/shared, ~/storage/downloads, ~/storage/dcim,")
            appendLine("               ~/storage/pictures, ~/storage/music, ~/storage/movies")
        } else {
            appendLine("Mount:         off")
            if (!ready) appendLine("Enable with:   noxs storage grant --direct (opens Android Settings)")
            if (!enabled) appendLine("Enable with:   noxs storage mount")
        }
    }.trimEnd()
}
