/*
 * Noxs — original implementation.
 * Configures the extracted Debian rootfs into the Noxs userspace:
 *  - writes overlay files (profile.d, environment, motd, apt sources, DNS)
 *  - installs the `noxs` CLI (code-server, services, sockets, processes)
 *  - installs Android proot-compatible `su` and `sudo` wrappers
 *  - repairs any 0-byte symlink placeholders from older installs
 *  - ensures /run, /var/run, /var/run/noxs, /var/log/noxs exist (FHS)
 * Content mirrors linux-runtime/rootfs/overlay (CI diff-checks both).
 */
package com.crossberry.noxs.runtime

import android.content.Context
import com.crossberry.noxs.BuildConfig
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsGroup
import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.NoxsUser
import com.crossberry.noxs.shared.PasswdDb
import com.crossberry.noxs.shared.RootfsExtractor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

object RootfsConfigurator {

    fun configure(context: Context, paths: NoxsPaths) = configure(paths)

    /** Configure the Debian sources as HTTP only while bootstrapping signed CA packages. */
    fun configure(context: Context, paths: NoxsPaths, bootstrapHttpApt: Boolean) =
        configure(paths, bootstrapHttpApt)

    fun configure(paths: NoxsPaths) = configure(paths, bootstrapHttpApt = false)

    fun configure(paths: NoxsPaths, bootstrapHttpApt: Boolean) =
        configure(paths, bootstrapHttpApt, hostLabel = "android")

    /**
     * [hostLabel] / [banner] / [hint] parameterize the shell prompt (noxs@debian,
     * noxs@ubuntu … spec §61), the welcome banner and the first-steps hint while
     * keeping the legacy Debian flow byte-identical via the defaults.
     */
    fun configure(
        paths: NoxsPaths,
        bootstrapHttpApt: Boolean,
        hostLabel: String,
        banner: String = "Noxs Debian 12 (bookworm)",
        hint: String = "sudo apt update"
    ) {
        val rootfs = paths.rootfs

        // --- Real directories first (usr/bin, usr/sbin, usr/lib BEFORE FHS links) ---
        listOf(
            "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin", "usr/local/lib",
            "usr/local/lib/noxs", "usr/share", "boot", "dev", "etc", "etc/noxs",
            "etc/profile.d", "etc/apt/apt.conf.d", "home", "home/noxs", "media",
            "mnt", "opt", "proc", "root", "run", "srv", "sys", "tmp",
            "var/cache", "var/cache/apt/archives/partial", "var/lib", "var/lib/dpkg",
            "var/lib/dpkg/alternatives", "var/lib/dpkg/info", "var/lib/dpkg/updates",
            "var/lib/dpkg/triggers", "var/lib/apt/lists/partial", "var/log/apt",
            "var/log/noxs", "var/run/noxs", "var/run/noxs/host", "var/spool"
        ).forEach { rel ->
            val f = File(rootfs, rel)
            if (!Files.isSymbolicLink(f.toPath())) f.mkdirs()
        }

        // --- FHS top-level symlinks (bin -> usr/bin, sbin -> usr/sbin, lib -> usr/lib) ---
        ensureSymlink(File(rootfs, "bin"), "usr/bin")
        ensureSymlink(File(rootfs, "sbin"), "usr/sbin")
        ensureSymlink(File(rootfs, "lib"), "usr/lib")

        // --- Default resource quotas file (bound by ProotLauncher) ---
        if (!paths.noxsResourcesConf.isFile) {
            writeFile(paths.noxsResourcesConf, ResourceQuotas().serialize())
        }

        // --- profile.d/noxs.sh ---
        writeFile(File(rootfs, "etc/profile.d/noxs.sh"), noxsProfile(hostLabel))
        File(rootfs, "etc/profile.d/noxs.sh").setExecutable(false)

        // --- profile.d/noxs-pkg-guard.sh (package-manager serialization) ---
        writeFile(File(rootfs, "etc/profile.d/noxs-pkg-guard.sh"), NOXS_PKG_GUARD_SH)
        File(rootfs, "etc/profile.d/noxs-pkg-guard.sh").setExecutable(false)

        // --- /usr/local/lib/noxs-welcome.sh (interactive welcome dashboard) ---
        installWelcomeLib(rootfs)


        // --- /etc/environment ---
        writeFile(File(rootfs, "etc/environment"), ENVIRONMENT)

        // --- /etc/motd ---
        writeFile(File(rootfs, "etc/motd"), motd(banner))

        // --- DNS ---
        if (!File(rootfs, "etc/resolv.conf").isFile || File(rootfs, "etc/resolv.conf").length() == 0L) {
            writeFile(File(rootfs, "etc/resolv.conf"), DNS)
        }
        writeFile(File(rootfs, "etc/hosts"), HOSTS)
        writeFile(File(rootfs, "etc/hostname"), "noxs\n")

        // --- APT configuration (one Debian source file, isolated to this rootfs) ---
        configureAptSources(paths, useHttps = !bootstrapHttpApt)
        writeFile(File(rootfs, "etc/apt/apt.conf.d/70noxs"), APT_CONF)
        repairDpkgPermissions(paths)

        // --- `noxs` CLI ---
        val cli = File(rootfs, "usr/local/bin/noxs")
        writeFile(cli, NOXS_CLI)
        cli.setExecutable(true, false)

        // --- `nx` package CLI (NX Package System) ---
        installNxPackageSystem(rootfs)

        val storageSetup = File(rootfs, "usr/local/bin/noxs-setup-storage")
        writeFile(storageSetup, NoxsCliTemplate.STORAGE_SETUP)
        storageSetup.setExecutable(true, false)

        // --- `noxs-hello` ---
        File(rootfs, "usr/local/bin/noxs-hello").let {
            writeFile(it, HELLO)
            it.setExecutable(true, false)
        }

        // --- Android proot-compatible `su` and `sudo` ---
        installSuAndSudo(rootfs)

        // --- Ensure `noxs` user and interactive bashrc prompt/banner ---
        ensureNoxsUserAndBashrc(rootfs, hostLabel, banner, hint)

        NoxsLog.i("RootfsConfig", "Debian userspace configured")
    }

