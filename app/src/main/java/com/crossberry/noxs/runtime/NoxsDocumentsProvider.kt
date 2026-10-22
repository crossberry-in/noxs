/*
 * Noxs — original implementation.
 * DocumentsProvider exposing the Noxs Linux guest home through Android's
 * Storage Access Framework, so any DocumentsUI-compatible app (system Files
 * picker, "Save to…", editors) can browse, open, edit, create, rename and
 * delete files that really live in the Noxs Linux filesystem.
 *
 * Architecture:
 *  - the guest rootfs is plain app-private storage on the host (proot maps
 *    it in), so document IO is direct java.io/nio access — the SAME mapping
 *    FileBrowserActivity, NoxsEditorFilesystem and the plugin installer use;
 *    no shell commands, no string-concatenated paths, no bridge daemons
 *  - ALL visibility and safety decisions live in NoxsDocumentsPolicy
 *    (home-only allowlist, traversal/symlink rejection, name sanitization)
 *    and are shared with its JVM tests; this class is a thin Android shell
 *  - openDocument hands out real ParcelFileDescriptors: edits are written
 *    through to the Noxs Linux files (never a temporary copy), large files
 *    stream without loading them into memory, and the descriptor is owned
 *    by the receiving process (flush/close semantics are the OS's)
 *  - children/roots cursors notify their observers so open pickers refresh
 *
 * Exposed root: "Noxs Linux Home" -> /home/noxs. Nothing else. The provider
 * grants no broader storage access and requests no new permissions: the
 * documents live in the app's own sandbox.
 */
package com.crossberry.noxs.runtime

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.crossberry.noxs.shared.NoxsConstants
import com.crossberry.noxs.shared.NoxsLog
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

class NoxsDocumentsProvider : DocumentsProvider() {

    private lateinit var paths: NoxsPaths
    private lateinit var fs: NoxsEditorFilesystem

    /** Lazy: the environment may not be installed when the system binds us. */
    private val ready: Boolean
        get() = this::paths.isInitialized && paths.isInstalled()

    override fun onCreate(): Boolean {
        val context = context ?: return false
        paths = (context.applicationContext as? android.app.Application)
            ?.let { it as? com.crossberry.noxs.NoxsApplication }
            ?.let { app -> runCatching { app.environments.activePaths() }.getOrNull() }
            ?: NoxsPaths(context)
        fs = NoxsEditorFilesystem(paths.rootfs)
        return true
    }

    // ------------------------------------------------------------ helpers

    private val authority: String
        get() = context?.packageName ?: NoxsConstants.APP_ID

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUri(authority, docId)

    private fun notifyChildren(parentDocId: String) {
        val resolver: ContentResolver = context?.contentResolver ?: return
        runCatching { resolver.notifyChange(docUri(parentDocId), null) }
    }

    private fun guessMime(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext.isEmpty()) null
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun requireReady(): NoxsEditorFilesystem {
        if (!ready) throw FileNotFoundException("Noxs Linux environment is not set up")
        return fs
    }

    /** resolve() at the API boundary: policy rejections surface to SAF as
     *  plain FileNotFoundException (never a binder-side crash). */
    private fun resolveF(docId: String): File = try {
        resolve(docId)
    } catch (e: NoxsDocumentsPolicy.PolicyException) {
        throw FileNotFoundException(e.message)
    }

    private fun resolve(docId: String): File = NoxsDocumentsPolicy.resolve(requireReady(), docId)

    // ------------------------------------------------------------- rows

