/*
 * Noxs — original implementation.
 * RootFS integrity verification and targeted repair.
 *
 * Answers the "No such file or directory" class of failures reported for
 * system executables (/usr/bin/docker, /usr/local/bin/sudo, /usr/bin/clear):
 *
 *  - A missing ELF can simply be absent (partial extraction, package
 *    removal, sandbox reset) — OR present-but-unexecutable because the
 *    dynamic loader chain (/lib/ld-linux-aarch64.so.1 -> ...) is a
 *    0-byte placeholder left by an interrupted extraction.
 *
 *  LAYOUT TRUTH (verified against the shipped noxs-ubuntu-24.04-arm64
 *  tarball: `tar -tzf` + PT_INTERP parse of ./usr/bin/ls):
 *    - aarch64 ELFs hard-code PT_INTERP = /lib/ld-linux-aarch64.so.1
 *    - the base image ships /bin /sbin /lib as usr-merge symlinks and
 *      usr/lib/ld-linux-aarch64.so.1 -> aarch64-linux-gnu/ld-linux-aarch64.so.1
 *    - it ships NO /lib64 and NO usr/lib64 at all — lib64 is an x86-64
 *      concept. An earlier gate revision demanded /lib64 and failed setup
 *      on every genuine arm64 rootfs (false positive: exactly the trio
 *      /lib64, /usr/lib64/ld-linux-aarch64.so.1, /lib64/ld-linux-aarch64.so.1).
 *    /lib64 is therefore OPTIONAL here: checked for health when present,
 *      never demanded when absent.
 *  - /usr/local/bin/sudo is a Noxs-written shim (RootfsConfigurator); a
 *    configure() step that aborted before installSuAndSudo leaves it missing.
 *
 * Nothing here deletes user data: repairs recreate ONLY Noxs-owned shim
 * files, FHS compatibility symlinks and loader links — never real Ubuntu
 * binaries. Real binaries that are missing are restored from the cached
 * base archive (single-member extraction) or reported honestly with the
 * apt command that restores them.
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import com.crossberry.noxs.shared.RootfsExtractor
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object RootfsIntegrity {

    /** Per-check result row (UI + doctor share this shape). */
    data class Row(
        val path: String,
        val kind: Kind,
        val ok: Boolean,
        val detail: String
    ) {
        enum class Kind { SYMLINK, FILE, DIRECTORY }
    }

    data class Report(
        val rows: List<Row>,
        /** FHS/loader structure damage — session-starting repair handles this. */
        val structuralDamage: List<String>,
        /** Missing system executables (restore candidates). */
        val missingExecutables: List<String>,
        /** Missing Noxs-owned shims (sudo/su wrappers). */
        val missingShims: List<String>
    ) {
        val healthy: Boolean get() = structuralDamage.isEmpty() && missingExecutables.isEmpty() && missingShims.isEmpty()
        fun displayLines(): List<String> = rows.map {
            val mark = when {
                it.ok -> "[✓]"
                it.path.endsWith("sudo") || it.path.endsWith("su") -> "[!]"
                else -> "[✗]"
            }
            "$mark ${it.path}${if (it.detail.isBlank()) "" else " — ${it.detail}"}"
        }
    }

    // FHS compatibility links (must be symlinks; 0-byte placeholder files are the poison).
    private const val BIN = "bin"
    private const val SBIN = "sbin"
    private const val LIB = "lib"

    /**
     * OPTIONAL FHS link. Genuine Ubuntu/Debian arm64 base images ship no
     * /lib64 (the arm64 interpreter lives at /lib/ld-linux-aarch64.so.1);
     * requiring it was a false positive that blocked every real arm64
     * setup. When an image does ship it, it must be a healthy usr-merge
     * symlink, never a placeholder or real directory.
     */
    private const val LIB64 = "lib64"

    /**
     * aarch64 ELF interpreter chain — every Ubuntu ELF exec resolves through
     * it. [real] is rootfs-relative (the file the loader resolves to);
     * [lnTarget] is the link-relative string used when creating the symlink.
     */
    private data class LoaderLink(val link: String, val real: String, val lnTarget: String)

    private val LOADER_LINKS = listOf(
        LoaderLink(
            // The aarch64 interpreter chain: /lib/ld-linux-aarch64.so.1 is
            // the PT_INTERP baked into every arm64 ELF; it resolves through
            // /lib -> usr/lib to this link, then to the real multiarch
            // loader. Verified against the Ubuntu 24.04 arm64 base image.
            link = "usr/lib/ld-linux-aarch64.so.1",
            real = "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            lnTarget = "aarch64-linux-gnu/ld-linux-aarch64.so.1"
        )
    )

    /** The interpreter path every arm64 ELF hard-codes at exec time (PT_INTERP). */
    private const val ELF_INTERP = "lib/ld-linux-aarch64.so.1"

    /** System executables that must exist in every healthy Ubuntu userspace. */
    val REQUIRED_EXECUTABLES = listOf(
        "usr/bin/clear",   // ncurses-bin — TerminalActivity's Clear action
        "usr/bin/sh",      // dash symlink — every #!/bin/sh script (sudo shim, nx, noxs)
        "usr/bin/dash",
        "usr/bin/env",
        "usr/bin/bash"
    )

    /** Noxs-owned shims — recreated by RootfsConfigurator, never shipped in Ubuntu. */
    val NOXS_SHIMS = listOf("usr/local/bin/sudo", "usr/bin/su")

    /**
     * Verifies the structural integrity of [rootfs]. Pure filesystem reads —
     * safe to call from any thread, never modifies anything.
     */
    fun verify(rootfs: File): Report {
        val rows = mutableListOf<Row>()
        val structural = mutableListOf<String>()
        val missingExec = mutableListOf<String>()
        val missingShims = mutableListOf<String>()

        // Required usr-merge links — present in every Ubuntu arm64 base image.
        for (name in listOf(BIN to "usr/bin", SBIN to "usr/sbin", LIB to "usr/lib")) {
            val f = File(rootfs, name.first)
            val p = f.toPath()
            // Target existence resolves against the ROOTFS (the link target is
            // stored root-relative); File.resolve would wrongly anchor it at
            // the link's own path (root/bin/usr/bin) and always miss.
            val targetFile = File(rootfs, name.second)
            rows += when {
                Files.isSymbolicLink(p) && targetFile.exists() ->
                    Row("/" + name.first, Row.Kind.SYMLINK, true, "→ ${name.second}")
                Files.isSymbolicLink(p) ->
                    Row("/" + name.first, Row.Kind.SYMLINK, false, "dangling symlink (target ${name.second} missing)").also { structural.add("/" + name.first) }
                f.isDirectory ->
                    Row("/" + name.first, Row.Kind.DIRECTORY, false, "real directory where a usr-merge symlink belongs").also { structural.add("/" + name.first) }
                f.isFile && f.length() == 0L ->
                    Row("/" + name.first, Row.Kind.FILE, false, "0-byte placeholder, expected symlink").also { structural.add("/" + name.first) }
                f.isFile ->
                    Row("/" + name.first, Row.Kind.FILE, false, "unexpected regular file, expected symlink").also { structural.add("/" + name.first) }
                else ->
                    Row("/" + name.first, Row.Kind.SYMLINK, false, "missing").also { structural.add("/" + name.first) }
            }
        }

        // /lib64 is OPTIONAL (see [LIB64]): absence is the correct arm64
        // base-image layout; presence must be a healthy usr-merge symlink.
        run {
            val f = File(rootfs, LIB64)
            val p = f.toPath()
            val targetFile = File(rootfs, "usr/lib64")
            val absent = !Files.isSymbolicLink(p) && !Files.exists(p)
            rows += when {
                absent ->
                    Row("/" + LIB64, Row.Kind.SYMLINK, true,
                        "not shipped by this arm64 base image (interpreter is /lib/ld-linux-aarch64.so.1)")
                Files.isSymbolicLink(p) && targetFile.exists() ->
                    Row("/" + LIB64, Row.Kind.SYMLINK, true, "→ usr/lib64")
                Files.isSymbolicLink(p) ->
                    Row("/" + LIB64, Row.Kind.SYMLINK, false, "dangling symlink (target usr/lib64 missing)").also { structural.add("/" + LIB64) }
                f.isDirectory ->
                    Row("/" + LIB64, Row.Kind.DIRECTORY, false, "real directory where a usr-merge symlink belongs").also { structural.add("/" + LIB64) }
                f.isFile && f.length() == 0L ->
                    Row("/" + LIB64, Row.Kind.FILE, false, "0-byte placeholder, expected symlink").also { structural.add("/" + LIB64) }
                f.isFile ->
                    Row("/" + LIB64, Row.Kind.FILE, false, "unexpected regular file, expected symlink").also { structural.add("/" + LIB64) }
                else ->
                    Row("/" + LIB64, Row.Kind.SYMLINK, false, "missing").also { structural.add("/" + LIB64) }
            }
        }

        for (spec in LOADER_LINKS) {
            val f = File(rootfs, spec.link)
            val realTarget = File(rootfs, spec.real)
            rows += when {
                f.isFile && f.length() > 0L ->
                    // follows a valid symlink or a real file — the loader resolves either way
                    Row("/${spec.link}", Row.Kind.SYMLINK, true, "→ ${spec.real}")
                f.isFile && f.length() == 0L ->
                    Row("/${spec.link}", Row.Kind.FILE, false, "0-byte placeholder — every ELF exec fails with ENOENT").also { structural.add("/${spec.link}") }
                realTarget.exists() ->
                    Row("/${spec.link}", Row.Kind.SYMLINK, false, "missing loader link (real loader exists — repairable)").also { structural.add("/${spec.link}") }
                else ->
                    Row("/${spec.link}", Row.Kind.FILE, false, "loader binary missing — restore from the base image").also { structural.add("/${spec.link}") }
            }
        }

        // The hard interpreter path baked into arm64 ELFs (PT_INTERP):
        // /lib/ld-linux-aarch64.so.1 must RESOLVE through the usr-merge
        // chain (/lib -> usr/lib -> the real multiarch loader).
        val interp = File(rootfs, ELF_INTERP)
        rows += when {
            interp.isFile && interp.length() > 0L ->
                Row("/$ELF_INTERP", Row.Kind.SYMLINK, true, "ELF interpreter resolves")
            else ->
                Row("/$ELF_INTERP", Row.Kind.SYMLINK, false, "ELF interpreter path broken — exec fails with ENOENT").also {
                    structural.add("/$ELF_INTERP")
                }
        }

        for (rel in REQUIRED_EXECUTABLES) {
            val f = File(rootfs, rel)
            rows += when {
                f.isFile && f.length() > 0L -> Row("/$rel", Row.Kind.FILE, true, "")
                f.isFile && f.length() == 0L ->
                    Row("/$rel", Row.Kind.FILE, false, "0-byte file — restore from the base image").also { missingExec.add(rel) }
                else ->
                    Row("/$rel", Row.Kind.FILE, false, "missing — restore from the base image").also { missingExec.add(rel) }
            }
        }

        for (rel in NOXS_SHIMS) {
            val f = File(rootfs, rel)
            rows += when {
                f.isFile && f.length() > 0L && f.canExecute() -> Row("/$rel", Row.Kind.FILE, true, "")
                f.isFile && f.length() == 0L ->
                    Row("/$rel", Row.Kind.FILE, false, "0-byte shim — repaired from the Noxs configurator").also { missingShims.add(rel) }
                else ->
                    Row("/$rel", Row.Kind.FILE, false, "missing Noxs shim — repaired from the Noxs configurator").also { missingShims.add(rel) }
            }
        }

        return Report(rows, structural, missingExec, missingShims)
    }

    /**
     * Restores missing system executables by extracting ONLY those members
     * from a cached base archive (single-pass filtered extraction — never a
     * full re-extract over a live rootfs). Returns the paths restored.
     */
    fun restoreFromArchive(rootfs: File, cachedArchive: File, missingRelativePaths: List<String>): List<String> {
        if (missingRelativePaths.isEmpty() || !cachedArchive.isFile) return emptyList()
        val wanted = missingRelativePaths.map { it.trimStart('/').trimEnd('/') }.toSet()
        val restored = mutableListOf<String>()
        runCatching {
            RootfsExtractor(rootfs).extract(cachedArchive, onlyPaths = wanted)
        }.onSuccess {
            for (rel in wanted) {
                val f = File(rootfs, rel)
                if (f.isFile && f.length() > 0L) restored.add(rel)
            }
        }.onFailure { e ->
            NoxsLog.w("RootfsIntegrity", "targeted restore failed: ${e.message}")
        }
        return restored
    }

    /**
     * Applies the safe in-place repairs: deletes ONLY 0-byte placeholder
     * files at symlink paths (never real directories or non-empty files),
     * then recreates the FHS/loader links, and restores Noxs shims through
     * the canonical configurator. Returns a human-readable list of what was
     * repaired; leaves genuine Ubuntu files untouched.
     */
    fun repair(rootfs: File): List<String> {
        val applied = mutableListOf<String>()
        for (name in listOf(BIN, SBIN, LIB, LIB64)) {
            val f = File(rootfs, name)
            val p = f.toPath()
            if (Files.isSymbolicLink(p)) continue
            if (f.isDirectory) continue // real directory (non-usr-merge) — keep it
            if (f.isFile && f.length() == 0L && f.delete()) applied.add("removed 0-byte placeholder /$name")
        }
        if (createdLink(File(rootfs, BIN), "usr/bin")) applied.add("restored /bin → usr/bin")
        if (createdLink(File(rootfs, SBIN), "usr/sbin")) applied.add("restored /sbin → usr/sbin")
        if (createdLink(File(rootfs, LIB), "usr/lib")) applied.add("restored /lib → usr/lib")
        if (File(rootfs, "usr/lib64").isDirectory && createdLink(File(rootfs, LIB64), "usr/lib64")) {
            applied.add("restored /lib64 → usr/lib64")
        }
        if (createdLink(File(rootfs, "usr/bin/sh"), "dash")) applied.add("restored /usr/bin/sh → dash")
        if (createdLink(File(rootfs, "usr/bin/awk"), "mawk")) applied.add("restored /usr/bin/awk → mawk")
        for (spec in LOADER_LINKS) {
            val real = File(rootfs, spec.real)
            if (!real.exists()) continue
            if (createdLink(File(rootfs, spec.link), spec.lnTarget)) applied.add("restored /${spec.link} → ${spec.real}")
        }
        // Noxs-owned shims: canonical restore path (same content the installer writes).
        runCatching { RootfsConfigurator.installNoxsSuSudo(rootfs) }
            .onSuccess { applied.add("restored Noxs su/sudo shims") }
            .onFailure { NoxsLog.w("RootfsIntegrity", "shim restore failed: ${it.message}") }
        return applied
    }

    /** Creates [link] → [target] when the link is absent or a placeholder; false when blocked. */
    private fun createdLink(link: File, target: String): Boolean {
        if (Files.isSymbolicLink(link.toPath())) return false
        if (link.isDirectory) return false
        if (link.isFile && link.length() > 0L) return false // never overwrite real content
        runCatching { Files.deleteIfExists(link.toPath()) }
        link.parentFile?.let { parent -> if (!parent.isDirectory) runCatching { parent.mkdirs() } }
        return try {
            Files.createSymbolicLink(link.toPath(), Paths.get(target))
            true
        } catch (e: Exception) {
            NoxsLog.w("RootfsIntegrity", "symlink $link -> $target failed: ${e.message}")
            false
        }
    }
}
