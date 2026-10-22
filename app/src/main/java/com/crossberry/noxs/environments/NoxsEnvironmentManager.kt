/*
 * Noxs — original implementation.
 * NoxsEnvironmentManager (spec §2, §32, §33, §34, §50, §63, §64):
 * provider discovery, environment registry, active-environment switching,
 * legacy Debian import and safe removal.
 *
 * The registry lives in app-private storage (environments/registry.json).
 * Existing Noxs Debian installations are detected and imported on upgrade —
 * never deleted, never reinstalled (spec §63).
 */
package com.crossberry.noxs.environments

import android.content.Context
import com.crossberry.noxs.environments.model.Environment
import com.crossberry.noxs.environments.model.EnvironmentStatus
import com.crossberry.noxs.environments.model.EnvJson
import com.crossberry.noxs.environments.model.PackageManagerKind
import com.crossberry.noxs.environments.model.TerminalProfile
import com.crossberry.noxs.environments.providers.ProviderRegistry
import com.crossberry.noxs.environments.setup.SetupTaskManager
import com.crossberry.noxs.runtime.NoxsPaths
import com.crossberry.noxs.shared.NoxsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class NoxsEnvironmentManager(private val context: Context) {

    /** Legacy (pre-multi-environment) Noxs base — the migration source (spec §63). */
    val legacyPaths: NoxsPaths = NoxsPaths(context)

    val environmentsRoot: File = File(context.filesDir, "environments")

    private val registryFile = File(environmentsRoot, "registry.json")
    private val activeFile = File(environmentsRoot, "active.txt")

    val providers: List<EnvironmentProvider> by lazy {
        ProviderRegistry.createAll(termuxInstalled = isTermuxInstalled())
    }

    private val _environments = MutableStateFlow<List<Environment>>(emptyList())
    val environments: StateFlow<List<Environment>> = _environments

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId

    val taskManager: SetupTaskManager = SetupTaskManager(
        context = context,
        environmentsRoot = environmentsRoot,
        pathsFactory = { base -> NoxsPaths(base, legacyPaths.nativeLibDir) },
        onEnvironmentChanged = { upsert(it) },
        onEnvironmentRemoved = { removeRecord(it) }
    )

    init {
        environmentsRoot.mkdirs()
        loadRegistry()
        importLegacyInstall()
        taskManager.scanForRecovery()
        refreshStatuses()
    }

    // ---------------------------------------------------------------- paths

    /** Paths of the active environment, or the legacy base when nothing else exists. */
    fun activePaths(): NoxsPaths {
        val id = _activeId.value
        val env = _environments.value.firstOrNull { it.id == id && it.status == EnvironmentStatus.READY }
        return if (env != null) pathsFor(env) else legacyPaths
    }

    fun pathsFor(env: Environment): NoxsPaths = NoxsPaths(File(env.storagePath), legacyPaths.nativeLibDir)

    fun environmentFor(id: String): Environment? = _environments.value.firstOrNull { it.id == id }

    /** Environment family of the active environment, or null for the legacy Debian. */
    fun activeFamilyIsApt(): Boolean {
        val id = _activeId.value ?: return true // legacy base is Debian
        val env = environmentFor(id) ?: return true
        if (env.storagePath == legacyPaths.base.absolutePath) return true
        return runCatching {
            val family = EnvJson.readObject(File(env.storagePath, "environment.json").readText())["family"]
            family != "ARCH"
        }.getOrDefault(true)
    }

    // ------------------------------------------------------------- registry

    @Synchronized
    fun upsert(environment: Environment) {
        val current = _environments.value.filterNot { it.id == environment.id } + environment
        val sorted = sortedEnvironments(current)
        _environments.value = sorted
        persistRegistry()
    }

    @Synchronized
    fun removeRecord(id: String) {
        _environments.value = _environments.value.filterNot { it.id == id }
        if (_activeId.value == id) {
            _activeId.value = _environments.value.firstOrNull { it.status == EnvironmentStatus.READY }?.id
            persistActive()
        }
        persistRegistry()
    }

    private fun sortedEnvironments(list: List<Environment>) =
        list.sortedBy { it.id }

    private fun persistRegistry() {
        runCatching {
            registryFile.writeText(
                EnvJson.write(_environments.value.map { it.toJson() })
            )
        }
    }

    private fun loadRegistry() {
        val list = runCatching {
            val raw = EnvJson.readObject(registryFile.readText())["environments"] as? List<*>
            raw?.mapNotNull { (it as? String)?.let { s -> Environment.fromJson(s) } }
        }.getOrNull().orEmpty()
        _environments.value = sortedEnvironments(list)
        _activeId.value = runCatching {
            activeFile.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    // ----------------------------------------------------------- migration

    /**
     * Existing Noxs Debian installation detected on upgrade (spec §63): import
     * it as an environment, set it active, no reinstall required.
     */
    private fun importLegacyInstall() {
        if (!legacyPaths.isInstalled()) return
        val existing = _environments.value.firstOrNull { it.id == LEGACY_DEBIAN_ID }
        if (existing != null) return
        val imported = Environment(
            id = LEGACY_DEBIAN_ID,
            providerId = "debian",
            displayName = "Debian 12",
            version = "bookworm",
            architecture = "arm64-v8a",
            variant = "bookworm",
            status = EnvironmentStatus.READY,
            storagePath = legacyPaths.base.absolutePath,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis()
        )
        upsert(imported)
        if (_activeId.value == null) setActive(LEGACY_DEBIAN_ID)
        NoxsLog.i("EnvManager", "Existing Debian environment imported (no reinstall required)")
    }

    /** Refreshes RUNNING/STOPPED statuses against on-disk reality (honest values). */
    private fun refreshStatuses() {
        val updated = _environments.value.map { env ->
            when (env.status) {
                EnvironmentStatus.INSTALLING ->
                    if (!File(env.storagePath, "environment.json").isFile && env.id != LEGACY_DEBIAN_ID)
                        env.with(status = EnvironmentStatus.RECOVERY_REQUIRED) else env
                else -> env
            }
        }
        _environments.value = updated
    }

    // ------------------------------------------------------------ switching

    /**
     * Switches the active environment (spec §32): never reinstalls. The caller
     * restarts the runtime service so sessions spawn against the new paths.
     */
    @Synchronized
    fun setActive(id: String): Boolean {
        val env = environmentFor(id) ?: return false
        if (env.status != EnvironmentStatus.READY) return false
        _activeId.value = id
        persistActive()
        upsert(env.copy(lastUsedAt = System.currentTimeMillis()))
        NoxsLog.i("EnvManager", "active environment -> $id")
        return true
    }

    private fun persistActive() {
        runCatching {
            _activeId.value?.let { activeFile.writeText(it + "\n") }
                ?: run { activeFile.delete() }
        }
    }

    // ------------------------------------------------------------- removal

    /** Explicit, user-confirmed removal only (spec §35). Never deletes silently. */
    @Synchronized
    fun remove(id: String): Boolean {
        val env = environmentFor(id) ?: return false
        if (taskManager.isRunning(id)) return false
        if (env.storagePath.startsWith(legacyPaths.base.absolutePath)) {
            // Legacy Debian removal also clears the legacy markers so the
            // next start routes through the environment picker honestly.
            runCatching { legacyPaths.installMarker.delete() }
        }
        taskManager.removeEnvironmentFiles(env)
        // The usage widget walks the rootfs on a TTL cache — drop the stale
        // figure immediately so the terminal never shows a removed
        // environment's size (and never walks a half-deleted tree on fire).
        // The size cache is process-wide (companion), so invalidating through
        // the legacy instance clears every paths instance's entries.
        legacyPaths.invalidateDirSizeCache()
        removeRecord(id)
        NoxsLog.i("EnvManager", "environment removed: $id")
        return true
    }

    // --------------------------------------------------------------- profile

    fun terminalProfile(id: String): TerminalProfile? {
        val env = environmentFor(id) ?: return null
        // One environment: the Debian profile (legacy imports keep the
        // "android" prompt host so pre-multi-env installs stay unchanged).
        return TerminalProfile(
            environmentId = env.id,
            displayName = env.displayName,
            shell = "/bin/bash",
            user = "noxs",
            home = "/home/noxs",
            workingDirectory = "/home/noxs",
            environmentVariables = mapOf("NOXS" to "1"),
            promptHost = if (env.storagePath == legacyPaths.base.absolutePath) "android" else "debian"
        )
    }

    fun activePackageManager(): PackageManagerKind {
        val id = _activeId.value ?: return PackageManagerKind.APT
        val env = environmentFor(id) ?: return PackageManagerKind.APT
        return packageManagerFor(env)
    }

    fun packageManagerFor(env: Environment): PackageManagerKind = runCatching {
        val kind = EnvJson.readObject(File(env.storagePath, "environment.json").readText())["packageManager"]
        PackageManagerKind.valueOf(kind as? String ?: PackageManagerKind.APT.name)
    }.getOrDefault(PackageManagerKind.APT)

    // ------------------------------------------------------------- termux

    private fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    companion object {
        const val LEGACY_DEBIAN_ID = "debian"
    }
}
