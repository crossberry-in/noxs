/*
 * Noxs — original implementation.
 * NoxsPluginInstaller: installs, updates, removes, enables and disables
 * Noxs plugins inside the active Linux environment.
 *
 * Install pipeline (Plugin Store contract §12):
 *   1. verify the registry entry's metadata + compatibility
 *   2. download the release artifact (.noxs-plugin — a gzipped tar)
 *   3. verify the sha256 checksum when the registry carries one
 *   4. extract safely (TarGuard path resolution — traversal never escapes
 *      the plugin directory; only regular files are accepted)
 *   5. re-validate the installed plugin.json (id must match the registry)
 *   6. register: the plugin directory IS the installation record
 *
 * Storage layout (inside the active rootfs):
 *   /home/noxs/.noxs/plugins/<id>/plugin.js|plugin.json|README.md|icon.svg
 *   /home/noxs/.noxs/plugins/<id>/bin/...            (guest command scripts)
 *   /home/noxs/.noxs/plugins/<id>/.disabled          (when disabled)
 *   /home/noxs/.noxs/plugins/<id>/.bin-manifest      (installed command names)
 *
 * Guest command scripts (bin/ inside the artifact) are installed into the
 * guest /usr/local/bin when the plugin declares the terminal permission —
 * `bin/code`, for example, becomes a real `code` command runnable from any
 * path, in the caller's own shell. Every shim carries a Noxs ownership
 * marker; uninstall/disable removes exactly the files this plugin installed
 * and never touches foreign files.
 *
 * A broken plugin can never crash Noxs: every failure surfaces as a
 * short, stable InstallException message — no stack traces reach users.
 */
package com.crossberry.noxs.runtime.plugins

import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.TarGuard
import com.crossberry.noxs.shared.TarReader
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