    private fun includeDocument(cursor: MatrixCursor, file: File, docId: String) {
        val isDir = file.isDirectory
        val flags = when {
            isDir -> (DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
            else -> (DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
        }.let { f -> if (file.canWrite()) f else DocumentsContract.Document.FLAG_SUPPORTS_DELETE }
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { NoxsDocumentsPolicy.ROOT_TITLE })
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, NoxsDocumentsPolicy.mimeFor(file.name, isDir, ::guessMime))
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_SIZE, if (isDir) 0L else file.length())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
    }

    private fun includeRoot(cursor: MatrixCursor) {
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, NoxsDocumentsPolicy.ROOT_TITLE)
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Debian 12 guest files")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, NoxsDocumentsPolicy.HOME_DOC)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
        }
    }

    // ---------------------------------------------------- provider contract

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: resolveRootProjection(projection))
        if (ready) {
            // The home directory must EXIST to be exposed — a half-installed
            // or reset environment reports no roots instead of failing.
            runCatching { NoxsDocumentsPolicy.resolve(fs, NoxsDocumentsPolicy.HOME_DOC) }
                .onSuccess { if (it.isDirectory) includeRoot(cursor) }
                .onFailure { NoxsLog.w("Documents", "roots: home unavailable (${it.message})") }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        val file = resolveF(documentId)
        includeDocument(cursor, file, documentId)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = resolveF(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val cursor = MatrixCursor(resolveDocumentProjection(projection))
        val children = parent.listFiles()
        if (children != null) {
            children
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .forEach { child ->
                    includeDocument(cursor, child, joinDoc(parentDocumentId, child.name))
                }
        }
        val uri = DocumentsContract.buildDocumentUri(authority, parentDocumentId)
        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = resolveF(documentId)
        if (file.isDirectory) throw FileNotFoundException("Cannot open a directory: $documentId")
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        val writable = mode.contains('w') || mode.contains('t')
        if (writable && !file.canWrite() && file.exists()) {
            throw FileNotFoundException("Document is read-only: $documentId")
        }
        // Direct descriptor on the REAL Noxs Linux file: edits persist in the
        // guest filesystem. MODE_CREATE applies when the client writes a new
        // document after createDocument() pre-created the empty file.
        return ParcelFileDescriptor.open(file, pfdMode)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val fsReady = requireReady()
        val parent = resolveF(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Parent is not a directory: $parentDocumentId")
        val name = NoxsDocumentsPolicy.sanitizeDisplayName(displayName)
        val target = File(parent, NoxsDocumentsPolicy.uniqueChild(parent, name))
        val docId = joinDoc(parentDocumentId, target.name)
        try {
            NoxsDocumentsPolicy.resolve(fsReady, docId) // validates the composed id
        } catch (e: NoxsDocumentsPolicy.PolicyException) {
            throw FileNotFoundException(e.message)
        }
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!target.mkdir()) throw FileNotFoundException("Cannot create directory")
        } else {
            if (!target.createNewFile()) throw FileNotFoundException("Cannot create document")
        }
        notifyChildren(parentDocumentId)
        return docId
    }

    override fun deleteDocument(documentId: String) {
        val parentDocId = documentId.substringBeforeLast('/', missingDelimiterValue = NoxsDocumentsPolicy.HOME_DOC)
        resolveF(documentId) // validated + inside home
        if (documentId == NoxsDocumentsPolicy.HOME_DOC) {
            throw FileNotFoundException("Refusing to delete the Noxs home root")
        }
        fs.delete(documentId)
        notifyChildren(parentDocId)
        notifyChildren(NoxsDocumentsPolicy.HOME_DOC)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val parentDocId = documentId.substringBeforeLast('/', missingDelimiterValue = NoxsDocumentsPolicy.HOME_DOC)
        val file = resolveF(documentId)
        val name = NoxsDocumentsPolicy.sanitizeDisplayName(displayName)
        val target = File(file.parentFile, NoxsDocumentsPolicy.uniqueChild(file.parentFile, name))
        val newDocId = joinDoc(parentDocId, target.name)
        try {
            NoxsDocumentsPolicy.resolve(requireReady(), newDocId) // target stays inside home
        } catch (e: NoxsDocumentsPolicy.PolicyException) {
            throw FileNotFoundException(e.message)
        }
        if (!file.renameTo(target)) throw FileNotFoundException("Cannot rename: $documentId")
        notifyChildren(parentDocId)
        return newDocId
    }

    /** Stable id composition: child ids are the parent id + "/" + name; the
     *  canonical check on every use keeps them honest even if a stale client
     *  crafted one. */
    private fun joinDoc(parent: String, name: String): String =
        if (parent.endsWith("/")) parent + name else "$parent/$name"

    companion object {
        const val ROOT_ID = "noxs-home"

        private fun resolveRootProjection(projection: Array<out String>?): Array<out String> =
            projection ?: arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_FLAGS
            )

        private fun resolveDocumentProjection(projection: Array<out String>?): Array<out String> =
            projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS
            )
    }
}
