package com.jdandroid.data

/**
 * Codes stored in `downloads.errorMessage` and compared later by SQL queries
 * or the engine. They are never stored translated (the language may change
 * between writing and reading); the UI translates them on display.
 */
object DownloadNotes {
    /** Completed archive part waiting for the remaining parts of its set. */
    const val WAITING_PARTS = "WAITING_PARTS"

    /** Download re-queued because of the "Wi-Fi only" setting. */
    const val WAITING_WIFI = "WAITING_WIFI"

    /** Finished locally, upload to the NFS target still pending (retried on network change and pump). */
    const val EXPORT_PENDING = "EXPORT_PENDING"

    /** German wording used before database version 11 (migration only). */
    const val LEGACY_WAITING_PARTS = "Warte auf weitere Archiv-Teile"

    /** German wording used before database version 11 (migration only). */
    const val LEGACY_WAITING_WIFI = "Wartet auf WLAN"
}
