# Changelog

Alle nennenswerten Änderungen an JDAndroid stehen in dieser Datei. Das Format
folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die neueste
Version steht oben. Jede Version ist mit dem Commit verlinkt, in dem sie
gebaut wurde; Git-Tags oder GitHub-Releases gibt es bisher nicht (der
Workflow `.github/workflows/release.yml` legt sie an, sobald ein Tag
`v<version>` gepusht wird), die fertigen APKs liegen unter
[`release/`](release/) (immer die fünf neuesten).

**Hinweis zur Zählung.** Die Versionen `0.1.0` und `1.0.0` bis `1.5.7` waren
interne Vorstufen aus den ersten Entwicklungstagen. Am 04.09.2026 wurde die
Versionsnummer auf `0.0.1` zurückgesetzt, weil die App noch nicht
veröffentlicht ist („0.0.x, solange unveröffentlicht“). Der interne
`versionCode` in `app/build.gradle.kts` zählt trotzdem weiter
(1.5.7 = 23, 0.0.1 = 25, 0.0.4 = 28; die 24 wurde übersprungen und ist in
keinem Commit enthalten), damit Android jede neue APK als Update über die
vorherige installiert. Wer noch eine 1.5.x installiert hat, kann
also direkt auf 0.0.4 aktualisieren.

Die Kategorien sind: **Hinzugefügt**, **Geändert**, **Behoben**, **Sicherheit**,
**Entfernt**.

## [Unveröffentlicht]

_Noch nichts._

## [0.0.16] – 2026-09-04

### Hinzugefügt

- Free-Modus: Downloads ohne Premium-Konto bei ddownload, Rapidgator und
  1fichier (Einstellung „Free-Modus“, standardmäßig an). Wartezeiten der
  Hoster laufen in der Zeile herunter und zählen nicht als Fehlversuch.
  Verlangt ein Hoster ein Captcha (ddownload und Rapidgator: Cloudflare
  Turnstile), bietet das Zeilenmenü „Captcha lösen“ an; die Seite öffnet im
  eingebetteten Browser, der Direktlink wird abgefangen und der Download
  startet. 1fichier läuft im Normalfall ganz ohne Browser. Ein Konto ohne
  Premium nutzt ebenfalls den Free-Modus.
- Flach entpacken (Einstellung, standardmäßig an): Ordner im Archiv werden
  ignoriert, alle Dateien landen direkt im Paketordner; gleiche Namen
  erhalten „(2)“, „(3)“ …

### Entfernt

- Abschnitt „Letzter Absturz“ in den Einstellungen (der Absturzdialog beim
  Start bleibt).

## [0.0.15] – 2026-09-04

### Behoben

- Rapidgator-Symbol nach dem offiziellen Logo: weißes Pfeil-in-Ablage-Zeichen
  auf orangefarbener Kachel.

## [0.0.14] – 2026-09-04

### Behoben

- Rapidgator-Symbol: das originale Website-Icon (orangefarbene Kachel mit
  „rg“), hochskaliert, statt der abweichenden Nachzeichnung.

## [0.0.13] – 2026-09-04

### Geändert

- Hoster-Symbole auch in den Zeilen der Download-Liste und des Linksammlers.
- Eindeutige Symbole: „Entpacken“ zeigt ein Archiv mit Pfeil, der DLC-Import
  eine Datei mit Pfeil; das Ordnersymbol war für beides im Einsatz.

## [0.0.12] – 2026-09-04

### Behoben

- Rapidgator-Symbol: orangefarbene Kachel mit „rg“-Monogramm als Vektorgrafik
  statt des weißen Pfeils aus der Wortmarke.

## [0.0.11] – 2026-09-04

### Hinzugefügt

- Kontenansicht zeigt die Symbole der Hoster (Rapidgator, 1fichier, ddownload)
  statt Anfangsbuchstaben; Herkunft und Hinweis in `THIRD_PARTY_NOTICES.md`.

## [0.0.10] – 2026-09-04

### Geändert

- Kontenübersicht: kein Zusatz „gerade geprüft“ mehr; ein Alter erscheint erst
  ab zwei Minuten.

## [0.0.9] – 2026-09-04

### Geändert

- Linksammler: Pakete lassen sich wie in der Download-Liste zusammenklappen;
  der Zustand überlebt Drehen und Tabwechsel.

## [0.0.8] – 2026-09-04

