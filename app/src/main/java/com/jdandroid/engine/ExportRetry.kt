package com.jdandroid.engine

import com.jdandroid.core.Clock
import com.jdandroid.data.DownloadDao
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadNotes
import com.jdandroid.data.DownloadStatus
import com.jdandroid.data.SettingsRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Pure decisions of the export retry: which entries are due and how often a pass may run. */
internal object ExportRetryPolicy {
    /** Minimum gap between two pump-triggered passes; a network change runs at once. */
    const val MIN_INTERVAL_MS = 60_000L

    fun isDue(nowMs: Long, lastRunMs: Long?, forced: Boolean): Boolean =
        forced || lastRunMs == null || nowMs - lastRunMs >= MIN_INTERVAL_MS

    /**
     * Pending entries grouped by their local path: the parts of an extracted
     * archive share one folder and are uploaded once. Entries without a path
     * have nothing to upload.
     */
    fun groups(items: List<DownloadItem>): Map<String, List<DownloadItem>> =
        items.filter {
            it.status == DownloadStatus.COMPLETED && it.errorMessage == DownloadNotes.EXPORT_PENDING &&
                !it.localPath.isNullOrBlank()
        }.groupBy { it.localPath!! }
}

/**
 * Uploads entries left local with [DownloadNotes.EXPORT_PENDING] once the NFS
 * target is reachable again. Transient failures stay silent, permanent ones
 * replace the note by the error text.
 */
internal class ExportRetry(
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val storage: StorageTarget,
    private val clock: Clock
) {
    private val mutex = Mutex()
    private var lastRunMs: Long? = null
    private val running = AtomicInteger()

    /** 1 while a pass uploads, so the service does not stop underneath it. */
    val activeCount: Int get() = running.get()

    /** Runs a pass if due; true when entries were attempted (the list may have changed). */
    suspend fun run(forced: Boolean): Boolean {
        // One pass at a time; a second caller has nothing to add
        if (!mutex.tryLock()) return false
        try {
            val now = clock.nowMillis()
            if (!ExportRetryPolicy.isDue(now, lastRunMs, forced)) return false
            lastRunMs = now
            if (!settings.currentNfs().isUsable) return false
            val groups = ExportRetryPolicy.groups(dao.byNote(DownloadNotes.EXPORT_PENDING))
            if (groups.isEmpty()) return false
            running.incrementAndGet()
            try {
                withContext(NonCancellable) { groups.forEach { (path, items) -> upload(path, items) } }
            } finally {
                running.decrementAndGet()
            }
            return true
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun upload(path: String, items: List<DownloadItem>) {
        val local = File(path)
        val ids = items.map { it.id }
        val placed = when {
            local.isDirectory -> storage.exportDirectory(local, local.name)
            local.isFile -> storage.finish(local, items.first().fileName ?: local.name)
            // Removed by the user meanwhile: nothing left to upload
            else -> { dao.setExported(ids, path, null); return }
        }
        if (placed.pending) return
        dao.setExported(ids, placed.path, placed.error)
    }
}
