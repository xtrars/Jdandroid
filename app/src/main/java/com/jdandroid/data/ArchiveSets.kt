package com.jdandroid.data

/**
 * SQL fuer Multipart-Archive: welche Eintraege gehoeren zu einem Set, und
 * fehlen noch Teile? Grundlage ist die Spalte downloads.archiveKey (siehe
 * [com.jdandroid.core.ArchiveNames.archiveKey]). Ein Set ist immer auf ein
 * Paket begrenzt - gleichnamige Archive in zwei Paketen sind zwei Sets;
 * "packageId IS :packageId" trifft dabei auch Eintraege ohne Paket.
 *
 * Die Abfragen stehen hier als Konstanten, damit [DownloadDao] und der
 * JVM-Test (ArchiveSetsTest, gegen eine echte SQLite-Datenbank) dieselben
 * Texte verwenden.
 */
object ArchiveSets {
    /** Noch nicht fertige Teile: blockieren das automatische Entpacken. */
    const val ACTIVE_STATUSES = "'COLLECTED', 'QUEUED', 'RUNNING', 'PAUSED', 'EXTRACTING'"

    /** Teile, die noch geladen werden (fuer das manuelle "Entpacken"). */
    const val LOADING_STATUSES = "'COLLECTED', 'QUEUED', 'RUNNING', 'PAUSED'"

    /**
     * Ausstehende Teile des Archivs :key im Paket :packageId ohne :selfId.
     * Zaehlt auch Eintraege desselben Pakets ohne Dateinamen (Sofortstart:
     * der Name kommt erst mit dem Aufloesen). FAILED-Teile und fremde
     * Pakete zaehlen nicht.
     */
    private const val PENDING_FROM =
        "FROM downloads WHERE id != :selfId AND packageId IS :packageId " +
            "AND (archiveKey = :key OR (fileName IS NULL AND packageId IS NOT NULL)) AND status IN ("

    const val PENDING_ACTIVE = "SELECT COUNT(*) $PENDING_FROM$ACTIVE_STATUSES)"

    const val PENDING_LOADING = "SELECT COUNT(*) $PENDING_FROM$LOADING_STATUSES)"

    /**
     * Alle fertigen oder entpackenden Eintraege des Sets, inklusive :selfId -
     * auch wenn der gerade noch RUNNING ist. Andere laufende Teile gehoeren
     * nicht dazu: sie wuerden sonst mitten im Download auf EXTRACTING gesetzt.
     */
    const val SET_IDS =
        "SELECT id FROM downloads WHERE id = :selfId OR (packageId IS :packageId AND archiveKey = :key " +
            "AND status IN ('COMPLETED', 'EXTRACTING')) ORDER BY id"

    /** Fertige Teile des Sets (nachtraegliches Entpacken, Zurueckholen der Dateien). */
    const val COMPLETED_PARTS =
        "SELECT * FROM downloads WHERE packageId IS :packageId AND archiveKey = :key AND status = 'COMPLETED' ORDER BY id"

    /** "Links nach dem Entpacken entfernen": alle fertigen Teile des Sets plus den Ausloeser. */
    const val DELETE_EXTRACTED =
        "DELETE FROM downloads WHERE packageId IS :packageId AND archiveKey = :key AND (id = :selfId OR status = 'COMPLETED')"

    /** Fertige Archiv-Teile eines Pakets mit Wartehinweis (:note), gruppierbar nach archiveKey. */
    const val WAITING_PARTS =
        "SELECT * FROM downloads WHERE packageId = :packageId AND status = 'COMPLETED' " +
            "AND errorMessage = :note AND archiveKey IS NOT NULL ORDER BY id"

    /** Fertige Archive eines Pakets (Aktion "Paket entpacken"). */
    const val COMPLETED_ARCHIVES =
        "SELECT * FROM downloads WHERE packageId = :packageId AND status = 'COMPLETED' AND archiveKey IS NOT NULL ORDER BY addedAt"

    /** Referenziert noch irgendein Eintrag (paketuebergreifend) die Archivdateien von :key? */
    const val COUNT_KEY = "SELECT COUNT(*) FROM downloads WHERE archiveKey = :key"

    /** Gleichnamige fertige/entpackende Datei in einem anderen Paket (liegt flach im App-Ordner). */
    const val SAME_NAME_ELSEWHERE =
        "SELECT COUNT(*) FROM downloads WHERE fileName = :fileName AND packageId IS NOT :packageId " +
            "AND status IN ('COMPLETED', 'EXTRACTING')"
}
