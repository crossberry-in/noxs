/*
 * Noxs — original implementation.
 * NoxsDocsMap: the single source of truth for every documentation URL the
 * product prints or opens (`noxs docs <topic>`, `noxs help <topic>`,
 * NX AI tool hints). Routes are verified against the deployed GitHub Pages
 * sitemap — never guessed. CI/test drift-checks this list; adding a route
 * requires re-verifying it on the live site first.
 *
 * Pure JVM (no Android imports) so the full mapping is unit-testable.
 */
package com.crossberry.noxs.runtime

import java.util.Locale

object NoxsDocsMap {

    /** Documentation base URL (configurable per environment via the bridge). */
    const val BASE = "https://crossberry-in.github.io/nxos-docs/"

    /**
     * topic -> path below [BASE]. Every path was verified live on the
     * deployed site (HTTP 200) when it was added. Keep alphabetical.
     */
    val ROUTES: Map<String, String> = sortedMapOf(
        "ai" to "ai/nx-ai/",
        "ai-api-keys" to "ai/api-keys/",
        "ai-providers" to "ai/providers/",
        "android" to "android/overview/",
        "api" to "reference/cli-reference/",
        "architecture" to "architecture/architecture/",
        "backup" to "environments/backup-restore/",
        "cert-fix" to "troubleshooting/certificate-errors/",
        "changelog" to "changelog/",
        "cli" to "reference/cli-reference/",
        "code" to "devtools/code-server/",
        "devtools" to "devtools/overview/",
        "error-codes" to "reference/error-codes/",
        "faq" to "getting-started/faq/",
        "file-picker" to "android/file-picker/",
        "files" to "files/file-browser/",
        "gallery" to "android/gallery/",
        "getting-started" to "getting-started/introduction/",
        "installation" to "getting-started/installation/",
        "kali" to "environments/kali-installation/",
        "kali-config" to "environments/kali-configuration/",
        "network" to "troubleshooting/network-errors/",
        "notifications" to "android/notifications/",
        "packages" to "packages/package-manager/",
        "permissions" to "android/app-permissions/",
        "plug" to "plugins/plugin-sdk/",
        "rootfs" to "environments/rootfs-management/",
        "sdcard" to "android/external-sdcard/",
        "sdk" to "reference/sdk-reference/",
        "security" to "architecture/security/",
        "sessions" to "terminal/sessions/",
        "shortcuts" to "terminal/shortcuts/",
        "storage" to "files/storage-access/",
        "storage-permissions" to "android/storage-permissions/",
        "switching" to "environments/environment-switching/",
        "terminal" to "terminal/terminal-interface/",
        "troubleshooting" to "troubleshooting/common-questions/",
        "ubuntu" to "environments/ubuntu-installation/",
        "ubuntu-config" to "environments/ubuntu-configuration/"
    )

    /** Friendly aliases printed in help (alias -> canonical topic). */
    private val ALIASES: Map<String, String> = mapOf(
        "commands" to "cli",
        "docs" to "getting-started",
        "nx" to "cli",
        "pkg" to "packages",
        "plugin-sdk" to "plug",
        "plugins" to "plug",
        "vpn" to "network",
        "apt" to "packages"
    )

    /** Resolve a user-typed topic to an absolute documentation URL, or null. */
    fun url(topic: String?): String? {
        val key = topic?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: return BASE
        val canonical = ALIASES[key] ?: key
        val path = ROUTES[canonical] ?: return null
        return BASE + path
    }

    /** Valid topics for error messages (canonical first, aliases skipped). */
    fun topicList(): String = ROUTES.keys.sorted().joinToString(", ")
}
