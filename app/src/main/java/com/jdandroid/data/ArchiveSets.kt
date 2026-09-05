package com.jdandroid.data

/**
 * SQL for multipart archives: which entries form a set and whether parts are
 * still missing, based on downloads.archiveKey (see
 * [com.jdandroid.core.ArchiveNames.archiveKey]). A set never spans packages;
 * "packageId IS :packageId" also matches entries without a package.
 *
 * Kept as constants so [DownloadDao] and the JVM test (ArchiveSetsTest,
 * against a real SQLite database) share the same text.
 */
object ArchiveSets {
    /** Unfinished parts that block automatic extraction. */
    const val ACTIVE_STATUSES = "'COLLECTED', 'QUEUED', 'RUNNING', 'PAUSED', 'EXTRACTING'"

    /** Parts still loading (for the manual "extract" action). */
    const val LOADING_STATUSES = "'COLLECTED', 'QUEUED', 'RUNNING', 'PAUSED'"

    /**
     * Pending parts of archive :key in package :packageId, excluding :selfId.
     * Entries of the same package without a file name count too (instant
     * start: the name only arrives with resolving). FAILED parts and other
     * packages do not.
     */
    private const val PENDING_FROM =
        "FROM downloads WHERE id != :selfId AND packageId IS :packageId " +
            "AND (archiveKey = :key OR (fileName IS NULL AND packageId IS NOT NULL)) AND status IN ("

    const val PENDING_ACTIVE = "SELECT COUNT(*) $PENDING_FROM$ACTIVE_STATUSES)"

    const val PENDING_LOADING = "SELECT COUNT(*) $PENDING_FROM$LOADING_STATUSES)"

    /**
     * All completed or extracting entries of the set including :selfId, even
     * if that one is still RUNNING. Other running parts are excluded: they
     * would otherwise be flipped to EXTRACTING mid-download.
     */
    const val SET_IDS =
        "SELECT id FROM downloads WHERE id = :selfId OR (packageId IS :packageId AND archiveKey = :key " +
            "AND status IN ('COMPLETED', 'EXTRACTING')) ORDER BY id"

    /** Completed parts of the set (manual extraction, restoring files). */
    const val COMPLETED_PARTS =
        "SELECT * FROM downloads WHERE packageId IS :packageId AND archiveKey = :key AND status = 'COMPLETED' ORDER BY id"

    /** "Remove links after extraction": all completed parts of the set plus the trigger. */
    const val DELETE_EXTRACTED =
        "DELETE FROM downloads WHERE packageId IS :packageId AND archiveKey = :key AND (id = :selfId OR status = 'COMPLETED')"

    /** Completed archive parts of a package carrying the wait note :note, groupable by archiveKey. */
    const val WAITING_PARTS =
        "SELECT * FROM downloads WHERE packageId = :packageId AND status = 'COMPLETED' " +
            "AND errorMessage = :note AND archiveKey IS NOT NULL ORDER BY id"

    /** Completed archives of a package ("extract package" action). */
    const val COMPLETED_ARCHIVES =
        "SELECT * FROM downloads WHERE packageId = :packageId AND status = 'COMPLETED' AND archiveKey IS NOT NULL ORDER BY addedAt"

    /** Does any entry of the package still reference the archive files of :key? */
    const val COUNT_KEY = "SELECT COUNT(*) FROM downloads WHERE packageId IS :packageId AND archiveKey = :key"
}
