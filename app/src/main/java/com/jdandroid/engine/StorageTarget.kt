package com.jdandroid.engine

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.jdandroid.core.FileNames
import com.jdandroid.core.Texts
import com.jdandroid.data.SettingsRepository
import java.io.File

/**
 * Ablageorte fertiger Dateien: der App-Ordner (Teildateien, Archive,
 * Entpack-Ziel), der vom Nutzer gewaehlte SAF-Zielordner und der oeffentliche
 * Ordner Downloads/JDAndroid (MediaStore). Kennt die Reihenfolge der Ziele
 * (SAF vor MediaStore vor App-Ordner) und holt Dateien fuer das nachtraegliche
 * Entpacken von dort zurueck.
 */
internal class StorageTarget(
    private val context: Context,
    private val settings: SettingsRepository
) {
    /** App-eigener Download-Ordner; wird bei Bedarf angelegt. */
    fun downloadDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
            .apply { mkdirs() }

    /**
     * Vom Nutzer gewaehlter Zielordner (Storage Access Framework), z.B. auf der
     * SD-Karte. null, wenn keiner gewaehlt oder die Berechtigung verloren ist.
     */
    suspend fun targetTree(): DocumentFile? {
        val uri = settings.currentDownloadTreeUri() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri.toUri()) }
            .getOrNull()?.takeIf { it.canWrite() }
    }

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

    /** Copies a file into the SAF directory; returns the display path or null on failure. */
    private fun copyToTree(dir: TreeDir, file: File, name: String): String? {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val candidate = FileNames.uniqueName(name) { dir.contains(it) }
        val target = dir.createFile(mime, candidate) ?: return null
        return try {
            context.contentResolver.openOutputStream(target)?.use { out ->
                file.inputStream().use { it.copyTo(out, COPY_BUFFER) }
            } ?: run { deleteDocument(target); return null }
            "${dir.name ?: Texts.t("engine_target_folder")}/$candidate"
        } catch (e: Exception) {
            deleteDocument(target)
            null
        }
    }

    /** Verschiebt die fertige Datei ins Ziel (oeffentlicher Download-Ordner oder App-Ordner). */
    suspend fun finish(temp: File, fileName: String): String {
        targetTree()?.let { root ->
            copyToTree(TreeDir(root.uri, root.name), temp, fileName)?.let { path ->
                temp.delete()
                return path
            }
            // Kopieren fehlgeschlagen (Berechtigung weg?) -> regulaerer Weg
        }
        val export = settings.currentExportToDownloads() && Build.VERSION.SDK_INT >= 29
        if (export) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JDAndroid")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null && copyToMediaStore(resolver, uri, temp)) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    temp.delete()
                    return "Downloads/JDAndroid/$fileName"
                }
            } catch (_: Exception) {
                // Export fehlgeschlagen -> Datei bleibt im App-Ordner
            }
        }
        // Liegt die Datei bereits unter ihrem Zielnamen im App-Ordner ("Erneut
        // laden" mit vorhandener Datei), nicht in "name (2)" umbenennen
        if (temp.path == File(downloadDir(), fileName).path) return temp.absolutePath
        val dest = FileNames.uniqueFile(downloadDir(), fileName)
        if (temp.path != dest.path) {
            temp.renameTo(dest)
        }
        return dest.absolutePath
    }

    /**
     * Exportiert alle entpackten Dateien in den Zielordner (SAF) oder den
     * oeffentlichen Download-Ordner (Downloads/JDAndroid/<base>/...). Liefert
     * den Anzeigepfad; ohne Export bleiben sie im App-Ordner ([dir]).
     */
    suspend fun exportDirectory(dir: File, base: String): String {
        // Eigener Zielordner (SAF) hat Vorrang vor Downloads/JDAndroid
        targetTree()?.let { root ->
            val target = TreeDir(root.uri, root.name).subDir(base) ?: return dir.absolutePath
            var allOk = true
            FileTrees.regularFiles(dir).forEach { file ->
                val relDir = file.parentFile!!.relativeTo(dir).path
                val destDir = if (relDir.isEmpty()) target else target.subDir(relDir)
                if (destDir == null || copyToTree(destDir, file, file.name) == null) allOk = false
                else file.delete()
            }
            return if (allOk) {
                FileTrees.deleteTree(dir)
                "${root.name ?: Texts.t("engine_target_folder")}/$base"
            } else dir.absolutePath
        }
        // Direkter SDK_INT-Vergleich statt Hilfsvariable: Lint (AGP 8.13) erkennt
        // den Versions-Guard sonst nicht und meldet NewApi fuer MediaStore.Downloads.
        if (!settings.currentExportToDownloads() || Build.VERSION.SDK_INT < 29) return dir.absolutePath
        val resolver = context.contentResolver
        var allOk = true
        FileTrees.regularFiles(dir).forEach { file ->
            val relDir = file.parentFile!!.relativeTo(dir).path
            val relativePath = buildString {
                append(Environment.DIRECTORY_DOWNLOADS).append("/JDAndroid/").append(base)
                if (relDir.isNotEmpty()) append('/').append(relDir)
            }
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    if (!copyToMediaStore(resolver, uri, file)) {
                        allOk = false
                    } else {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        file.delete()
                    }
                } else {
                    allOk = false
                }
            } catch (_: Exception) {
                allOk = false
            }
        }
        return if (allOk) {
            FileTrees.deleteTree(dir)
            "Downloads/JDAndroid/$base"
        } else {
            dir.absolutePath
        }
    }

    /**
     * Datei in einen MediaStore-Eintrag kopieren. Ohne Ausgabestream oder bei
     * einem Fehler mitten im Kopieren wird der (halbe) Eintrag wieder
     * geloescht - sonst bliebe eine leere Datei in "Downloads" zurueck und die
     * Quelle wuerde trotzdem entfernt.
     */
    private fun copyToMediaStore(resolver: ContentResolver, uri: Uri, source: File): Boolean {
        try {
            val out = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return false
            }
            out.use { o -> source.inputStream().use { it.copyTo(o, COPY_BUFFER) } }
            return true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
    }

    /**
     * Exportierte Datei [name] aus dem Zielordner (SAF) oder aus
     * Downloads/JDAndroid (MediaStore) nach [dest] zurueckkopieren. false,
     * wenn sie dort nicht liegt oder das Kopieren scheitert.
     */
    suspend fun restoreExported(name: String, dest: File): Boolean {
        targetTree()?.let { root -> TreeDir(root.uri, null).fileUri(name) }?.let { uri ->
            return copyUriTo(uri, dest)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/JDAndroid%")
            runCatching {
                resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val mediaId = cursor.getLong(0)
                            val uri = ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, mediaId
                            )
                            return copyUriTo(uri, dest)
                        }
                    }
            }
        }
        return false
    }

    private fun copyUriTo(uri: Uri, dest: File): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it, COPY_BUFFER) }
        } != null
    } catch (_: Exception) {
        dest.delete()
        false
    }

    private companion object {
        /** Streams over ParcelFileDescriptor are unbuffered; 1 MiB keeps syscalls low on multi-GiB files. */
        const val COPY_BUFFER = 1 shl 20
    }
}
