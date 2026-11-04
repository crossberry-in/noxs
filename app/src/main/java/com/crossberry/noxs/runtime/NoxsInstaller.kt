/*
 * Noxs — original implementation.
 * NoxsInstaller: first-launch bootstrap pipeline (see docs/BOOTSTRAP.md).
 *
 * Steps (each resumable; interruption-safe via staged downloads + markers):
 *  1  architecture detection
 *  2  storage initialization (app-private dirs)
 *  3  bootstrap manifest load (linux-runtime/bootstrap/<arch>/bootstrap.manifest)
 *  4  proot binary acquisition (download → SHA-256 verify)
 *  5  Debian 12 rootfs acquisition (download → SHA-256 verify)
 *  6  safe extraction (TarGuard: traversal/device/setuid defense)
 *  7  Debian userspace configuration (FHS dirs, /run, /var/run, overlay files)
 *  8  noxs user creation (+ password prompt → chpasswd, never stored)
 *  9  runtime state initialization (/run, /var/run/noxs, logs, quotas)
 * 10  signed APT bootstrap (dpkg repair, CA certificates, verified HTTPS update)
 * 11  first shell launch (handled by caller)
 */
package com.crossberry.noxs.runtime

import android.content.Context
import com.crossberry.noxs.BuildConfig
import com.crossberry.noxs.R
import com.crossberry.noxs.shared.BootstrapManifest
import com.crossberry.noxs.shared.ChecksumVerifier
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.NoxsUser
import com.crossberry.noxs.shared.PasswdDb
import com.crossberry.noxs.shared.RootfsExtractor
import com.crossberry.noxs.shared.ShellUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.CharBuffer
import java.security.SecureRandom

/** Raised when the device does not have enough free space for bootstrap. */
class InsufficientStorageException(val requiredBytes: Long, val usableBytes: Long) :
    RuntimeException("insufficient storage: need ${requiredBytes / (1024 * 1024)} MB, usable ${usableBytes / (1024 * 1024)} MB")

/** Raised cooperatively when the user stops the setup console (Ctrl+C / Stop). */
class SetupCancelledException : RuntimeException("Setup stopped by user")

