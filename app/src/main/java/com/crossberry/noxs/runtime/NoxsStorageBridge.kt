/*
 * Noxs SAF storage access.
 * SAF grants document-tree URIs, not host filesystem paths. Noxs deliberately
 * exposes those grants through explicit `noxs storage` commands; it never
 * pretends that a content:// URI is a POSIX mount or requests all-files access.
 */
package com.crossberry.noxs.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

class NoxsStorageBridge(context: Context, private val paths: NoxsPaths) {

    enum class Category(val key: String, val label: String, val aliases: List<String> = emptyList()) {
        SHARED("shared", "Shared"),
        DOWNLOADS("downloads", "Downloads", listOf("Download", "Downloads")),
        DCIM("dcim", "DCIM", listOf("DCIM")),
        PICTURES("pictures", "Pictures", listOf("Pictures")),
        MUSIC("music", "Music", listOf("Music")),
        MOVIES("movies", "Movies", listOf("Movies", "Videos")),
        DOCUMENTS("documents", "Documents", listOf("Document", "Documents"));

        companion object {
            fun fromKey(key: String): Category? = values().firstOrNull { it.key == key.lowercase(Locale.ROOT) }
        }
    }

    data class CategoryStatus(
        val category: Category,
        val available: Boolean,
        val displayName: String? = null
    )

