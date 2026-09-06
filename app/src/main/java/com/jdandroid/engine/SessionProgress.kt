package com.jdandroid.engine

import com.jdandroid.core.LiveProgress
import com.jdandroid.data.DownloadItem
import com.jdandroid.data.DownloadStatus

/**
 * Entries of the current download session for the notification bar. Finished
 * entries stay in the set until nothing runs any more, so the bar only moves
 * forward instead of jumping whenever a download leaves the open set.
 */
internal class SessionProgress {
    private val ids = LinkedHashSet<Long>()

    fun add(open: Collection<Long>) = synchronized(ids) { ids.addAll(open) }

    fun clear() = synchronized(ids) { ids.clear() }

    fun ids(): List<Long> = synchronized(ids) { ids.toList() }

    companion object {
        /**
         * Done and total bytes over [items]; sizes still unknown do not count,
         * a finished entry counts in full, live bytes replace the database
         * snapshot and never exceed the size.
         */
        fun ratio(items: List<DownloadItem>, live: Map<Long, LiveProgress>): Pair<Long, Long> {
            var done = 0L
            var total = 0L
            for (item in items) {
                if (item.fileSize <= 0) continue
                total += item.fileSize
                done += when (item.status) {
                    DownloadStatus.COMPLETED, DownloadStatus.EXTRACTING -> item.fileSize
                    else -> {
                        val bytes = live[item.id]?.downloadedBytes?.takeIf { it >= 0 } ?: item.downloadedBytes
                        bytes.coerceIn(0, item.fileSize)
                    }
                }
            }
            return done to total
        }

        /** Percent for the notification, -1 without a known total. */
        fun percent(done: Long, total: Long): Int =
            if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
    }
}
