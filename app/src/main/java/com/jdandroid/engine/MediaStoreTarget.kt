package com.jdandroid.engine

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/** The public folder Downloads/JDAndroid, written through MediaStore without storage permission. */
@RequiresApi(29)
internal class MediaStoreTarget(context: Context) : ExportTarget {

    private val resolver: ContentResolver = context.contentResolver

    override suspend fun finish(temp: File, fileName: String, progress: ((done: Long, total: Long) -> Unit)?): Placed? {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null && copyTo(uri, temp)) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                temp.delete()
                return Placed("${StorageTarget.MEDIA_STORE_DISPLAY}${resolver.displayName(uri) ?: fileName}")
            }
        } catch (_: Exception) {
        }
        return null
    }

    override suspend fun exportDirectory(
        dir: File, base: String, progress: ((done: Long, total: Long) -> Unit)?, replace: Boolean
    ): Placed {
        val files = FileTrees.regularFiles(dir)
        var failed = 0
        files.forEach { file ->
            val relDir = file.parentFile!!.relativeTo(dir).path
            val relativePath = buildString {
                append(RELATIVE_DIR).append('/').append(base)
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
                    if (!copyTo(uri, file)) {
                        failed++
                    } else {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        file.delete()
                    }
                } else {
                    failed++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        return if (failed == 0) {
            FileTrees.deleteTree(dir)
            Placed("${StorageTarget.MEDIA_STORE_DISPLAY}$base")
        } else incompleteExport(dir, failed, files.size)
    }

    override suspend fun restore(name: String, dest: File): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        return runCatching {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, SELECTION, arrayOf(name, "$RELATIVE_DIR/"), null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use false
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    resolver.copyUriTo(uri, dest)
                } ?: false
        }.getOrDefault(false)
    }

    override suspend fun delete(displayPath: String): Boolean = runCatching {
        val args = arrayOf(StorageTarget.storedName(displayPath), "$RELATIVE_DIR/")
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, SELECTION, args) > 0
    }.getOrDefault(false)

    /** On failure the half-written entry is deleted, otherwise an empty file would remain in Downloads. */
    private fun copyTo(uri: Uri, source: File): Boolean {
        try {
            val out = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return false
            }
            out.use { o -> source.inputStream().use { it.copyTo(o, EXPORT_COPY_BUFFER) } }
            return true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }
    }

    private companion object {
        val RELATIVE_DIR = Environment.DIRECTORY_DOWNLOADS + "/JDAndroid"

        // Exact folder: a LIKE prefix would also match extracted files of the same name in sub folders
        val SELECTION = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    }
}
