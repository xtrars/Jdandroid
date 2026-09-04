# Wiederkehrende Gesamtprüfung

Diese Checkliste wird regelmäßig (Routine) und bei größeren Änderungen
abgearbeitet. Funde werden direkt behoben, nicht nur protokolliert.
Ergebnis jeder Prüfung: Kurzbericht an den Nutzer, Version erhöht, APK
gebaut und gesendet (siehe `CLAUDE.md`).

## 1. Fehler

- Engine: Zustände (QUEUED, RUNNING, PAUSED, EXTRACTING, COMPLETED, FAILED)
  lückenlos? Hängen Einträge nach Absturz, Pause oder Abbruch fest?
- Übertragung: Range/206, 416, HTML statt Datei, Content-Length unbekannt,
  Weiterleitungen, Dateinamen (Content-Disposition, Sonderzeichen, Länge).
- Entpacken: Multipart-Sets, Namen ohne Endung, Ausschlussmuster,
  Fortschritt, Passwortliste, Paketordner, Export (SAF, MediaStore).
- Hoster: Einheiten (1024), Ablaufdaten, vorübergehend vs. dauerhaft
  (5xx, 403-Sperren dürfen Konten nie abschalten), Weiterleitungsketten,
  Cookie-Handling, API-Antworten ohne JSON.
- Click'n'Load: Preflight-Header (Private/Local Network Access), Formular-
  Felder, Größenlimits, Statuszeile.
- UI: alle Schaltflächen sichtbar, Aktionsmenüs vollständig, Dialoge
  drehfest (rememberSaveable), keine Hauptthread-IO.

## 2. Architektur und Stand der Technik

- Schichten sauber (ui / data / engine / hoster / container)? Zu große
  Klassen aufteilen, doppelte Hilfsfunktionen zusammenführen.
- Coroutines: Scopes, Dispatcher, NonCancellable nur wo nötig, keine
  `runBlocking` außer dem Theme-Start.
- Room: gezielte Abfragen statt `all()`-Schleifen, Transaktionen,
  Schema-Export und Migrationen (Test unter androidTest).
- Android-Plattform: Vordergrunddienst-Typen (Android 14/15), Insets,
  Predictive Back, SAF/MediaStore, Boot, Netzwerk-Callbacks, WakeLock.
- Sicherheit: Klartext-Konfiguration, exportierte Komponenten, Eingaben
  des CnL-Servers, Keystore, WebView-Einstellungen, keine Geheimnisse in
  Logs.
- Abhängigkeiten: Versionen in `gradle/libs.versions.toml` gegen den
  aktuellen Stand prüfen, `verification-metadata.xml` nachziehen.

## 3. Tests

- Laufen alle Unit-Tests grün (`./gradlew testDebugUnitTest`)?
- Prüft jeder Test Verhalten statt Implementierung? Gibt es Tests, die
  auch bei kaputtem Code grün wären?
- Was ist ungetestet und sinnvoll auf der JVM testbar (reine Funktionen,
  MockWebServer für Hoster, ggf. Robolectric für Room)? Fehlende Tests
  ergänzen, wo sie echten Schutz bringen.
- Instrumentierte Tests (MigrationTest) kompilieren?

## 4. Abschluss

- Version erhöhen, `./gradlew --offline -q assembleRelease` und danach
  `testDebugUnitTest` (nacheinander), APK nach `release/`, README, Commit
  mit den festgelegten Schlusszeilen, Push, APK senden.
- Kurzbericht: was gefunden, was behoben, was offen und warum.
