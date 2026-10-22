/*
 * Noxs — original implementation.
 * Pure decision layer for the Noxs DocumentsProvider — no Android imports,
 * fully JVM-testable. It owns the ONLY file-visibility policy of the
 * provider:
 *
 *  - the exposed root is exactly the guest home ("/home/noxs"), never the
 *    whole rootfs, never /data/data, never another app's storage
 *  - every document id is an ABSOLUTE GUEST path validated through
 *    NoxsEditorFilesystem (rejects "..", control characters, oversized
 *    paths and — via canonical resolution — symlink escapes), and must
 *    additionally resolve INSIDE the home subtree
 *  - display names coming from the system UI are sanitized (no separators,
 *    no control characters, no empty names, collision-safe numbering)
 */
package com.crossberry.noxs.runtime

import java.io.File

object NoxsDocumentsPolicy {

    /** The document id of the exposed root — the guest user's home. */
    const val HOME_DOC: String = "/home/noxs"

    /** Title shown in the system picker. */
    const val ROOT_TITLE: String = "Noxs Linux Home"

    /** Raised for every rejected id / name — the provider maps it to errors. */
    class PolicyException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Resolves [docId] to a canonical host file inside the guest home.
     * The two-layer check mirrors the app's own file stack:
     *  1. [NoxsEditorFilesystem.validateGuestPath] — rootfs containment,
     *     traversal and symlink-escape rejection
     *  2. home allowlist — the canonical path must stay under /home/noxs so
     *     /etc, /run/noxs (runtime control files) and friends stay invisible
     */
    fun resolve(fs: NoxsEditorFilesystem, docId: String): File {
        if (docId != HOME_DOC && !docId.startsWith("$HOME_DOC/")) {
            throw PolicyException("Only Noxs Linux Home documents are exposed")
        }
        val file = try {
            fs.validateGuestPath(docId)
        } catch (e: NoxsEditorFilesystem.FilesystemException) {
            throw PolicyException(e.message ?: "Invalid document", e)
        }
        val home = try {
            fs.validateGuestPath(HOME_DOC)
        } catch (e: NoxsEditorFilesystem.FilesystemException) {
            throw PolicyException("Noxs home is unavailable", e)
        }
        if (docId == HOME_DOC && !file.isDirectory) {
            // The allowlist boundary must physically exist — a reset or
            // half-installed environment exposes no root at all.
            throw PolicyException("Noxs home is unavailable")
        }
        val p = inv(file)
        val homePath = inv(home)
        if (p != homePath && !p.startsWith(homePath + "/")) {
            // A symlink inside home may still resolve outside of home while
            // remaining inside the rootfs — the allowlist catches it here.
            throw PolicyException("Document escapes Noxs Linux Home")
        }
        return file
    }

    /** Forward-slash invariant path of a canonical file (same rule the
     *  editor filesystem uses; kept local — that helper is private). */
    private fun inv(f: File): String =
        f.absolutePath.replace(File.separatorChar, '/').trimEnd('/')

    /** True when [docId] is a valid, exposed directory document. */
    fun isExposedDirectory(fs: NoxsEditorFilesystem, docId: String): Boolean = try {
        resolve(fs, docId).isDirectory
    } catch (_: PolicyException) {
        false
    }

    /**
     * Sanitizes a display name from the system UI into a safe guest file
     * name: separators and traversal segments are collapsed, control
     * characters are dropped, hidden names are prefixed to stay visible,
     * and reserved names are rejected.
     */
    fun sanitizeDisplayName(raw: String): String {
        val cleaned = raw.split('/', '\\')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("-")
            .filter { it.code >= 0x20 && it.code != 0x7f }
            .trim()
            .ifEmpty { "untitled" }
        val safe = if (cleaned.startsWith(".")) "_" + cleaned.substring(1) else cleaned
        if (safe == "." || safe == "..") throw PolicyException("Reserved name")
        return safe.take(255)
    }

    /**
     * Returns [candidate] when free inside [dir], otherwise "name (n).ext"
     * style unique variants — never overwrites an existing document.
     */
    fun uniqueChild(dir: File, candidate: String): String {
        require(dir.isDirectory) { "parent is not a directory" }
        if (!File(dir, candidate).exists()) return candidate
        val dot = candidate.lastIndexOf('.')
        val base = if (dot > 0) candidate.substring(0, dot) else candidate
        val ext = if (dot > 0) candidate.substring(dot) else ""
        var n = 1
        while (true) {
            val next = "$base ($n)$ext"
            if (!File(dir, next).exists()) return next
            if (n == 4096) throw PolicyException("Too many duplicates")
            n++
        }
    }

    /** MIME type for a document: directories get the DIR type, files are
     *  guessed from the extension with a binary fallback. */
    fun mimeFor(name: String, isDirectory: Boolean, guess: (String) -> String?): String =
        if (isDirectory) "vnd.android.document/directory"
        else guess(name) ?: "application/octet-stream"
}
