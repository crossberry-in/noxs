/*
 * Noxs — original implementation.
 * Security-hardened tar extraction for the Debian rootfs.
 *
 * Threat model (see docs/SECURITY.md):
 *  - absolute paths           → rejected
 *  - ".." traversal           → rejected
 *  - symlink/hardlink escapes → rejected (link targets resolved against root)
 *  - device/fifo/special      → rejected
 *  - setuid/setgid bits       → stripped
 *  - extraction bombs         → capped by NoxsConstants.MAX_ROOTFS_BYTES
 *
 * The tar reader is self-contained (ustar/gnu long names, PAX headers consumed)
 * so no third-party archive library is trusted with our security boundary.
 */
package com.crossberry.noxs.shared

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

class ExtractedStats(val files: Int, val dirs: Int, val links: Int, val bytes: Long)

object TarGuard {

    /**
     * Validates a tar entry path and resolves it under [root].
     * @throws SecurityException on any traversal attempt.
     */
    fun safeResolve(root: File, entryName: String): File {
        if (entryName.isEmpty()) throw SecurityException("Empty entry name")
        if (entryName.contains('\u0000')) throw SecurityException("NUL byte in entry name")
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("/")) throw SecurityException("Absolute path entry: $entryName")
        if (normalized.length >= 2 && normalized[1] == ':') throw SecurityException("Drive-letter entry: $entryName")
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return root
        if (parts.any { it == ".." }) throw SecurityException("Path traversal entry: $entryName")
        return File(root, parts.joinToString(File.separator))
    }

    /** Link target validation: link must resolve inside the rootfs. */
    fun isSafeLinkTarget(root: File, linkPath: File, target: String): Boolean {
        if (target.isEmpty() || target.contains('\u0000')) return false
        return try {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            val linkAbs = linkPath.toPath().toAbsolutePath().normalize()
            if (!linkAbs.startsWith(rootPath)) return false
            val resolved = if (target.startsWith("/")) {
                rootPath.resolve(target.trimStart('/')).normalize()
            } else {
                val parent = linkAbs.parent ?: rootPath
                parent.resolve(target).normalize()
            }
            resolved.startsWith(rootPath)
        } catch (e: Exception) {
            false
        }
    }
}

class RootfsExtractor(private val root: File) {

    /**
     * Extracts a .tar.xz / .tar.gz / .tar [archive] into [root].
     * Permissions are applied from tar mode with setuid/setgid/sticky stripped.
     *
     * [onProgress] receives the real cumulative entry count while extraction
     * runs; [beforeEntry] is invoked before every entry so callers can
     * abort cooperatively (e.g. user-initiated setup cancellation).
     *
     * [onlyPaths] restricts extraction to a fixed set of archive members
     * (relative paths, no trailing slash) — used by the integrity repair to
     * restore individual missing system files from the cached base archive
     * WITHOUT re-extracting over a live rootfs. Members outside the set are
     * skipped (their payload is drained so the tar stream stays aligned).
     */
    fun extract(
        archive: File,
        onProgress: (Long) -> Unit = {},
        beforeEntry: (() -> Unit)? = null,
        onlyPaths: Set<String>? = null
    ): ExtractedStats {
        RootfsLinkQueue.drain()
        root.mkdirs()
        if (!root.isDirectory) throw IOException("Cannot create rootfs directory: $root")
        val base: InputStream = BufferedInputStream(FileInputStream(archive), 256 * 1024)
        val tarStream: InputStream = when {
            archive.name.endsWith(".tar.xz") -> XZInputStream(base)
            archive.name.endsWith(".tar.gz") -> GZIPInputStream(base, 64 * 1024)
            archive.name.endsWith(".tar") -> base
            else -> { base.close(); throw IOException("Unsupported archive format: ${archive.name}") }
        }
        var seen = 0L
        tarStream.use { stream ->
            TarReader(stream).readAll { entry, payload ->
                beforeEntry?.invoke()
                if (onlyPaths == null || wanted(entry.name, onlyPaths)) {
                    handleEntry(entry, payload)
                } else {
                    // keep the stream aligned: drop this entry's payload
                    payload.copyTo(NullOutputStream)
                }
                seen++
                if (seen % 32L == 0L) onProgress(seen)
            }
        }
        onProgress(seen)
        return ExtractedStats(files, dirs, links, bytes)
    }