Zwei Umbauten unter der Haube, keine neuen Funktionen.

### Geändert

- Archiv-Zugehörigkeit wird in der Datenbank gespeichert (Spalte
  `archiveKey`, indiziert; Datenbankversion 10 mit Migration und Rückfüllung
  aus den Dateinamen). Alle Set-Berechnungen laufen jetzt als gezielte
  Abfragen statt über die ganze Tabelle, immer auf das Paket begrenzt; gleiche
  Archivnamen in zwei Paketen kollidieren nicht mehr. Namenslogik in
  `core/ArchiveNames`, Dateinamen-Logik in `core/FileNames`.
- Live-Fortschritt (Bytestand, Geschwindigkeit, Entpack-Prozent) liegt in
  einem Speicher-Bus (`core/ProgressBus`), nicht mehr in der Datenbank. Die
  Datenbank sieht nur Zustandswechsel und alle 30 Sekunden eine Sicherung des
  Bytestands. Die Liste wird dadurch bei mehreren Downloads deutlich seltener
  neu aufgebaut.
- SQL-Abfragen der Set- und Fortschrittslogik sind in JVM-Tests gegen das
  exportierte Schema geprüft (sqlite-jdbc als Testabhängigkeit). 184 Tests.

## [0.0.7] – 2026-09-04

### Geändert

- Geschwindigkeitslimit in Mbit/s statt KiB/s, Dezimalwerte erlaubt
  (z. B. 2,5); ein früher gespeicherter KiB/s-Wert wird umgerechnet. Das
  Feld zeigt die Entsprechung in Bytes pro Sekunde an.

## [0.0.6] – 2026-09-04

### Geändert

- Oberflächentexte ohne überflüssige Zusätze wie „(wie im JDownloader)“ in
  den Einstellungen und in der Click'n'Load-Meldung.

## [0.0.5] – 2026-09-04

Ergebnis der ersten Gesamtprüfung (drei Prüfberichte, 41 Funde, davon 32
nach Verifizierung durch je zwei Skeptiker bestätigt und behoben) sowie der
Open-Source-Grundausstattung des Repositories.

### Hinzugefügt

- Projektdokumentation: README neu geschrieben (war seit 1.5.4 leer), Lizenz
  (Apache-2.0), Beitragsregeln, Verhaltenskodex, Sicherheitshinweise,
  Lizenzliste der Bibliotheken (`THIRD_PARTY_NOTICES.md`), dieses Changelog,
  Architekturbeschreibung unter `docs/ARCHITEKTUR.md`, Issue- und
  Pull-Request-Vorlagen, Dependabot, `.editorconfig`.
- Release-Workflow `.github/workflows/release.yml`: baut bei einem Tag
  `v<versionName>` die signierte APK, prüft die Signatur und legt sie mit
  SHA-256-Prüfsumme als GitHub-Release ab.
- `CLAUDE.md` als Projektgedächtnis, Prüf-Checkliste `docs/PRUEFUNG.md` und
  der dazugehörige Prüf-Workflow unter `.claude/workflows/pruefung.js`.
- Wöchentlicher CI-Lauf (montags 05:00 UTC) zusätzlich zu Push und Pull Request.

- Neue Tests: Dateinamen (`FileNames`), Antwortklassifikation beim Download
  (`ResponseKind`), Archiv-Set-Logik (`ArchiveSets`), Click'n'Load-Server
  (Port frei wählbar), DLC-/CnL-Entschlüsselung, Fehlerklassifikation der
  Hoster, WebLogin-Hostfilter, Paketgruppierung; Migrationstest 7→8 und
  Kette bis 9. Insgesamt 158 Unit-Tests (vorher 85).

### Geändert

- In `release/` bleiben nur noch die fünf neuesten APKs.
- Einstellungen: Passwortliste und Ausschlussliste sind zusammenklappbar
  (Kopfzeile mit Anzahl der Einträge).
- Aufräumarbeiten aus der Prüfung: gemeinsames Paket `core` für Meldungen und
  Größenformatierung (keine Abhängigkeit engine→ui mehr), Dateinamen-Logik in
  `FileNames`, Archiv-Set-Logik in `ArchiveSets`, Paketaktionen als ein
  Dienstbefehl statt je Eintrag, entprellter Netzwerk-Callback, toter Code
  entfernt.

### Behoben