    /**
     * Keep exactly one active APT definition. The stock Debian rootfs ships
     * debian.sources; remove it and any other active .list/.sources files so
     * APT sees only the Noxs Bookworm, updates, and security repositories.
     */
    fun configureAptSources(paths: NoxsPaths, useHttps: Boolean) {
        val aptDir = File(paths.rootfs, "etc/apt")
        val sourceDir = File(aptDir, "sources.list.d")
        if (!sourceDir.isDirectory && !sourceDir.mkdirs()) {
            throw IllegalStateException("Cannot create ${sourceDir.absolutePath}")
        }
        File(aptDir, "sources.list").let { legacy ->
            if ((legacy.exists() || Files.isSymbolicLink(legacy.toPath())) && !legacy.delete()) {
                throw IllegalStateException("Cannot remove duplicate APT source ${legacy.absolutePath}")
            }
        }
        sourceDir.listFiles()?.filter {
            it.name.endsWith(".list") || it.name.endsWith(".sources")
        }?.forEach { oldSource ->
            if (!oldSource.delete()) {
                throw IllegalStateException("Cannot remove duplicate APT source ${oldSource.absolutePath}")
            }
        }
        writeFile(File(sourceDir, "noxs.sources"), aptSources(useHttps))
    }

    /**
     * Install the `nx` CLI, the pkg modules and the language templates
     * (NX Package System). Idempotent: safe to run on every configuration.
     */
    private fun installNxPackageSystem(rootfs: File) {
        val nx = File(rootfs, "usr/local/bin/nx")
        writeFile(nx, NoxsNxTemplate.NX_CLI)
        nx.setExecutable(true, false)

        val libDir = File(rootfs, "usr/local/lib/noxs-pkg")
        if (!libDir.isDirectory && !libDir.mkdirs()) {
            throw IllegalStateException("Cannot create ${libDir.absolutePath}")
        }
        listOf(
            "pkg-lib.sh" to NoxsNxPkgLib.PKG_LIB,
            "pkg-init.sh" to NoxsNxPkgInit.PKG_INIT,
            "pkg-dev.sh" to NoxsNxPkgDev.PKG_DEV,
            "pkg-install.sh" to NoxsNxPkgInstall.PKG_INSTALL,
            "web-lib.sh" to NoxsNxWebTemplate.WEB_LIB,
            "vpn-lib.sh" to NoxsNxVpnLib.VPN_LIB,
            "plug-lib.sh" to NoxsNxPlugLib.PLUG_LIB,
            "cert-fix.sh" to NoxsNxCertFix.CERT_FIX_LIB,
            "ai-lib.sh" to NoxsNxAiLib.AI_LIB
        ).forEach { (name, content) ->
            val f = File(libDir, name)
            writeFile(f, content)
            f.setExecutable(true, false)
        }

        val templateRoot = File(rootfs, "usr/local/share/noxs-pkg")
        NoxsNxPackageTemplates.FILES.forEach { (rel, content) ->
            installNxTemplateFile(templateRoot, rel, content)
        }
        // Assemble complete per-language trees: every language gets the
        // shared workflow + common metadata files inside its files/ dir so
        // `nx pkg init` can copy one directory unmodified.
        NoxsNxPackageTemplates.LANGUAGES.forEach { lang ->
            val filesDir = File(templateRoot, "templates/$lang/files")
            val workflowDir = File(filesDir, ".github/workflows")
            if (!workflowDir.isDirectory && !workflowDir.mkdirs()) {
                throw IllegalStateException("Cannot create ${workflowDir.absolutePath}")
            }
            val workflow = File(workflowDir, "pkg.yml")
            writeFile(workflow, NoxsNxWorkflow.WORKFLOW_YML)
            NoxsNxPackageTemplates.COMMON_FILES.forEach { name ->
                writeFile(File(filesDir, name), NoxsNxPackageTemplates.FILES.getValue("templates/_shared/$name"))
            }
        }
        writeFile(File(templateRoot, ".nx-version"), "$NX_PACKAGE_SYSTEM_VERSION\n")

        // --- @noxs/nx-api SDK (Noxs API for NX packages) ---
        val apiDir = File(rootfs, "usr/local/lib/noxs/nx-api")
        if (!apiDir.isDirectory && !apiDir.mkdirs()) {
            throw IllegalStateException("Cannot create ${apiDir.absolutePath}")
        }
        writeFile(File(apiDir, "nx-api.js"), NoxsNxApiTemplate.API_JS)
        writeFile(File(apiDir, "package.json"), NoxsNxApiTemplate.PACKAGE_JSON)
        writeFile(File(apiDir, "README.md"), NoxsNxApiTemplate.API_README)

        // --- Noxs AI Agent runtime (nx ai) ---
        val aiDir = File(rootfs, "usr/local/lib/noxs/ai")
        if (!aiDir.isDirectory && !aiDir.mkdirs()) {
            throw IllegalStateException("Cannot create ${aiDir.absolutePath}")
        }
        writeFile(File(aiDir, "provider.py"), NoxsNxAiProvider.AI_PROVIDER_PY)
        writeFile(File(aiDir, "tools.py"), NoxsNxAiTools.AI_TOOLS_PY)
        writeFile(File(aiDir, "agent.py"), NoxsNxAiAgent.AI_AGENT_PY)
        NoxsLog.i(
            "RootfsConfig",
            "NX package system installed (nx + ${NoxsNxPackageTemplates.LANGUAGES.size} templates + nx-api + ai)"
        )
    }

