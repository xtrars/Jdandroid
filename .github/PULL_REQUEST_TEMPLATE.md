## Was ändert sich?

<!-- Kurz und auf Deutsch. Bei Hoster-Änderungen den Hoster nennen, bei
     Entpack-Änderungen den Archivtyp (ZIP, 7z, RAR4/RAR5, Multipart). -->

## Warum?

<!-- Anlass: Issue-Nummer, beobachteter Fehler, Prüfungsfund aus docs/PRUEFUNG.md. -->

## Prüfung

- [ ] `./gradlew testDebugUnitTest` läuft grün (85+ Unit-Tests unter `app/src/test`)
- [ ] `./gradlew lintDebug` ohne neue Warnungen
- [ ] `./gradlew assembleRelease` baut; bei Entpack-Änderungen auf einem Gerät mit
      Release-APK getestet (R8 ist aus, JNI-Callbacks des RAR-Entpackers)
- [ ] Bei Änderungen an Entities in `Db.kt`: Schema-Version erhöht, Migration ergänzt,
      neues Schema unter `app/schemas/` exportiert, `MigrationTest` (androidTest) erweitert
- [ ] Bei neuen oder geänderten Abhängigkeiten: `gradle/verification-metadata.xml`
      nachgezogen (`./gradlew --write-verification-metadata sha256`)
- [ ] Bei Hoster-Änderungen: vorübergehende Fehler (5xx, 403-Sperren) schalten kein
      Konto dauerhaft ab; HTML wird nie als Dateiinhalt gespeichert; Einheiten 1024-basiert
- [ ] Bei Oberflächen-Änderungen: nur Material You, alle Schaltflächen sichtbar
      (Insets, Tastatur), Passwort-/URL-Felder ohne Autokorrektur
- [ ] `versionName` und `versionCode` in `app/build.gradle.kts` erhöht, falls ein
      Release folgt (versionCode zählt immer weiter)
- [ ] Texte in Oberfläche, Kommentaren und Commit-Nachricht auf Deutsch

## Hinweise für den Test

<!-- Welche Links, Konten-Typen (API-Key / Browser-Login) oder Archive braucht man,
     um die Änderung nachzuvollziehen? Keine Zugangsdaten eintragen. -->
