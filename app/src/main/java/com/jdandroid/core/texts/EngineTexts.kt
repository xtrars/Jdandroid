package com.jdandroid.core.texts

/**
 * Deutsche Standardtexte der Engine (DownloadEngine, Extractor, FreeMode,
 * Format) fuer [com.jdandroid.core.Texts] ohne Android-Context. Jeder
 * Schluessel steht wortgleich in `res/values/strings_engine.xml` und
 * uebersetzt in `res/values-en/strings_engine.xml` (geprueft von `TextsTest`).
 *
 * Schluesselschema: `engine_<bedeutung>`; Platzhalter `%1$s`, `%1$d`.
 */
object EngineTexts {
    val texts: Map<String, String> = mapOf(
        // Free-Modus (FreeMode): Anzeige der gespeicherten Codes
        "engine_free_wait" to "Wartezeit im Free-Modus: %1\$s",
        "engine_free_wait_reason" to "Wartezeit im Free-Modus: %1\$s – %2\$s",
        "engine_free_captcha" to "Captcha nötig – im Menü „Captcha lösen“",
        "engine_free_captcha_reason" to "%1\$s – im Menü „Captcha lösen“",
        "engine_free_disabled" to "Kein Konto und Free-Modus aus",
        "engine_free_no_premium" to "Konto ohne Premium und Free-Modus aus",

        // Download-Ablauf (DownloadEngine)
        "engine_target_folder" to "Zielordner",
        "engine_unknown_hoster" to "Unbekannter Hoster",
        "engine_generic_error" to "Fehler",
        "engine_invalid_download_url" to "Ungültige Download-Adresse: %1\$s",
        "engine_gave_up" to "%1\$s (nach %2\$d Versuchen aufgegeben)",
        "engine_retry_scheduled" to "%1\$s – Versuch %2\$d/%3\$d in %4\$d s",
        "engine_part_size_mismatch" to "Teildatei passt nicht zur Dateigröße – Neustart",
        "engine_http_error" to "Server antwortete mit HTTP %1\$d",
        "engine_html_instead_of_file" to "Server lieferte eine HTML-Seite statt der Datei (%1\$s) – Link wird neu aufgelöst",
        "engine_empty_response" to "Leere Antwort beim Download – Link wird neu aufgelöst",
        "engine_size_mismatch" to "Server meldet %1\$s statt %2\$s – Link wird neu aufgelöst",
        "engine_not_enough_space" to "Zu wenig Speicherplatz: %1\$s benötigt, %2\$s frei",
        "engine_download_incomplete" to "Download unvollständig (%1\$s von %2\$s)",
        "engine_hash_mismatch" to "Prüfsumme (%1\$s) stimmt nicht – Datei wird erneut geladen",

        // Archive und Entpacken (DownloadEngine)
        "engine_first_volume_missing_not_extracted" to "Erstes Archiv-Teil fehlt, nicht entpackt",
        "engine_first_volume_missing" to "Erstes Archiv-Teil fehlt",
        "engine_entry_not_found" to "Eintrag nicht gefunden",
        "engine_already_extracting" to "Wird bereits entpackt",
        "engine_only_completed_extractable" to "Nur fertige Downloads lassen sich entpacken",
        "engine_file_name_unknown" to "Dateiname unbekannt",
        "engine_not_an_archive" to "Kein Archiv: %1\$s",
        "engine_archive_part_missing" to "Archivteil nicht mehr vorhanden: %1\$s",
        "engine_archive_incomplete_loading" to "Archiv unvollständig – weitere Teile werden noch geladen",

        // Entpacker (Extractor)
        "engine_seven_zip_unavailable" to "Native 7-Zip-Bibliothek konnte nicht geladen werden (RAR-Entpacken nicht möglich): %1\$s",
        "engine_unknown_archive_format" to "Unbekanntes Archivformat: %1\$s",
        "engine_extract_failed" to "Entpacken fehlgeschlagen (Passwort nicht in der Liste?): %1\$s",
        "engine_extract_error" to "Entpacken fehlgeschlagen: %1\$s",
        "engine_move_failed" to "Konnte %1\$s nicht nach %2\$s verschieben",
        "engine_password_required" to "Passwort erforderlich",
        "engine_wrong_password" to "Falsches Passwort",
        "engine_rar_extraction_result" to "RAR-Extraktion: %1\$s",
        "engine_invalid_archive_path" to "Ungültiger Pfad im Archiv: %1\$s",
        "engine_secrets_encrypt_failed" to "Zugangsdaten konnten nicht verschlüsselt werden (Android-Keystore nicht verfügbar: %1\$s). Bitte Gerät entsperren und erneut versuchen.",
        "engine_secrets_decrypt_failed" to "Zugangsdaten nicht lesbar (Android-Keystore: %1\$s). Bitte das Konto löschen und neu anlegen.",
        "engine_secrets_key_missing" to "Schlüssel fehlt im Keystore",
        "engine_package_from_date" to "Paket vom %1\$s",
    )
}