class NoxsPluginInstaller(
    private val pluginsRoot: File,
    private val appVersion: String,
    private val fetcher: NoxsPluginRegistry.Fetcher = HttpsFetcher(),
    private val guestBinDir: File? = null
) {

    class InstallException(message: String) : Exception(message)

    private val lock = Any()

    val root: File get() = pluginsRoot

    fun pluginsDir(id: String): File = File(pluginsRoot, id)

    // -------------------------------------------------------------- listing

    fun listInstalled(): List<InstalledPlugin> = synchronized(lock) {
        if (!pluginsRoot.isDirectory) return emptyList()
        pluginsRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val metaFile = File(dir, META_NAME)
                if (!metaFile.isFile) return@mapNotNull null
                val meta = runCatching { PluginJson.parseMetaFile(metaFile) }.getOrNull()
                    ?: return@mapNotNull null
                if (!PluginValidation.valid(meta)) return@mapNotNull null
                InstalledPlugin(dir, meta, isEnabled(dir))
            }
            .sortedBy { it.meta.id }
    }

    fun installed(id: String): InstalledPlugin? = listInstalled().firstOrNull { it.meta.id == id }

    fun isEnabled(dir: File): Boolean = !File(dir, DISABLED_MARKER).isFile

    fun isInstalled(id: String): Boolean = installed(id) != null

    /**
     * True when a plugin directory exists but fails its metadata/integrity
     * validation — a broken or tampered install the store must surface as
     * BLOCKED instead of pretending the plugin was never installed.
     */
    fun hasInvalidRecord(id: String): Boolean {
        if (!PluginJson.validId(id)) return false
        val dir = pluginsDir(id)
        if (!dir.isDirectory) return false
        val meta = runCatching { PluginJson.parseMetaFile(File(dir, META_NAME)) }.getOrNull()
            ?: return true
        return !PluginValidation.valid(meta) || meta.id != id
    }

    /** Update available when the registry version is newer semver-wise. */
    fun updateAvailable(entry: RegistryEntry, current: InstalledPlugin): Boolean {
        val registryVersion = PluginSemver.parse(entry.version) ?: return false
        val installedVersion = PluginSemver.parse(current.meta.version) ?: return true
        return registryVersion > installedVersion
    }

    // ------------------------------------------------------------- install

    /**
     * Installs (or replaces) a plugin from a registry entry.
     * Synchronous — call off the main thread; artifacts are small and the
     * store surfaces progress through [onProgress] percentages.
     *
     * [bytes] supplies an already-downloaded artifact (cloud install path —
     * the download happened during metadata resolution); it still goes
     * through the same size + checksum verification as a fetched artifact.
     */
    fun install(
        entry: RegistryEntry,
        onProgress: (Int) -> Unit = {},
        bytes: ByteArray? = null
    ): InstalledPlugin = synchronized(lock) {
        // 1. metadata + compatibility gate (registry entries are untrusted)
        val entryMeta = PluginMeta(
            id = entry.id, name = entry.name, version = entry.version,
            description = entry.description, main = PluginJson.RUNTIME_ENTRY,
            permissions = entry.permissions.ifEmpty { listOf(PluginPermissions.UI) },
            minimumNoxsVersion = entry.minimumNoxsVersion ?: "0.0.0",
            logo = entry.logo, readme = entry.readme,
            author = null, license = null, category = entry.category,
            commands = emptyList(), keywords = entry.keywords,
            homepage = null, repository = null,
            sdkVersion = entry.sdkVersion ?: SdkRequirements.DEFAULT_SDK_VERSION,
            minimumSdkVersion = entry.minimumSdkVersion,
            maximumSdkVersion = entry.maximumSdkVersion,
            apiFeatures = entry.apiFeatures
        )
        if (PluginValidation.validate(entryMeta).isNotEmpty()) {
            throw InstallException("Registry metadata for this plugin is invalid")
        }
        if (!PluginValidation.compatible(entry.minimumNoxsVersion, appVersion)) {
            throw InstallException("This plugin needs Noxs ${entry.minimumNoxsVersion} or newer")
        }
        // SDK compatibility gate — a plugin this app cannot host is refused
        // before anything is downloaded (mirror of the manager pre-check).
        when (val plan = PluginSdkCatalog.resolve(SdkRequirements.from(entryMeta), appVersion)) {
            is SdkPlan.Incompatible -> throw InstallException(plan.reason)
            is SdkPlan.NeedsNewerApp -> throw InstallException(
                "Update Noxs to ${plan.requiredNoxs} to use this plugin"
            )
            is SdkPlan.Supported -> Unit
        }
        val artifact = entry.artifact?.takeIf { it.startsWith("https://") }
            ?: throw InstallException("No release artifact is published for this plugin yet")

        // 2-3. download (or take the supplied bytes) + integrity check
        onProgress(20)
        val fetched: ByteArray = if (bytes != null) {
            bytes
        } else {
            try {
                fetcher.get(artifact, ARTIFACT_TIMEOUT_MS)
            } catch (e: Exception) {
                NoxsLog.w("PluginInstaller", "artifact fetch failed: ${e.javaClass.simpleName}")
                throw InstallException("The plugin package could not be downloaded")
            }
        }
        if (fetched.isEmpty() || fetched.size > MAX_ARTIFACT_BYTES) {
            throw InstallException("The plugin package has an invalid size")
        }
        val checksum = entry.checksum?.lowercase()
        if (!checksum.isNullOrBlank() && PluginChecksum.sha256(fetched) != checksum) {
            throw InstallException("The plugin package failed its integrity check")
        }
        onProgress(60)

        // 4-5. extract into staging, validate, then swap into place.
        val target = pluginsDir(entry.id)
        val staging = File(pluginsRoot, ".staging-${entry.id}-${System.nanoTime()}")
        if (!staging.mkdirs()) throw InstallException("Plugin storage is not writable")
        try {
            extractArtifact(fetched, staging)
            validateStaged(staging, entry.id, entry.version, entryMeta)
            onProgress(85)
            if (target.exists()) {
                // Refresh ownership first so an update cannot leak stale shims.
                removeCommandShims(target)
                target.deleteRecursively()
            }
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (e: InstallException) {
            throw e
        } catch (e: Exception) {
            NoxsLog.w("PluginInstaller", "extract failed: ${e.javaClass.simpleName}")
            throw InstallException("The plugin package could not be unpacked")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        syncCommandShims(target)
        onProgress(100)
        installed(entry.id) ?: throw InstallException("The plugin could not be registered")
    }

    /** Updates a plugin to its registry version (preserving enabled state). */
    fun update(
        entry: RegistryEntry,
        current: InstalledPlugin,
        onProgress: (Int) -> Unit = {},
        bytes: ByteArray? = null
    ): InstalledPlugin {
        if (!updateAvailable(entry, current)) return current
        val wasEnabled = current.enabled
        val result = install(entry, onProgress, bytes)
        if (!wasEnabled) disable(result.meta.id)
        return installed(result.meta.id) ?: result
    }

    // ----------------------------------------------------------- lifecycle

    fun uninstall(id: String) {
        if (!PluginJson.validId(id)) throw InstallException("Invalid plugin id")
        val dir = pluginsDir(id)
        if (!dir.isDirectory) throw InstallException("Plugin is not installed")
        removeCommandShims(dir)
        if (!dir.deleteRecursively()) {
            NoxsLog.w("PluginInstaller", "partial uninstall of $id")
        }
    }

    fun enable(id: String) {
        val dir = requireInstalledDir(id)
        File(dir, DISABLED_MARKER).delete()
        syncCommandShims(dir)
    }

    fun disable(id: String) {
        val dir = requireInstalledDir(id)
        File(dir, DISABLED_MARKER).writeText("disabled\n", Charsets.UTF_8)
        removeCommandShims(dir)
    }

    private fun requireInstalledDir(id: String): File {
        if (!PluginJson.validId(id)) throw InstallException("Invalid plugin id")
        val dir = pluginsDir(id)
        if (!dir.isDirectory || !File(dir, META_NAME).isFile) {
            throw InstallException("Plugin is not installed")
        }
        return dir
    }

    // ------------------------------------------------- guest command shims

    /**
     * Installs the plugin's guest command scripts (bin/) into the guest
     * /usr/local/bin so `bin/<name>` becomes a real terminal command —
     * available from any path, in the caller's own interactive shell.
     *
     * Security rules:
     *  - only plugins granted the terminal permission get commands
     *  - names must satisfy the plugin id grammar (no traversal, no dots)
     *  - scripts must start with a "#!" shebang and stay under [MAX_SHIM_BYTES]
     *  - an existing file that is not owned by THIS plugin is never replaced
     *  - every written shim carries an ownership marker line, and the exact
     *    set of installed names is recorded in the plugin's .bin-manifest
     *
     * Failures degrade to log lines — a shim problem never fails an install.
     */
    private fun syncCommandShims(dir: File) {
        val binDir = guestBinDir ?: return
        val meta = runCatching { PluginJson.parseMetaFile(File(dir, META_NAME)) }.getOrNull()
            ?: return
        val installed = mutableListOf<String>()
        val bin = File(dir, BIN_DIR)
        if (PluginPermissions.TERMINAL in meta.permissions && bin.isDirectory) {
            bin.listFiles().orEmpty().filter { it.isFile }.forEach { script ->
                installShim(dir.name, script, binDir)?.let { installed += it }
            }
        } else if (bin.isDirectory) {
            NoxsLog.w(TAG, "plugin ${meta.id} ships bin/ without the terminal permission — commands not installed")
        }
        // Drop commands that the current version no longer ships.
        previousShims(dir).forEach { name ->
            if (name in installed) return@forEach
            val dest = File(binDir, name)
            if (ownedBy(dest, dir.name)) {
                runCatching { dest.delete() }
            }
        }
        val manifest = File(dir, BIN_MANIFEST)
        if (installed.isEmpty()) {
            manifest.delete()
        } else {
            runCatching {
                manifest.writeText(installed.sorted().joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
        }
    }

    /** Installs one script as a guest command; returns the command name or null. */
    private fun installShim(pluginId: String, script: File, binDir: File): String? {
        val name = script.name
        if (!PluginJson.validId(name)) {
            NoxsLog.w(TAG, "bin: refusing invalid command name for $pluginId: $name")
            return null
        }
        val bytes = runCatching { script.readBytes() }.getOrNull() ?: return null
        if (bytes.size > MAX_SHIM_BYTES) {
            NoxsLog.w(TAG, "bin: $name is too large (${bytes.size} bytes), skipped")
            return null
        }
        if (bytes.size < 2 || bytes[0] != '#'.code.toByte() || bytes[1] != '!'.code.toByte()) {
            NoxsLog.w(TAG, "bin: $name has no shebang, skipped")
            return null
        }
        val dest = File(binDir, name)
        if (dest.exists() && !ownedBy(dest, pluginId)) {
            NoxsLog.w(TAG, "bin: command '$name' already exists and is not owned by $pluginId, skipped")
            return null
        }
        return runCatching {
            if (!binDir.isDirectory) binDir.mkdirs()
            val text = String(bytes, Charsets.UTF_8)
            val marked = markShim(pluginId, text)
            dest.writeText(marked, Charsets.UTF_8)
            dest.setReadable(true, false)
            dest.setExecutable(true, false)
            name
        }.getOrElse {
            NoxsLog.w(TAG, "bin: could not install command '$name'")
            null
        }
    }

    /**
     * The shebang must stay the first line for the kernel, so the ownership
     * marker is inserted directly below it (or appended when absent — the
     * validator already rejects shebang-less scripts).
     */
    private fun markShim(pluginId: String, text: String): String {
        val marker = markerLine(pluginId)
        val firstNewline = text.indexOf('\n')
        return if (firstNewline >= 0) {
            text.substring(0, firstNewline + 1) + marker + "\n" + text.substring(firstNewline + 1)
        } else {
            text + "\n" + marker + "\n"
        }
    }

    private fun markerLine(pluginId: String): String = "# Noxs plugin command shim ($pluginId) — installed by the Noxs Plugin Store"

    /** True when [file] starts with THIS plugin's ownership marker. */
    private fun ownedBy(file: File, pluginId: String): Boolean = runCatching {
        file.useLines(Charsets.UTF_8) { lines ->
            lines.take(4).any { it.startsWith("# Noxs plugin command shim (") && it.contains("($pluginId)") }
        }
    }.getOrDefault(false)

    private fun previousShims(dir: File): List<String> = runCatching {
        File(dir, BIN_MANIFEST).readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && PluginJson.validId(it) }
    }.getOrDefault(emptyList())

    private fun removeCommandShims(dir: File) {
        val binDir = guestBinDir ?: return
        previousShims(dir).forEach { name ->
            val dest = File(binDir, name)
            if (dest.isFile && ownedBy(dest, dir.name)) {
                runCatching { dest.delete() }
                    .onFailure { NoxsLog.w(TAG, "bin: could not remove command '$name'") }
            }
        }
        runCatching { File(dir, BIN_MANIFEST).delete() }
    }

    // ------------------------------------------------------------ internals

    /**
     * Safe extraction of the .noxs-plugin gzipped tar. Every entry is
     * resolved through TarGuard; only regular files are accepted (plugins
     * are data, not system images).
     */
    private fun extractArtifact(bytes: ByteArray, into: File) {
        val payload = File(into, PAYLOAD_NAME)
        payload.writeBytes(bytes)
        try {
            GZIPInputStream(FileInputStream(payload)).use { gzip ->
                TarReader(gzip).readAll { entry, stream ->
                    when (entry.type) {
                        TarReader.Type.REGULAR -> {
                            val dest = TarGuard.safeResolve(into, entry.name)
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { out -> stream.copyTo(out) }
                        }
                        // Directories are implicit; anything else is rejected.
                        TarReader.Type.DIR -> Unit
                        else -> throw InstallException("The plugin package contains unsupported entries")
                    }
                }
            }
        } finally {
            payload.delete()
        }
    }

    /** The staged tree must carry a valid plugin.json matching the registry. */
    private fun validateStaged(staged: File, expectedId: String, expectedVersion: String, expected: PluginMeta) {
        val metaFile = File(staged, META_NAME)
        if (!metaFile.isFile) throw InstallException("The plugin package has no plugin.json")
        val meta = try {
            PluginJson.parseMetaFile(metaFile)
        } catch (e: IllegalArgumentException) {
            throw InstallException("The plugin package has invalid metadata")
        }
        if (PluginValidation.validate(meta).isNotEmpty()) {
            throw InstallException("The plugin package has invalid metadata")
        }
        if (meta.id != expectedId) {
            throw InstallException("The plugin package does not match its registry entry")
        }
        // A package whose version differs from the registry (stale or
        // tampered artifact) must not silently install as a different release.
        if (meta.version != expectedVersion) {
            throw InstallException("The plugin package does not match its registry entry")
        }
        // SDK requirements inside the artifact must match the registry too —
        // a mismatch means the archive is stale or tampered.
        if (meta.sdkVersion != expected.sdkVersion ||
            meta.minimumSdkVersion != expected.minimumSdkVersion ||
            meta.maximumSdkVersion != expected.maximumSdkVersion
        ) {
            throw InstallException("The plugin package does not match its registry entry")
        }
        if (!File(staged, PluginJson.RUNTIME_ENTRY).isFile) {
            throw InstallException("The plugin package has no runtime entry")
        }
    }

    companion object {
        private const val TAG = "PluginInstaller"
        const val DISABLED_MARKER = ".disabled"
        const val META_NAME = "plugin.json"
        const val PAYLOAD_NAME = ".payload.tar"
        const val BIN_DIR = "bin"
        const val BIN_MANIFEST = ".bin-manifest"
        const val ARTIFACT_TIMEOUT_MS = 60_000
        const val MAX_ARTIFACT_BYTES = 32L * 1024L * 1024L
        const val MAX_SHIM_BYTES = 128 * 1024
    }
}
