package com.jdandroid.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.jdandroid.core.FileNames
import com.jdandroid.core.Texts
import com.jdandroid.data.NfsSettings
import com.jdandroid.engine.nfs.NfsTarget
import java.io.File

/**
 * One place finished files can be stored. [StorageTarget] asks the targets in
 * a fixed order (NAS, SAF folder, Downloads/JDAndroid, app folder); a new
 * target is one more implementation, not a branch in every method.
 */
internal interface ExportTarget {
    /** Stores [temp] as [fileName]; null when this target could not take it and the next one is asked. */
    suspend fun finish(temp: File, fileName: String, progress: ((done: Long, total: Long) -> Unit)?): Placed?

    /**
     * Exports every file below [dir] into the folder [base]. Files that could
     * not go stay in [dir], and the result says so with [Placed.error] or
     * [Placed.pending], so the entries are not removed from the list.
     */
    suspend fun exportDirectory(dir: File, base: String, progress: ((done: Long, total: Long) -> Unit)?, replace: Boolean): Placed

    /** Copies the stored file [name] back to [dest]; false when it is not there. */
    suspend fun restore(name: String, dest: File): Boolean

    /** Removes the copy behind [displayPath]; false when nothing was removed. */
    suspend fun delete(displayPath: String): Boolean
}

/** The app's own download folder: always takes the file, the fallback of every other target. */
internal class AppFolderTarget(private val dir: File) : ExportTarget {

    /** A file already stored here (re-download, export retry) keeps its name. */
    override suspend fun finish(temp: File, fileName: String, progress: ((done: Long, total: Long) -> Unit)?): Placed =
        Placed(storeLocally(temp, fileName))

    fun storeLocally(temp: File, fileName: String): String {
        if (temp.path == File(dir, fileName).path) return temp.absolutePath
        if (temp.parentFile?.path == dir.path && !temp.name.endsWith(".part")) return temp.absolutePath
        val dest = FileNames.uniqueFile(dir, fileName)
        if (temp.path != dest.path) {
            temp.renameTo(dest)
        }
        return dest.absolutePath
    }

    override suspend fun exportDirectory(
        dir: File, base: String, progress: ((done: Long, total: Long) -> Unit)?, replace: Boolean
    ): Placed = Placed(dir.absolutePath)

    override suspend fun restore(name: String, dest: File): Boolean = false

    override suspend fun delete(displayPath: String): Boolean =
        File(displayPath).let { it.isFile && it.startsWith(dir) && it.delete() }
}

/** The NAS share: definitive once configured, a failure keeps the files local for the retry or with the error. */
internal class NasTarget(
    private val nfs: NfsTarget,
    private val settings: NfsSettings,
    private val local: AppFolderTarget
) : ExportTarget {

    override suspend fun finish(temp: File, fileName: String, progress: ((done: Long, total: Long) -> Unit)?): Placed {
        val total = temp.length()
        return when (val outcome = nfs.finish(settings, temp, fileName, progress?.let { p -> { done: Long -> p(done, total) } })) {
            is NfsTarget.Outcome.Done -> { temp.delete(); Placed(outcome.displayPath) }
            is NfsTarget.Outcome.Failed -> Placed.local(local.storeLocally(temp, fileName), outcome.failure)
        }
    }

    override suspend fun exportDirectory(
        dir: File, base: String, progress: ((done: Long, total: Long) -> Unit)?, replace: Boolean
    ): Placed = when (val outcome = nfs.exportDirectory(settings, dir, base, progress, replace)) {
        is NfsTarget.Outcome.Done -> Placed(outcome.displayPath)
        is NfsTarget.Outcome.Failed -> Placed.local(dir.absolutePath, outcome.failure)
    }

    override suspend fun restore(name: String, dest: File): Boolean = nfs.restoreExported(settings, name, dest)

    override suspend fun delete(displayPath: String): Boolean =
        nfs.deleteExported(settings, StorageTarget.storedName(displayPath))
}

/**
 * Some files stayed in the app folder: the error keeps the entries in the
 * list (no "remove after extraction"), otherwise they would vanish unseen.
 */
internal fun incompleteExport(dir: File, failed: Int, total: Int): Placed =
    Placed(dir.absolutePath, error = Texts.t("engine_export_incomplete", failed, total))

/** Streams over ParcelFileDescriptor are unbuffered; 1 MiB keeps syscalls low on multi-GiB files. */
internal const val EXPORT_COPY_BUFFER = 1 shl 20

internal fun ContentResolver.copyUriTo(uri: Uri, dest: File): Boolean = try {
    openInputStream(uri)?.use { input ->
        dest.outputStream().use { input.copyTo(it, EXPORT_COPY_BUFFER) }
    } != null
} catch (_: Exception) {
    dest.delete()
    false
}

/** Name the provider actually stored the document under; providers rename on collision ("x (1).rar"). */
internal fun ContentResolver.displayName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
    }
}.getOrNull()
