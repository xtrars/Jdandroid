package com.jdandroid.data

/** SQL of the package-wide DAO statements, shared with the JVM tests. */
object PackageQueries {

    /** Manual restart of a whole package; running and completed entries stay untouched. */
    const val REQUEUE_PACKAGE =
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attempts = 0, " +
            "retryAt = 0 WHERE packageId = :packageId AND status IN ('PAUSED', 'FAILED', 'OFFLINE')"

    const val DELETE_COLLECTED_IN_PACKAGE =
        "DELETE FROM downloads WHERE packageId = :packageId AND status = 'COLLECTED'"

    const val COUNT_NOT_COLLECTED =
        "SELECT COUNT(*) FROM downloads WHERE packageId = :packageId AND status != 'COLLECTED'"
}
