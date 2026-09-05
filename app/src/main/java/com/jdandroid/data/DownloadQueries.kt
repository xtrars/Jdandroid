package com.jdandroid.data

/**
 * [DownloadDao] queries with non-trivial logic, kept as constants so the DAO
 * and the JVM test (DownloadQueriesTest, against a real SQLite database with
 * the exported schema) share the same text.
 */
object DownloadQueries {
    /**
     * Records an online check. Without :fileName the archiveKey is kept (an
     * OFFLINE result must not drop set membership); with a name the computed
     * key is stored, including NULL for a non-archive.
     */
    const val APPLY_CHECK =
        "UPDATE downloads SET online = :online, errorMessage = :note, " +
            "fileName = COALESCE(:fileName, fileName), " +
            "archiveKey = CASE WHEN :fileName IS NULL THEN archiveKey ELSE :archiveKey END, " +
            "fileSize = CASE WHEN :fileSize > 0 THEN :fileSize ELSE fileSize END " +
            "WHERE id = :id AND status = 'COLLECTED'"

    /**
     * "Wi-Fi only" on a metered network: a running entry goes back to the
     * queue with the [DownloadNotes.WAITING_WIFI] code, never a translated
     * text. Only RUNNING rows, so a completion in progress is left alone.
     */
    const val REQUEUE_IF_RUNNING =
        "UPDATE downloads SET status = 'QUEUED', retryAt = 0, speedBps = 0, " +
            "errorMessage = '${DownloadNotes.WAITING_WIFI}' WHERE id = :id AND status = 'RUNNING'"

    /**
     * Completes an entry whose finished file was exported outside the
     * completion lock. A pause or requeue that arrived during the export loses
     * (the transfer was already complete); a deleted entry stays deleted.
     */
    const val COMPLETE_EXPORTED =
        "UPDATE downloads SET status = 'COMPLETED', localPath = :path, errorMessage = :note, " +
            "speedBps = 0, attempts = 0, retryAt = 0 " +
            "WHERE id = :id AND status IN ('RUNNING', 'QUEUED', 'PAUSED')"

    /**
     * Next entry for pump(): due (retryAt <= :now), oldest first, and not in
     * :running, which requeueRunning() would otherwise hand out a second time.
     * Room expands the list; it must never be empty (callers append -1).
     */
    const val NEXT_QUEUED =
        "SELECT * FROM downloads WHERE status = 'QUEUED' AND retryAt <= :now " +
            "AND id NOT IN (:running) ORDER BY addedAt ASC LIMIT 1"

    /**
     * "Resume all" (screen and notification): paused and failed entries in one
     * step, nothing else. retryAt and attempts are cleared, otherwise an entry
     * paused during a wait would sit in the queue without its countdown.
     */
    const val REQUEUE_PAUSED_AND_FAILED =
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attempts = 0, " +
            "retryAt = 0 WHERE status IN ('PAUSED', 'FAILED')"

    /**
     * Next time a waiting entry starts on its own: smallest future retryAt up
     * to :horizon (captcha entries lie beyond it), NULL if nothing is pending.
     * The engine arms its timer from this, also after a service restart whose
     * delay() calls died with the old process.
     */
    const val NEXT_RETRY_AT =
        "SELECT MIN(retryAt) FROM downloads WHERE status = 'QUEUED' " +
            "AND retryAt > :now AND retryAt <= :horizon"

    /**
     * Sum of downloaded bytes of open entries except :except, the ones whose
     * live value lives in the ProgressBus while the database value may be up
     * to 30 s old. Room expands the list; it must never be empty (callers
     * append -1).
     */
    const val OPEN_DOWNLOADED_BYTES_EXCEPT =
        "SELECT COALESCE(SUM(downloadedBytes), 0) FROM downloads " +
            "WHERE status IN ('RUNNING', 'QUEUED', 'PAUSED') AND id NOT IN (:except)"
}