    data class CommandResult(val ok: Boolean, val output: String)

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        paths.ensureBaseDirs()
        ensureControlDirectories()
        writeStatusFile()
    }

    /** Recreate only Noxs' IPC folders if a shell replaced one with a symlink. */
    fun ensureControlDirectories(): Boolean {
        var recreated = false
        listOf(
            paths.run,
            paths.storageControl,
            paths.storageRequests,
            paths.storageResponses,
            paths.storagePayloads,
            paths.storageResponsePayloads
        ).forEach { directory ->
            if (isSymlink(directory)) {
                directory.delete()
                recreated = true
            }
            if (!directory.isDirectory) {
                directory.mkdirs()
                recreated = true
            }
            check(directory.isDirectory && !isSymlink(directory)) { "Unable to prepare Noxs storage bridge" }
        }
        return recreated
    }

    /** Take and retain only the exact tree the user selected, with read/write access. */
    fun grantTree(category: Category, treeUri: Uri, resultFlags: Int) {
        val allowed = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val hasRead = (allowed and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        val hasWrite = (allowed and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        require(hasRead && hasWrite) { "Android did not grant read and write access for this folder" }

        resolver.takePersistableUriPermission(treeUri, allowed)
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
        if (root == null || !root.isDirectory || !root.canRead() || !root.canWrite()) {
            if (Category.values().none { preferences.getString(treeKey(it), null) == treeUri.toString() }) {
                runCatching { resolver.releasePersistableUriPermission(treeUri, allowed) }
            }
            throw SecurityException("The selected folder is not writable")
        }
        val newValue = treeUri.toString()
        val oldValue = preferences.getString(treeKey(category), null)
        preferences.edit().putString(treeKey(category), newValue).apply()
        if (!oldValue.isNullOrBlank() && oldValue != newValue &&
            Category.values().none { preferences.getString(treeKey(it), null) == oldValue }
        ) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(oldValue),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        writeStatusFile()
    }

    fun hasExplicitMapping(category: Category): Boolean =
        !preferences.getString(treeKey(category), null).isNullOrBlank()

    fun status(): List<CategoryStatus> = Category.values().map { category ->
        val root = runCatching { rootFor(category) }.getOrNull()
        val usable = root != null && runCatching { root.isDirectory && root.canRead() && root.canWrite() }.getOrDefault(false)
        CategoryStatus(category, usable, if (usable) safeDisplayName(root?.name) else null)
    }

    fun statusText(): String {
        val items = status()
        val permissionGranted = items.any { it.available }
        val hasSavedMapping = Category.values().any(::hasExplicitMapping)
        val shared = items.first { it.category == Category.SHARED }
        val permissionText = when {
            permissionGranted -> "Granted"
            hasSavedMapping -> "Revoked or unavailable"
            else -> "Not granted"
        }
        return buildString {
            appendLine("Noxs Storage")
            appendLine()
            appendLine("Status: ${if (permissionGranted) "enabled" else "unavailable"}")
            items.forEach { item ->
                val value = if (item.available) item.displayName ?: "available" else "unavailable"
                appendLine("${item.category.label}: $value")
            }
            appendLine("Permission: $permissionText")
            appendLine("Access: SAF-backed Noxs commands (not a POSIX mount)")
            when {
                !shared.available && hasSavedMapping ->
                    appendLine("The saved Shared mapping is revoked or stale — run: noxs-setup-storage")
                !shared.available ->
                    appendLine("Run: noxs-setup-storage")
                else -> {
                    val unmapped = items.filter { it.category != Category.SHARED && !it.available }
                    if (unmapped.isNotEmpty()) {
                        // Honest hint: aliases resolve ONLY inside the selected
                        // Shared tree — a non-root Shared folder cannot map them.
                        appendLine(
                            "Note: ${unmapped.joinToString(", ") { it.category.label }} map automatically " +
                                "only when Shared is the device root (/storage/emulated/0). " +
                                "Map any of them individually: noxs storage map <category>"
                        )
                    }
                }
            }
        }.trimEnd()
    }

    fun statusFileText(): String {
        val items = status()
        val enabled = items.any { it.available }
        val hasSavedMapping = Category.values().any(::hasExplicitMapping)
        val permission = when {
            enabled -> "granted"
            hasSavedMapping -> "revoked_or_unavailable"
            else -> "not_granted"
        }
        return buildString {
            appendLine("enabled=${if (enabled) "true" else "false"}")
            appendLine("permission=$permission")
            items.forEach { appendLine("${it.category.key}=${if (it.available) "available" else "unavailable"}") }
        }
    }

    fun writeStatusFile() {
        runCatching {
            ensureControlDirectories()
            val target = File(paths.storageControl, STATUS_FILE)
            val temp = File(paths.storageControl, "$STATUS_FILE.${java.util.UUID.randomUUID()}.tmp")
            temp.writeText(statusFileText())
            if (isSymlink(target)) target.delete()
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    fun repairText(): String {
        val items = status()
        val enabled = items.any { it.available }
        val hasSavedMapping = Category.values().any(::hasExplicitMapping)
        writeStatusFile()
        val permission = when {
            enabled -> "OK"
            hasSavedMapping -> "Revoked or unavailable"
            else -> "Not granted"
        }
        return buildString {
            appendLine("Checking Noxs storage access...")
            appendLine("Permission: $permission")
            appendLine("Refreshing document mappings...")
            appendLine("Done.")
            if (!items.first { it.category == Category.SHARED }.available) {
                appendLine("Shared storage is not mapped. Run noxs-setup-storage to choose a folder.")
            }
        }.trimEnd()
    }

    /** Release Noxs' persisted URI grants only; no document or user file is deleted. */
    fun resetMappings() {
        val uris = Category.values().mapNotNull { preferences.getString(treeKey(it), null) }.distinct()
        preferences.edit().clear().apply()
        uris.forEach { value ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        writeStatusFile()
    }

    fun unmap(category: Category) {
        val old = preferences.getString(treeKey(category), null) ?: return
        preferences.edit().remove(treeKey(category)).apply()
        val stillUsed = Category.values().any { preferences.getString(treeKey(it), null) == old }
        if (!stillUsed) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        writeStatusFile()
    }

    /** Process a non-interactive shell request. Picker and reset-confirmation requests are handled by TerminalActivity. */
    fun execute(
        requestId: String,
        operation: String,
        categoryKey: String,
        path: String,
        argument: String
    ): CommandResult = try {
        ensureControlDirectories()
        when (operation) {
            "status" -> CommandResult(true, statusText())
            "repair" -> CommandResult(true, repairText())
            "list" -> CommandResult(true, list(category(categoryKey), path))
            "mkdir" -> CommandResult(true, mkdir(category(categoryKey), path))
            "read", "get" -> {
                val bytes = copyDocumentToResponse(category(categoryKey), path, requestId)
                CommandResult(true, "Ready: $bytes bytes")
            }
            "write", "put" -> {
                val bytes = copyPayloadToDocument(category(categoryKey), path, requestId)
                CommandResult(true, "Written: $bytes bytes")
            }
            "delete" -> CommandResult(true, delete(category(categoryKey), path))
            "copy" -> CommandResult(true, copyOrMove(category(categoryKey), path, argument, move = false))
            "move" -> CommandResult(true, copyOrMove(category(categoryKey), path, argument, move = true))
            else -> CommandResult(false, "Unknown storage operation: $operation")
        }
    } catch (e: Exception) {
        CommandResult(false, e.message?.take(240) ?: "Storage operation failed")
    }.also { writeStatusFile() }

    private fun category(key: String): Category =
        Category.fromKey(key) ?: throw IllegalArgumentException("Unknown storage category: $key")

    private fun list(category: Category, path: String): String {
        val directory = requireDirectory(category, path)
        val files = directory.listFiles().sortedWith(
            compareByDescending<DocumentFile> { it.isDirectory }
                .thenBy { safeDisplayName(it.name)?.lowercase(Locale.ROOT).orEmpty() }
        )
        if (files.isEmpty()) return "(empty)"
        return files.take(MAX_LIST_ENTRIES).joinToString("\n") { file ->
            val name = safeDisplayName(file.name) ?: "(unnamed)"
            if (file.isDirectory) "$name/" else "$name  (${file.length()} bytes)"
        }
    }

    private fun mkdir(category: Category, path: String): String {
        val parts = pathParts(path)
        require(parts.isNotEmpty()) { "A folder name is required" }
        var current = requireRoot(category)
        parts.forEach { part ->
            val existing = current.findFile(part)
            current = when {
                existing == null -> current.createDirectory(part)
                    ?: throw IllegalStateException("The selected provider could not create a folder")
                existing.isDirectory -> existing
                else -> throw IllegalArgumentException("A file already exists at $part")
            }
        }
        return "Created folder: ${parts.joinToString("/")}"
    }

    private fun delete(category: Category, path: String): String {
        val document = requireDocument(category, path)
        val name = safeDisplayName(document.name) ?: "document"
        deleteRecursively(document)
        return "Deleted from the selected Android folder: $name"
    }

    private fun copyOrMove(category: Category, sourcePath: String, destinationPath: String, move: Boolean): String {
        val sourceParts = pathParts(sourcePath)
        val destinationParts = pathParts(destinationPath)
        require(sourceParts.isNotEmpty() && destinationParts.isNotEmpty()) { "Source and destination paths are required" }
        require(sourceParts != destinationParts) { "Source and destination are the same" }
        if (sourceParts.size < destinationParts.size && destinationParts.take(sourceParts.size) == sourceParts) {
            throw IllegalArgumentException("A folder cannot be copied or moved into itself")
        }
        val source = requireDocument(category, sourcePath)
        val (parent, name) = requireParent(category, destinationPath)
        require(parent.findFile(name)?.uri != source.uri) { "Source and destination are the same" }
        val target = copyDocument(source, parent, name)
        if (move) deleteRecursively(source)
        return "${if (move) "Moved" else "Copied"}: ${safeDisplayName(target.name) ?: name}"
    }

    private fun copyDocument(source: DocumentFile, destinationParent: DocumentFile, destinationName: String): DocumentFile {
        if (source.isDirectory) {
            val target = destinationParent.findFile(destinationName) ?: destinationParent.createDirectory(destinationName)
                ?: throw IllegalStateException("The provider could not create the destination folder")
            require(target.isDirectory) { "Destination exists and is not a folder" }
            source.listFiles().forEach { child ->
                val childName = safeComponent(child.name ?: throw IllegalStateException("Unnamed document"))
                copyDocument(child, target, childName)
            }
            return target
        }
        val target = destinationParent.findFile(destinationName)?.also {
            require(!it.isDirectory) { "Destination exists and is a folder" }
        } ?: destinationParent.createFile(mimeType(destinationName), destinationName)
            ?: throw IllegalStateException("The provider could not create the destination file")
        val input = resolver.openInputStream(source.uri) ?: throw IllegalStateException("Unable to open source document")
        val output = resolver.openOutputStream(target.uri, "wt") ?: throw IllegalStateException("Unable to write destination document")
        input.use { src -> output.use { dst -> src.copyTo(dst) } }
        return target
    }

    private fun deleteRecursively(document: DocumentFile) {
        if (document.isDirectory) document.listFiles().forEach(::deleteRecursively)
        if (!document.delete()) throw IllegalStateException("The selected provider could not delete this item")
    }

    private fun copyDocumentToResponse(category: Category, path: String, requestId: String): Long {
        val source = requireDocument(category, path)
        require(!source.isDirectory) { "Choose a file, not a folder" }
        val id = safeRequestId(requestId)
        val destination = File(paths.storageResponsePayloads, id)
        val temporary = File(paths.storageResponsePayloads, "$id.${java.util.UUID.randomUUID()}.tmp")
        destination.parentFile?.mkdirs()
        try {
            val input = resolver.openInputStream(source.uri) ?: throw IllegalStateException("Unable to open document")
            input.use { src -> FileOutputStream(temporary).use { out -> src.copyTo(out) } }
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
        if (isSymlink(destination)) destination.delete()
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("Unable to replace transfer data")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Unable to save transfer data")
        }
        return destination.length()
    }

    private fun copyPayloadToDocument(category: Category, path: String, requestId: String): Long {
        val id = safeRequestId(requestId)
        val source = File(paths.storagePayloads, id)
        require(source.isFile && !isSymlink(source)) { "Upload payload is missing or unsafe" }
        val (parent, name) = requireParent(category, path)
        val target = parent.findFile(name)?.also {
            require(!it.isDirectory) { "Destination is a folder" }
        } ?: parent.createFile(mimeType(name), name)
            ?: throw IllegalStateException("The provider could not create the destination file")
        val byteCount = source.length()
        try {
            FileInputStream(source).use { input ->
                val output = resolver.openOutputStream(target.uri, "wt") ?: throw IllegalStateException("Unable to write destination document")
                output.use { input.copyTo(it) }
            }
        } finally {
            source.delete()
        }
        return byteCount
    }

    private fun requireRoot(category: Category): DocumentFile =
        rootFor(category) ?: throw SecurityException("${category.label} is unavailable; run noxs-setup-storage or noxs storage map ${category.key}")

    private fun requireDirectory(category: Category, path: String): DocumentFile {
        var current = requireRoot(category)
        pathParts(path).forEach { part ->
            current = current.findFile(part) ?: throw IllegalArgumentException("Folder not found: $part")
            if (!current.isDirectory) throw IllegalArgumentException("Not a folder: $part")
        }
        return current
    }

    private fun requireDocument(category: Category, path: String): DocumentFile {
        val (parent, name) = requireParent(category, path)
        return parent.findFile(name) ?: throw IllegalArgumentException("Document not found: $name")
    }

    private fun requireParent(category: Category, path: String): Pair<DocumentFile, String> {
        val parts = pathParts(path)
        require(parts.isNotEmpty()) { "A document path is required" }
        var parent = requireRoot(category)
        parts.dropLast(1).forEach { part ->
            parent = parent.findFile(part) ?: throw IllegalArgumentException("Folder not found: $part")
            if (!parent.isDirectory) throw IllegalArgumentException("Not a folder: $part")
        }
        return parent to parts.last()
    }

    private fun rootFor(category: Category): DocumentFile? {
        val explicit = preferences.getString(treeKey(category), null)
        if (!explicit.isNullOrBlank()) {
            val uri = Uri.parse(explicit)
            if (!hasPersistedWriteGrant(uri)) return null
            return DocumentFile.fromTreeUri(appContext, uri)?.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
        }
        if (category == Category.SHARED) return null

        val sharedValue = preferences.getString(treeKey(Category.SHARED), null) ?: return null
        val sharedUri = Uri.parse(sharedValue)
        if (!hasPersistedWriteGrant(sharedUri)) return null
        val sharedRoot = DocumentFile.fromTreeUri(appContext, sharedUri) ?: return null
        return category.aliases.asSequence()
            .mapNotNull { alias -> sharedRoot.findFile(alias) }
            .firstOrNull { candidate -> candidate.isDirectory && candidate.canRead() && candidate.canWrite() }
    }

    private fun hasPersistedWriteGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    private fun pathParts(path: String): List<String> {
        require(!path.startsWith('/') && !path.contains('\u0000')) { "Use a relative path inside the selected folder" }
        if (path.isBlank()) return emptyList()
        return path.split('/').filter { it.isNotEmpty() && it != "." }.map(::safeComponent)
    }

    private fun safeComponent(value: String): String {
        require(value.isNotBlank() && value != "." && value != ".." && '/' !in value && '\u0000' !in value) {
            "Unsafe document name"
        }
        return value
    }

    private fun safeDisplayName(name: String?): String? = name
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.take(255)

    private fun safeRequestId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid request id" }
        return id
    }

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        val mode = Os.lstat(file.absolutePath).st_mode
        (mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
    }.getOrDefault(false)

    private fun treeKey(category: Category) = "tree_${category.key}"

    companion object {
        const val PREFERENCES = "noxs_storage_access"
        const val STATUS_FILE = "status"
        const val MAX_LIST_ENTRIES = 1000
    }
}
