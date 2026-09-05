# JDAndroid – Projektgedächtnis

Android-Download-Manager nach dem Vorbild von JDownloader (Hoster: ddownload,
Rapidgator, 1fichier; Linksammler, Click'n'Load, DLC, Entpacken). Sprache der
Oberfläche (Standard, dazu Englisch), der Doku und der Commit-Texte ist
Deutsch. Kommentare im Code sind Englisch.

## Arbeitsweise mit dem Nutzer

- Antworten auf Deutsch, knapp, mit dem Ergebnis zuerst.
- Nach jeder fertigen Änderung: Version erhöhen, Release-APK bauen, APK in
  `release/JDAndroid-<version>.apk` ablegen, README-Link anpassen, committen,
  pushen, Tag `v<version>` pushen (löst `release.yml` aus und legt das
  GitHub-Release an) und die APK per Datei senden.
- In `release/` bleiben nur die fünf neuesten APKs; ältere werden mit
  `git rm` entfernt (Reihenfolge nach Version, `versionCode` in
  `app/build.gradle.kts` ist maßgeblich). APKs mit dem alten, öffentlichen
  Schlüssel (bis 0.0.16) sind entfernt und dürfen nie wieder verteilt werden.
- Versionsschema `a.b.c` (Major.Minor.Patch): `0.x` seit der ersten
  Veröffentlichung (0.1.0, versionCode 41); Minor für neue Funktionen, Patch
  für Behebungen. `1.0.0` erst nach Store-Reife. `versionCode` zählt bei
  jedem Build hoch (sonst verweigert Android das Update). Mit `1.0.0` wird
  der `versionCode` auf 1 zurückgesetzt (Wunsch des Nutzers); Nutzer der
  0.x-Versionen müssen dann einmal deinstallieren, weil Android keinen
  kleineren versionCode installiert.
- Branch: `claude/android-jdownloader-app-1zqi1n`. Nie auf einen anderen Branch
  pushen. Push mit `git push -u origin <branch>`.
- Commit-Texte enden mit den Zeilen
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` und
  `Claude-Session: https://claude.ai/code/session_01UV4wJYXghm3NB8sw2efw2Y`.
  Keine Modellnamen in Code, Kommentaren oder Commits darüber hinaus.
