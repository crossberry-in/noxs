/*
 * Noxs — original implementation.
 * Multi-environment data model (spec §60, §31, §21, §5, §6, §14, §15).
 *
 * Pure JVM data: no Android imports, so the model, the JSON codec and the
 * state machine are unit-testable without Robolectric. Serialization uses the
 * same strict MiniJson parser as the bootstrap manifest plus a tiny writer.
 */
package com.crossberry.noxs.environments.model

import com.crossberry.noxs.shared.MiniJson

// --------------------------------------------------------------------- enums

/** Package-manager family of an environment (spec §28). */
enum class PackageManagerKind { APT, PACMAN, PKG, NONE }

/** Runtime family: how the userspace is structured (spec §24). */
enum class EnvironmentFamily { DEBIAN, UBUNTU, ARCH, TERMUX_NATIVE }

/** Honest compatibility verdict (spec §5): never faked. */
enum class CompatibilityLevel { SUPPORTED, LIMITED, UNSUPPORTED }

/** Lifecycle of an installed environment (spec §60). */
enum class EnvironmentStatus {
    NOT_INSTALLED, INSTALLING, READY, RUNNING, STOPPED, FAILED, REMOVING, RECOVERY_REQUIRED
}

/** Features a provider can honestly offer (spec §21). The UI hides the rest. */
enum class EnvironmentCapability {
    TERMINAL, FILES, PROCESSES, PACKAGE_MANAGER, SUDO, DOCKER, CODE_SERVER, GUI_KE_X, BACKGROUND_TASKS
}

// -------------------------------------------------------------------- checks

/** One device-compatibility probe result (spec §5). */
data class CompatibilityCheck(
    val label: String,
    val passed: Boolean?,
    val detail: String = ""
) {
    val passedText: String get() = when (passed) { true -> "✓"; false -> "✗"; null -> "⚠" }
}

data class CompatibilityReport(
    val level: CompatibilityLevel,
    val checks: List<CompatibilityCheck>,
    val reasons: List<String> = emptyList()
) {
    val badge: String
        get() = when (level) {
            CompatibilityLevel.SUPPORTED -> "✓ Supported"
            CompatibilityLevel.LIMITED -> "⚠ Limited"
            CompatibilityLevel.UNSUPPORTED -> "✗ Unsupported"
        }
}

// ------------------------------------------------------------------ variants

/** Installable variant (Kali full/minimal/nano — spec §9; others: default). */
data class EnvironmentVariant(
    val id: String,
    val name: String,
    val description: String,
    /** Compressed download size estimate; 0 means "fetch live metadata". */
    val downloadBytesEstimate: Long,
    /** Unpacked size estimate used for the storage pre-check (spec §6). */
    val extractedBytesEstimate: Long,
    val isDefault: Boolean = false
)

// ------------------------------------------------------------------- storage

/**
 * Storage requirement breakdown (spec §6):
 * download + temporary + extracted + safety margin, all checked before any
 * byte is downloaded.
 */
data class StoragePlan(
    val downloadBytes: Long,
    val extractedBytes: Long,
    val tempBytes: Long,
    val marginBytes: Long
) {
    val totalRequired: Long get() = downloadBytes + tempBytes + extractedBytes + marginBytes

    companion object {
        /** Safety margin: 256 MB or 10% of the extracted size, whichever is larger. */
        const val MIN_MARGIN_BYTES: Long = 256L * 1024 * 1024

        fun compute(downloadBytes: Long, extractedBytes: Long, tempBytes: Long = 64L * 1024 * 1024): StoragePlan {
            val margin = maxOf(MIN_MARGIN_BYTES, extractedBytes / 10)
            return StoragePlan(downloadBytes, extractedBytes, tempBytes, margin)
        }
    }
}

data class StorageCheck(val plan: StoragePlan, val availableBytes: Long) {
    val enough: Boolean get() = availableBytes >= plan.totalRequired
}

