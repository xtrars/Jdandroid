package com.jdandroid.engine

import android.content.Context
import android.os.Build
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.SettingsRepository
import com.jdandroid.engine.nfs.NfsFailure
import com.jdandroid.engine.nfs.NfsTarget
import java.io.File

/**
 * Where a finished file or folder ended up. [pending]: the NFS target was
 * unreachable, the file stays at [path] (local) until the retry; [error]: the
 * NFS target refused it for good, also local.
 */
internal data class Placed(val path: String, val pending: Boolean = false, val error: String? = null) {
    /** Note for `errorMessage`: the retry code, the error text or nothing. */
    val note: String? get() = if (pending) DownloadNotes.EXPORT_PENDING else error

    companion object {
        fun local(path: String, failure: NfsFailure): Placed =
            Placed(path, pending = failure is NfsFailure.Transient, error = (failure as? NfsFailure.Permanent)?.message)
    }
}

/**
 * Storage locations for finished files, built from the current settings: an
 * enabled NFS target comes first and keeps files local when it fails;
 * otherwise the SAF folder, Downloads/JDAndroid (MediaStore) and the app
 * directory are tried in this order. Exported files can be fetched back for
 * later extraction.
 */
internal class StorageTarget(
    private val context: Context,
    private val settings: SettingsRepository,
    private val nfs: NfsTarget = NfsTarget()
) : DownloadFiles {
    override fun downloadDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
            .apply { mkdirs() }

    /** The user's SAF target folder, or null if none is chosen or the permission was lost. */
    suspend fun targetTree(): DocumentFile? {
        val uri = settings.currentDownloadTreeUri() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri.toUri()) }
            .getOrNull()?.takeIf { it.canWrite() }
    }

    private fun appFolder() = AppFolderTarget(downloadDir())

    private suspend fun nas(): NasTarget? =
        settings.currentNfs().takeIf { it.isUsable }?.let { NasTarget(nfs, it, appFolder()) }

    private suspend fun saf(): SafTarget? = targetTree()?.let { SafTarget(context, it) }

    // Lint only recognises the version guard as a direct SDK_INT comparison.
    private suspend fun mediaStore(): ExportTarget? =
        if (settings.currentExportToDownloads() && Build.VERSION.SDK_INT >= 29) MediaStoreTarget(context) else null

    /** Moves a finished file to its target and returns where it went; [progress] reports NAS uploads only. */
    suspend fun finish(temp: File, fileName: String, progress: ((done: Long, total: Long) -> Unit)? = null): Placed {
        nas()?.let { return it.finish(temp, fileName, progress) }
        saf()?.finish(temp, fileName, progress)?.let { return it }
        mediaStore()?.finish(temp, fileName, progress)?.let { return it }
        return appFolder().finish(temp, fileName, progress)
    }

    /**
     * Exports all extracted files to the first configured target below the
     * folder [base] and returns the display path; without export they stay in [dir].
     */
    suspend fun exportDirectory(
        dir: File,
        base: String,
        progress: ((done: Long, total: Long) -> Unit)? = null,
        replace: Boolean = false
    ): Placed {
        val target = nas() ?: saf() ?: mediaStore() ?: appFolder()
        return target.exportDirectory(dir, base, progress, replace)
    }

    /**
     * Copies the exported file back to [dest] from the NFS target, the SAF
     * folder or Downloads/JDAndroid. [name] is the stored name (see
     * [storedName]), which differs from the download name after a collision.
     */
    suspend fun restoreExported(name: String, dest: File): Boolean {
        nas()?.let { if (it.restore(name, dest)) return true }
        saf()?.let { if (it.restore(name, dest)) return true }
        // Regardless of the current setting: the file may have been exported while it was on
        return Build.VERSION.SDK_INT >= 29 && MediaStoreTarget(context).restore(name, dest)
    }

    /**
     * Removes the exported copy behind a stored display path (NAS, SAF folder,
     * Downloads/JDAndroid or the app folder), for a re-download that must not
     * leave the corrupt copy behind. Best effort; false when nothing was removed.
     */
    override suspend fun deleteExported(displayPath: String): Boolean {
        if (storedName(displayPath).isBlank()) return false
        val saf = saf()
        return when (exportedAt(displayPath, saf?.displayRoot)) {
            ExportedAt.APP_FOLDER -> appFolder().delete(displayPath)
            ExportedAt.NFS -> nas()?.delete(displayPath) ?: false
            ExportedAt.MEDIA_STORE -> Build.VERSION.SDK_INT >= 29 && MediaStoreTarget(context).delete(displayPath)
            ExportedAt.SAF -> saf?.delete(displayPath) ?: false
            ExportedAt.UNKNOWN -> false
        }
    }

    companion object {
        /**
         * Name a finished file was stored under, taken from the display path
         * in `localPath` ("Downloads/JDAndroid/x (1).rar", "Target/x (1).rar",
         * "nfs://host/share/x (2).rar" or a local path).
         */
        fun storedName(displayPath: String): String = displayPath.substringAfterLast('/')

        /** Display prefix of files exported to the public Downloads/JDAndroid folder (MediaStore). */
        internal const val MEDIA_STORE_DISPLAY = "Downloads/JDAndroid/"

        /**
         * Which copy a display path names; [safRoot] is the display name of the
         * chosen SAF folder, null without one. Decided by the path alone, so a
         * later change of the target never removes a same-named file elsewhere.
         */
        internal fun exportedAt(displayPath: String, safRoot: String?): ExportedAt = when {
            File(displayPath).isAbsolute -> ExportedAt.APP_FOLDER
            displayPath.startsWith("nfs://") -> ExportedAt.NFS
            displayPath.startsWith(MEDIA_STORE_DISPLAY) -> ExportedAt.MEDIA_STORE
            safRoot != null && displayPath.startsWith("$safRoot/") -> ExportedAt.SAF
            else -> ExportedAt.UNKNOWN
        }
    }

    internal enum class ExportedAt { APP_FOLDER, NFS, MEDIA_STORE, SAF, UNKNOWN }
}