- Keystore liegt NICHT im Repo (seit 0.1.0; der alte öffentliche Schlüssel
  ist außer Dienst). Signierung: Umgebungsvariablen `KEYSTORE_FILE`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` oder `keystore.properties`
  im Projektstamm (gitignored). In einer Claude-Sitzung: liegt
  `KEYSTORE_BASE64` (plus Passwörter) in der Umgebung, den Keystore in den
  Scratchpad dekodieren und `keystore.properties` daraus erzeugen; fehlt das,
  entsteht eine unsignierte APK – dann den Nutzer um den Schlüssel bitten,
  nie einen neuen erzeugen. Die APKs in `release/` bleiben (fünf neueste).
- Keine Diagnose-Ausgaben in den Einstellungen anhäufen („vollgespamt“).
  Fehler sollen als eine klare Meldung erscheinen, nicht als Protokoll.

## Kommentare im Code (vom Nutzer festgelegt)

- Englisch, knapp, nur wo sie etwas Nichtoffensichtliches erklären (Warum,
  Hoster-Eigenheiten, Plattform-Fallen). Kein Kommentar, der den Code
  wiederholt.
- Keine erzählenden oder KI-typischen Kommentare: keine Verweise auf Funde,
  Prüfungen, Sitzungen oder Entscheidungen („bewusst“, „Hinweis:“,
  „wichtig:“), keine Aufzählung dessen, was gerade geändert wurde.
- KDoc nur für öffentliche Klassen und nicht triviale Funktionen, ein bis drei
  Sätze.

## Gestaltungsregeln (vom Nutzer festgelegt)

- Nur Material You: dynamisches Farbschema ab Android 12, darunter das
  Material-Standardschema. Keine eigenen Farbpaletten.
- Alle Schaltflächen müssen auf dem Bildschirm sichtbar bleiben (Insets,
  Tastatur). Zeilen nicht mit Symbolen überladen: pro Zeile ein
  Drei-Punkte-Aktionsmenü, am Paket zusätzlich Start/Pause direkt.
- Passwort- und URL-Felder ohne Autokorrektur und ohne automatisches
  Leerzeichen nach Punkt (`KeyboardType.Uri`, `autoCorrectEnabled = false`).
- Links hinzufügen nur im Linksammler, nicht in der Download-Liste.
- Einheiten immer 1024-basiert (KiB, MiB, GiB, TiB).
- Kontenübersicht: nur Restmenge anzeigen (kein „Verbleibend:“-Präfix),
  Balken bleibt, Auffrischen jede Minute, solange der Tab sichtbar ist.
- Entpacken immer in einen Unterordner mit dem Paketnamen, standardmäßig
  flach (Ordner im Archiv ignorieren, gleiche Namen mit „(2)“); Entpack-Status
  an allen Teilen eines Archivs und am Paket, mit Prozent und Balken.
- Keine Diagnose-Abschnitte in den Einstellungen, auch nicht „Letzter
  Absturz“; der Absturzdialog beim Start genügt.

## Technik in Kürze

- Kotlin, Jetpack Compose Material3, Room (Schema exportiert unter
  `app/schemas`, Migrationen in `Db.kt`, `MigrationTest` unter androidTest),
  DataStore, OkHttp, eigener Mini-HTTP-Server auf `ServerSocket`
  (Click'n'Load auf 127.0.0.1:9666, `container/ClickNLoadServer.kt`),
  7-Zip-JBinding (RAR), zip4j, commons-compress (7z).
- R8 ist bewusst aus (`isMinifyEnabled = false`): Shrinking entfernte
  JNI-Callback-Klassen des RAR-Entpackers.
- Build: `./gradlew --offline -q testDebugUnitTest assembleRelease`.
  Beide Tasks nacheinander ausführen, wenn eine neue Schema-Version
  entsteht: parallele KSP-Läufe legen sonst eine leere `N.json` an
  (dann löschen und neu bauen).
- Kotlin-Blockkommentare sind verschachtelbar: `/*` in KDoc (z. B. `proof/*`)
  öffnet einen Kommentar und bricht den Build.

## Hoster-Besonderheiten

- ddownload: Browser-Login (Cloudflare Turnstile), Kontoseite zeigt
  Kontingent falsch beschriftet („197040 GB“ = MB), daher Plausibilitätsgrenze
  ab 16 TiB; API `premium_traffic_left` in MB; Dateiseite ersetzt Punkte in
  Namen durch Leerzeichen („name part1 rar“), `Extractor.repairName()`
  stellt sie her; Download-Formular antwortet mit Weiterleitungsketten, die
  ohne Dateiladen verfolgt werden. WebView-Kennung für die Session nutzen
  (cf_clearance ist daran gebunden). Free: Download-Formular verlangt ein
  Cloudflare-Turnstile-Token (`op=download2` ohne Token = „Wrong captcha“),
  daher Captcha-Ansicht nötig; Sperren stehen als `data-wait-seconds` am
  gesperrten Knopf, Formularfehler in `dk-dl-alert` (`DdownloadFreePage`).
  Ein Countdown über 180 s wird als Wartezeit an die Engine gegeben, das
  Formular (`rand`) bleibt dafür je Dateicode gemerkt – ohne das stünde der
  Countdown beim Folgeversuch wieder auf demselben Wert.
- 1fichier: HTTP 403 ist meist Flood-Sperre, nie pauschal „Konto ungültig“;
  `check_links.pl` liefert drei Felder; `user/info` nur alle 5 Minuten.
  Free (`OneFichierFreePage`): Dateiseite mit `&lg=en`/Cookie `LG=en`
  (sonst französische Meldungen) → Formular `f1` (hidden `adz`, `save`
  entfernen) → Countdown `var count` → POST → Direktlink
  `a-<n>.1fichier.com/<token>` („Click here to download“ oder 302), nur
  wenige Minuten gültig, HTTP→HTTPS (Cleartext gesperrt). Antwort ist
  schon die Datei = Hotlink. Der Hinweis „only one file at a time“ steht
  auf jeder Free-Dateiseite: mit Formular keine Sperre (`classify(…,
  downloadOffered)`), erst in der Formular-Antwort. Direktlink-Kandidaten
  müssen `isFileServerUrl` bestehen (ein Pfadsegment, keine www/static-
  Subdomain). Kein Captcha im Normalablauf; Server-/VPN-IPs
  werden pauschal gesperrt („Accès restreint“). Passwortgeschützte Dateien
  und Captchas laufen über die Captcha-Ansicht auf der Dateiseite.
- Rapidgator: 403 mit Tageslimit/IP-Sperre ist vorübergehend. Free
  (`RapidgatorFreePage`): Dateiseite → `AjaxStartTimer?fid` → `secs`
  (180 s) warten → `AjaxGetDownloadLink?sid` → `/download/captcha`
  (Turnstile, nur Browser). Zu früher Abruf macht den Timer ungültig (dann
  von vorn); die Freischaltung (`sdata__`) ist an die IP gebunden, die
  Captcha-Seite leitet bei fremdem Zustand auf die Dateiseite zurück. Die
  OkHttp-Cookies gehen mit der `CaptchaRequiredException` an die
  Captcha-Ansicht, die sie beim Öffnen in die WebView setzt;
  Direktlink `pr<N>.rapidgator.net//?r=download/index&session_id=…` ohne
  Dateiendung (eigenes `isDirectDownloadUrl`).
- Allgemein: HTML statt Datei nie als Dateiinhalt speichern; vorübergehende
  Fehler (5xx, Sperren) dürfen ein Konto nie dauerhaft abschalten.

## Gesamtprüfung auf Wunsch

Die Gesamtprüfung (Architektur, Fehler, Stand der Technik, Tests) läuft nur
auf Wunsch des Nutzers, nicht mehr regelmäßig (Routine und CI-Zeitplan wurden
entfernt). Ablauf und Checkliste stehen in `docs/PRUEFUNG.md`, das Skript in
`.claude/workflows/pruefung.js` (Workflow-Tool mit `scriptPath`; optional
`args: {reports: [...]}`). Nach dem Workflow: Version/APK wie oben.

## Veröffentlichung

- Verteilung über GitHub Releases (`release.yml` bei Tag `v*`): mit den
  Repository-Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD` baut und signiert die CI, ohne Secrets nimmt sie die
  eingecheckte `release/JDAndroid-<version>.apk`.
- Checkliste in `docs/RELEASE.md`, Datenschutz in `docs/DATENSCHUTZ.md`,
  Play-Formulare in `docs/PLAY_DATA_SAFETY.md`.

## Parallele Workflows

Mehrere Workflows gleichzeitig nur in getrennten Git-Worktrees
(`git worktree add ../wt-<name> -b wt/<name>`), sonst kollidieren die
Gradle-Builds im selben Arbeitsbaum. Die Zweige danach in den Hauptbranch
mergen, einmal gemeinsam bauen und testen, dann freigeben.
