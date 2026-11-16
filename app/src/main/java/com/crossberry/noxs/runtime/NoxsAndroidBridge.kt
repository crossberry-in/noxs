/*
 * Noxs — original implementation.
 * NX Android Bridge: the secure, well-defined interface between the Noxs
 * Linux terminal and the Android application (Noxs Android Integration).
 *
 * Wire protocol (file-based, same family as the SAF storage bridge):
 *   /var/run/noxs/host/android/requests/<id>     operation + args lines
 *   /var/run/noxs/host/android/responses/<id>    "OK\n<text>" or "ERR\n<text>"
 *   /var/run/noxs/host/android/payloads/<id>           guest -> host bytes
 *   /var/run/noxs/host/android/response-payloads/<id>  host -> guest bytes
 *
 *   Request: line 1 = operation, lines 2-6 = positional args (may be empty).
 *   Payloads are optional and always referenced by the SAME request id.
 *
 * Security model:
 *  - every capability is OPTIONAL and user-authorized through Android's own
 *    runtime permission UI or Settings; nothing is granted silently
 *  - request/response ids are validated ([A-Za-z0-9._-]{1,120}); the control
 *    dirs refuse symlinks (same hardening as the storage bridge)
 *  - device/network reports are non-identifying summaries — no IMSI, no
 *    phone number, no SSID, no credentials
 *  - SMS messages are handed to the system SMS app (composer intent): the
 *    user presses send in their own messaging app, so Noxs holds NO direct
 *    SMS permission at all; the flow still shows an explicit in-app
 *    confirmation dialog with recipient + message first
 *  - operations that need UI (pickers, camera, confirmations) are executed
 *    by the foreground TerminalActivity; the bridge itself never starts
 *    activities from the background
 *
 * Pure decision logic (request parsing, report formatting, capability
 * classification) lives in the [NoxsAndroidBridge] object and is JVM-tested;
 * [NoxsAndroidBridgeHost] is the thin Android shell.
 */
package com.crossberry.noxs.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.NoxsLog
import java.io.File
import java.util.Locale

object NoxsAndroidBridge {

    const val CONTROL_DIR = "/var/run/noxs/host/android"
    const val MAX_LIST_ITEMS = 64

    data class Request(val id: String, val operation: String, val args: List<String>) {
        /** arg1/arg2/... 1-based accessor, empty string when absent. */
        fun arg(n: Int): String = args.getOrNull(n - 1)?.trim().orEmpty()
    }

    data class CommandResult(val ok: Boolean, val output: String)

    // ---------------------------------------------------------- parsing (pure)

    /** Parses request lines; trailing missing args become empty strings. */
    fun parseRequest(id: String, lines: List<String>): Request {
        val operation = lines.firstOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val args = lines.drop(1).map { it.trimEnd('\r') }
        return Request(safeId(id), operation, args)
    }

    fun safeId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid request id" }
        return id
    }

    // ------------------------------------------------- capability set (pure)

    /** Operations that MUST be executed by the foreground activity (UI). */
    val uiOperations: Set<String> = setOf(
        "ui-files", "ui-storage",
        "doc-open", "doc-import", "doc-export", "doc-gallery", "doc-pick",
        "camera", "share-file", "share-text",
        "sms-send", "sms-compose", "sms-contacts",
        "notify-permission", "perm-request", "mount-grant"
    )

    fun needsUi(operation: String): Boolean = operation in uiOperations

    // --------------------------------------------- report builders (pure)

    fun deviceReport(
        model: String, device: String, manufacturer: String, brand: String,
        sdkInt: Int, release: String, abis: List<String>, appVersion: String
    ): String = buildString {
        appendLine("Noxs device report (non-identifying)")
        appendLine("Model:        $model")
        appendLine("Device:       $device ($manufacturer, $brand)")
        appendLine("Android:      $release (API $sdkInt)")
        appendLine("ABIs:         ${abis.joinToString(", ")}")
        appendLine("Noxs version: $appVersion")
    }.trimEnd()

    fun batteryReport(levelPct: Int?, charging: Boolean, powerSource: String, temperatureC: Double?): String =
        buildString {
            appendLine("Battery")
            if (levelPct != null) appendLine("Level: ${levelPct}%")
            appendLine("Charging: ${if (charging) "yes ($powerSource)" else "no (on battery)"}")
            if (temperatureC != null) appendLine("Temperature: %.1f °C".format(temperatureC))
        }.trimEnd()

    fun netReport(connected: Boolean, transport: String, metered: Boolean, validated: Boolean): String =
        buildString {
            appendLine("Network")
            appendLine("Connected: ${if (connected) "yes" else "no"}")
            if (connected) {
                appendLine("Transport: $transport")
                appendLine("Metered:   ${if (metered) "yes (data-capped)" else "no"}")
                appendLine("Internet:  ${if (validated) "validated by Android" else "not validated"}")
            }
            appendLine("No identifying details (network name, device ids, credentials) are ever reported.")
        }.trimEnd()

    fun capacityLine(label: String, totalBytes: Long, freeBytes: Long): String {
        fun gb(bytes: Long) = "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        return "$label: ${gb(freeBytes)} free (${gb(totalBytes)} total)"
    }
}