- Archive wurden doppelt entpackt: Der Dienst konnte sich als untätig beenden,
  während das Entpacken noch lief; die nächste Dienst-Instanz reihte die
  Einträge neu ein. Prozessweites Register laufender Entpackvorgänge, kein
  Archiv wird zweimal gestartet.
- Ein fehlgeschlagener Passwortversuch beim Entpacken löschte den gesamten
  Paketordner samt Dateien anderer Archive. Jeder Versuch läuft jetzt in einem
  eigenen Arbeitsordner, erst bei Erfolg wandern die Dateien in den
  Paketordner.
- „Entpacken“ aus dem Menü zog noch laufende Teile in das Set; fehlte das
  erste Volume, blieben die übrigen Teile dauerhaft auf „Entpacken“; ein
  wartendes Set („Warte auf weitere Archiv-Teile“) wurde nie erneut
  angestoßen; Fehler aus der nativen RAR-Bibliothek ließen das Set hängen.
- Pause oder Netzwechsel während des Abschlusses eines Downloads konnte den
  Eintrag auf „Pausiert“ setzen, obwohl die Datei bereits verschoben war
  (Neudownload und Duplikat).
- Pause kappte die Verbindung erst nach dem Lese-Timeout.
- ddownload: Serverfehler (5xx, 429) beim Auflösen galten als dauerhaft; der
  Titel-Fallback schnitt Dateinamen am ersten Bindestrich ab; ein 200 mit
  Dateiinhalt lieferte die Seitenadresse als Direktlink.
- Rapidgator meldete sich bei jedem vorübergehenden Fehler neu an statt nur
  bei abgelaufenem Token.
- 1fichier: Fehlmeldungen wurden fünf Minuten zwischengespeichert; „Premium
  required“ galt als vorübergehend.
- Browser-Login: Cookies und WebStorage werden auch bei Abbrechen gelöscht,
  der Hostfilter gilt auch für eingebettete Ressourcen.
- „Erneut laden“ im App-Ordner-Modus benannte die Datei in „name (2)“ um;
  Einträge gelöschter Pakete verschwanden aus der Liste; Schalter „Archiv
  nach dem Entpacken löschen“ flackerte beim Öffnen.

### Entfernt

- Abschnitt „Letzter Absturz“ in den Einstellungen (der Absturzdialog beim
  Start bleibt).

## [0.0.4] – 2026-09-04