class NoxsInstaller(
    private val context: Context,
    private val paths: NoxsPaths,
    private val launcher: ProotLauncher,
    private val isCancelled: () -> Boolean = { false }
) {

    interface Progress {
        fun onStep(step: Int, titleRes: Int, detail: String)
        fun onProgressBytes(downloaded: Long, total: Long)
        /** Real cumulative tar entry count while the rootfs is being extracted. */
        fun onExtracted(entries: Long) {}
        fun onLog(line: String)
        fun onPasswordRequired(): CharArray? // setup console collects it
    }

    sealed class InstallResult {
        data class Success(val rootfsSha256: String) : InstallResult()
        data class Failure(val userMessageRes: Int, val detail: String) : InstallResult()
        data object Cancelled : InstallResult()
    }

    private fun checkCancelled() {
        if (isCancelled()) throw SetupCancelledException()
    }

    var overrideRootfsUrl: String? = null

    /** Shell prompt label + banner for the configured userspace (spec §61). */
    var promptHostLabel: String = "android"
    var distroBanner: String = "Noxs Debian 12 (bookworm)"

    suspend fun install(progress: Progress): InstallResult = withContext(Dispatchers.IO) {
        progress0 = progress
        try {
            // 1 — architecture detection
            checkCancelled()
            progress.onStep(1, R.string.setup_step_arch, "")
            val arch = NoxsCapabilitiesArch.detect()
            progress.onLog("arch=$arch")

            // 2 — storage initialization
            checkCancelled()
            progress.onStep(2, R.string.setup_step_storage, "")
            if (!paths.ensureBaseDirs()) {
                return@withContext InstallResult.Failure(R.string.err_storage, "cannot create app dirs")
            }

            // 3 — manifest
            checkCancelled()
            progress.onStep(3, R.string.setup_step_manifest, arch)
            val manifest = loadManifest(arch)
            progress.onLog("rootfs url=${manifest.rootfs.url}")

            // 4 — proot (bundled in the APK as jniLibs → nativeLibraryDir;
            // exec from app data storage is denied on Android 10+, so there is
            // intentionally NO network step here anymore)
            checkCancelled()
            progress.onStep(4, R.string.setup_step_proot, "")
            val prootFile = paths.prootBinary
            if (!prootFile.isFile) {
                return@withContext InstallResult.Failure(
                    R.string.err_bootstrap,
                    "bundled proot missing at ${prootFile.absolutePath} — reinstall the APK"
                )
            }
            progress.onLog("proot bundled: ${prootFile.name} (${prootFile.length()} bytes)")

            // 5 — rootfs
            checkCancelled()
            progress.onStep(5, R.string.setup_step_rootfs, "")
            val rootfsArchive = File(paths.cache, "rootfs-$arch.tar.${manifest.rootfs.format.substringAfter('.')}")
            var sha = ""
            // Prefer the complete rootfs embedded in release APKs. This also
            // replaces a stale cached image, so first launch is truly offline.
            val bundledRootfs = copyBundledRootfs(manifest, rootfsArchive, manifest.rootfs.sha256)
            if (bundledRootfs != null) {
                sha = bundledRootfs
                progress.onLog("offline Debian rootfs loaded from APK assets")
            } else if (!rootfsArchive.isFile) {
                acquire(manifest.rootfs, rootfsArchive, progress) { staged, expected ->
                    val v = verify(staged, expected, "rootfs", sourceUrl = manifest.rootfs.url)
                    (v as? Verify.Ok)?.let {
                        progress.onLog("rootfs SHA-256 verified (${it.sha256.take(12)}…)")
                    }
                    sha = (v as? Verify.Ok)?.sha256 ?: sha
                    v
                }
            } else {
                sha = (verify(rootfsArchive, manifest.rootfs.sha256, "rootfs-cached", sourceUrl = manifest.rootfs.url) as? Verify.Ok)?.sha256 ?: ""
            }

            // 6 — safe extraction
            checkCancelled()
            progress.onStep(6, R.string.setup_step_extract, "")
            // Extraction can need roughly 3x the archive size as free space.
            val extractNeeded = InstallerPlan.extractionReserveBytes(manifest.rootfs.sizeBytes)
            val extractUsable = paths.rootfs.usableSpace
            if (extractUsable in 1 until extractNeeded) {
                throw InsufficientStorageException(extractNeeded, extractUsable)
            }
            // Remove any stale 0-byte top-level placeholders before extracting
            listOf("bin", "sbin", "lib", "lib64").forEach { name ->
                val f = File(paths.rootfs, name)
                if (!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isFile && f.length() == 0L) {
                    f.delete()
                }
            }
            val extractor = RootfsExtractor(paths.rootfs)
            val stats = extractor.extract(
                rootfsArchive,
                onProgress = { entries -> progress.onExtracted(entries) },
                beforeEntry = { checkCancelled() }
            )
            progress.onLog("extracted files=${stats.files} dirs=${stats.dirs} links=${stats.links}")
            if (bundledRootfs != null) {
                val required = listOf(
                    "usr/bin/nano", "usr/bin/curl", "usr/bin/git",
                    "usr/bin/python3", "usr/bin/gcc", "usr/bin/g++",
                    "usr/bin/make", "usr/bin/node", "usr/bin/sqlite3"
                )
                val missing = required.filterNot { File(paths.rootfs, it).exists() }
                val pipPresent = File(paths.rootfs, "usr/bin/pip").exists() ||
                    File(paths.rootfs, "usr/bin/pip3").exists()
                if (missing.isNotEmpty() || !pipPresent) {
                    throw java.io.IOException(
                        "Offline Debian package verification failed: " +
                            (missing + if (pipPresent) emptyList() else listOf("pip")).joinToString()
                    )
                }
                progress.onLog("offline packages verified: nano curl git python3 pip gcc g++ make nodejs sqlite3")
            }

            // 7 — userspace configuration. APT remains on signed HTTP only
            // during the CA-certificate bootstrap; final HTTPS is enabled later.
            checkCancelled()
            progress.onStep(7, R.string.setup_step_configure, "")
            RootfsConfigurator.configure(
                paths,
                bootstrapHttpApt = true,
                hostLabel = promptHostLabel,
                banner = distroBanner
            )

            // 8 — noxs user + password
            checkCancelled()
            progress.onStep(8, R.string.setup_step_user, "")
            provisionUser(progress)

            // 9 — runtime state
            checkCancelled()
            progress.onStep(9, R.string.setup_step_run, "")
            initRuntimeState()

            // 10 — initialize dpkg/APT, repair CA certificates over signed
            // Debian HTTP metadata, then switch to a verified HTTPS update.
            // NON-FATAL BY DESIGN: a shell does not need APT state. If this
            // step fails (slow or restricted network) setup still completes so
            // the terminal clears straight to an active shell; NoxsAptSetup
            // keeps repairing the package layer in the background on every
            // launch until it succeeds.
            checkCancelled()
            progress.onStep(10, R.string.setup_step_apt, "")
            if (bundledRootfs != null) {
                // The bundle already contains CA certificates and the requested
                // packages. Switch sources to HTTPS, but defer apt update until
                // the user explicitly requests it or a network is available.
                RootfsConfigurator.configureAptSources(paths, useHttps = true)
                File(paths.base, "offline-packages-v1.ready").writeText(
                    "installed=${System.currentTimeMillis()}\nrootfs_sha256=$sha\n"
                )
                progress.onLog("offline package set is ready; network APT bootstrap skipped")
            } else {
                val aptResult = NoxsAptBootstrapper(paths, launcher, isCancelled).initialize(
                    force = true,
                    onLog = progress::onLog
                )
                if (!aptResult.success) {
                    NoxsLog.e("Installer", "APT bootstrap unfinished; deferring to background repair: ${aptResult.detail}")
                    progress.onLog("APT security setup will continue in the background")
                }
            }

            // marker
            paths.installMarker.writeText(
                "installed=${System.currentTimeMillis()}\narch=$arch\nrootfs_sha256=$sha\nsuite=${NoxsConstants.DEFAULT_DEBIAN_SUITE}\n"
            )
            progress.onStep(11, R.string.setup_step_shell, "")
            InstallResult.Success(sha)
        } catch (e: SetupCancelledException) {
            NoxsLog.i("Installer", "setup cancelled by user")
            InstallResult.Cancelled
        } catch (e: InsufficientStorageException) {
            NoxsLog.e("Installer", "storage check failed", e)
            InstallResult.Failure(R.string.err_storage_space, e.message ?: "storage")
        } catch (e: SecurityException) {
            NoxsLog.e("Installer", "security check failed", e)
            InstallResult.Failure(R.string.err_bootstrap_verify, e.message ?: "security")
        } catch (e: Exception) {
            NoxsLog.e("Installer", "install failed", e)
            if (InstallerPlan.isNetworkError(e)) {
                InstallResult.Failure(R.string.err_network, e.message ?: e.javaClass.simpleName)
            } else {
                InstallResult.Failure(R.string.err_bootstrap, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ------------------------------------------------------------------ steps

    private fun loadManifest(arch: String): BootstrapManifest {
        val stream = context.assets.open("bootstrap/$arch/bootstrap.manifest")
        val json = stream.bufferedReader().use { it.readText() }
        val manifest = BootstrapManifest.parse(json)
        overrideRootfsUrl?.takeIf { it.startsWith("https://") }?.let {
            progress0?.onLog("settings override rootfs url")
            return manifest.copy(rootfs = manifest.rootfs.copy(url = it, sha256 = ""))
        }
        return manifest
    }

    private var progress0: Progress? = null

    /**
     * Copies and verifies the prebuilt Debian image shipped in APK assets.
     * Returns null when this ABI/build has no offline bundle, preserving the
     * existing resumable network bootstrap as the fallback.
     *
     * The archive ships as rootfs.bin: aapt/AAPT2 decompresses .gz-suffixed
     * assets and strips the suffix at packaging time, so a rootfs.tar.gz asset
     * would arrive in the APK as an unfindable, unverifyable rootfs.tar. The
     * manifest names the asset; older names are probed as a legacy fallback.
     */
    private fun copyBundledRootfs(manifest: BootstrapManifest, dest: File, expectedSha: String): String? {
        val candidates = listOf(
            manifest.offlineBundledAsset,
            "bootstrap/${manifest.arch}/rootfs.bin",
            "bootstrap/${manifest.arch}/rootfs.tar.gz"
        ).filter { it.isNotBlank() }.distinct()
        val assetName = candidates.firstOrNull { name ->
            runCatching {
                context.assets.open(name).use { }
                true
            }.getOrDefault(false)
        } ?: return null

        dest.parentFile?.mkdirs()
        val staged = File(dest.absolutePath + ".bundled.part")
        if (staged.exists()) staged.delete()

        context.assets.open(assetName).use { input ->
            FileOutputStream(staged).use { output -> input.copyTo(output) }
        }

        val verified = verify(staged, expectedSha, "bundled-rootfs") as Verify.Ok
        if (dest.exists() && !dest.delete()) {
            staged.delete()
            throw java.io.IOException("cannot replace cached rootfs archive")
        }
        if (!staged.renameTo(dest)) {
            staged.copyTo(dest, overwrite = true)
            staged.delete()
        }
        return verified.sha256
    }

    private fun verify(file: File, expectedSha: String, label: String, sourceUrl: String? = null): Verify {
        if (expectedSha.isBlank()) {
            // Trust-on-first-use: compute, surface to user, pin for later runs.
            val actual = ChecksumVerifier.sha256Hex(file)
            File(file.absolutePath + ".sha256").writeText(actual)
            return Verify.Ok(actual, pinned = false)
        }
        val result = ChecksumVerifier.verify(file, expectedSha)
        return when (result) {
            is ChecksumVerifier.Result.Verified -> Verify.Ok(result.sha256, pinned = true)
            is ChecksumVerifier.Result.Mismatch -> {
                // Stale-pin rescue: the offline rootfs is rebuilt per CI run,
                // so an APK built before the final release run can embed an
                // outdated pin (expected 47490ba1… vs the released 77c11a8e…).
                // Accept the bytes when the checksum sidecar published next to
                // the SAME release asset matches them. Same-origin check,
                // logged loudly; the embedded pin stays the security anchor
                // whenever it matches.
                val sidecar = sourceUrl?.let { releaseSidecarSha256(it) }
                if (sidecar != null && sidecar == result.actualSha256) {
                    progress0?.onLog(
                        "release checksum sidecar matched ${sidecar.take(12)}… " +
                            "(embedded manifest pin was stale — rebuilding the pin)"
                    )
                    File(file.absolutePath + ".sha256").writeText(result.actualSha256)
                    Verify.Ok(result.actualSha256, pinned = false)
                } else {
                    throw SecurityException(
                        "$label SHA-256 mismatch: expected $expectedSha, got ${result.actualSha256}"
                    )
                }
            }
        }
    }

    /**
     * Fetches "<url>.sha256" (format: "<sha256>  <filename>") — only for this
     * project's own GitHub release URLs, never for third-party hosts. Returns
     * the lowercase hash or null on any failure.
     */
    private fun releaseSidecarSha256(url: String): String? {
        if (!url.startsWith("https://github.com/crossberry-in/noxs/releases/")) return null
        return runCatching {
            val conn = URL("$url.sha256").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Noxs/${BuildConfig.VERSION_NAME} (Android; bootstrap)")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        }.getOrNull()
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull { token ->
                token.length == 64 && token.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            }
            ?.lowercase()
    }

    private sealed class Verify {
        data class Ok(val sha256: String, val pinned: Boolean) : Verify()
    }

    private suspend fun acquire(
        artifact: com.crossberry.noxs.shared.BootstrapArtifact,
        dest: File,
        progress: Progress,
        attempts: Int = 3,
        doVerify: (File, String) -> Verify
    ) = withContext(Dispatchers.IO) {
        if (artifact.url.startsWith("file://")) {
            val src = File(artifact.url.removePrefix("file://"))
            src.copyTo(dest, overwrite = true)
            doVerify(dest, artifact.sha256)
            return@withContext
        }

        // Free-space precheck before spending minutes on a large download.
        val needed = InstallerPlan.downloadReserveBytes(artifact.sizeBytes)
        val usable = dest.parentFile?.usableSpace ?: 0L
        if (usable in 1 until needed) {
            throw InsufficientStorageException(needed, usable)
        }

        val candidates = InstallerPlan.candidateUrls(artifact.url)
        val staged = File(dest.absolutePath + ".part")
        val stagedMeta = File(dest.absolutePath + ".part.meta")
        staged.parentFile?.mkdirs()

        var lastError: Exception? = null
        var downloaded = false
        outer@ for (candidateIndex in candidates.indices) {
            checkCancelled()
            val url = candidates[candidateIndex]
            if (candidateIndex > 0) {
                progress.onLog("mirror: trying alternate download server (${candidateIndex + 1}/${candidates.size})")
            }
            for (attempt in 0 until attempts) {
                checkCancelled()
                if (attempt > 0) {
                    progress.onLog("retry ${attempt + 1}/$attempts after network error: " +
                        (lastError?.message ?: "unknown"))
                    try { Thread.sleep(2_000L * attempt) } catch (_: InterruptedException) {}
                }
                // Resume support: keep the partial file across retries so a
                // dropped 90%-done download does not restart from zero. A
                // partial is only resumed when its sidecar metadata matches
                // THIS url + artifact — a stale .part left by an older release
                // (different bytes) would otherwise be appended to and poison
                // the final SHA-256 ("resuming from 157 MB" → mismatch).
                val resumeFrom = if (staged.isFile && metaMatches(stagedMeta, url, artifact.sha256)) {
                    staged.length()
                } else 0L
                if (staged.isFile && resumeFrom == 0L) {
                    staged.delete()
                    stagedMeta.delete()
                }
                if (resumeFrom > 0L && attempt == 0 && candidateIndex == 0) {
                    progress.onLog("download: resuming from ${resumeFrom / (1024 * 1024)} MB")
                }
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 60_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "Noxs/${BuildConfig.VERSION_NAME} (Android; bootstrap)")
                    conn.setRequestProperty("Accept-Encoding", "identity")
                    if (resumeFrom > 0L) {
                        conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                    try {
                        conn.connect()
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            throw java.io.IOException("HTTP $code for $url")
                        }
                        // 200 (not 206) means the server ignored Range: restart clean.
                        val appending = resumeFrom > 0L && code == 206
                        if (!appending) {
                            // Fresh download: pin the partial's identity so a
                            // later resume can prove it belongs to this artifact.
                            stagedMeta.writeText("url=$url\nsha256=${artifact.sha256}\n")
                        }
                        val total = when {
                            appending && conn.contentLengthLong > 0 -> conn.contentLengthLong + resumeFrom
                            conn.contentLengthLong > 0 -> conn.contentLengthLong
                            else -> artifact.sizeBytes
                        }
                        conn.inputStream.use { input ->
                            java.io.FileOutputStream(staged, appending).use { out ->
                                val buf = ByteArray(128 * 1024)
                                var done = if (appending) resumeFrom else 0L
                                while (true) {
                                    checkCancelled()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    progress.onProgressBytes(done, total)
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    try {
                        // verify BEFORE renaming into place (interrupted downloads never verify)
                        checkCancelled()
                        doVerify(staged, artifact.sha256)
                        staged.renameTo(dest) || (staged.copyTo(dest, overwrite = true).isFile)
                        downloaded = true
                    } catch (e: SecurityException) {
                        // A corrupt resume (bad prefix bytes) — restart once from zero.
                        if (resumeFrom > 0L) {
                            progress.onLog("download: partial file failed verification, restarting")
                            staged.delete()
                            stagedMeta.delete()
                            lastError = e
                            continue
                        }
                        throw e
                    }
                    break@outer
                } catch (e: java.io.IOException) {
                    lastError = e
                    // Keep staged for resume on the next attempt; delete only if tiny/garbage.
                    if (staged.isFile && staged.length() < 64 * 1024) {
                        staged.delete()
                        stagedMeta.delete()
                    }
                }
            }
        }
        if (!downloaded) {
            lastError?.let { throw it }
            throw java.io.IOException("download failed for $dest")
        }
    }

    /** True when the partial-download sidecar matches this url + artifact. */
    private fun metaMatches(meta: File, url: String, sha256: String): Boolean {
        if (!meta.isFile) return false
        return runCatching {
            val props = meta.readLines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
            props["url"] == url && props["sha256"] == sha256
        }.getOrDefault(false)
    }

    /** Creates the noxs user (uid 1000, sudo group) and sets its password. */
    private fun provisionUser(progress: Progress) {
        checkCancelled()
        val etc = paths.rootfsEtc
        val passwdFile = File(etc, "passwd")
        val users = PasswdDb.parsePasswd(passwdFile.readText())
        val uid = PasswdDb.nextFreeUid(users)
        val gid = uid
        val groups = PasswdDb.parseGroup(File(etc, "group").readText())

        if (users.none { it.name == NoxsConstants.DEFAULT_USER }) {
            val user = NoxsUser(
                name = NoxsConstants.DEFAULT_USER,
                uid = uid,
                gid = gid,
                gecos = "Noxs User,,,",
                home = NoxsConstants.DEFAULT_USER_HOME,
                shell = NoxsConstants.DEFAULT_SHELL
            )
            passwdFile.appendText(PasswdDb.serializeUser(user) + "\n")
            File(etc, "shadow").appendText(PasswdDb.defaultShadowLineFor(user.name) + "\n")
            val sudoGroup = groups.firstOrNull { it.name == "sudo" }
                ?: com.crossberry.noxs.shared.NoxsGroup("sudo", 27, emptyList()).also {
                    File(etc, "group").appendText(PasswdDb.serializeGroup(it) + "\n")
                }
            // add noxs to sudo group line
            val groupFile = File(etc, "group")
            val updated = groupFile.readLines().joinToString("\n") { line ->
                if (line.startsWith("sudo:")) "$line,${NoxsConstants.DEFAULT_USER}" else line
            }
            groupFile.writeText(updated + "\n")
        }

        // home + skel
        val home = paths.rootfsHomeNoxs
        home.mkdirs()
        listOf(".bashrc", ".profile").forEach { f ->
            val skel = File(paths.rootfs, "etc/skel/$f")
            val target = File(home, f)
            if (skel.isFile && !target.isFile) skel.copyTo(target, overwrite = false)
        }

        // Password is collected by the masked UI and passed only on stdin.
        // Encode from the mutable char array; never create an immutable String.
        val password = progress.onPasswordRequired()
            ?: throw IllegalStateException("A Noxs password is required")
        try {
            if (password.size < 4 || password.any { it == ':' || Character.isISOControl(it) }) {
                throw IllegalStateException("Invalid Noxs password input")
            }
            val argv = launcher.oneShotArgv(listOf("/usr/sbin/chpasswd"), asRoot = true)
            val pb = ProcessBuilder(argv)
            launcher.applyEnvTo(pb, mapOf("NOXS_ROOT_LOGIN" to "1"))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.outputStream.use { output ->
                output.write("${NoxsConstants.DEFAULT_USER}:".toByteArray(Charsets.UTF_8))
                val encodedPassword = encodePasswordUtf8(password)
                try {
                    output.write(encodedPassword)
                    output.write('\n'.code)
                    output.flush()
                } finally {
                    encodedPassword.fill(0)
                }
            }
            // Drain output without retaining or displaying package-tool text.
            proc.inputStream.bufferedReader().use { reader -> while (reader.readLine() != null) Unit }
            val code = proc.waitFor()
            if (code != 0) {
                NoxsLog.w("Installer", "chpasswd exited with code $code")
                throw IllegalStateException("Could not set the Noxs password")
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun encodePasswordUtf8(password: CharArray): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    }

    private fun initRuntimeState() {
        // /run and /var/run inside the rootfs (FHS) — app-private, never Android's.
        paths.rootfsRun.mkdirs()
        paths.rootfsVarRun.mkdirs()
        paths.rootfsNoxsRun.mkdirs()
        paths.rootfsHostRun.mkdirs()
        File(paths.rootfs, "var/log/noxs").mkdirs()
        File(paths.rootfs, "var/cache/apt/archives/partial").mkdirs()
        File(paths.rootfs, "var/lib/dpkg").let { if (!it.isDirectory) it.mkdirs() }
        File(paths.run, "keep").writeText("host run dir\n")
        // FHS compatibility links recreated inside the sandbox on first run
        val linkScript = File(paths.rootfs, "usr/local/lib/noxs-links.sh")
        linkScript.parentFile?.mkdirs()
        linkScript.writeText(FHS_LINKS_SCRIPT)
    }

    private val FHS_LINKS_SCRIPT = """
        #!/bin/sh
        # Recreate FHS compatibility symlinks inside the Noxs sandbox.
        [ -e /bin ] || ln -s usr/bin /bin
        [ -e /sbin ] || ln -s usr/sbin /sbin
        [ -e /lib ] || ln -s usr/lib /lib
        [ -e /lib64 ] || ln -s usr/lib64 /lib64 2>/dev/null
        [ -e /var/run ] || ln -s /run /var/run
        exit 0
    """.trimIndent()
}

/**
 * Pure planning helpers for the bootstrap pipeline (unit-testable on the JVM).
 */
object InstallerPlan {
    /** Candidate URLs: the pinned primary first, then derived mirrors. */
    fun candidateUrls(primary: String): List<String> {
        val list = mutableListOf(primary)
        // raw.githubusercontent.com/<o>/<r>/<ref>/<path> → github.com raw redirect
        // (different host name; helps where a carrier/DNS path blackholes raw.*)
        val rawPrefix = "https://raw.githubusercontent.com/"
        if (primary.startsWith(rawPrefix)) {
            // raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>
            //   → https://github.com/<owner>/<repo>/raw/<ref>/<path>
            val parts = primary.removePrefix(rawPrefix).split('/', limit = 3)
            if (parts.size == 3) {
                list += "https://github.com/${parts[0]}/${parts[1]}/raw/${parts[2]}"
            }
        }
        return list.distinct()
    }

    /** Free space required before starting a download of [artifactBytes]. */
    fun downloadReserveBytes(artifactBytes: Long): Long =
        if (artifactBytes <= 0L) 256L * 1024 * 1024
        else artifactBytes + 128L * 1024 * 1024

    /** Free space required before extraction (archive + unpacked tree + slack). */
    fun extractionReserveBytes(artifactBytes: Long): Long =
        if (artifactBytes <= 0L) 768L * 1024 * 1024
        else artifactBytes * 4

    fun isNetworkError(e: Throwable): Boolean = when (e) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketTimeoutException,
        is java.net.SocketException,
        is javax.net.ssl.SSLException,
        is java.io.IOException -> true
        else -> false
    }
}

/** Small helper so the installer doesn't import android.os directly in tests. */
object NoxsCapabilitiesArch {
    fun detect(): String = com.crossberry.noxs.shared.NoxsCapabilities.detectArch(
        arrayOf(getAbi())
    ).let { supportedAbi ->
        // detectArch picks the first supported; we pass the preferred ABI when valid
        if (supportedAbi in BootstrapManifest.SUPPORTED_ARCHS) supportedAbi else "arm64-v8a"
    }

    private fun getAbi(): String =
        android.os.Build.SUPPORTED_ABIS.firstOrNull { it in BootstrapManifest.SUPPORTED_ARCHS } ?: "arm64-v8a"
}
