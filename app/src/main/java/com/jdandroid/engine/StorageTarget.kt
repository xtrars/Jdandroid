package com.jdandroid.engine

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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

    /** Datei in den SAF-Ordner kopieren; liefert den Anzeigepfad oder null bei Fehler. */
    private fun copyToTree(dir: DocumentFile, file: File, name: String): String? {
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        // Vorhandene Datei nicht ueberschreiben, sondern durchnummerieren
        var candidate = name
        var index = 2
        while (dir.findFile(candidate) != null && index < 1000) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            candidate = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            index++
        }
        val target = dir.createFile(mime, candidate) ?: return null
        return try {
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: run { target.delete(); return null }
            "${dir.name ?: Texts.t("engine_target_folder")}/${target.name ?: candidate}"
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    private fun subDir(root: DocumentFile, path: String): DocumentFile? {
        var current: DocumentFile = root
        path.split('/').filter { it.isNotBlank() }.forEach { part ->
            current = current.findFile(part)?.takeIf { it.isDirectory }
                ?: current.createDirectory(part) ?: return null
        }
        return current
    }

    /** Verschiebt die fertige Datei ins Ziel (oeffentlicher Download-Ordner oder App-Ordner). */
    suspend fun finish(temp: File, fileName: String): String {
        targetTree()?.let { root ->
            copyToTree(root, temp, fileName)?.let { path ->
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
            val target = subDir(root, base) ?: return dir.absolutePath
            var allOk = true
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relDir = file.parentFile!!.relativeTo(dir).path
                val destDir = if (relDir.isEmpty()) target else subDir(target, relDir)
                if (destDir == null || copyToTree(destDir, file, file.name) == null) allOk = false
                else file.delete()
            }
            return if (allOk) {
                dir.deleteRecursively()
                "${root.name ?: Texts.t("engine_target_folder")}/$base"
            } else dir.absolutePath
        }
        // Direkter SDK_INT-Vergleich statt Hilfsvariable: Lint (AGP 8.13) erkennt
        // den Versions-Guard sonst nicht und meldet NewApi fuer MediaStore.Downloads.
        if (!settings.currentExportToDownloads() || Build.VERSION.SDK_INT < 29) return dir.absolutePath
        val resolver = context.contentResolver
        var allOk = true
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
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
            dir.deleteRecursively()
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
            out.use { o -> source.inputStream().use { it.copyTo(o) } }
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
        targetTree()?.findFile(name)?.takeIf { it.isFile }?.let { doc ->
            return copyUriTo(doc.uri, dest)
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
            dest.outputStream().use { input.copyTo(it) }
        } != null
    } catch (_: Exception) {
        dest.delete()
        false
    }
}