Commit [`80abe87`](https://github.com/xtrars/Jdandroid/commit/80abe87), versionCode 28,
Datenbank-Schema 9.

### Hinzugefügt

- Entpack-Fortschritt in Prozent mit Balken – an allen Teilen eines
  Archiv-Sets und am Paket („wird entpackt 42 %“). Der Fortschritt kommt aus
  dem `Extractor` (RAR über den Ausgabestrom, 7z über die Leseschleife, ZIP je
  Datei) und wird höchstens einmal pro Sekunde in die neue Spalte
  `downloads.extractProgress` geschrieben.

### Geändert

- Alle Teile eines Archivs wechseln gemeinsam auf `EXTRACTING` und nach dem
  Entpacken gemeinsam zurück auf fertig; bisher stand nur der zuletzt fertig
  gewordene Teil auf „Entpacken“.
- Migration 8→9 mit Migrationstest.

## [0.0.3] – 2026-09-04

Commit [`f84d79a`](https://github.com/xtrars/Jdandroid/commit/f84d79a), versionCode 27.

### Hinzugefügt

- Click'n'Load: Status der letzten Anfrage (Uhrzeit, Methode, Pfad, Ergebnis
  wie „3 Link(s) übernommen“ oder „keine Links im Formular“) als eine Zeile in
  den Einstellungen.
- Click'n'Load-Selbsttest („Verbindung testen“) gegen `127.0.0.1:9666`;
  dafür ist Klartext-HTTP ausschließlich auf Loopback in der
  Netzwerksicherheitskonfiguration freigegeben.
- Hinweis auf die Chrome-Berechtigung „Lokales Netzwerk“.

### Behoben

- Chrome ab Version 138 (Local Network Access) verlangt den Header
  `Access-Control-Allow-Local-Network`; der Server setzt jetzt diesen und den
  älteren `Access-Control-Allow-Private-Network` und spiegelt die im Preflight
  angefragten Header zurück.

## [0.0.2] – 2026-09-04

Commit [`d6fb3c6`](https://github.com/xtrars/Jdandroid/commit/d6fb3c6), versionCode 26.

### Geändert

- Nur noch Material You: dynamisches Farbschema ab Android 12, darunter das
  Material-Standardschema.

### Entfernt

- Die eigene Petrol-Farbpalette aus 1.4.0 und die Option, Material-You-Farben
  abzuschalten.

## [0.0.1] – 2026-09-04

Commit [`fab2b60`](https://github.com/xtrars/Jdandroid/commit/fab2b60), versionCode 25.
**Versionsreset** von 1.5.7 auf 0.0.1 (siehe Hinweis oben).

### Hinzugefügt

- Ausschlussmuster beim Entpacken wie im JDownloader (`*.nfo`, `*sample*`,
  `proof/*`; `*` und `?`, ohne Groß-/Kleinschreibung, gegen Dateiname und
  Archivpfad) für ZIP, 7z und RAR. Die Liste wird in den Einstellungen wie die
  Passwortliste gepflegt.

### Geändert

- Entpackt wird immer in einen Unterordner mit dem (dateisystemtauglich
  gekürzten) Paketnamen; ohne Paket in einen Ordner mit dem Archivnamen.
- ZIP-Archive werden Datei für Datei entpackt statt mit `extractAll`, damit
  Ausschlussmuster greifen.

## [1.5.7] – 2026-09-04

Commit [`173368c`](https://github.com/xtrars/Jdandroid/commit/173368c), versionCode 23.
Letzte Version vor dem Reset; die APK liegt weiterhin unter `release/`.

### Behoben

- Dateinamen, in denen ddownload Punkte durch Leerzeichen ersetzt hat
  („name part1 rar“), werden vor dem Entpacken repariert
  (`Extractor.repairName()`), damit Archive und ihre Teile erkannt werden.

## [1.5.6] – 2026-09-04

Commit [`6791440`](https://github.com/xtrars/Jdandroid/commit/6791440), versionCode 22.

### Hinzugefügt

- Nachträgliches Entpacken über das Aktionsmenü (`ACTION_EXTRACT`): alle Teile
  eines Archiv-Sets werden bei Bedarf aus dem Zielordner (SAF) oder aus
  `Downloads/JDAndroid` (MediaStore) zurückgeholt; am Paket für alle ersten
  Teile.
- Drei-Punkte-Aktionsmenü je Zeile und Paket (Pause/Fortsetzen/Erneut
  versuchen je Status, Entpacken, Erneut laden, Löschen; Paket: Umbenennen,
  Archive entpacken, Löschen). Start/Pause bleibt als Direktaktion am Paket.

### Behoben

- Ein Platzhaltername ohne Endung (etwa aus dem Seitentitel der Linkprüfung)
  verhinderte die Archiverkennung. Der Name aus der Auflösung bzw. aus
  `Content-Disposition` ersetzt jetzt einen Namen ohne Endung, und beim
  Abschluss wird das Format anhand der Magic Bytes (RAR/ZIP/7z) erkannt und
  die Endung ergänzt.

### Entfernt

- Diagnose-Ausgaben in den Einstellungen und in Meldungen; Fehler erscheinen
  als eine klare Meldung.

## [1.5.5] – 2026-09-04

Commit [`2770487`](https://github.com/xtrars/Jdandroid/commit/2770487), versionCode 21.

### Behoben

- Prüfung aller Hoster-Antworten auf zu strenge Annahmen (fehlende Felder,
  Antworten ohne JSON, unerwartete Einheiten); alle Funde behoben.

## [1.5.1] bis [1.5.4] – 2026-09-04

Commits [`525c238`](https://github.com/xtrars/Jdandroid/commit/525c238) bis
[`6a557d3`](https://github.com/xtrars/Jdandroid/commit/6a557d3), versionCodes 17–20.

### Hinzugefügt

- Versionsanzeige in den Einstellungen.
- Neues App-Icon und eigenes Benachrichtigungssymbol.
- ddownload: Ultimate-Konten und „Aktiv bis“ werden erkannt.

### Geändert

- ddownload: Kontingent wieder in MB gerechnet, mit Plausibilitätsgrenze ab
  16 TiB (die Kontoseite beschriftet MB als „GB“).
- ddownload: Weiterleitungsketten des Download-Formulars werden verfolgt, ohne
  die Datei selbst zu laden.

### Behoben

- Die README wurde in 1.5.4 versehentlich geleert (nachgeholt unter
  „Unveröffentlicht“).

## [1.5.0] – 2026-09-04

Commit [`55d3b58`](https://github.com/xtrars/Jdandroid/commit/55d3b58), versionCode 16,
Datenbank-Schema 8.

Großes Audit in vier parallelen Arbeitskopien, zusammengeführt:

### Geändert

- Hoster (Funde H1–H13), Engine und Daten (E1–E14, D1–D10), Oberfläche
  (U1–U14) und Build (B2–B14) überarbeitet.
- Toolchain angehoben: AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.11, Compose BOM
  2026.06.01, Room 2.8.4, compileSdk/targetSdk 36; Gradle-Wrapper mit
  SHA-256-Prüfsumme.
- Dependency-Verification (`gradle/verification-metadata.xml`) aktiv.
- Eindeutiger Index auf `downloads.url` (Migration 7→8 bereinigt Duplikate aus
  parallelen Click'n'Load-Anfragen).

### Hinzugefügt

- Instrumentierter `MigrationTest` gegen die exportierten Schemata.
- App-Start stößt hängen gebliebene Einträge an; Archiv nach dem Entpacken
  standardmäßig löschen.
- 71 Unit-Tests grün, Lint ohne Fehler.

Bewusst nicht umgesetzt: Keystore und APK aus dem Repository entfernen,
Lokalisierung, Umbau auf User-Initiated Data Transfer Jobs.

## [1.4.0] bis [1.4.6] – 2026-09-04

Commits [`1a8bb94`](https://github.com/xtrars/Jdandroid/commit/1a8bb94) bis
[`633295e`](https://github.com/xtrars/Jdandroid/commit/633295e), versionCodes 9–15.

### Hinzugefügt

- Hell/Dunkel per Einstellung (System/Hell/Dunkel), Systemleisten folgen dem
  Modus.
- Gemeinsame Bausteine (`Components.kt`): Statusplaketten, Paket- und
  Zeilenkarten, dünne Fortschrittsbalken, Einstellungsgruppen.
- Zentrale Meldungen im App-Design (`Messages.kt`) mit Symbol, dunkel im
  Dunkelmodus, mit Wiedergabe von Meldungen, die vor Aufbau der Oberfläche
  entstanden.
- DLC-Import (Öffnen mit, Teilen, Ordner-Symbol) läuft im Linksammler.

### Geändert

- Links werden nur noch im Linksammler hinzugefügt, nicht in der
  Download-Liste.
- ddownload-Kontingent aus `premium_traffic_left` (API) statt aus dem
  Free-Feld; beim Browser-Login tolerantes Auslesen der Kontoseite.
- Konten ohne Kontingent (1fichier) werden nicht im Minutentakt abgefragt.

### Behoben

- **Entpacken repariert:** R8/Minify im Release-Build deaktiviert. Der
  Shrinker entfernte `Extractor.RarOpenCallback` und das
  `ISequentialOutStream`-Lambda, die nur per JNI aus nativem 7-Zip-Code
  aufgerufen werden – die Release-Builds 1.1.0 bis 1.4.1 entpackten deshalb
  kein RAR. Keep-Regeln in `proguard-rules.pro` ergänzt.
- 16 Funde aus zwei Reviews, unter anderem: Kontoprüfung setzt „ungültig“ nur
  noch bei endgültigen Fehlern (ein Netzausfall ließ sonst alle Downloads des
  Hosters mit „kein Premium-Konto“ scheitern); „Alle pausieren“ hält zuerst
  die Warteschlange an; „Fortsetzen“ nach dem Android-15-Zeitlimit startet den
  Dienst aus dem Vordergrund; „Alle starten“ war wirkungslos; kein doppelter
  DLC-Import beim Drehen; Hell/Dunkel ab dem ersten Frame.
- Click'n'Load-Serverstart synchronisiert (`onCreate` und `ACTION_START_CNL`
  banden den Port doppelt → „Address already in use“); toter Server wird neu
  gestartet, klare Meldung bei belegtem Port.
- Archivteile, die noch im Linksammler liegen, zählen als ausstehend.

## [1.3.0] bis [1.3.4] – 2026-09-04

Commits [`4a60108`](https://github.com/xtrars/Jdandroid/commit/4a60108) bis
[`5095b33`](https://github.com/xtrars/Jdandroid/commit/5095b33), versionCodes 4–8,
Datenbank-Schema 7.

### Hinzugefügt

- Kontenübersicht mit verbleibender Restmenge und Balken zum Gesamtkontingent
  (neue Spalten `trafficTotal`, `trafficUnlimited`, Migration 6→7).
- Automatische Aktualisierung jede Minute, solange der Konten-Tab sichtbar ist.
- ddownload liefert das Gesamtkontingent für den Balken.

### Geändert

- Nur die Restmenge anzeigen, ohne Vorsatz „Verbleibend:“.

## [1.2.0] – 2026-09-02

Commit [`ec69a88`](https://github.com/xtrars/Jdandroid/commit/ec69a88), versionCode 3,
Datenbank-Schema 6.

### Hinzugefügt

- **Linksammler** wie im JDownloader: neue Links (Einfügen, Teilen, DLC,
  Click'n'Load) landen zuerst dort (Status `COLLECTED`); „Starten“ / „Alle
  starten“ reiht sie ein. Option „Neue Links sofort starten“ für das alte
  Verhalten.
- **Online-Prüfung** (`LinkChecker`, höchstens drei Anfragen parallel):
  Rapidgator `file/info`, 1fichier `check_links.pl` (ohne Konto), ddownload
  API oder Dateiseite. Name und Größe werden vorab eingetragen, Offline-Links
  markiert und auf Wunsch entfernt (neue Spalte `online`, Migration 5→6).
- Zielordner per Storage Access Framework wählbar (auch SD-Karte); Engine
  schreibt Dateien und entpackte Ordner dorthin.
- Passwortliste als Liste mit Hinzufügen/Entfernen und Sammel-Import.
- Suche und Filterchips (Alle, Läuft, Wartend, Fertig, Fehler) in der
  Download-Liste.
- Tests für die Linkprüfung.

## [1.1.0] – 2026-09-02

Commit [`f3c6fd7`](https://github.com/xtrars/Jdandroid/commit/f3c6fd7), versionCode 2,
Datenbank-Schema 5. Erstes Audit; davor am selben Tag
[`0aff202`](https://github.com/xtrars/Jdandroid/commit/0aff202) (Eingabefelder ohne
Autokorrektur) und [`3bfd458`](https://github.com/xtrars/Jdandroid/commit/3bfd458)
(DLC-Entschlüsselung repariert).

### Sicherheit

- Click'n'Load bindet ausschließlich an Loopback (`127.0.0.1`, ersatzweise
  `localhost`), kein Fallback auf alle Schnittstellen.
- Zugangsdaten: kein stiller Klartext-Fallback mehr – schlägt der
  Android-Keystore fehl, wird das Konto nicht gespeichert und der Fehler
  gemeldet.
- `allowBackup="false"`, DLC-Dienst per HTTPS, Klartext-HTTP app-weit gesperrt
  (Network-Security-Config).
- Browser-Login nur auf der Login-Domain und Cloudflare, keine
  Drittanbieter-Cookies, Browser-Cookies nach der Übernahme gelöscht.
- Geteilte und geöffnete Dateien werden mit 2-MiB-Limit gelesen (kein
  OutOfMemory durch versehentlich geteilte Videos).
- Löschen von Konten, Paketen und Downloads mit Rückfrage.

### Hinzugefügt

- Click'n'Load: Paketname, Passwörter und Herkunft (`source`) werden
  übernommen und angezeigt; `/flash/addcrypted` (DLC-Inhalt) unterstützt;
  OPTIONS-Preflight mit `Access-Control-Allow-Private-Network`; „failed“ bei
  leerem Ergebnis; Benachrichtigung bei eingehenden Links.
- Prüfsummen nach dem Download (MD5 bei Rapidgator, SHA-1 bei 1fichier).
- Benachrichtigung mit Fortschritt und den Aktionen „Alle pausieren“ /
  „Fortsetzen“.
- `onTimeout` für das 6-Stunden-Limit von Android 15: sauber pausieren statt
  hartem Abbruch.
- `BootReceiver`: hängende Downloads nach Neustart einreihen, Erinnerung per
  Benachrichtigung.
- „Öffnen mit“ für DLC über `application/octet-stream` und
  `pathAdvancedPattern`.
- Option, Einträge nach erfolgreichem Entpacken aus der Liste zu entfernen.
- Room-Schema exportiert (`app/schemas/`), Migration 4→5.
- GitHub-Actions-Workflow (Tests, Lint, Release-APK); Tests für den
  Click'n'Load-Server.

### Geändert

- Einheiten durchgehend 1024-basiert und korrekt beschriftet (KiB/MiB/GiB;
  vorher „KB/MB“ bei 1024er-Rechnung).
- Geschwindigkeit als 5-Sekunden-Mittel, Datenbank-Schreibvorgänge alle 2 s,
  tabulare Ziffern.
- 7-Zip-JBinding 2.03 (kompatibel mit 16-KB-Speicherseiten).
- R8 ohne Obfuskation (`-dontobfuscate`), damit Absturzberichte lesbar bleiben.

### Behoben

- DLC-Entschlüsselung: der statische IV für die Ableitung des
  Container-Schlüssels war falsch. Ein falscher IV liefert bei einem einzelnen
  CBC-Block trotzdem ASCII-ähnliche Bytes und täuschte einen korrekten
  Schlüssel vor; erst die Datenentschlüsselung scheiterte. Vier
  Regressionstests decken das ab. DLC-Pakete werden mit ihrem Namen aus dem
  Container übernommen (je DLC-Paket ein App-Paket).
- Thread-Sicherheit: `ConcurrentHashMap` für Jobs, synchronisierte Cookies.
- Eingabefelder für Passwörter und URLs ohne Autokorrektur und ohne
  automatisches Leerzeichen nach Punkt.

## [1.0.0] – 2026-08-30

Commit [`1e6a86e`](https://github.com/xtrars/Jdandroid/commit/1e6a86e) und die
Folgecommits desselben Tages bis
[`c418709`](https://github.com/xtrars/Jdandroid/commit/c418709); versionCode 1,
die APK `JDAndroid-1.0.0.apk` wurde mehrfach überschrieben.

### Hinzugefügt

- Erste signierte Release-APK.
- Globale Geschwindigkeitsbegrenzung, 1–99 gleichzeitige Downloads.
- **RAR4 und RAR5** inklusive Verschlüsselung und Multivolume durch Umstellung
  von junrar auf das native 7-Zip-Binding.
- **DLC-Container**-Import über „Öffnen mit“ und Teilen, später mit
  Dateipicker in der App; Entschlüsselung über den JDownloader-DLC-Dienst mit
  klarer Fehlermeldung bei Nichterreichbarkeit.
- **Click'n'Load 2**: lokaler Server auf Port 9666, rein lokale
  Entschlüsselung, über Einstellung aktivierbar.
- Konten-Bereich neu gestaltet (Hoster-Karten, Statusfarben, Vollbild-Dialog).
- Automatische Wiederholversuche mit exponentiellem Backoff (bis 5 Versuche)
  für vorübergehende Fehler; permanente Fehler wie „Datei offline“ werden
  sofort gemeldet.
- Zugangsdaten mit einem Schlüssel aus dem Android-Keystore verschlüsselt
  (AES-GCM); Altbestände werden beim nächsten Zugriff verschlüsselt.
- „Nur über WLAN laden“ mit Netzwerküberwachung: bei mobiler Verbindung
  pausieren, bei WLAN automatisch fortsetzen.
- Speicherplatzprüfung vor dem Download; Absturzbericht als Dialog beim
  Start mit Kopierfunktion.
- Pakete wie im JDownloader: zusammen hinzugefügte Links bilden ein Paket
  (automatisch benannt, umbenennbar, gemeinsam pausier- und löschbar).
- ddownload: Login auf Benutzername/Passwort umgestellt; wegen des
  Cloudflare-Turnstile-Captchas zusätzlich API-Key und Browser-Login im
  eingebetteten WebView.
- Unit-Tests für Linkparser, Entpacker, Click'n'Load-Schlüssel/AES und
  Geschwindigkeitsbegrenzung.

### Behoben

- Geteilte Links kamen nicht an, wenn die App bereits lief (`onNewIntent`).
- Race beim Abschluss mehrteiliger Archive (`completionMutex`); `part01`
  wurde fälschlich als Folgeteil erkannt.
- Dateinamen vom Server werden bereinigt (Pfad-Traversal).
- HTTP 416 beim Fortsetzen einer bereits vollständigen Datei gilt als Erfolg.
- Duplikat-Links werden übersprungen; WakeLock gegen Doze-Stillstand.
- Geschwindigkeitsbegrenzung wartete innerhalb des Locks und serialisierte
  dadurch alle Downloads (mit Regressionstest).
- Gleichnamige Dateien überschrieben sich; neue Downloads erhalten
  „name (2).ext“.
- Jedes Schema-Update löschte die Datenbank (`fallbackToDestructiveMigration`);
  jetzt echte Migrationen 1→2 und 2→3, Konten und Downloadliste überleben
  Updates.
- Schlug `startForeground` fehl, beendete das System den Prozess; der Dienst
  beendet sich jetzt selbst.
- OutOfMemoryError beim Auflösen von ddownload-Links: die Antwort auf das
  Download-Formular (Weiterleitung auf die Datei) wurde komplett in den
  Speicher gelesen. Antwortkörper werden nur noch bei textartigem Content-Type
  und auf 2 MB begrenzt gelesen.
- DLC-Cleartext und Null-Bytes in der Container-Entschlüsselung; native
  7-Zip-Initialisierung; Click'n'Load-Race; Rapidgator `file_id`, 1fichier
  Offer-Parsing; Schaltflächen bleiben über der Tastatur sichtbar.

## [0.1.0] – 2026-08-30

Commit [`98e7010`](https://github.com/xtrars/Jdandroid/commit/98e7010), versionCode 1.

### Hinzugefügt

- Erstfassung: Download-Manager im JDownloader-Stil mit Hoster-Plugins für
  Rapidgator, 1fichier und ddownload über die offiziellen Premium-APIs,
  Kontenverwaltung mit Statusprüfung, Vordergrund-Download-Dienst mit
  Pause/Fortsetzen und parallelen Downloads, automatischem Entpacken
  (ZIP/7z/RAR, mehrteilig) mit Passwortliste, Material-3-Oberfläche mit
  dynamischen Farben und Hell-/Dunkelmodus (Kotlin, Jetpack Compose, Room).

[Unveröffentlicht]: https://github.com/xtrars/Jdandroid/tree/claude/android-jdownloader-app-1zqi1n
[0.0.16]: https://github.com/xtrars/Jdandroid/commit/6db7793
[0.0.15]: https://github.com/xtrars/Jdandroid/commit/f2c5395
[0.0.14]: https://github.com/xtrars/Jdandroid/commit/891a924
[0.0.13]: https://github.com/xtrars/Jdandroid/commit/d958c03
[0.0.12]: https://github.com/xtrars/Jdandroid/commit/6b50d9d
[0.0.11]: https://github.com/xtrars/Jdandroid/commit/e839f09
[0.0.10]: https://github.com/xtrars/Jdandroid/commit/ceef892
[0.0.9]: https://github.com/xtrars/Jdandroid/commit/f8e4331
[0.0.8]: https://github.com/xtrars/Jdandroid/commit/f23de7f
[0.0.7]: https://github.com/xtrars/Jdandroid/commit/5a65ec8
[0.0.6]: https://github.com/xtrars/Jdandroid/commit/7afb954
[0.0.5]: https://github.com/xtrars/Jdandroid/commit/d03642a
[0.0.4]: https://github.com/xtrars/Jdandroid/commit/80abe87
[0.0.3]: https://github.com/xtrars/Jdandroid/commit/f84d79a
[0.0.2]: https://github.com/xtrars/Jdandroid/commit/d6fb3c6
[0.0.1]: https://github.com/xtrars/Jdandroid/commit/fab2b60
[1.5.7]: https://github.com/xtrars/Jdandroid/commit/173368c
[1.5.6]: https://github.com/xtrars/Jdandroid/commit/6791440
[1.5.5]: https://github.com/xtrars/Jdandroid/commit/2770487
[1.5.4]: https://github.com/xtrars/Jdandroid/commit/6a557d3
[1.5.1]: https://github.com/xtrars/Jdandroid/commit/525c238
[1.5.0]: https://github.com/xtrars/Jdandroid/commit/55d3b58
[1.4.6]: https://github.com/xtrars/Jdandroid/commit/633295e
[1.4.0]: https://github.com/xtrars/Jdandroid/commit/1a8bb94
[1.3.4]: https://github.com/xtrars/Jdandroid/commit/5095b33
[1.3.0]: https://github.com/xtrars/Jdandroid/commit/4a60108
[1.2.0]: https://github.com/xtrars/Jdandroid/commit/ec69a88
[1.1.0]: https://github.com/xtrars/Jdandroid/commit/f3c6fd7
[1.0.0]: https://github.com/xtrars/Jdandroid/commit/1e6a86e
[0.1.0]: https://github.com/xtrars/Jdandroid/commit/98e7010
