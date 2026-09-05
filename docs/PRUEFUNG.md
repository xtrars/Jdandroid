# Wiederkehrende Gesamtprüfung

Diese Checkliste wird auf Wunsch des Nutzers und bei größeren Änderungen
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

## 1a. Hoster live

Die Free- und Login-Abläufe hängen an der HTML-Struktur der Hoster-Seiten,
und die ändert sich, ohne dass ein Unit-Test es merkt. Der Blickwinkel
`live` in `.claude/workflows/pruefung.js` ruft deshalb mit `curl` (über den
konfigurierten Proxy) die öffentlichen Seiten ab und vergleicht sie mit den
Regexen und Selektoren unter `app/src/main/java/com/jdandroid/hoster`:

| Hoster | Seiten | Verglichene Muster |
|---|---|---|
| ddownload | Dateiseite `https://ddownload.com/<code>`, Login `https://ddownload.com/login.html` | Formular `op=download1/download2`, `rand`, `id`; `data-wait-seconds`; `dk-dl-alert`; Countdown `dk-countdown-num`/`countdown_str`; Turnstile (`cf-turnstile`, `data-sitekey`); Login-Formular |
| Rapidgator | Dateiseite `https://rapidgator.net/file/<id>` | `var fid/secs/captchaUrl`, `AjaxStartTimer`, `AjaxGetDownloadLink`, `/download/captcha`, „Downloading:“ / „File size:“, 404-Seite |
| 1fichier | Dateiseite `https://1fichier.com/?<id>&lg=en` (Cookie `LG=en`) | Formular `f1` (hidden `adz`, `save`), `var count`, Tabelle Filename/Size, Meldungen (File not found, IP Locked, Accès restreint) |

Regeln:

- Beispiel-Links zuerst in den Tests unter `app/src/test` suchen; gibt es
  keinen echten Link, Platzhalter-Codes verwenden. Die Seite „Datei nicht
  gefunden“ zeigt Gerüst, Skripte und Formulare trotzdem und reicht für den
  Strukturvergleich; Countdown und Download-Formular sind dann natürlich nicht
  prüfbar und werden nicht als Abweichung gemeldet.
- Eine Abweichung ist ein Fund, wenn ein Regex des Codes die Live-Seite
  nicht mehr trifft oder die Seite ein neues Muster benutzt (neuer Feldname,
  anderes Attribut, umbenannte Klasse). Der Fund nennt Datei und Zeile des
  Regex und den Ausschnitt der Live-Seite.
- Ist eine Seite nicht erreichbar (Timeout, Cloudflare-Hürde, Proxy-Fehler,
  Sperre der Server-IP – 1fichier sperrt Rechenzentrums-IPs pauschal), gibt
  es dafür keinen Fund, nur einen Hinweis (`hint`) im Protokoll.
- Der Blickwinkel ändert keine Dateien und legt keine Konten oder Downloads
  an; er lädt keine Dateien herunter, nur HTML.

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
