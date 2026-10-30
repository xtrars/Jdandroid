# JDAndroid – Projektgedächtnis

Android-Download-Manager nach dem Vorbild von JDownloader (Hoster: ddownload,
Rapidgator, 1fichier; Linksammler, Click'n'Load, DLC, Entpacken). Sprache der
Oberfläche (Standard, dazu Englisch), der Doku und der Commit-Texte ist
Deutsch. Kommentare im Code sind Englisch.

## Arbeitsweise mit dem Nutzer

- Antworten auf Deutsch, knapp, mit dem Ergebnis zuerst.
- Nach jeder fertigen Änderung: Version erhöhen, Release-APK bauen, APK in
  `release/JDAndroid-<version>.apk` ablegen, README-Link anpassen, committen,
  pushen, `release.yml` mit dem Tag `v<version>` starten (legt Tag und
  GitHub-Release an; ein Tag-Push über den Git-Proxy ist gesperrt, siehe
  „Release aus der Cloud-Sitzung“) und die APK per Datei senden.
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
  pushen. Push mit `git push -u origin <branch>`. Gibt eine Sitzung einen
  eigenen Arbeitsbranch vor (z. B. `ccr-…`), gilt trotzdem dieser Branch
  (Entscheidung des Nutzers vom 28.10.2026).
- Commit-Texte enden mit den Zeilen der führenden Sitzung (seit 28.10.2026)
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` und
  `Claude-Session: https://claude.ai/code/session_016ZLCisa2oNkpKs5W9L6TVd`.
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
  Material-Standardschema. Einzige Ausnahme ist der Gamermodus (Schaltfläche „Space“)
  (`GamerColorScheme` in `ui/Theme.kt`, Neon auf Weltraumdunkel, plus
  Sternenhimmel/Neon-Rahmen/HUD-Schrift in `ui/GamerStyle.kt`, geschaltet
  über `LocalGamer`); sonst keine eigenen Farbpaletten.
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
- R8 an (seit 0.9.7), `-dontobfuscate`, Ressourcen geschrumpft; alle Texte
  und Pluralformen hält `res/raw/keep.xml` (`ResourceTexts` sucht sie per
  `getIdentifier`, das sieht der Shrinker nicht). Keep-Regeln in
  `proguard-rules.pro` für 7-Zip-JBinding samt Rückrufen (JNI) sowie Netty 3
  und NFS-Client (Reflection); ohne sie entpackte der Release kein RAR.
  Instrumentierte Tests laufen gegen `minified` (R8 wie Release,
  Debug-Schlüssel): `./gradlew -Pjd.testMinified connectedMinifiedAndroidTest`;
  Test-Aufrufe halten `proguard-minified-tests.pro` (App) und
  `proguard-androidtest.pro` (Test-APK). Mapping je Release als
  `JDAndroid-<v>-mapping.txt.gz` am GitHub-Release (Zeilennummern).
- Build: `./gradlew --offline -q testDebugUnitTest assembleRelease`; Lint
  (`lintDebug`) einzeln und ohne `--offline` starten (im selben Aufruf
  scheitert der Konfigurations-Cache, offline fehlen Lint-Artefakte).
  Abhängigkeits-Updates: danach
  `./gradlew --no-configuration-cache --write-verification-metadata sha256
  testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleRelease`.
  Beide Tasks nacheinander ausführen, wenn eine neue Schema-Version
  entsteht: parallele KSP-Läufe legen sonst eine leere `N.json` an
  (dann löschen und neu bauen).
- Kotlin-Blockkommentare sind verschachtelbar: `/*` in KDoc (z. B. `proof/*`)
  öffnet einen Kommentar und bricht den Build.

## Hoster-Besonderheiten

- ddownload: nur Browser-Login (Cloudflare Turnstile, keine API), Kontoseite
  zeigt Kontingent falsch beschriftet („197040 GB“ = MB), daher
  Plausibilitätsgrenze ab 16 TiB; Dateiseite ersetzt Punkte in Namen durch
  Leerzeichen („name part1 rar“), `Extractor.repairName()`
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

## Stand für die nächste Sitzung (30.10.2026)

