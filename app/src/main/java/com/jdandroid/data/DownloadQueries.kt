package com.jdandroid.data

/**
 * Weitere Abfragen des [DownloadDao] mit eigener Logik, die der JVM-Test
 * (DownloadQueriesTest, gegen eine echte SQLite-Datenbank mit dem
 * exportierten Schema) prueft - wie die Set-Abfragen in [ArchiveSets]
 * stehen sie hier als Konstanten, damit DAO und Test denselben Text nutzen.
 */
object DownloadQueries {
    /**
     * Ergebnis der Online-Pruefung eintragen. Name, Archivschluessel und
     * Groesse nur, wenn bekannt: ohne :fileName bleibt archiveKey stehen
     * (eine OFFLINE-Pruefung darf die Set-Zugehoerigkeit nicht loeschen),
     * mit Name wird der berechnete Schluessel gesetzt - auch NULL fuer ein
     * Nicht-Archiv.
     */
    const val APPLY_CHECK =
        "UPDATE downloads SET online = :online, errorMessage = :note, " +
            "fileName = COALESCE(:fileName, fileName), " +
            "archiveKey = CASE WHEN :fileName IS NULL THEN archiveKey ELSE :archiveKey END, " +
            "fileSize = CASE WHEN :fileSize > 0 THEN :fileSize ELSE fileSize END " +
            "WHERE id = :id AND status = 'COLLECTED'"

    /** "Alle fortsetzen": pausierte und gescheiterte Eintraege in einem Schritt, nichts anderes. */
    const val REQUEUE_PAUSED_AND_FAILED =
        "UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, attempts = 0, " +
            "retryAt = 0 WHERE status IN ('PAUSED', 'FAILED')"

    /**
     * Naechster Zeitpunkt, zu dem ein wartender Eintrag von selbst startet:
     * kleinstes retryAt in der Zukunft bis :horizon (Captcha-Eintraege liegen
     * dahinter). NULL, wenn nichts ansteht. Die Engine stellt darauf ihren
     * Timer - auch nach einem Neustart des Dienstes, dessen delay()-Aufrufe
     * mit dem alten Prozess verschwunden sind.
     */
    const val NEXT_RETRY_AT =
        "SELECT MIN(retryAt) FROM downloads WHERE status = 'QUEUED' " +
            "AND retryAt > :now AND retryAt <= :horizon"

    /**
     * Summe des Bytestands offener Eintraege ohne :except - die Eintraege mit
     * Live-Stand im ProgressBus, deren Datenbankwert bis zu 30 s alt ist.
     * Room expandiert die Liste; sie darf nie leer sein (Aufrufer haengt -1 an).
     */
    const val OPEN_DOWNLOADED_BYTES_EXCEPT =
        "SELECT COALESCE(SUM(downloadedBytes), 0) FROM downloads " +
            "WHERE status IN ('RUNNING', 'QUEUED', 'PAUSED') AND id NOT IN (:except)"
}
