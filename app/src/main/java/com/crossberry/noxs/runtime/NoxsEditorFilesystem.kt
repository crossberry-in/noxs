/*
 * Noxs — original implementation.
 * NXEditor filesystem adapter: typed, validated file operations over the
 * active Noxs Linux environment (the guest rootfs is plain app-private
 * storage on the host, so operations are direct java.io/nio calls — no shell
 * execution, no string-concatenated commands, ever).
 *
 * Security model:
 *  - Every guest path is validated and canonicalized; the canonical host
 *    path must stay inside the rootfs, which also catches symlink escapes
 *    (Files.createSymbolicLink is emulated by proot inside the same tree,
 *    but a malicious link out of the tree is still rejected here).
 *  - Paths are absolute guest paths ("/home/noxs/…") mapped by prefixing
 *    the host rootfs directory — the same mapping FileBrowserActivity and
 *    NoxsPluginInstaller already use.
 *  - Guest permission bits are NOT re-enforced here: proot already runs the
 *    user as fake-root inside the sandbox, and the host side is the app's
 *    own private storage (documented limitation).
 */
package com.crossberry.noxs.runtime

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class NoxsEditorFilesystem(private val rootfs: File) {

    /** Refuse to load files larger than this into the editor. */
    val maxFileBytes: Long = 2_000_000L

    data class ListingEntry(
        val name: String,
        val guestPath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModifiedMs: Long
    )

    data class LoadedFile(
        val guestPath: String,
        val text: String,
        val lastModifiedMs: Long,
        val sizeBytes: Long
    )

    /** Raised for validation failures, IO errors, conflicts and oversized files. */
    class FilesystemException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val rootfsCanonical: String by lazy {
        rootfs.canonicalFile.invariantPath()
    }

    // ------------------------------------------------------------ validation

    /**
     * Validates an absolute guest path and returns the canonical host [File].
     * Rejects: relative paths, NUL/control characters, "." / ".." segments,
     * oversized paths, and any canonical resolution that escapes the rootfs
     * (traversal and symlink escapes alike).
     */
    fun validateGuestPath(guestPath: String): File {
        if (guestPath.isEmpty() || guestPath.length > 4096) {
            throw FilesystemException("Invalid path length")
        }
        if (!guestPath.startsWith("/")) {
            throw FilesystemException("Path must be absolute: $guestPath")
        }
        if (guestPath.any { it.code < 0x20 || it.code == 0x7f || it == '\u0000' }) {
            throw FilesystemException("Path contains control characters")
        }
        val segments = guestPath.split('/')
        if (segments.any { it == ".." }) {
            throw FilesystemException("Path traversal is not allowed")
        }
        val host = File(rootfs, guestPath.trimStart('/'))
        val canonical = try {
            host.canonicalFile
        } catch (e: java.io.IOException) {
            throw FilesystemException("Cannot resolve path: ${e.message}", e)
        }
        val canonicalPath = canonical.invariantPath()
        if (canonicalPath != rootfsCanonical && !canonicalPath.startsWith(rootfsCanonical + "/")) {
            throw FilesystemException("Path escapes the Noxs environment")
        }
        return canonical
    }

    /** Host file -> validated guest path ("/" for the rootfs itself). */
    fun guestPathOf(hostFile: File): String {
        val canonical = try {
            hostFile.canonicalFile
        } catch (e: java.io.IOException) {
            throw FilesystemException("Cannot resolve path: ${e.message}", e)
        }
        val p = canonical.invariantPath()
        return when {
            p == rootfsCanonical -> "/"
            p.startsWith(rootfsCanonical + "/") -> p.substring(rootfsCanonical.length)
            else -> throw FilesystemException("Path escapes the Noxs environment")
        }
    }

    /** True when [guestPath] exists and is a directory (canonical-resolved). */
    fun isDirectory(guestPath: String): Boolean = try {
        validateGuestPath(guestPath).isDirectory
    } catch (_: FilesystemException) {
        false
    }

    // ------------------------------------------------------------- listing

    fun list(guestDir: String): List<ListingEntry> {
        val dir = validateGuestPath(guestDir)
        if (!dir.isDirectory) {
            throw FilesystemException("Not a directory: $guestDir")
        }
        val children = dir.listFiles() ?: throw FilesystemException("Cannot list directory: $guestDir")
        return children
            .map { child ->
                ListingEntry(
                    name = child.name,
                    guestPath = guestPathOf(child),
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isDirectory) 0L else child.length(),
                    lastModifiedMs = child.lastModified()
                )
            }
            .sortedWith(compareByDescending<ListingEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    // --------------------------------------------------------- read / save

    fun read(guestPath: String): LoadedFile {
        val file = validateGuestPath(guestPath)
        if (!file.isFile) throw FilesystemException("Not a regular file: $guestPath")
        val size = file.length()
        if (size > maxFileBytes) {
            throw FilesystemException("File too large for the editor (${size / 1024} KB, limit ${maxFileBytes / 1024} KB)")
        }
        return try {
            LoadedFile(
                guestPath = guestPathOf(file),
                text = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8),
                lastModifiedMs = file.lastModified(),
                sizeBytes = size
            )
        } catch (e: java.io.IOException) {
            throw FilesystemException("Cannot read file: ${e.message}", e)
        }
    }

    /**
     * Writes [text] to [guestPath]. When [expectedLastModifiedMs] is
     * non-null, the on-disk file must still carry that timestamp (and size),
     * otherwise another process (a terminal command) changed it since it was
     * opened — the caller decides whether to overwrite after warning.
     * Size is checked alongside mtime: filesystems with coarse (1s) mtime
     * granularity would otherwise miss same-tick rewrites of different
     * length. Pass `force = true` to skip the external-change guard
     * (explicit user confirmation is expected to have happened in the UI).
     */
    fun write(
        guestPath: String,
        text: String,
        expectedLastModifiedMs: Long? = null,
        expectedSizeBytes: Long? = null,
        force: Boolean = false
    ) {
        val file = validateGuestPath(guestPath)
        if (file.exists() && !file.isFile) {
            throw FilesystemException("Not a regular file: $guestPath")
        }
        if (expectedLastModifiedMs != null && !force && file.exists()) {
            val sizeChanged = expectedSizeBytes != null && file.length() != expectedSizeBytes
            if (file.lastModified() != expectedLastModifiedMs || sizeChanged) {
                throw FilesystemException(
                    "File changed on disk since it was opened — reload or force-save to overwrite"
                )
            }
        }
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) {
            throw FilesystemException("Parent directory missing: $guestPath")
        }
        try {
            java.io.FileOutputStream(file).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        } catch (e: java.io.IOException) {
            throw FilesystemException("Cannot save file: ${e.message}", e)
        }
    }

    // --------------------------------------------------- create / rename / delete

    fun createFile(guestPath: String) {
        val file = validateGuestPath(guestPath)
        if (file.exists()) throw FilesystemException("Already exists: $guestPath")
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) {
            throw FilesystemException("Parent directory missing: $guestPath")
        }
        try {
            if (!file.createNewFile()) throw FilesystemException("Cannot create file: $guestPath")
        } catch (e: java.io.IOException) {
            throw FilesystemException("Cannot create file: ${e.message}", e)
        }
    }

    fun createDirectory(guestPath: String) {
        val file = validateGuestPath(guestPath)
        if (file.exists()) throw FilesystemException("Already exists: $guestPath")
        if (!file.mkdirs()) throw FilesystemException("Cannot create directory: $guestPath")
    }

    fun rename(oldGuestPath: String, newGuestPath: String) {
        val from = validateGuestPath(oldGuestPath)
        if (!from.exists()) throw FileNotFoundException(oldGuestPath)
        val to = validateGuestPath(newGuestPath)
        if (to.exists()) throw FilesystemException("Target already exists: $newGuestPath")
        if (from.parentFile?.canonicalPath != to.parentFile?.canonicalPath) {
            // Same-directory rename only in the UI; keep the adapter honest.
            val toParent = to.parentFile ?: throw FilesystemException("Invalid target")
            if (!toParent.isDirectory) throw FilesystemException("Target parent missing")
        }
        if (!from.renameTo(to)) {
            throw FilesystemException("Cannot rename: $oldGuestPath")
        }
    }

    fun delete(guestPath: String) {
        val file = validateGuestPath(guestPath)
        if (file.invariantPath() == rootfsCanonical) {
            throw FilesystemException("Refusing to delete the environment root")
        }
        val isLink = Files.isSymbolicLink(file.toPath())
        if (!file.exists() && !isLink) throw FileNotFoundException(guestPath)
        val ok = try {
            if (file.isDirectory && !isLink) {
                // NIO walk WITHOUT following links: an outbound symlink inside
                // the tree is deleted as a link and its target is untouched
                // (File.deleteRecursively would descend through listFiles()).
                Files.walk(file.toPath(), Integer.MAX_VALUE)
                    .use { stream ->
                        stream.sorted(java.util.Comparator.reverseOrder())
                            .allMatch { Files.deleteIfExists(it) }
                    }
            } else {
                Files.deleteIfExists(file.toPath())
            }
        } catch (e: java.io.IOException) {
            throw FilesystemException("Cannot delete: ${e.message}", e)
        }
        if (!ok) throw FilesystemException("Cannot delete: $guestPath")
    }

    // ------------------------------------------------------------- helpers

    private fun File.invariantPath(): String =
        absolutePath.replace(File.separatorChar, '/').trimEnd('/').ifEmpty { "/" }
}
