/*
 * Noxs — original implementation.
 * App-private filesystem layout. Everything lives under the app sandbox;
 * Android's real system filesystem is never modified.
 */
package com.crossberry.noxs.runtime

import android.content.Context
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import java.io.File
import java.nio.file.Files

class NoxsPaths(baseDir: File, val nativeLibDir: File? = null) {

    /**
     * Android constructor — rooted in the app-private filesDir; native libs
     * resolve to [Context#getApplicationInfo].nativeLibraryDir, the ONLY
     * location Android allows execve() from on Android 10+ (W^X / SELinux:
     * exec of files in app data storage is denied since targetSdk 29).
     */
    constructor(context: Context) : this(
        File(context.filesDir, NoxsConstants.ANDROID_BASE_DIR),
        File(context.applicationInfo.nativeLibraryDir)
    )

    val base: File = baseDir
    val rootfs: File = File(base, NoxsConstants.ANDROID_ROOTFS_DIR)
    val bin: File = File(base, NoxsConstants.ANDROID_BIN_DIR)
    val run: File = File(base, NoxsConstants.ANDROID_RUN_DIR)
    val storageControl: File = File(run, "storage")
    val storageRequests: File = File(storageControl, "requests")
    val storageResponses: File = File(storageControl, "responses")
    val storagePayloads: File = File(storageControl, "payloads")
    val storageResponsePayloads: File = File(storageControl, "response-payloads")
    val webControl: File = File(run, "web")
    val webRequests: File = File(webControl, "requests")
    val webResponses: File = File(webControl, "responses")
    // Guest→Android `nx plug` bridge (Noxs Plugin Store)
    val pluginControl: File = File(run, "plugin")
    val pluginRequests: File = File(pluginControl, "requests")
    val pluginResponses: File = File(pluginControl, "responses")
    val pluginCatalogSnapshot: File = File(pluginControl, "catalog.txt")
    val pluginInstalledSnapshot: File = File(pluginControl, "plugins.txt")
    val cache: File = File(base, NoxsConstants.ANDROID_CACHE_DIR)
    val logs: File = File(base, NoxsConstants.ANDROID_LOGS_DIR)
    val tmp: File = File(base, NoxsConstants.ANDROID_TMP_DIR)

    /**
     * proot ships inside the APK as jniLibs (libproot.so) so the OS extracts
     * it into nativeLibraryDir where execve is permitted. JVM tests (no
     * native dir) fall back to the bin/ layout.
     */
    val prootBinary: File
        get() = nativeLibDir?.let { File(it, "libproot.so") } ?: File(bin, "proot")
    val prootLoaderBinary: File
        get() = nativeLibDir?.let { File(it, "libproot-loader.so") } ?: File(bin, "proot-loader")
    val prootLoader32Binary: File
        get() = nativeLibDir?.let { File(it, "libproot-loader32.so") } ?: File(bin, "proot-loader32")
    val installMarker: File = File(base, NoxsConstants.INSTALL_COMPLETE_MARKER)
    val aptReadyMarker: File = File(base, "apt-bootstrap-v1.ready")

    // Rootfs-internal virtual paths (physical files under rootfs/)
    val rootfsEtc: File get() = File(rootfs, "etc")
    val rootfsRun: File get() = File(rootfs, NoxsConstants.RUN_DIR)
    val rootfsVarRun: File get() = File(rootfs, NoxsConstants.VAR_RUN_DIR)
    val rootfsNoxsRun: File get() = File(rootfs, NoxsConstants.NOXS_RUN_DIR)
    val rootfsHostRun: File get() = File(rootfs, NoxsConstants.HOST_RUN_DIR)
    val rootfsHomeNoxs: File get() = File(rootfs, NoxsConstants.DEFAULT_USER_HOME)
    /** Plugin Store installations live inside the active environment. */
    val rootfsPluginsDir: File get() = File(rootfsHomeNoxs, ".noxs/plugins")
    val rootfsServices: File get() = File(rootfs, NoxsConstants.NOXS_SERVICE_DIR)
    val noxsResourcesConf: File = File(rootfs, "etc/noxs/resources.conf")

    fun ensureBaseDirs(): Boolean =
        listOf(base, bin, run, storageRequests, storageResponses, storagePayloads, storageResponsePayloads, webRequests, webResponses, pluginRequests, pluginResponses, cache, logs, tmp)
            .all { it.isDirectory || it.mkdirs() }