- Zuletzt veröffentlicht: 0.9.11 (versionCode 76), Release v0.9.11 mit
  CI-signierter APK; `release/` enthält 0.9.7 bis 0.9.11. 0.9.11:
  SOLID-Umbau 3 erledigt: Exportziele als Klassen hinter `ExportTarget`
  (`AppFolderTarget`, `NasTarget` in `ExportTarget.kt`, `SafTarget`,
  `MediaStoreTarget`), `StorageTarget` legt nur die Reihenfolge fest;
  `ExportTargetTest` (JVM, `FakeNfsShare`), `MediaStoreTargetTest`
  (Emulator; SAF ohne Test, braucht einen Dokumentanbieter). 0.9.10:
  SOLID-Umbau 2 erledigt (DownloadEngine über `engine/EnginePorts.kt` und
  `QueueDao` injiziert, `DownloadEngine.create` verdrahtet,
  `DownloadEngineTest` mit Fakes und MockWebServer, `Clock.wallMillis`).
  0.9.9: SOLID-Umbau 1 (`AccountInfo.premium` Pflichtfeld, `Account.premium`,
  Schema 12, `hasPremium` ohne Textvergleich; JVM-Migrationstests teilen
  `MigrationSql`). Offen: Umbau 4 (siehe unten). 0.9.8: Fehler aus
  der SOLID-Prüfung behoben (Export-Teilfehler behält Einträge,
  `StorageTarget.exportedAt` nach Anzeigepfad, Rapidgator-Login unter Sperre,
  HTTPS zentral), ddownload wartet bei Sperre 15, bei Wartung 20 Minuten
  (Wunsch des Nutzers). Als Nächstes vereinbart, mit Tests: SOLID-Umbau 4,
  gemeinsamer Free-Ablauf der Hoster.
  0.9.7: R8 an
  (APK 22,2 → 12,5 MB, siehe „Technik in Kürze“). 0.9.6: Effizienz
  (Download-Schleife `readChunk` 128 KiB, Benachrichtigung seriell über einen
  Channel und bei aus Bildschirm alle 15 s, Wake-Lock ohne Referenzzählung,
  `LocalCalmMotion` für Neon-Effekte, RAR ohne Passwort sofort erkannt,
  Regex vorübersetzt), `RarExtractionTest` im Emulator mit Archiven aus
  `tools/mkrar.py` (7-Zip prüft sie: `apt-get install 7zip`, `7z t`).
  Bewusst offen: direkter Export beim
  Entpacken (doppeltes Kopieren in MediaStore) und Fortschritt der
  Download-Liste ohne Neuberechnung aller Gruppen alle 500 ms. 0.9.5: Build ohne
  Compiler-Warnungen (Stand nach Kotlin 2.4); neue Warnungen gleich
  beheben. 0.9.4: AGP 9.5.0
  mit eingebautem Kotlin (kein `kotlin-android` im App-Modul; im Root-Build
  mit `apply false`, damit die KGP-Version 2.4.10 gilt), Gradle 9.8.2,
  compileSdk 37 (targetSdk 36), APK-Name über `androidComponents.onVariants`;
  damit sind die Dependabot-PRs #6, #12–#15 inhaltlich erledigt. Neue
  androidx-Versionen verlangen AGP 9.1+ und compileSdk 37. 0.9.3:
  Sternenflug steht bei „Animationen entfernen“ still, Energiesparmodus etwa
  30 Bilder/s (`FramePacing`). Seit 0.9.1 läuft
  der Sternenflug ohne Schleife: Zeit = Uhrzeit in Sekunden als `Double`
  (`rememberFlightTime`: beim Start eines Laufs gelesen, im Bildtakt
  fortgezählt, nur in der Zeichenphase gelesen), jede Runde eines Sterns
  bekommt aus ihrer Nummer eine neue Position. Was sich
  seit 0.4.1 geändert hat, steht im CHANGELOG: Space-Schema mit Sternenflug
  und Lage-Parallaxe, ruhiger Fortschritt (ProgressBus 500 ms,
  SessionProgress in der Benachrichtigung), NAS-Verschieben als eigener
  Balken, Löschsperre beim Entpacken und Verschieben (`DeleteGuard`), „Erneut
  laden“ (`redownload`), aufklappbare Namen, NAS-Assistent in einem Dialog,
  ddownload nur noch über Browser-Login (`AccountType.BROWSER_LOGIN`).