class NoxsAndroidBridgeHost(private val context: Context, private val paths: NoxsPaths) {

    private val appContext = context.applicationContext

    init {
        ensureControlDirectories()
    }

    /** Recreate only Noxs' IPC folders if a shell replaced one with a symlink. */
    fun ensureControlDirectories(): Boolean {
        var recreated = false
        listOf(
            paths.run, paths.bridgeControl, paths.bridgeRequests,
            paths.bridgeResponses, paths.bridgePayloads, paths.bridgeResponsePayloads
        ).forEach { directory ->
            if (isSymlink(directory)) { directory.delete(); recreated = true }
            if (!directory.isDirectory) { directory.mkdirs(); recreated = true }
            check(directory.isDirectory && !isSymlink(directory)) { "Unable to prepare the Noxs Android bridge" }
        }
        return recreated
    }

    fun cleanupStaleTransfers() {
        val cutoff = System.currentTimeMillis() - 6L * 60L * 60L * 1000L
        listOf(paths.bridgeRequests, paths.bridgeResponses, paths.bridgePayloads, paths.bridgeResponsePayloads)
            .forEach { directory ->
                directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            }
    }

    /** Guest-staged payload for this request (upload direction). */
    fun payloadFile(request: NoxsAndroidBridge.Request): File =
        File(paths.bridgePayloads, NoxsAndroidBridge.safeId(request.id))

    /** Host-staged result bytes for this request (download direction). */
    fun responsePayloadFile(requestId: String): File =
        File(paths.bridgeResponsePayloads, NoxsAndroidBridge.safeId(requestId))

    /** Scratch file for in-progress response payloads (atomic rename follows). */
    fun tempResponsePayloadFile(requestId: String): File {
        val id = NoxsAndroidBridge.safeId(requestId)
        return File(paths.bridgeResponsePayloads, "$id.${java.util.UUID.randomUUID()}.tmp")
    }

