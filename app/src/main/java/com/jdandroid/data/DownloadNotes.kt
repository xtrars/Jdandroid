package com.jdandroid.data

/**
 * Vermerke, die als Code in `downloads.errorMessage` gespeichert und von
 * SQL-Abfragen oder der Engine spaeter verglichen werden. Sie duerfen nie
 * uebersetzt gespeichert werden (Sprachwechsel zwischen Schreiben und Lesen);
 * die Oberflaeche uebersetzt den Code erst bei der Anzeige.
 */
object DownloadNotes {
    /** Fertiger Archiv-Teil, der auf die uebrigen Teile des Sets wartet. */
    const val WAITING_PARTS = "WAITING_PARTS"

    /** Download wurde wegen "Nur WLAN" zurueck in die Warteschlange gestellt. */
    const val WAITING_WIFI = "WAITING_WIFI"

    /** Deutscher Wortlaut vor Version 11 der Datenbank (nur fuer die Migration). */
    const val LEGACY_WAITING_PARTS = "Warte auf weitere Archiv-Teile"

    /** Deutscher Wortlaut vor Version 11 der Datenbank (nur fuer die Migration). */
    const val LEGACY_WAITING_WIFI = "Wartet auf WLAN"
}
