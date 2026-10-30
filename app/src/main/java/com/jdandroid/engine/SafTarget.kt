package com.jdandroid.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.jdandroid.core.FileNames
import com.jdandroid.core.Texts
import java.io.File

/** The folder the user chose via the system picker (Storage Access Framework). */
internal class SafTarget(private val context: Context, private val root: DocumentFile) : ExportTarget {

    /** First segment of the display paths this target writes. */
    val displayRoot: String get() = root.name ?: Texts.t("engine_target_folder")

    override suspend fun finish(temp: File, fileName: String, progress: ((done: Long, total: Long) -> Unit)?): Placed? =
        copyToTree(TreeDir(root.uri, root.name), temp, fileName)?.let { path ->
            temp.delete()
            Placed(path)
        }

    override suspend fun exportDirectory(
        dir: File, base: String, progress: ((done: Long, total: Long) -> Unit)?, replace: Boolean
    ): Placed {
        val files = FileTrees.regularFiles(dir)
        val target = TreeDir(root.uri, root.name).subDir(base) ?: return incompleteExport(dir, files.size, files.size)
        var failed = 0
        files.forEach { file ->
            val relDir = file.parentFile!!.relativeTo(dir).path
            val destDir = if (relDir.isEmpty()) target else target.subDir(relDir)
            if (destDir == null || copyToTree(destDir, file, file.name) == null) failed++
            else file.delete()
        }
        return if (failed == 0) {
            FileTrees.deleteTree(dir)
            Placed("$displayRoot/$base")
        } else incompleteExport(dir, failed, files.size)
    }

    override suspend fun restore(name: String, dest: File): Boolean =
        TreeDir(root.uri, null).fileUri(name)?.let { context.contentResolver.copyUriTo(it, dest) } ?: false

    override suspend fun delete(displayPath: String): Boolean =
        TreeDir(root.uri, null).fileUri(StorageTarget.storedName(displayPath))?.let { uri ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        } ?: false

    private class Child(val uri: Uri, val isDir: Boolean)

    /**
     * SAF directory whose listing is queried once; name checks and child
     * lookups then run in memory. DocumentFile.findFile would list the
     * directory and query each child's name per call.
     */
    private inner class TreeDir(val uri: Uri, val name: String?) {
        private val children = HashMap<String, Child>()
        private val subDirs = HashMap<String, TreeDir>()

        init {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                uri, DocumentsContract.getDocumentId(uri)
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val childName = cursor.getString(1) ?: continue
                        val isDir = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                        children[childName] = Child(DocumentsContract.buildDocumentUriUsingTree(uri, id), isDir)
                    }
                }
            }
        }

        fun contains(childName: String): Boolean = childName in children

        fun fileUri(childName: String): Uri? = children[childName]?.takeIf { !it.isDir }?.uri

        /** Creates a file; the provider may still rename it if the name is taken. */
        fun createFile(mime: String, childName: String): Uri? =
            create(mime, childName)?.also { children[childName] = Child(it, false) }

        /** Existing or newly created directory below this one, cached per path. */
        fun subDir(path: String): TreeDir? {
            var current: TreeDir = this
            path.split('/').filter { it.isNotBlank() }.forEach { part ->
                current = current.subDirs[part] ?: current.openOrCreateDir(part)?.also { current.subDirs[part] = it }
                    ?: return null
            }
            return current
        }

        private fun openOrCreateDir(childName: String): TreeDir? {
            val dirUri = children[childName]?.takeIf { it.isDir }?.uri
                ?: create(DocumentsContract.Document.MIME_TYPE_DIR, childName)
                    ?.also { children[childName] = Child(it, true) }
                ?: return null
            return TreeDir(dirUri, childName)
        }

        private fun create(mime: String, childName: String): Uri? =
            runCatching { DocumentsContract.createDocument(context.contentResolver, uri, mime, childName) }
                .getOrNull()
    }

    private fun deleteDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    /** Copies a file into the SAF directory; returns the display path (with the stored name) or null on failure. */
    private fun copyToTree(dir: TreeDir, file: File, name: String): String? {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val candidate = FileNames.uniqueName(name) { dir.contains(it) }
        val target = dir.createFile(mime, candidate) ?: return null
        return try {
            context.contentResolver.openOutputStream(target)?.use { out ->
                file.inputStream().use { it.copyTo(out, EXPORT_COPY_BUFFER) }
            } ?: run { deleteDocument(target); return null }
            "${dir.name ?: Texts.t("engine_target_folder")}/${context.contentResolver.displayName(target) ?: candidate}"
        } catch (e: Exception) {
            deleteDocument(target)
            null
        }
    }
}