    /** Copy one template file; the key set is a compile-time constant, but
     *  the relative path is still boundary-checked before touching disk. */
    private fun installNxTemplateFile(templateRoot: File, rel: String, content: String) {
        require(!rel.startsWith("/") && !rel.contains("..")) {
            "Unsafe template path: $rel"
        }
        val f = File(templateRoot, rel)
        f.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IllegalStateException("Cannot create ${parent.absolutePath}")
            }
        }
        writeFile(f, content)
    }

    /**
     * Migration hook (spec §64 spirit): environments installed by earlier
     * Noxs versions gain the `nx` package system without a reinstall. The
     * version marker keeps the per-start cost to one stat + one read.
     */
    fun ensureNxPackageSystem(paths: NoxsPaths) {
        val marker = File(paths.rootfs, "usr/local/share/noxs-pkg/.nx-version")
        if (marker.isFile && marker.readText().trim() == NX_PACKAGE_SYSTEM_VERSION) return
        val rootfs = paths.rootfs
        listOf("usr/local/bin", "usr/local/lib").forEach { rel ->
            val dir = File(rootfs, rel)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IllegalStateException("Cannot create ${dir.absolutePath}")
            }
        }
        installNxPackageSystem(rootfs)
        // Migration cleanups: retired guest lib files (nx env removal).
        val libDir = File(rootfs, "usr/local/lib/noxs-pkg")
        RETIRED_LIB_FILES.forEach { name ->
            val stale = File(libDir, name)
            if (stale.isFile && !stale.delete()) {
                NoxsLog.w("RootfsConfig", "migration could not delete stale lib $name")
            }
        }
        refreshShellIntegration(paths)
    }

    /** Writes the interactive welcome dashboard renderer (idempotent). */
    private fun installWelcomeLib(rootfs: File) {
        val lib = File(rootfs, "usr/local/lib/noxs-welcome.sh")
        writeFile(lib, NoxsWelcome.WELCOME_SH)
        lib.setExecutable(true, false)
    }

    /**
     * Migration helper: rewrites /etc/profile.d/noxs.sh wholesale and replaces
     * the appended "Noxs interactive shell setup" block (marker to EOF) in the
     * bashrc files with the current snippet, so environments installed by
     * earlier Noxs versions gain updated shell integration (currently OSC 7
     * working-directory reporting) without a reinstall. Content above the
     * marker — user edits and distro defaults — is kept intact.
     */
    private fun refreshShellIntegration(paths: NoxsPaths) {
        runCatching {
            val rootfs = paths.rootfs
            writeFile(File(rootfs, "etc/profile.d/noxs.sh"), noxsProfile("android"))
            File(rootfs, "etc/profile.d/noxs.sh").setExecutable(false)
            installWelcomeLib(rootfs)
            val marker = "# --- Noxs interactive shell setup ---"
            val snippet = bashrcSnippet("android", "Noxs Debian 12 (bookworm)", "sudo apt update")
            listOf(
                File(rootfs, "etc/bash.bashrc"),
                File(rootfs, "home/noxs/.bashrc"),
                File(rootfs, "root/.bashrc")
            ).forEach { rc ->
                if (!rc.isFile) return@forEach
                val existing = rc.readText()
                val idx = existing.indexOf(marker)
                val updated = if (idx >= 0) {
                    val kept = existing.substring(0, idx).trimEnd()
                    if (kept.isEmpty()) snippet + "\n" else kept + "\n\n" + snippet + "\n"
                } else {
                    existing.trimEnd() + "\n\n" + snippet + "\n"
                }
                writeFile(rc, updated)
            }
        }.onFailure {
            NoxsLog.w("RootfsConfig", "shell integration refresh failed: ${it.message}")
        }
    }

    // 5: added env-lib.sh (nx env Multi-Env Manager CLI) — REMOVED in 9
    // 6: adds vpn-lib.sh (nx vpn Tor over PRoot) to existing environments
    // 7: adds plug-lib.sh (nx plug Noxs Plugin Store) to existing environments
    // 8: adds cert-fix.sh (nx cert-fix — APT/CA repair + curl/wget provisioning)
    // 9: removes env-lib.sh + the `nx env` dispatcher (multi-OS environments
    //    retired; Debian 12 is the one environment). The migration deletes
    //    the stale guest lib so no orphan script lingers on existing installs.
    // 10: rewrites cert-fix.sh — apt-get update is now verified beyond the
    //    exit code (strict error mode + output scan + populated-lists check)
    //    so the signed-HTTP fallback actually fires on fresh rootfs installs
    //    without ca-certificates; dpkg recovery runs before the install;
    //    a missing noxs.sources is self-healed; the terminal output uses a
    //    scrollback-safe transcript instead of cursor-redrawn live boxes.
    // 11: shell integration emits OSC 7 on every prompt (PROMPT_COMMAND in
    //    both profile.d/noxs.sh and the bashrc snippets) so the app tracks
    //    the shell working directory for the NXEditor workspace detection;
    //    migration rewrites profile.d/noxs.sh and replaces the bashrc blocks.
    // 12: welcome dashboard — installs /usr/local/lib/noxs-welcome.sh and
    //    replaces the plain two-line bashrc banner with a guarded source of
    //    the renderer: new interactive sessions only, real system facts
    //    (os-release, uname, user), NO_COLOR/narrow fallbacks; migration
    //    installs the lib and rewrites the bashrc blocks in existing
    //    environments without a reinstall.
    // internal: asserted from unit tests (NoxsNxAiTest migration marker check)
    internal val NX_PACKAGE_SYSTEM_VERSION = "12"

    /** Guest lib files removed by a migration version — deleted on update. */
    private val RETIRED_LIB_FILES = listOf("env-lib.sh")

    /**
     * Repair only dpkg/APT state directories that the sandbox process must
     * update. This deliberately does not recurse over or chmod the rootfs.
     */
    fun repairDpkgPermissions(paths: NoxsPaths) {
        val root = paths.rootfs.canonicalFile.toPath()
        val writableDirs = listOf(
            "var/lib/dpkg", "var/lib/dpkg/alternatives", "var/lib/dpkg/info",
            "var/lib/dpkg/updates", "var/lib/dpkg/triggers", "var/lib/apt",
            "var/lib/apt/lists", "var/lib/apt/lists/partial", "var/cache/apt",
            "var/cache/apt/archives", "var/cache/apt/archives/partial", "var/log/apt"
        )
        writableDirs.forEach { relative ->
            assertDpkgPathContained(paths, relative)
            val dir = File(paths.rootfs, relative)
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IllegalStateException("Cannot create dpkg state directory: $relative")
            }
            assertDpkgPathContained(paths, relative)
            val canonical = dir.canonicalFile.toPath()
            if (!canonical.startsWith(root)) {
                throw SecurityException("dpkg state directory escapes rootfs: $relative")
            }
            if (!dir.setReadable(true, true) && !dir.canRead()) {
                throw IllegalStateException("Cannot restore owner read permission: $relative")
            }
            if (!dir.setWritable(true, true) && !dir.canWrite()) {
                throw IllegalStateException("Cannot restore owner write permission: $relative")
            }
            if (!dir.setExecutable(true, true) && !dir.canExecute()) {
                throw IllegalStateException("Cannot restore owner search permission: $relative")
            }
        }
        // dpkg keeps the installed-package database and its backups in this
        // directory. Symlinks that appear here (status-old -> status shows up
        // under some proot/filesystem combinations while dpkg rewrites its
        // status database) are first normalized into real regular files;
        // links resolving outside the rootfs are still refused.
        listOf("status", "status-old", "status-new").forEach { name ->
            val relative = "var/lib/dpkg/$name"
            normalizeDpkgStateFile(paths, relative)
            assertDpkgPathContained(paths, relative)
            val file = File(paths.rootfs, relative)
            if (!file.exists()) return@forEach
            if (!file.isFile) {
                throw IllegalStateException("Refusing non-regular dpkg database file: $name")
            }
            if (!file.canonicalFile.toPath().startsWith(root)) {
                throw SecurityException("dpkg database file escapes rootfs: $name")
            }
            if (!file.setReadable(true, true) && !file.canRead()) {
                throw IllegalStateException("Cannot restore dpkg database read permission: $name")
            }
            if (!file.setWritable(true, true) && !file.canWrite()) {
                throw IllegalStateException("Cannot restore dpkg database write permission: $name")
            }
        }
        NoxsLog.i("RootfsConfig", "Repaired only dpkg/APT state owner permissions")
    }

    /**
     * Replaces a dpkg database symlink with a real regular file so dpkg's
     * rename-based status backup keeps working under proot. Internal links
     * (for example status-old -> status) are materialized from their content;
     * links resolving outside the rootfs are refused with a SecurityException.
     */
    private fun normalizeDpkgStateFile(paths: NoxsPaths, relative: String) {
        val file = File(paths.rootfs, relative)
        if (!Files.isSymbolicLink(file.toPath())) return
        val root = paths.rootfs.canonicalFile.toPath()
        val target = runCatching { Files.readSymbolicLink(file.toPath()).toString() }.getOrDefault("")
        val parent = requireNotNull(file.parentFile) { "dpkg state path has no parent: $relative" }
        val resolved = if (target.startsWith("/")) {
            // An absolute target can be a host-shaped link created outside the
            // Noxs guest. If a real host file exists at that path and outside
            // the rootfs, refuse; otherwise proot treats it as guest-absolute.
            val asHost = File(target)
            val hostOutside = asHost.exists() &&
                !runCatching { asHost.canonicalFile.toPath().startsWith(root) }.getOrDefault(false)
            if (hostOutside) {
                throw SecurityException("dpkg state symlink escapes rootfs: $relative -> $target")
            }
            File(paths.rootfs, target.trimStart('/'))
        } else {
            File(parent, target)
        }
        val contained = runCatching { resolved.canonicalFile.toPath().startsWith(root) }.getOrDefault(false)
        if (!contained) {
            throw SecurityException("dpkg state symlink escapes rootfs: $relative -> $target")
        }
        val replacement = materializeDpkgStateContent(file, resolved)
        if (file.name == "status" && replacement.isEmpty()) {
            throw IllegalStateException("dpkg status database is a dangling symlink: $relative -> $target")
        }
        val temp = File(parent, "${file.name}.noxs-repair")
        try {
            temp.writeBytes(replacement)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            NoxsLog.i("RootfsConfig", "Normalized dpkg state symlink: $relative -> $target")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Content for the normalized dpkg state file: the link target's bytes, or
     * the sibling dpkg backup when the target is dangling, or an empty file
     * dpkg safely rewrites on its next status update.
     */
    private fun materializeDpkgStateContent(file: File, resolved: File): ByteArray {
        val fromTarget = runCatching {
            if (Files.isRegularFile(resolved.toPath())) Files.readAllBytes(resolved.toPath()) else null
        }.getOrNull()
        if (fromTarget != null) return fromTarget
        val sibling = when (file.name) {
            "status" -> File(file.parentFile, "status-old")
            "status-old" -> File(file.parentFile, "status")
            else -> null
        }
        val fromSibling = sibling?.let {
            runCatching {
                if (it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() > 0L) it.readBytes() else null
            }.getOrNull()
        }
        return fromSibling ?: ByteArray(0)
    }

    /** Checks each component before mkdirs can follow or create through a symlink. */
    private fun assertDpkgPathContained(paths: NoxsPaths, relative: String) {
        val root = paths.rootfs.canonicalFile.toPath()
        var current = paths.rootfs
        relative.split('/').forEach { segment ->
            current = File(current, segment)
            val path = current.toPath()
            if (Files.isSymbolicLink(path)) {
                throw IllegalStateException("Refusing symlink in dpkg state path: $relative")
            }
            if (current.exists() && !current.canonicalFile.toPath().startsWith(root)) {
                throw SecurityException("dpkg state path escapes rootfs: $relative")
            }
        }
    }

    /**
     * Repairs an existing rootfs if an earlier version extracted 0-byte placeholder
     * files instead of real symbolic links, then refreshes configuration.
     */
    fun ensureHealthyRootfs(paths: NoxsPaths) {
        val rootfs = paths.rootfs
        if (!rootfs.isDirectory) return
        val binFile = File(rootfs, "bin")
        val shFile = File(rootfs, "usr/bin/sh")
        val ldAarch64 = File(rootfs, "usr/lib/ld-linux-aarch64.so.1")
        val needsRepair = (!Files.isSymbolicLink(binFile.toPath()) && binFile.isFile) ||
            (shFile.exists() && !Files.isSymbolicLink(shFile.toPath()) && shFile.length() == 0L) ||
            (ldAarch64.exists() && !Files.isSymbolicLink(ldAarch64.toPath()) && ldAarch64.length() == 0L)

        if (needsRepair) {
            NoxsLog.i("RootfsConfig", "Detected broken 0-byte symlink placeholders in rootfs; repairing...")
            // Remove 0-byte top-level files that block directory/symlink creation
            listOf("bin", "sbin", "lib", "lib64").forEach { name ->
                val f = File(rootfs, name)
                if (!Files.isSymbolicLink(f.toPath()) && f.isFile && f.length() == 0L) {
                    f.delete()
                }
            }
            val cachedArchive = paths.cache.listFiles()?.firstOrNull {
                it.isFile && it.name.startsWith("rootfs-") && it.length() > 1_000_000L
            }
            if (cachedArchive != null) {
                runCatching {
                    val stats = RootfsExtractor(rootfs).extract(cachedArchive)
                    NoxsLog.i("RootfsConfig", "Re-extracted rootfs from cache (links=${stats.links})")
                }.onFailure { e ->
                    NoxsLog.w("RootfsConfig", "Cache re-extract failed: ${e.message}; running in-place symlink repair")
                }
            }
            repairZeroByteSymlinks(rootfs)
        }
        configure(paths)
    }

    private fun ensureSymlink(link: File, target: String) {
        val p = link.toPath()
        if (Files.isSymbolicLink(p)) return
        if (link.isFile && link.length() == 0L) link.delete()
        if (!link.exists()) {
            runCatching { Files.createSymbolicLink(p, Paths.get(target)) }
        }
    }

    private fun repairZeroByteSymlinks(rootfs: File) {
        ensureSymlink(File(rootfs, "bin"), "usr/bin")
        ensureSymlink(File(rootfs, "sbin"), "usr/sbin")
        ensureSymlink(File(rootfs, "lib"), "usr/lib")
        ensureSymlink(File(rootfs, "usr/bin/sh"), "dash")
        ensureSymlink(File(rootfs, "usr/bin/awk"), "mawk")
        if (File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").isFile) {
            ensureSymlink(File(rootfs, "usr/lib/ld-linux-aarch64.so.1"), "aarch64-linux-gnu/ld-linux-aarch64.so.1")
        }
        // Repair 0-byte shared library SONAME placeholders (e.g. libtinfo.so.6 -> libtinfo.so.6.4)
        val usrLib = File(rootfs, "usr/lib")
        if (usrLib.isDirectory) {
            usrLib.walkTopDown().filter {
                !Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() == 0L && it.name.contains(".so")
            }.forEach { zeroSo ->
                val parent = zeroSo.parentFile ?: return@forEach
                val candidate = parent.listFiles()?.firstOrNull {
                    it.name != zeroSo.name &&
                        it.name.startsWith(zeroSo.name + ".") &&
                        it.isFile &&
                        it.length() > 0L
                }
                if (candidate != null) {
                    zeroSo.delete()
                    runCatching { Files.createSymbolicLink(zeroSo.toPath(), Paths.get(candidate.name)) }
                }
            }
        }
    }

    private fun installSuAndSudo(rootfs: File) {
        val suFile = File(rootfs, "usr/bin/su")
        val suOrig = File(rootfs, "usr/bin/su.orig")
        if (suFile.isFile && !suOrig.exists() && suFile.length() > 4096L) {
            runCatching { suFile.renameTo(suOrig) }
        }
        writeFile(suFile, NOXS_SU_WRAPPER)
        suFile.setExecutable(true, false)

        val sudoFile = File(rootfs, "usr/local/bin/sudo")
        writeFile(sudoFile, NOXS_SUDO_WRAPPER)
        sudoFile.setExecutable(true, false)
    }

    private fun ensureNoxsUserAndBashrc(
        rootfs: File,
        hostLabel: String = "android",
        banner: String = "Noxs Debian 12 (bookworm)",
        hint: String = "sudo apt update"
    ) {
        val etc = File(rootfs, "etc")
        val passwdFile = File(etc, "passwd")
        val groupFile = File(etc, "group")
        val shadowFile = File(etc, "shadow")
        if (passwdFile.isFile) {
            val users = PasswdDb.parsePasswd(passwdFile.readText())
            if (users.none { it.name == NoxsConstants.DEFAULT_USER }) {
                val uid = PasswdDb.nextFreeUid(users)
                val user = NoxsUser(
                    name = NoxsConstants.DEFAULT_USER,
                    uid = uid,
                    gid = uid,
                    gecos = "Noxs User,,,",
                    home = NoxsConstants.DEFAULT_USER_HOME,
                    shell = NoxsConstants.DEFAULT_SHELL
                )
                passwdFile.appendText(PasswdDb.serializeUser(user) + "\n")
                if (shadowFile.isFile) {
                    shadowFile.appendText(PasswdDb.defaultShadowLineFor(user.name) + "\n")
                }
                if (groupFile.isFile) {
                    val groups = PasswdDb.parseGroup(groupFile.readText())
                    if (groups.none { it.name == "sudo" }) {
                        groupFile.appendText(PasswdDb.serializeGroup(NoxsGroup("sudo", 27, emptyList())) + "\n")
                    }
                    val updated = groupFile.readLines().joinToString("\n") { line ->
                        if (line.startsWith("sudo:") && !line.contains(NoxsConstants.DEFAULT_USER)) {
                            "$line,${NoxsConstants.DEFAULT_USER}"
                        } else line
                    }
                    groupFile.writeText(updated + "\n")
                }
            }
        }

        val homeNoxs = File(rootfs, "home/noxs")
        val homeRoot = File(rootfs, "root")
        homeNoxs.mkdirs()
        homeRoot.mkdirs()

        listOf(".bashrc", ".profile").forEach { name ->
            val skel = File(rootfs, "etc/skel/$name")
            listOf(homeNoxs, homeRoot).forEach { dir ->
                val target = File(dir, name)
                if (skel.isFile && !target.isFile) {
                    runCatching { skel.copyTo(target, overwrite = false) }
                }
            }
        }

        val marker = "# --- Noxs interactive shell setup ---"
        listOf(
            File(rootfs, "etc/bash.bashrc"),
            File(homeNoxs, ".bashrc"),
            File(homeRoot, ".bashrc")
        ).forEach { rc ->
            val existing = if (rc.isFile) rc.readText() else ""
            if (!existing.contains(marker)) {
                writeFile(rc, existing.trimEnd() + "\n\n" + bashrcSnippet(hostLabel, banner, hint) + "\n")
            }
        }
    }

    private fun writeFile(f: File, content: String) {
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun motd(banner: String = "Noxs Debian 12 (bookworm)"): String = """
        Welcome to Noxs ($banner userspace)

        * All operations run inside the Android app sandbox — you have full
          control of the Noxs Linux environment, not of the Android device.
        * `sudo` asks for your noxs password and manages THIS environment.
        * `noxs code` manages the integrated code-server (VS Code in browser).
        * `noxs-service start|stop|status` manages sandbox services.
        * `noxs-setup-storage` opens Android's folder picker for SAF command access.
        * `noxs storage help` lists SAF document commands (not POSIX paths).
        * `noxs help` lists all Noxs commands.
    """.trimIndent() + "\n"

    private fun aptSources(useHttps: Boolean): String {
        val scheme = if (useHttps) "https" else "http"
        return """
            Types: deb
            URIs: $scheme://deb.debian.org/debian
            Suites: bookworm bookworm-updates
            Components: main contrib non-free non-free-firmware
            Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg

            Types: deb
            URIs: $scheme://security.debian.org/debian-security
            Suites: bookworm-security
            Components: main contrib non-free non-free-firmware
            Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
        """.trimIndent() + "\n"
    }

    // ------------------------------------------------------------ templates

    /**
     * Friendly package-manager serialization for interactive shells.
     * REAL detection only: the app-side transaction flag (/run/noxs/pkg-tx,
     * armed by every Noxs-managed apt/dpkg one-shot) plus running package
     * processes. NEVER deletes or bypasses dpkg lock files.
     */
    internal val NOXS_PKG_GUARD_SH = """
        # /etc/profile.d/noxs-pkg-guard.sh — Noxs package-manager guard
        noxs_pkg_busy() {
            [ -f /run/noxs/pkg-tx ] && return 0
            pgrep -x dpkg >/dev/null 2>&1 && return 0
            pgrep -x apt-get >/dev/null 2>&1 && return 0
            pgrep -x apt >/dev/null 2>&1 && return 0
            return 1
        }
        noxs_pkg_busy_msg() {
            printf '%s\n' 'Noxs package manager is busy.'
            printf '%s\n' 'Another apt/dpkg operation is currently running.'
            printf '%s\n' 'Wait for it to finish, then run your command again.'
        }
        apt() {
            if noxs_pkg_busy; then noxs_pkg_busy_msg; return 1; fi
            command apt "\$@"
        }
        apt-get() {
            if noxs_pkg_busy; then noxs_pkg_busy_msg; return 1; fi
            command apt-get "\$@"
        }
    """.trimIndent()

    private fun noxsProfile(hostLabel: String): String = """
        # /etc/profile.d/noxs.sh — Noxs environment integration
        export NOXS=1
        export NOXS_USER=${NoxsConstants.DEFAULT_USER}
        export NOXS_RUN_DIR=${NoxsConstants.NOXS_RUN_DIR}
        export NOXS_HOME=${NoxsConstants.DEFAULT_USER_HOME}
        export PATH="/usr/local/bin:${'$'}PATH"
        export PS1='noxs@$hostLabel:\w\$ '
        export PS0=${'$'}'\033]133;C\a'
        # OSC 133 marks the prompt; OSC 7 reports the working directory so the
        # app (NXEditor workspace, Activity Center) tracks it without polling.
        PROMPT_COMMAND='printf "\033]133;A\007\033]7;file://%s\007" "${'$'}{PWD// /%20}"'
        # recreate FHS links once (app storage cannot create symlinks directly)
        if [ -x /usr/local/lib/noxs-links.sh ]; then
            /usr/local/lib/noxs-links.sh >/dev/null 2>&1 || true
        fi
        # apply resource quotas (children of this shell only)
        if [ -r /etc/noxs/resources.conf ]; then
            . /etc/noxs/resources.conf 2>/dev/null || true
            [ -n "${'$'}MAX_PROCESSES" ] && ulimit -u "${'$'}MAX_PROCESSES" 2>/dev/null || true
            [ -n "${'$'}MAX_OPEN_FILES" ] && ulimit -n "${'$'}MAX_OPEN_FILES" 2>/dev/null || true
        fi
    """.trimIndent()

    private fun bashrcSnippet(hostLabel: String, banner: String, hint: String) = """
        # --- Noxs interactive shell setup ---
        unset LD_LIBRARY_PATH
        export NOXS=1
        export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        if [ "${'$'}{NOXS_ROOT_LOGIN:-0}" = "1" ]; then
            export USER="root"
            export LOGNAME="root"
            export PS1='\[\033[01;31m\]root@noxs\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]# '
        else
            export USER="${'$'}{USER:-noxs}"
            export LOGNAME="${'$'}{LOGNAME:-noxs}"
            export PS1='\[\033[01;32m\]noxs@$hostLabel\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]$ '
        fi
        # OSC 133 marks Bash's editable prompt; the terminal asks readline to
        # redisplay after background output arrives during command editing.
        # OSC 7 reports the working directory for the Noxs app (NXEditor).
        export PS0=${'$'}'\033]133;C\a'
        PROMPT_COMMAND='printf "\033]133;A\007\033]7;file://%s\007" "${'$'}{PWD// /%20}"'
        # Welcome dashboard: rendered by /usr/local/lib/noxs-welcome.sh. The
        # guard keeps it to NEW INTERACTIVE sessions only — never one-shot
        # commands, scripts or non-tty output — and never twice per session
        # tree (NOXS_BANNER_SHOWN is inherited by child shells).
        if [ -z "${'$'}{NOXS_BANNER_SHOWN:-}" ] && [ -t 1 ] && [ -r /usr/local/lib/noxs-welcome.sh ]; then
            export NOXS_BANNER_SHOWN=1
            . /usr/local/lib/noxs-welcome.sh
            noxs_welcome
        fi
    """.trimIndent()

    private val NOXS_SU_WRAPPER = """
        #!/bin/sh
        # Noxs proot-compatible su implementation.
        # Debian's PAM su calls libaudit NETLINK_AUDIT which Android SELinux blocks with EACCES.
        SHELL_BIN="/bin/bash"
        LOGIN_SHELL=0
        CMD=""
        HAS_CMD=0
        TARGET_USER="root"

        while [ ${'$'}# -gt 0 ]; do
            case "${'$'}1" in
                -l|-|--login)
                    LOGIN_SHELL=1
                    shift
                    ;;
                -s|--shell)
                    SHELL_BIN="${'$'}{2:-/bin/bash}"
                    shift 2
                    ;;
                -c|--command)
                    CMD="${'$'}{2:-}"
                    HAS_CMD=1
                    shift 2
                    ;;
                -p|-m|--preserve-environment)
                    shift
                    ;;
                --)
                    shift
                    break
                    ;;
                -*)
                    shift
                    ;;
                *)
                    TARGET_USER="${'$'}1"
                    shift
                    ;;
            esac
        done

        if [ ${'$'}# -gt 0 ] && [ "${'$'}TARGET_USER" = "root" ]; then
            TARGET_USER="${'$'}1"
        fi

        unset LD_LIBRARY_PATH
        export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        export SHELL="${'$'}SHELL_BIN"

        if [ "${'$'}TARGET_USER" = "root" ]; then
            export USER="root"
            export LOGNAME="root"
            export HOME="/root"
            export NOXS_ROOT_LOGIN="1"
        else
            export USER="${'$'}TARGET_USER"
            export LOGNAME="${'$'}TARGET_USER"
            export HOME="/home/${'$'}TARGET_USER"
            unset NOXS_ROOT_LOGIN
        fi

        if [ "${'$'}LOGIN_SHELL" = "1" ] && [ -d "${'$'}HOME" ]; then
            cd "${'$'}HOME" 2>/dev/null || true
        fi

        if [ "${'$'}HAS_CMD" = "1" ]; then
            exec "${'$'}SHELL_BIN" -c "${'$'}CMD"
        fi

        exec "${'$'}SHELL_BIN" --login
    """.trimIndent() + "\n"

    private val NOXS_SUDO_WRAPPER = """
        #!/bin/sh
        # Noxs sandbox sudo wrapper (executes inside proot -0 fake-root userspace).
        unset LD_LIBRARY_PATH
        export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        SHELL_MODE=0
        while [ ${'$'}# -gt 0 ]; do
            case "${'$'}1" in
                -i|--login|-s|--shell)
                    SHELL_MODE=1
                    shift
                    ;;
                -u|--user)
                    shift 2
                    ;;
                -E|-H|-n|-S|-v|-k|-K)
                    shift
                    ;;
                --)
                    shift
                    break
                    ;;
                -*)
                    shift
                    ;;
                *)
                    break
                    ;;
            esac
        done
        export USER="root"
        export LOGNAME="root"
        export NOXS_ROOT_LOGIN="1"
        if [ "${'$'}SHELL_MODE" = "1" ] && [ ${'$'}# -eq 0 ]; then
            export HOME="/root"
            cd /root 2>/dev/null || true
            exec /bin/bash --login
        fi
        if [ ${'$'}# -eq 0 ]; then
            echo "usage: sudo [command ...]" >&2
            exit 1
        fi
        exec "${'$'}@"
    """.trimIndent() + "\n"

    private val ENVIRONMENT = """
        NOXS=1
        LANG=C.UTF-8
        EDITOR=nano
    """.trimIndent()

    private val DNS = """
        # Noxs sandbox DNS (editable in Settings)
        nameserver 1.1.1.1
        nameserver 8.8.8.8
    """.trimIndent()

    private val HOSTS = """
        127.0.0.1 localhost
        127.0.1.1 noxs
        ::1 localhost ip6-localhost
    """.trimIndent()

    private val APT_CONF = """
        // Noxs sandbox apt tuning (original file, isolated to the Noxs rootfs)
        APT::Install-Recommends "false";
        APT::Get::AllowUnauthenticated "false";
        Acquire::AllowInsecureRepositories "false";
        Acquire::AllowDowngradeToInsecureRepositories "false";
        Acquire::Retries "1";
        Acquire::http::Timeout "20";
        Acquire::https::Timeout "20";
        Acquire::Languages "none";
        Dir::Cache::archives "/var/cache/apt/archives";
    """.trimIndent()

    private val HELLO = """
        #!/bin/sh
        echo "Noxs ${BuildConfig.VERSION_NAME} — Debian 12 userspace inside the Android app sandbox."
        echo "Security notice: 'sudo' here manages the Noxs environment ONLY."
        echo "It does not provide Android device root access."
    """.trimIndent()

    /**
     * The `noxs` CLI — service manager, socket manager, process view and
     * code-server integration. Canonical source: linux-runtime/launcher/noxs-cli
     * (CI diff-checks the asset copy against it).
     */
    val NOXS_CLI = NoxsCliTemplate.CLI

    // ------------------------------------------------- multi-environment hooks
    // Internal (same Gradle module) accessors so the environments package can
    // reuse the battle-tested primitives with distro-specific parameters
    // instead of duplicating them. The legacy Debian path is untouched.

    internal fun ensureFhsLink(rootfs: File, name: String, target: String) {
        ensureSymlink(File(rootfs, name), target)
    }

    internal fun installNoxsSuSudo(rootfs: File) {
        installSuAndSudo(rootfs)
    }

    internal fun ensureNoxsUser(
        rootfs: File,
        hostLabel: String = "android",
        banner: String = "Noxs Debian 12 (bookworm)",
        hint: String = "sudo apt update"
    ) {
        ensureNoxsUserAndBashrc(rootfs, hostLabel, banner, hint)
    }
}
