/*
 * Noxs — original implementation.
 * Unified runtime-permission helper for the Android integration layer
 * (Noxs Android Bridge). One place that knows, for every Noxs capability,
 * which Android permissions back it, how to ask for them, and how to report
 * status honestly. Nothing here grants anything by itself: every grant flows
 * through Android's own runtime permission UI or Settings.
 */
package com.crossberry.noxs.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object NoxsAndroidPermissions {

    /** Noxs capabilities exposed to the bridge, with their backing grants. */
    enum class Perm(
        val id: String,
        val label: String,
        /** Runtime permissions to request together (empty = Settings/special). */
        val runtime: List<String>,
        /** Minimum SDK for the runtime grant; lower SDKs use the fallback below. */
        val minSdk: Int = 0,
        val fallbackBelow: List<String> = emptyList()
    ) {
        MEDIA(
            "media",
            "Photos and videos (media library)",
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            ),
            minSdk = 33,
            fallbackBelow = listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ),
        CAMERA(
            "camera",
            "Camera",
            listOf(Manifest.permission.CAMERA)
        ),
        NOTIFICATIONS(
            "notifications",
            "Notifications",
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            minSdk = 33,
            fallbackBelow = emptyList() // pre-13: notifications never need a runtime grant
        )
    }

    private fun grantsFor(perm: Perm): List<String> =
        if (Build.VERSION.SDK_INT >= perm.minSdk) perm.runtime else perm.fallbackBelow

    fun isGranted(context: Context, perm: Perm): Boolean {
        val grants = grantsFor(perm)
        if (grants.isEmpty()) return true
        return grants.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** One-line honest status for the bridge/CLI. */
    fun statusText(context: Context, perm: Perm): String = when {
        isGranted(context, perm) -> "${perm.id}: granted"
        perm.runtime.isNotEmpty() && Build.VERSION.SDK_INT >= perm.minSdk ->
            "${perm.id}: not granted (Noxs asks on next use)"
        perm.fallbackBelow.isNotEmpty() && Build.VERSION.SDK_INT < perm.minSdk ->
            "${perm.id}: not granted (Noxs asks on next use)"
        else -> "${perm.id}: not required on this Android version"
    }

    /** All Noxs capability statuses — the "nx storage permission"-style views. */
    fun statusAllText(context: Context): String =
        Perm.values().joinToString("\n") { statusText(context, it) }

    /** Permissions to hand to RequestMultiplePermissions for [perm] right now. */
    fun requestList(perm: Perm): List<String> = grantsFor(perm)

    /** Android 11+ All-files-access: a Settings screen, never a silent grant. */
    const val ALL_FILES_ACTION = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION

    /**
     * App-specific All-files-access Settings screen (directly shows Noxs).
     * Not every OEM resolves it — callers must fall back to [ALL_FILES_ACTION].
     */
    const val ALL_FILES_APP_ACTION =
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
}
