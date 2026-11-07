/*
 * Noxs — original implementation.
 * Ubuntu-first migration manager (Ubuntu-first spec §9).
 *
 * Existing Noxs installations ship a Debian 12 (bookworm) userspace. The
 * Ubuntu-first migration NEVER silently erases user data:
 *
 *   1. DETECT — the install marker decides: a marker with suite=bookworm (or
 *      without distro=ubuntu) identifies a legacy Debian environment.
 *   2. BACK UP — /home/noxs and /root (the user's real data) plus
 *      /etc/noxs/resources.conf are archived into app-private storage
 *      (files/noxs/migration/) via a proot one-shot tar, so the user can
 *      restore their files into the Ubuntu environment afterwards.
 *   3. MIGRATE — only an explicit user action starts the Ubuntu install; the
 *      legacy environment keeps working until the user removes it through
 *      the explicit, confirmed removal path (NoxsEnvironmentManager.remove).
 *
 * Android system partitions are never touched; no Android root is required.
 * The detection/plan logic is pure Kotlin and JVM-tested; the tar execution
 * runs on-device through ProotLauncher (device flow verification is pending
 * the M9 automation milestone — honest status: implemented with limitations).
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import java.io.File

/** Immutable migration plan (JVM-testable, no Android imports). */
data class MigrationPlan(
    val legacyDistro: String,
    val backupEntries: List<String>,
    val backupFileName: String
)

object NoxsMigrationManager {

    /** The rootfs-relative paths that carry USER data (never the distro itself). */
    val USER_DATA_ENTRIES: List<String> = listOf(
        "home/noxs",
        "root",
        "etc/noxs/resources.conf"
    )

    /**
     * Reads the install marker and classifies the environment. Returns
     * "ubuntu" for a fresh Ubuntu-first install, "debian" for a legacy
     * Debian install, "unknown" when the marker is missing or unreadable.
     */
    fun classifyInstall(installMarker: File): String {
        val content = runCatching { installMarker.readText() }.getOrNull() ?: return "unknown"
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val value = { key: String ->
            lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()
        }
        // distro=ubuntu wins; an explicit suite=bookworm or a missing distro
        // key with any suite is legacy Debian.
        when (value("distro")) {
            "ubuntu" -> return "ubuntu"
            "debian" -> return "debian"
        }
        return when (value("suite")) {
            null -> "unknown"
            "noble" -> "ubuntu"
            else -> "debian"
        }
    }

    /** True when the given install is a legacy Debian userspace. */
    fun isLegacyDebianInstall(installMarker: File): Boolean =
        classifyInstall(installMarker) == "debian"

    /**
     * Builds the backup plan for a legacy install. Only entries that actually
     * exist on disk are included (honest plan — no empty promises), and the
     * backup file name carries the UTC timestamp for repeat migrations.
     */
    fun planBackup(paths: NoxsPaths, nowMs: Long): MigrationPlan {
        val distro = classifyInstall(paths.installMarker)
        val existing = USER_DATA_ENTRIES.filter { entry ->
            val f = File(paths.rootfs, entry)
            f.exists() && (f.isDirectory || f.isFile)
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(nowMs))
        return MigrationPlan(
            legacyDistro = distro,
            backupEntries = existing,
            backupFileName = "debian-home-backup-$stamp.tar.gz"
        )
    }

    /**
     * Archives the planned user data through a proot one-shot tar into the
     * migration directory. The archive stays in app-private storage — the
     * user can restore it into the Ubuntu environment with `tar -xzf`.
     */
    fun performBackup(
        paths: NoxsPaths,
        launcher: ProotLauncher,
        onLog: (String) -> Unit
    ): Result<File> {
        val plan = planBackup(paths, System.currentTimeMillis())
        if (plan.backupEntries.isEmpty()) {
            return Result.failure(IllegalStateException("nothing to back up — no user data found in the legacy rootfs"))
        }
        val migrationDir = File(paths.base, "migration").apply { mkdirs() }
        val archive = File(migrationDir, plan.backupFileName)
        val args = mutableListOf("/bin/tar", "-czf", "/noxs-backup/out.tar.gz", "-C", "/")
        args.addAll(plan.backupEntries)
        val argv = try {
            launcher.buildArgv(
                ProotLauncher.Options(
                    loginAsRoot = true,
                    workDir = "/root",
                    extraBinds = listOf(migrationDir.absolutePath to "/noxs-backup")
                ),
                oneShotCmd = args
            )
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return try {
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1"))
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0 || !archive.isFile || archive.length() == 0L) {
                Result.failure(IllegalStateException("backup tar exited $code: ${output.takeLast(400)}"))
            } else {
                onLog("user data backed up: ${archive.name} (${archive.length()} bytes)")
                NoxsLog.i("Migration", "legacy backup complete: ${archive.absolutePath}")
                Result.success(archive)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