    /** Publishes a finished scratch file as the response payload; returns bytes. */
    fun finishResponsePayload(requestId: String, temporary: File): Long {
        val id = NoxsAndroidBridge.safeId(requestId)
        val destination = File(paths.bridgeResponsePayloads, id)
        destination.parentFile?.mkdirs()
        if (isSymlink(destination)) destination.delete()
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("Unable to replace transfer data")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Unable to save transfer data")
        }
        return destination.length()
    }

    /** Streams [input] into the response payload slot atomically; returns bytes. */
    fun stageResponsePayload(requestId: String, input: java.io.InputStream): Long {
        val id = NoxsAndroidBridge.safeId(requestId)
        val destination = File(paths.bridgeResponsePayloads, id)
        val temporary = File(paths.bridgeResponsePayloads, "$id.${java.util.UUID.randomUUID()}.tmp")
        destination.parentFile?.mkdirs()
        try {
            input.use { src -> temporary.outputStream().use { src.copyTo(it) } }
            if (isSymlink(destination)) destination.delete()
            if (destination.exists() && !destination.delete()) throw IllegalStateException("Unable to replace transfer data")
            if (!temporary.renameTo(destination)) throw IllegalStateException("Unable to save transfer data")
            return destination.length()
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
    }

    // ------------------------------------------------------ immediate ops

    /**
     * Executes a non-UI operation. UI operations throw [UiOperationException]
     * so the caller (foreground activity) never mistakes them for immediate.
     */
    fun execute(request: NoxsAndroidBridge.Request): NoxsAndroidBridge.CommandResult {
        if (NoxsAndroidBridge.needsUi(request.operation)) {
            throw UiOperationException(request.operation)
        }
        return try {
            when (request.operation) {
                "" -> err("Empty bridge request")
                "perm-status" -> permStatus(request.arg(1).ifEmpty { "all" })
                "device-info" -> deviceInfo()
                "device-battery" -> batteryInfo()
                "device-storage" -> deviceStorage()
                "net-status" -> netStatus()
                "clip-get" -> clipGet()
                "clip-set" -> clipSet(request)
                "notify-status" -> notifyStatus()
                "notify-post" -> notifyPost(request)
                "sms-status" -> smsStatus()
                "mount-status" -> ok(NoxsStorageMount.statusText(appContext))
                "mount-on" -> mountSet(true)
                "mount-off" -> mountSet(false)
                else -> err("Unknown Android bridge operation: ${request.operation}")
            }
        } catch (e: Exception) {
            NoxsLog.w("AndroidBridge", "${request.operation} failed: ${e.message}")
            err(e.message?.take(240) ?: "Android bridge operation failed")
        }
    }

    class UiOperationException(operation: String) : Exception(operation)

    private fun ok(text: String) = NoxsAndroidBridge.CommandResult(true, text)
    private fun err(text: String) = NoxsAndroidBridge.CommandResult(false, text)

    private fun permStatus(which: String): NoxsAndroidBridge.CommandResult {
        val text = if (which == "all") NoxsAndroidPermissions.statusAllText(appContext)
        else {
            val perm = NoxsAndroidPermissions.Perm.values().firstOrNull { it.id == which }
                ?: return err("Unknown capability: $which (media|camera|sms|notifications)")
            NoxsAndroidPermissions.statusText(appContext, perm)
        }
        return ok(text)
    }

    private fun deviceInfo(): NoxsAndroidBridge.CommandResult = ok(
        NoxsAndroidBridge.deviceReport(
            model = Build.MODEL ?: "unknown",
            device = Build.DEVICE ?: "unknown",
            manufacturer = Build.MANUFACTURER ?: "unknown",
            brand = Build.BRAND ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE ?: "unknown",
            abis = Build.SUPPORTED_ABIS.orEmpty().toList(),
            appVersion = try {
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                info.versionName ?: "unknown"
            } catch (_: Exception) { "unknown" }
        )
    )

    private fun batteryInfo(): NoxsAndroidBridge.CommandResult {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return err("Battery information is unavailable on this device")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC adapter"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unknown source"
        }
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = if (temp != Int.MIN_VALUE) temp / 10.0 else null
        return ok(NoxsAndroidBridge.batteryReport(pct, charging, source, tempC))
    }

    private fun deviceStorage(): NoxsAndroidBridge.CommandResult {
        val lines = mutableListOf<String>()
        runCatching {
            val data = Environment.getDataDirectory()
            val stat = StatFs(data.absolutePath)
            lines += NoxsAndroidBridge.capacityLine("Internal (Noxs app storage)", stat.totalBytes, stat.availableBytes)
        }.onFailure { lines += "Internal: unavailable (${it.message?.take(80)})" }
        if (NoxsStorageMount.isDirectStorageReady()) {
            runCatching {
                val stat = StatFs(NoxsStorageMount.HOST_SHARED)
                lines += NoxsAndroidBridge.capacityLine("Shared storage (/sdcard mount)", stat.totalBytes, stat.availableBytes)
            }
        } else {
            lines += "Shared storage: SAF-selected folders only (run: noxs storage status)"
        }
        return ok(lines.joinToString("\n"))
    }

    private fun netStatus(): NoxsAndroidBridge.CommandResult {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return err("Connectivity service unavailable")
        val network = cm.activeNetwork
            ?: return ok(NoxsAndroidBridge.netReport(false, "-", false, false))
        val caps = cm.getNetworkCapabilities(network)
            ?: return ok(NoxsAndroidBridge.netReport(false, "-", false, false))
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
        return ok(
            NoxsAndroidBridge.netReport(
                connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                transport = transport,
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        )
    }

    private fun clipGet(): NoxsAndroidBridge.CommandResult {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return err("Clipboard service unavailable")
        val text = runCatching {
            cm.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
        }.getOrNull()
        return if (text.isNullOrEmpty()) {
            err("Clipboard is empty or Android blocked the read (foreground app only)")
        } else ok(text.take(4000))
    }

    private fun clipSet(request: NoxsAndroidBridge.Request): NoxsAndroidBridge.CommandResult {
        val text = payloadText(request).ifEmpty { request.arg(1) }
        if (text.isEmpty()) return err("No text provided (pass text or stage a payload)")
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return err("Clipboard service unavailable")
        cm.setPrimaryClip(ClipData.newPlainText("noxs", text.take(10000)))
        return ok("Clipboard set (${text.length} chars)")
    }

    private fun notifyStatus(): NoxsAndroidBridge.CommandResult = ok(
        NoxsAndroidPermissions.statusText(appContext, NoxsAndroidPermissions.Perm.NOTIFICATIONS) +
            "\nChannel: ${if (bridgeChannel() != null) "ready" else "unavailable"}"
    )

    private fun notifyPost(request: NoxsAndroidBridge.Request): NoxsAndroidBridge.CommandResult {
        val granted = ContextCompat.checkSelfPermission(
            appContext, "android.permission.POST_NOTIFICATIONS"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && !granted) {
            return err("Notification permission not granted — run: noxs notification permission")
        }
        val title = request.arg(1).ifEmpty { "Noxs" }
        val text = payloadText(request).ifEmpty { request.arg(2) }.ifEmpty { "Posted from the Noxs terminal" }
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return err("Notification service unavailable")
        val channel = bridgeChannel() ?: return err("Notification channel unavailable")
        val notification = NotificationCompat.Builder(appContext, channel.id)
            .setSmallIcon(R.drawable.ic_stat_noxs)
            .setContentTitle(title.take(80))
            .setContentText(text.take(240))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(2000)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFY_TAG, System.currentTimeMillis().toInt(), notification)
        return ok("Notification posted: ${title.take(60)}")
    }

    private fun smsStatus(): NoxsAndroidBridge.CommandResult = ok(
        buildString {
            appendLine("sms: no SMS permission held — sending goes through YOUR SMS app")
            appendLine("Compose via system SMS app: always available (no permission needed)")
            appendLine("Noxs never reads messages and never sends automatically or in bulk.")
        }.trimEnd()
    )

    private fun mountSet(enabled: Boolean): NoxsAndroidBridge.CommandResult {
        NoxsStorageMount.setEnabled(appContext, enabled)
        if (enabled && !NoxsStorageMount.isDirectStorageReady()) {
            return ok(
                NoxsStorageMount.statusText(appContext) +
                    "\nNote: Android grant is missing — run: noxs storage grant --direct"
            )
        }
        return ok(NoxsStorageMount.statusText(appContext) + "\nApplies to NEW terminal sessions.")
    }

    // ----------------------------------------------------------- helpers

    /** Opens the system SMS app with the message prefilled — the user sends
     *  it there, so Noxs needs no SMS permission at all. */
    fun sendSms(number: String, message: String): NoxsAndroidBridge.CommandResult {
        if (number.isBlank() || message.isEmpty()) return err("Recipient and message are required")
        return runCatching {
            appContext.startActivity(smsComposeIntent(number, message))
            ok("Opened the SMS app — press send there to deliver the message to $number")
        }.getOrElse { err("SMS app unavailable: ${it.message?.take(160) ?: "unknown error"}") }
    }

    /** System SMS composer — final approval ALWAYS happens in the SMS app. */
    fun smsComposeIntent(number: String, message: String): Intent =
        Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${android.net.Uri.encode(number)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Android Sharesheet for text. */
    fun shareTextIntent(text: String, subject: String?): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            "Share via Noxs"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Android Sharesheet for a staged payload file via FileProvider. */
    fun shareFileIntent(payload: File, displayName: String, mimeType: String): Intent {
        val authority = "${appContext.packageName}.files"
        val uri = androidx.core.content.FileProvider.getUriForFile(appContext, authority, payload)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mimeType.ifEmpty { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent.createChooser(share, "Share via Noxs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Copies a staged payload into the share cache (FileProvider root). */
    fun stageForSharing(payload: File): File {
        val dir = File(appContext.cacheDir, "shared").apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}-${payload.name.take(80)}")
        payload.copyTo(target, overwrite = true)
        return target
    }

    fun payloadText(request: NoxsAndroidBridge.Request): String = runCatching {
        payloadFile(request).takeIf { it.isFile && !isSymlink(it) }?.readText(Charsets.UTF_8).orEmpty()
    }.getOrDefault("")

    private fun bridgeChannel(): NotificationChannel? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return existing
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Noxs terminal notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications posted from the Noxs Linux terminal (nx notification)"
        }
        manager.createNotificationChannel(channel)
        return channel
    }

    fun toast(text: String) = Toast.makeText(appContext, text.take(120), Toast.LENGTH_SHORT).show()

    private fun isSymlink(file: File): Boolean = runCatching {
        val mode = android.system.Os.lstat(file.absolutePath).st_mode
        (mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
    }.getOrDefault(false)

    companion object {
        const val CHANNEL_ID = "noxs_android_bridge"
        const val NOTIFY_TAG = "noxs-bridge"
    }
}