    /** Archive member names arrive as "./usr/bin/clear" — compare against both forms. */
    private fun wanted(entryName: String, onlyPaths: Set<String>): Boolean {
        val normalized = entryName.removePrefix("./").trimEnd('/')
        return normalized in onlyPaths
    }

    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private var files = 0
    private var dirs = 0
    private var links = 0
    private var bytes = 0L

    private fun ensureDirectory(dir: File): Boolean {
        val path = dir.toPath()
        if (Files.isSymbolicLink(path)) {
            val target = runCatching { Files.readSymbolicLink(path).toString() }.getOrNull()
            if (target != null) {
                val resolved = if (target.startsWith("/")) {
                    File(root, target.trimStart('/'))
                } else {
                    File(dir.parentFile ?: root, target)
                }
                return resolved.isDirectory || resolved.mkdirs()
            }
        }
        return dir.isDirectory || dir.mkdirs() || dir.isDirectory
    }

    private fun handleEntry(entry: TarReader.Entry, payload: InputStream) {
        when (entry.type) {
            TarReader.Type.DIR -> {
                val dir = TarGuard.safeResolve(root, entry.name)
                if (!ensureDirectory(dir)) {
                    throw IOException("Cannot create directory: ${entry.name}")
                }
                dirs++
            }
            TarReader.Type.SYMLINK -> {
                val link = TarGuard.safeResolve(root, entry.name)
                if (!TarGuard.isSafeLinkTarget(root, link, entry.linkName)) {
                    throw SecurityException("Escaping symlink: ${entry.name} -> ${entry.linkName}")
                }
                link.parentFile?.let { ensureDirectory(it) }
                val linkPath = link.toPath()
                // Remove any existing file, broken symlink, or empty directory at linkPath
                runCatching {
                    if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
                        Files.delete(linkPath)
                    }
                }
                try {
                    Files.createSymbolicLink(linkPath, Paths.get(entry.linkName))
                } catch (e: Exception) {
                    // Fallback via android.system.Os.symlink on older API levels if needed
                    val createdViaOs = runCatching {
                        val osClass = Class.forName("android.system.Os")
                        val m = osClass.getMethod("symlink", String::class.java, String::class.java)
                        m.invoke(null, entry.linkName, link.absolutePath)
                        true
                    }.getOrDefault(false)
                    if (!createdViaOs) {
                        // NEVER leave a 0-byte placeholder file at a symlink path:
                        // it poisons `[ -e ... ]` checks in-shell (noxs-links.sh
                        // treated the placeholder as existing) and makes every exec
                        // resolving through the path fail with ENOENT. The entry
                        // stays queued for the next extraction pass; post-extract
                        // integrity verification catches anything still missing.
                    }
                }
                RootfsLinkQueue.enqueue(link.absolutePath, entry.linkName)
                links++
            }
            TarReader.Type.HARDLINK -> {
                val link = TarGuard.safeResolve(root, entry.name)
                if (!TarGuard.isSafeLinkTarget(root, link, entry.linkName)) {
                    throw SecurityException("Escaping hard link: ${entry.name} -> ${entry.linkName}")
                }
                val src = TarGuard.safeResolve(root, entry.linkName)
                link.parentFile?.let { ensureDirectory(it) }
                val linkPath = link.toPath()
                val srcPath = src.toPath()
                runCatching { Files.deleteIfExists(linkPath) }
                if (Files.exists(srcPath) || Files.isSymbolicLink(srcPath)) {
                    val hardlinked = runCatching {
                        Files.createLink(linkPath, srcPath)
                        true
                    }.getOrDefault(false)
                    if (!hardlinked && src.isFile) {
                        src.copyTo(link, overwrite = true)
                        applyMode(link, entry.mode)
                    }
                }
                links++
            }
            TarReader.Type.REGULAR -> {
                val out = TarGuard.safeResolve(root, entry.name)
                out.parentFile?.let { p ->
                    if (!ensureDirectory(p)) {
                        throw IOException("Cannot create parent for: ${entry.name}")
                    }
                }
                bytes += entry.size
                if (bytes > NoxsConstants.MAX_ROOTFS_BYTES) {
                    throw SecurityException("Archive exceeds MAX_ROOTFS_BYTES — possible extraction bomb")
                }
                runCatching {
                    if (Files.isSymbolicLink(out.toPath())) Files.delete(out.toPath())
                }
                out.outputStream().use { fos -> payload.copyTo(fos, 128 * 1024) }
                applyMode(out, entry.mode)
                files++
            }
            else -> throw SecurityException("Unsupported special entry: ${entry.name}")
        }
    }

    private fun applyMode(file: File, mode: Int) {
        val clean = mode and 0b111111111
        try {
            if (clean and 0b100_000_000 != 0) file.setExecutable(true, false)
            file.setReadable(true, false)
            file.setWritable(clean and 0b010_000_000 != 0, true)
        } catch (e: SecurityException) {
            NoxsLog.w("TarGuard", "chmod limited for ${file.name}")
        }
    }
}

