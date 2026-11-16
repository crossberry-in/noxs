/*
 * Noxs — original implementation.
 * NoxsPermissionCatalog: the single source of truth for every Noxs
 * permission (Noxs API spec §7-§8). Only permissions that are actually
 * implemented and permission-controlled are listed here — the API bridge
 * routes every privileged operation through one of these ids.
 *
 * Levels (spec §8):
 *   REQUIRED           — the platform cannot function without it
 *   OPTIONAL           — user-selectable, graceful degradation
 *   FEATURE_DEPENDENT  — only meaningful while the matching feature is on
 *
 * States (spec §8): Allowed / Not granted / Denied / Restricted / Not supported.
 */
package com.crossberry.noxs.runtime

object NoxsPermissionCatalog {

    enum class Level { REQUIRED, OPTIONAL, FEATURE_DEPENDENT }

    data class Permission(
        val id: String,
        val group: String,
        val level: Level,
        val description: String,
        /** Real Android runtime permission backing this Noxs permission. */
        val androidPermission: String? = null,
        /** Android settings screen for special access (verified after return). */
        val settingsIntentAction: String? = null,
        /** Minimum API level that supports this permission; null = all. */
        val minSdk: Int? = null
    )

    const val GROUP_NOTIFICATIONS = "notifications"
    const val GROUP_STORAGE = "storage"
    const val GROUP_BACKGROUND = "background"
    const val GROUP_DEVICE = "device"
    const val GROUP_SYSTEM = "system"
    const val GROUP_ENVIRONMENT = "environment"
    const val GROUP_ANDROID = "android"

    val GROUPS: List<String> = listOf(
        GROUP_NOTIFICATIONS,
        GROUP_STORAGE,
        GROUP_ANDROID,
        GROUP_BACKGROUND,
        GROUP_DEVICE,
        GROUP_SYSTEM,
        GROUP_ENVIRONMENT
    )

    val ALL: List<Permission> = listOf(
        Permission(
            id = "notifications.show",
            group = GROUP_NOTIFICATIONS,
            level = Level.OPTIONAL,
            description = "Show progress and control for long-running tasks such as installs and downloads.",
            androidPermission = "android.permission.POST_NOTIFICATIONS"
        ),
        Permission(
            id = "storage.read",
            group = GROUP_STORAGE,
            level = Level.OPTIONAL,
            description = "Read files inside the folders the user explicitly grants to Noxs (SAF picker — never all-files access).",
            androidPermission = null
        ),
        Permission(
            id = "storage.write",
            group = GROUP_STORAGE,
            level = Level.OPTIONAL,
            description = "Save files into the folders the user explicitly grants to Noxs (SAF picker — never all-files access).",
            androidPermission = null
        ),
        Permission(
            id = "background.tasks",
            group = GROUP_BACKGROUND,
            level = Level.OPTIONAL,
            description = "Keep package installs, downloads and environment setup running while Noxs is in the background.",
            androidPermission = "android.permission.FOREGROUND_SERVICE"
        ),
        Permission(
            id = "background.keepawake",
            group = GROUP_BACKGROUND,
            level = Level.FEATURE_DEPENDENT,
            description = "Keep CPU and Wi-Fi awake while servers started in the terminal serve requests.",
            androidPermission = "android.permission.WAKE_LOCK"
        ),
        Permission(
            id = "device.vibrate",
            group = GROUP_DEVICE,
            level = Level.OPTIONAL,
            description = "Subtle vibration feedback on task completion.",
            androidPermission = "android.permission.VIBRATE"
        ),
        Permission(
            id = "system.info",
            group = GROUP_SYSTEM,
            level = Level.OPTIONAL,
            description = "Allow packages to read basic, non-identifying system information.",
            androidPermission = null
        ),
        Permission(
            id = "terminal.read",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Allow packages to read terminal output.",
            androidPermission = null
        ),
        Permission(
            id = "terminal.subscribe",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Allow packages to receive live terminal updates.",
            androidPermission = null
        ),
        Permission(
            id = "terminal.write",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Allow packages to type into the active terminal session.",
            androidPermission = null
        ),
        Permission(
            id = "terminal.execute",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Allow packages to run commands in the environment. Never granted automatically.",
            androidPermission = null
        ),
        Permission(
            id = "logs.read",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Allow packages to read filtered Noxs logs.",
            androidPermission = null
        ),
        Permission(
            id = "logs.subscribe",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Allow packages to receive live log updates.",
            androidPermission = null
        ),
        Permission(
            id = "storage.package",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Per-package isolated storage inside the Noxs environment.",
            androidPermission = null
        ),
        Permission(
            id = "package.read",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Allow packages to query installed NX package metadata.",
            androidPermission = null
        ),
        Permission(
            id = "package.install",
            group = GROUP_ENVIRONMENT,
            level = Level.OPTIONAL,
            description = "Allow packages to request NX package installs. Every install stays user-visible.",
            androidPermission = null
        ),
        Permission(
            id = "window.create",
            group = GROUP_SYSTEM,
            level = Level.OPTIONAL,
            description = "Allow packages to open Noxs floating windows.",
            androidPermission = null
        ),
        Permission(
            id = "window.control",
            group = GROUP_SYSTEM,
            level = Level.OPTIONAL,
            description = "Allow packages to move, resize, minimize or close windows they own.",
            androidPermission = null
        ),
        Permission(
            id = "web.open",
            group = GROUP_SYSTEM,
            level = Level.OPTIONAL,
            description = "Allow packages to open websites inside the Noxs browser window.",
            androidPermission = null
        ),
        Permission(
            id = "web.control",
            group = GROUP_SYSTEM,
            level = Level.OPTIONAL,
            description = "Allow packages to navigate web windows they own.",
            androidPermission = null
        )
    )

    /** The network row is informational only — never an Android runtime permission. */
    const val NETWORK_INFO_ID = "network.informational"

    // ---- NX Android Integration (every grant is explicit and revocable) ----

    val ANDROID_INTEGRATION: List<Permission> = listOf(
        Permission(
            id = "android.media",
            group = GROUP_ANDROID,
            level = Level.OPTIONAL,
            description = "Import photos/videos through the Android Photo Picker or SAF (Android 13+ media permissions, asked on use).",
            androidPermission = "android.permission.READ_MEDIA_IMAGES",
            minSdk = 33
        ),
        Permission(
            id = "android.camera",
            group = GROUP_ANDROID,
            level = Level.OPTIONAL,
            description = "Open the native camera for noxs camera; captures are handed to the terminal only after capture.",
            androidPermission = "android.permission.CAMERA"
        ),
        Permission(
            id = "android.sms.send",
            group = GROUP_ANDROID,
            level = Level.OPTIONAL,
            description = "Hands the message to your own SMS app — you press send there. Never automatic, never bulk; Noxs requests no SMS permission at all."
        ),
        Permission(
            id = "android.storage.mount",
            group = GROUP_ANDROID,
            level = Level.OPTIONAL,
            description = "OPT-IN direct shared-storage mount (/sdcard inside the guest) — requires Android's user-granted all-files access in Settings; default off; revocable any time.",
            settingsIntentAction = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"
        )
    )

    fun byId(id: String): Permission? = ALL.firstOrNull { it.id == id } ?: ANDROID_INTEGRATION.firstOrNull { it.id == id }

    fun inGroup(group: String): List<Permission> = ALL.filter { it.group == group } + ANDROID_INTEGRATION.filter { it.group == group }
}