    fun isInstalled(): Boolean = installMarker.isFile && prootBinary.isFile && rootfs.isDirectory

    /**
     * Sums the file sizes under [dir] in bytes. Hardened against the field
     * crash `AssertionError: rootDir must be verified to be directory
     * beforehand` (kotlin.io.FileTreeWalk): the root can vanish between the
     * caller's check and the walk construction (environment reset/repair
     * runs concurrently with the terminal's resource refresh).
     *
     * Guarantees:
     *  - the root is canonicalized and verified to be a real directory
     *    BEFORE traversal; anything else returns 0L — but never silently:
     *    every fallback logs the reason so a misconfigured path is visible
     *  - traversal I/O errors, permission errors, vanishing files and broken
     *    symlinks are skipped (onFail -> SKIP), never fatal
     *  - symlinks are never followed outside [dir]: directory descent and
     *    per-file size reads require the canonical path to stay inside the
     *    root, so guest-created links cannot leak foreign storage into the
     *    usage figure (or make it explode)
     *  - the total saturates at Long.MAX_VALUE instead of overflowing
     */
    fun dirSize(dir: File): Long {
        val root = try {
            dir.canonicalFile
        } catch (e: Exception) {
            NoxsLog.w("Paths", "dirSize: cannot canonicalize ${dir.path} — reporting 0 (${e.javaClass.simpleName})")
            return 0L
        }
        if (!root.isDirectory) {
            // Missing, not-yet-created, or a plain file: a real configuration
            // problem when it happens on the installed rootfs — say so.
            NoxsLog.w("Paths", "dirSize: root is not a directory (path=${dir.path} exists=${root.exists()}) — reporting 0")
            return 0L
        }
        val rootPath = root.path + File.separator
        var total = 0L
        try {
            root.walkBottomUp()
                .onEnter { d ->
                    // Descend only into directories that stay inside the
                    // root once canonicalized (root itself passes).
                    val c = runCatching { d.canonicalPath }.getOrNull()
                    c != null && (c == root.path || c.startsWith(rootPath))
                }
                .onFail { d, e ->
                    NoxsLog.w("Paths", "dirSize: skipping unreadable ${d.path} (${e.javaClass.simpleName})")
                    OnFailResult.SKIP
                }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .forEach { f ->
                    // length() is 0 for entries that vanished mid-walk; the
                    // saturating add keeps a hostile tree from overflowing.
                    val n = runCatching { f.length() }.getOrDefault(0L)
                    total = if (n > 0 && total > Long.MAX_VALUE - n) Long.MAX_VALUE else total + n
                }
        } catch (e: Throwable) {
            // Throwable, not Exception: the field crash was an AssertionError
            // from FileTreeWalk's internal require() — Errors bypass Exception
            // handlers. A usage probe must never take the app down; the
            // partial total up to the failure point is the honest answer.
            NoxsLog.w("Paths", "dirSize: walk aborted for ${dir.path} — partial total kept (${e.javaClass.simpleName})")
        }
        return total
    }

    /** TTL memo for [dirSize] — the status widget refreshes every few seconds
     *  and must not re-walk a 100k-file rootfs each tick. [ttlMs] <= 0 scans. */
    fun dirSizeCached(dir: File, ttlMs: Long = DEFAULT_DIR_SIZE_TTL_MS): Long {
        val now = System.nanoTime()
        val key = dir.absolutePath
        synchronized(sizeCacheLock) {
            sizeCache[key]?.let { (value, at) ->
                if (ttlMs > 0 && (now - at) < ttlMs * 1_000_000) return value
            }
        }
        val value = dirSize(dir)
        synchronized(sizeCacheLock) { sizeCache[key] = value to now }
        return value
    }

    fun invalidateDirSizeCache(dir: File? = null) {
        synchronized(sizeCacheLock) {
            if (dir == null) sizeCache.clear() else sizeCache.remove(dir.absolutePath)
        }
    }

    companion object {
        /** Status-bar granularity: 15 s is imperceptible for a usage widget. */
        const val DEFAULT_DIR_SIZE_TTL_MS = 15_000L

        private val sizeCacheLock = Any()
        private val sizeCache = HashMap<String, Pair<Long, Long>>() // path -> (bytes, nanoTime)
    }
}