// ------------------------------------------------------------------- profile

/** Terminal launch profile handed to the runtime (spec §31). */
data class TerminalProfile(
    val environmentId: String,
    val displayName: String,
    val shell: String,
    val user: String,
    val home: String,
    val workingDirectory: String,
    val environmentVariables: Map<String, String>,
    /** Prompt host label (noxs@ubuntu for the Noxs environment — spec §4). */
    val promptHost: String
)

// ------------------------------------------------------------------ metadata

data class EnvironmentMetadata(
    val id: String,
    val providerId: String,
    val displayName: String,
    val version: String,
    val architecture: String,
    val packageManager: PackageManagerKind,
    val runtimeType: String,
    val description: String,
    val family: EnvironmentFamily,
    val homepage: String,
    val recommended: Boolean = false,
    val badges: List<String> = emptyList()
)

// -------------------------------------------------------------- environment

data class Environment(
    val id: String,
    val providerId: String,
    val displayName: String,
    val version: String,
    val architecture: String,
    val variant: String,
    val status: EnvironmentStatus,
    val storagePath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long = 0L
) {
    fun with(status: EnvironmentStatus = this.status, variant: String = this.variant): Environment =
        copy(status = status, variant = variant, updatedAt = System.currentTimeMillis())

    // ------------------------------------------------------------- JSON

    fun toJson(): String {
        val obj = LinkedHashMap<String, Any?>()
        obj["id"] = id
        obj["providerId"] = providerId
        obj["displayName"] = displayName
        obj["version"] = version
        obj["architecture"] = architecture
        obj["variant"] = variant
        obj["status"] = status.name
        obj["storagePath"] = storagePath
        obj["createdAt"] = createdAt
        obj["updatedAt"] = updatedAt
        obj["lastUsedAt"] = lastUsedAt
        return EnvJson.write(obj)
    }

    companion object {
        fun fromJson(text: String): Environment? = runCatching {
            val m = MiniJson.parse(text) as? Map<*, *> ?: return null
            Environment(
                id = m["id"] as? String ?: return null,
                providerId = m["providerId"] as? String ?: "",
                displayName = m["displayName"] as? String ?: "",
                version = m["version"] as? String ?: "",
                architecture = m["architecture"] as? String ?: "",
                variant = m["variant"] as? String ?: "",
                status = EnvironmentStatus.entries.firstOrNull { it.name == m["status"] }
                    ?: EnvironmentStatus.NOT_INSTALLED,
                storagePath = m["storagePath"] as? String ?: "",
                createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0L,
                updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
                lastUsedAt = (m["lastUsedAt"] as? Number)?.toLong() ?: 0L
            )
        }.getOrNull()
    }
}

// ------------------------------------------------------------------ EnvJson

/**
 * Tiny strict JSON writer for Noxs models. Values: String, Number, Boolean,
 * null, Map, List. Reads go through the shared [MiniJson] parser.
 */
object EnvJson {

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(value, sb)
        return sb.toString()
    }

    fun readObject(text: String): Map<String, Any?> =
        (MiniJson.parse(text) as? Map<*, *>)?.mapKeys { it.key.toString() }
            ?: throw IllegalArgumentException("Expected a JSON object")

    fun optString(map: Map<String, Any?>, key: String, def: String = ""): String =
        map[key] as? String ?: def

    fun optLong(map: Map<String, Any?>, key: String, def: Long = 0L): Long =
        (map[key] as? Number)?.toLong() ?: def

    fun optBool(map: Map<String, Any?>, key: String, def: Boolean = false): Boolean =
        (map[key] as? Boolean) ?: def

    fun optStringList(map: Map<String, Any?>, key: String): List<String> =
        (map[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun writeValue(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> {
                if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(value.toString())
            }
            is Float -> writeValue(value.toDouble(), sb)
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    writeValue(v, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(v, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code))
                    else sb.append(c)
            }
        }
        sb.append('"')
    }
}