/**
 * Symlinks recorded during rootfs extraction.
 */
object RootfsLinkQueue {
    private val links = mutableListOf<Pair<String, String>>() // absolutePath -> target
    fun enqueue(absolutePath: String, target: String) = synchronized(links) { links.add(absolutePath to target) }
    fun drain(): List<Pair<String, String>> = synchronized(links) { val c = links.toList(); links.clear(); c }
    fun size(): Int = synchronized(links) { links.size }
}

/**
 * Minimal, dependency-free tar reader (ustar / GNU / PAX long-name aware).
 */
class TarReader(private val input: InputStream) {

    enum class Type { REGULAR, DIR, SYMLINK, HARDLINK, OTHER }

    data class Entry(
        val name: String,
        val type: Type,
        val size: Long,
        val mode: Int,
        val linkName: String
    )

    /** Streams entries; [handler] receives entry + a stream limited to its payload. */
    fun readAll(handler: (Entry, InputStream) -> Unit) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        while (true) {
            if (!readFully(header)) return // EOF
            if (isZeroBlock(header)) {
                // One zero block expected at end; read the second for symmetry.
                readFully(header)
                return
            }
            var name = parseString(header, 0, 100)
            val mode = (parseOctal(header, 100, 8) and 0b111_111_111).toInt()
            val size = parseOctal(header, 124, 12)
            val typeFlag = header[156].toInt() and 0xff
            val linkName = parseString(header, 157, 100)
            val magic = parseString(header, 257, 6)

            if (magic.startsWith("ustar")) {
                val prefix = parseString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }

            val padded = (size + 511) / 512 * 512

            when (typeFlag) {
                'L'.code -> { // GNU long name (next entry's name)
                    pendingLongName = readPayloadString(size)
                    skip(padded - size)
                    continue
                }
                'x'.code, 'g'.code -> { // PAX extended header — consume
                    val paxData = readPayloadString(size)
                    skip(padded - size)
                    pendingLongName = parsePaxPath(paxData) ?: pendingLongName
                    continue
                }
            }

            val effectiveName = pendingLongName ?: name
            pendingLongName = null

            val type = when (typeFlag) {
                '0'.code, 0 -> Type.REGULAR
                '5'.code -> Type.DIR
                '2'.code -> Type.SYMLINK
                '1'.code -> Type.HARDLINK
                else -> Type.OTHER
            }
            val entry = Entry(effectiveName, type, size, mode, linkName)
            if (type == Type.REGULAR) {
                LimitedInputStream(input, size).use { handler(entry, it) }
                skip(padded - size)
            } else {
                skip(padded)
                handler(entry, ByteArrayInputStream(ByteArray(0)))
            }
        }
    }

    private fun parsePaxPath(data: String): String? {
        for (line in data.split('\n')) {
            if (line.contains(" path=")) {
                return line.substringAfter(" path=")
            }
        }
        return null
    }

    private fun readPayloadString(size: Long): String {
        val buf = ByteArray(size.toInt())
        if (!readFully(buf)) throw IOException("Truncated tar payload")
        return String(buf, Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun skip(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val s = input.skip(remaining)
            if (s <= 0) {
                if (input.read() < 0) throw IOException("Truncated tar stream")
                remaining -= 1
            } else remaining -= s
        }
    }

    private fun isZeroBlock(block: ByteArray): Boolean = block.all { it.toInt() == 0 }

    private fun readFully(buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off == 0 // clean EOF only if nothing read
            off += n
        }
        return true
    }

    private fun parseString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.ISO_8859_1).trimEnd('\u0000')
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        var started = false
        for (i in offset until offset + length) {
            val b = header[i].toInt() and 0xff
            if (b == 0 || b == ' '.code) {
                if (started) break else continue
            }
            if (b in '0'.code..'7'.code) {
                started = true
                result = (result shl 3) or (b - '0'.code).toLong()
            } else break
        }
        return result
    }

    private class LimitedInputStream(private val src: InputStream, private val limit: Long) : InputStream() {
        private var remaining = limit
        override fun read(): Int {
            if (remaining <= 0) return -1
            val r = src.read()
            if (r >= 0) remaining--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    private class ByteArrayInputStream(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
    }
}