- Offen beim Nutzer: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD` für die Sitzungsumgebung; sie fehlen seit dem
  Container-Neustart am 27.10.2026 (am 28.10.2026 ebenfalls nicht da). Die
  Repository-Secrets der CI sind gesetzt, die CI signiert. Ohne lokalen Keystore den Weg unten nehmen, nie
  einen neuen Schlüssel erzeugen.
- Nur geschätzt, nicht begonnen: Linux-/Flatpak-Fassung (zwei bis drei
  Wochen; Kern und Hoster in ein gemeinsames Gradle-Modul, Compose
  Multiplatform für den Desktop, Browser-Login und Captcha als größte Hürde).
- In `release/` liegen seit 0.9.4 nur CI-Dateien (ab 0.9.0 byteweise gleich
  den Release-Assets); ältere lokale Builds trugen dieselbe Signatur.
- Die Git-Historie bis 0.8.2 ist auf den 08.09.2026 datiert, die Sitzung
  danach lief mit Datum 27.10.2026; CHANGELOG-Daten folgen dem Sitzungsdatum.

## Release aus der Cloud-Sitzung

- `release.yml` per GitHub-MCP `actions_run_trigger` starten (`run_workflow`,
  `workflow_id` `release.yml`, `ref` = Branch, `inputs {"tag": "v<version>"}`);
  der Lauf legt den fehlenden Tag auf dem Branch-Stand an. Nur einmal
  starten, doppelte Läufe für denselben Tag abbrechen. Der Lauf braucht etwa
  sechs Minuten.
- Ohne lokalen Keystore: Version, CHANGELOG, README committen und pushen →
  Workflow starten → im Hintergrund auf das Asset warten (`curl -s -o
  /dev/null -w '%{http_code}'` auf
  `https://github.com/xtrars/Jdandroid/releases/download/v<v>/JDAndroid-<v>.apk`
  bis 302) → mit `curl -sL` laden (HEAD liefert am Blob-Server 401,
  `gh release download` scheitert am gesperrten GraphQL, `gh api` als REST
  geht) → `apksigner verify --print-certs` (Zertifikat SHA-256
  `86eb8953d023a3ed046ecc1f08b36bb6d527d5edea2b9cbc795c6b451b1abd2e`) und
  `aapt dump badging` (versionCode) prüfen → nach `release/` kopieren,
  älteste APK mit `git rm` entfernen, committen, pushen, APK per Datei
  senden. Erinnerung per `send_later` (10–12 Minuten) als Rückfallebene
  anlegen und nach Erfolg löschen.
- Frischer Container: Android-SDK nach `/opt/android-sdk` (cmdline-tools,
  platform-tools, `platforms;android-37.0` (AGP lädt sie sonst selbst nach),
  build-tools 34.0.0/35.0.0/36.0.0),
  `sdk.dir` in `local.properties` (gitignored, auch in jedem Worktree);
  `apksigner` und `aapt` liegen unter `/opt/android-sdk/build-tools/34.0.0/`.
  Maven Central drosselt (HTTP 429). Abhilfe ohne Repo-Änderung: ein
  Init-Skript `~/.gradle/init.d/central-mirror.gradle`, das in
  `settingsEvaluated` die URL `https://repo.maven.apache.org/maven2` der
  `pluginManagement`- und `dependencyResolutionManagement`-Repositories
  durch Googles Spiegel
  `https://maven-central.storage-download.googleapis.com/maven2/` ersetzt;
  damit lief der erste Build am 28.10.2026 ohne Wiederholung durch.
  `--offline` erst, wenn der Cache alles hat.
- `cd` gilt nur innerhalb eines Bash-Aufrufs. Merges aus Worktrees immer als
  `git -C /home/user/Jdandroid merge --no-edit wt/<name>`, sonst landet der
  Merge im falschen Zweig; Worktrees und Zweige danach entfernen.

## Parallele Workflows

Mehrere Workflows gleichzeitig nur in getrennten Git-Worktrees
(`git worktree add ../wt-<name> -b wt/<name>`), sonst kollidieren die
Gradle-Builds im selben Arbeitsbaum. Die Zweige danach in den Hauptbranch
mergen, einmal gemeinsam bauen und testen, dann freigeben.
