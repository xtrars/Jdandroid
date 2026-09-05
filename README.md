# JDAndroid

[![Android CI](https://github.com/xtrars/Jdandroid/actions/workflows/android.yml/badge.svg?branch=claude%2Fandroid-jdownloader-app-1zqi1n)](https://github.com/xtrars/Jdandroid/actions/workflows/android.yml)
[![Lizenz: Apache-2.0](https://img.shields.io/badge/Lizenz-Apache--2.0-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84.svg)
![Version 0.1.1](https://img.shields.io/badge/Version-0.2.0-informational.svg)

## English summary

**What it is.** JDAndroid is a download manager for Android modelled on
JDownloader: premium accounts for the one-click hosters ddownload, Rapidgator
and 1fichier, a link grabber with online availability check, Click'n'Load 2
and DLC container import, and automatic extraction of RAR, ZIP and 7z
archives (including multi-part and password-protected ones). The app is
written in Kotlin with Jetpack Compose and Room. The user interface is
available in English and German (it follows the device language; German is
the default).

**Features.** Package-based queue with pause/resume (HTTP range), 1-99
parallel downloads, global speed limit, Wi-Fi-only mode, checksum
verification (MD5/SHA-1 where the hoster provides one), remaining-traffic
overview per account, password list and exclude patterns for extraction,
extraction into a folder named after the package with per-file and
per-package progress, optional NFS share on a NAS as storage target
(NFSv3, finished files and extracted content are uploaded and retried
until the NAS is reachable), Material You theming, Click'n'Load server bound to
127.0.0.1:9666 only, credentials encrypted with an Android KeyStore key.

**Install.** No store release. Download the newest APK from the
[GitHub Releases](https://github.com/xtrars/Jdandroid/releases) page (currently `JDAndroid-0.2.0.apk`, with a
SHA-256 checksum; the five newest APKs are also kept in
[`release/`](release/)), allow installation from unknown sources for your
browser or file manager, and open the file. Requires Android 8.0 (API 26) or
newer. **Upgrading from a 0.0.x build:** 0.1.0 is signed with a new key, so
Android refuses it as an update. Uninstall the old version first (queue and
accounts are lost). From 0.1.0 on, updates install over the previous version.

The rest of this document is in German, the project language.

---

## Was ist JDAndroid?

JDAndroid ist ein Download-Manager für Android nach dem Vorbild von
JDownloader. Die App lädt mit Premium-Konten von den One-Click-Hostern
**ddownload**, **Rapidgator** und **1fichier**, sammelt Links in einem
Linksammler mit Online-Prüfung, nimmt Links per **Click'n'Load 2** und
**DLC-Container** entgegen und entpackt fertige Archive (RAR, ZIP, 7z, auch
mehrteilig und verschlüsselt) automatisch in einen Paketordner.

JDAndroid verwendet keine Quelltexte des JDownloaders. Gemeinsam sind nur das
Click'n'Load-2-Protokoll und das DLC-Dateiformat, dessen Entschlüsselung über
den Webdienst des JDownloader-Projekts läuft. „JDownloader“ ist ein Name der
AppWork GmbH; JDAndroid steht in keiner Verbindung zu AppWork.

**Fertige APK:** [GitHub Releases](https://github.com/xtrars/Jdandroid/releases) (aktuell 0.1.0, signiert, mit
SHA-256-Prüfsumme; installierbar ab Android 8.0). Zusätzlich liegen die fünf
neuesten APKs unter [`release/`](release/). Wer noch eine `0.0.x` installiert
hat, muss sie wegen des Signaturwechsels einmal deinstallieren (siehe
[Installation](#installation)).

## Bildschirmfotos

Noch keine. Welche vier Ansichten unter [`docs/screenshots/`](docs/screenshots/)
erwartet werden, steht dort in der `README.md`; sobald die Bilder vorliegen,
die Tabelle unten einkommentieren.

<!--
| Downloads | Linksammler | Konten | Einstellungen |
|---|---|---|---|
| ![Downloads](docs/screenshots/downloads.png) | ![Linksammler](docs/screenshots/linksammler.png) | ![Konten](docs/screenshots/konten.png) | ![Einstellungen](docs/screenshots/einstellungen.png) |
-->

## Funktionen

### Hoster

| Hoster | Domains | Zugangsdaten | Weg |
|---|---|---|---|
| ddownload | ddownload.com, ddl.to | API-Key **oder** Browser-Login (Benutzername/Passwort) | API v2 oder Session-Cookies aus dem eingebetteten Browser |
| Rapidgator | rapidgator.net, rg.to | Benutzername + Passwort | offizielle API v2 |
| 1fichier | 1fichier.com und die Alias-Domains (z. B. desfichiers.com, megadl.fr, dl4free.com) | API-Key (Kontoeinstellungen → API) | offizielle REST-API |

Die Downloads laufen über die offiziellen APIs bzw. den Weblogin der Hoster
und benötigen ein **Premium-Konto** (bei 1fichier ein Konto mit API-Zugang).
Free-Downloads mit Captcha und Wartezeit werden nicht unterstützt. Neue
Hoster lassen sich über das `Hoster`-Interface ergänzen (siehe
[Beitragen](#beitragen)).

### Linksammler mit Online-Prüfung

- Beliebigen Text einfügen oder aus dem Browser „Teilen“: unterstützte Links
  werden erkannt und landen im Linksammler.
- Dort werden sie **online geprüft** (verfügbar? Dateiname? Größe?):

  | Hoster | Prüfung | Konto nötig |
  |---|---|---|
  | ddownload | API `file/info` mit Key, sonst die öffentliche Dateiseite | nein |
  | Rapidgator | API `file/info` (Name, Größe, MD5) | ja (Session-Token) |
  | 1fichier | öffentliches `check_links.pl` (Name, Größe) | nein |

- Downloads starten erst mit „Starten“ bzw. „Alle starten“ – oder sofort,
  wenn „Neue Links sofort starten“ eingeschaltet ist.
- Zusammen hinzugefügte Links bilden ein **Paket** (automatisch benannt,
  umbenennbar, gemeinsam pausier- und löschbar). Links werden nur im
  Linksammler hinzugefügt, nicht in der Download-Liste.

### Download-Engine

- Vordergrunddienst mit Benachrichtigung (Fortschritt, „Alle pausieren“ /
  „Fortsetzen“), Erinnerung an offene Downloads nach einem Neustart.
- 1–99 gleichzeitige Downloads, globale Geschwindigkeitsbegrenzung (Mbit/s),
  „Nur über WLAN laden“ (setzt automatisch fort, sobald WLAN da ist).
- Pause und Fortsetzen mit HTTP-Range-Resume, Wiederholversuche bei
  vorübergehenden Fehlern (5xx, Sperren), geglättete Geschwindigkeitsanzeige.
- Prüfsummenkontrolle, wenn der Hoster eine liefert (MD5 bei Rapidgator,
  SHA-1 bei 1fichier).
- Suche und Filter in der Download-Liste (Läuft, Wartend, Fertig, Fehler),
  Zustände `QUEUED`, `RUNNING`, `PAUSED`, `EXTRACTING`, `COMPLETED`, `FAILED`.
- Zielordner wählbar über das Storage Access Framework (auch SD-Karte);
  Standard ist `Downloads/JDAndroid/`. Alternativ eine **NFS-Freigabe auf
  einem NAS** (siehe [NAS-Ziel](#nas-ziel-nfs)). Einheiten sind durchgehend
  1024-basiert (KiB, MiB, GiB, TiB).

### Click'n'Load 2

Die App betreibt bei aktiviertem Click'n'Load einen lokalen Server auf
`127.0.0.1:9666`. Ein Browser **auf demselben Gerät** sendet die Links über
den Click'n'Load-Knopf einer Webseite direkt an die App; Paketname,
Entpack-Passwörter und Herkunftsseite werden übernommen, die Entschlüsselung
passiert vollständig offline. Unterstützt sind `/flash/addcrypted2` (AES),
`/flash/addcrypted` (DLC-Inhalt), `/flash/add` (Klartext) sowie der
CORS-Preflight mit den Headern für Private Network Access und Local Network
Access. In den Einstellungen gibt es einen Selbsttest („Verbindung testen“)
und den Status der letzten Anfrage. Hinweise zu Browsern stehen unter
[Click'n'Load im Browser](#clicknload-im-browser).

### DLC-Container

`.dlc`-Dateien lassen sich über „Öffnen mit“, Teilen oder den Ordner-Knopf im
Linksammler importieren. Android kennt die Endung nicht und meldet solche
Dateien als `application/octet-stream`; JDAndroid registriert sich für diesen
MIME-Typ und für die Endung und prüft am Inhalt, ob es wirklich ein DLC ist.
Die Entschlüsselung läuft über den DLC-Webdienst des JDownloader-Projekts
(`service.jdownloader.org`) – der Schlüssel liegt serverseitig, die
Container-Daten verlassen dafür das Gerät. Ist der Dienst nicht erreichbar,
meldet die App das; Click'n'Load funktioniert davon unabhängig.

### Entpacken

- **ZIP** (auch AES-verschlüsselt, zip4j), **7z** (auch mit Passwort,
  commons-compress) und **RAR4 + RAR5** (auch verschlüsselt, natives
  7-Zip-Binding).
- Mehrteilige Archive (`.part1.rar`, `.r00`, `.z01`, `.7z.001`) werden
  entpackt, sobald alle Teile fertig sind; das Entpacken lässt sich auch
  nachträglich über das Aktionsmenü anstoßen.
- Entpackt wird immer in einen Unterordner mit dem Paketnamen. Der
  Entpack-Status mit Prozent und Balken erscheint an allen Teilen des Archivs
  und am Paket.
- **Passwortliste** wie im JDownloader: einzeln oder zeilenweise einfügen,
  wird beim Entpacken der Reihe nach durchprobiert; Passwörter aus
  Click'n'Load werden automatisch ergänzt.
- **Ausschlussmuster** wie im JDownloader (`*.nfo`, `*sample*`, `proof/*`):
  `*` steht für beliebig viele Zeichen, `?` für eines; der Vergleich läuft
  ohne Groß-/Kleinschreibung gegen Dateiname und Pfad im Archiv.
- Dateinamen, in denen ddownload Punkte durch Leerzeichen ersetzt hat
  („name part1 rar“), werden vor dem Entpacken repariert.
- Fertige Dateien werden nach `Downloads/JDAndroid/` bzw. in den gewählten
  Zielordner exportiert (abschaltbar) – oder auf das NAS, wenn das
  NFS-Ziel eingeschaltet ist.

### NAS-Ziel (NFS)

In den Einstellungen lässt sich unter „NFS-Freigabe (NAS)“ eine
NFSv3-Freigabe im eigenen Netz als Speicherziel eintragen (Server,
Export-Pfad, optionaler Unterordner, uid/gid; „Verbindung prüfen“ hängt
den Export ein, listet den Zielordner und zeigt den freien Platz).
Download und Entpacken bleiben lokal; fertige Dateien und entpackte Inhalte
werden danach in `Export-Pfad/Unterordner/<Paketname>/` hochgeladen und
lokal gelöscht. Ist das NAS nicht erreichbar, bleibt die Datei mit dem
Vermerk „Wartet auf NAS“ liegen und wird bei Netzwechsel bzw. im
Minutentakt erneut hochgeladen. Das NFS-Ziel hat Vorrang vor dem
SAF-Zielordner und `Downloads/JDAndroid/`. Am NAS müssen NFSv3, die Ports
111/2049/mountd und Verbindungen von nicht-privilegierten Ports erlaubt
sein; NFSv3 ist unverschlüsselt und nur für das eigene Netz gedacht.
Einrichtung für Synology, QNAP, TrueNAS und Linux, Fehlerbilder und
Grenzen stehen in [`docs/NFS.md`](docs/NFS.md).

### Konten

Konten pro Hoster hinterlegen und prüfen (Premium-Status, „Aktiv bis“).
Die Kontenübersicht zeigt die verbleibende Restmenge mit Balken zum
Kontingent (Rapidgator: `traffic.left/total`; ddownload:
`premium_traffic_left` gegen das Gesamtkontingent, auch Ultimate-Konten;
1fichier: unbegrenzt) und frischt sie jede Minute auf, solange der Tab
sichtbar ist, sowie nach jedem fertigen Download. Vorübergehende Fehler
(HTTP 403 bei 1fichier ist meist eine Flood-Sperre, Tageslimit oder IP-Sperre
bei Rapidgator) schalten ein Konto nie dauerhaft ab.

### Oberfläche

Jetpack Compose mit Material 3 und **Material You**: dynamisches Farbschema
ab Android 12, darunter das Material-Standardschema; Hell/Dunkel nach System
oder fest wählbar. Alle Schaltflächen bleiben auch bei eingeblendeter
Tastatur sichtbar; pro Zeile gibt es ein Drei-Punkte-Aktionsmenü, am Paket
zusätzlich Start/Pause. Fehler erscheinen als eine klare Meldung, ein
Absturzbericht steht in den Einstellungen unter „Letzter Absturz“.

Die Oberfläche gibt es auf **Deutsch und Englisch**; sie folgt der
Gerätesprache (Deutsch ist die Standardsprache). Alle Texte – auch die
Meldungen aus Engine und Hostern – liegen als String-Ressourcen vor, siehe
[`docs/I18N.md`](docs/I18N.md).

## Installation

JDAndroid wird nicht über einen App-Store verteilt. Jede Version erscheint
als [GitHub-Release](https://github.com/xtrars/Jdandroid/releases) mit APK und SHA-256-Prüfsumme; zusätzlich liegen
die fünf neuesten APKs im Repository unter [`release/`](release/):

| Version | `versionCode` | Datei |
|---|---|---|
| **0.2.0** (aktuell) | 43 | [Release v0.2.0](https://github.com/xtrars/Jdandroid/releases/tag/v0.2.0) · [`release/JDAndroid-0.2.0.apk`](release/JDAndroid-0.2.0.apk) |
| 0.1.1 | 42 | [Release v0.1.1](https://github.com/xtrars/Jdandroid/releases/tag/v0.1.1) · [`release/JDAndroid-0.1.1.apk`](release/JDAndroid-0.1.1.apk) |
| 0.1.0 | 41 | [Release v0.1.0](https://github.com/xtrars/Jdandroid/releases/tag/v0.1.0) · [`release/JDAndroid-0.1.0.apk`](release/JDAndroid-0.1.0.apk) |
| 0.0.16 | 40 | [`release/JDAndroid-0.0.16.apk`](release/JDAndroid-0.0.16.apk) |
| 0.0.15 | 39 | [`release/JDAndroid-0.0.15.apk`](release/JDAndroid-0.0.15.apk) |

**Signaturwechsel mit 0.1.0.** Die `0.0.x`-Versionen waren mit einem
Schlüssel signiert, der im Repository lag und deshalb als kompromittiert
gilt. Seit 0.1.0 signiert ein neuer, nicht veröffentlichter Schlüssel
(Fingerabdruck in [SECURITY.md](SECURITY.md)). Android lässt kein Update
mit anderer Signatur zu: Wer 0.0.x installiert hat, muss die App einmal
deinstallieren und 0.1.0 neu installieren; Warteschlange, Konten und
Einstellungen gehen dabei verloren. Ab 0.1.0 installieren sich Updates
wieder über die bestehende Version.

Die Versionsnummern wurden am 04.09.2026 von `1.5.7` auf `0.0.1`
zurückgesetzt; mit der ersten Veröffentlichung gilt seit dem 05.09.2026 das
Schema `0.1.x`. Der interne `versionCode` zählt durchgehend hoch
(1.5.7 = 23, 0.0.16 = 40, 0.1.0 = 41). Einzelheiten stehen im
[CHANGELOG](CHANGELOG.md).

1. APK auf dem Gerät herunterladen: [GitHub Releases](https://github.com/xtrars/Jdandroid/releases) → neueste
   Version → `JDAndroid-<version>.apk` (alternativ `release/` → Datei →
   „Download raw file“).
2. Beim ersten Mal erlaubt Android die Installation aus dieser Quelle nicht:
   in der Abfrage „Unbekannte Apps installieren“ für den Browser bzw.
   Dateimanager zulassen (Einstellungen → Apps → Spezieller App-Zugriff).
3. APK öffnen und installieren. Updates ab 0.1.0 werden über die bestehende
   Installation eingespielt; eine `0.0.x` vorher deinstallieren (siehe
   oben). Die Signatur lässt sich mit `apksigner verify --print-certs`
   gegen den Fingerabdruck in [SECURITY.md](SECURITY.md) prüfen.
4. Beim ersten Start fragt die App die Berechtigung für Benachrichtigungen
   ab (Android 13+), damit der Download-Fortschritt sichtbar ist.

Mindest-Android-Version: 8.0 (API 26), Ziel: Android 16 (API 36).

## Konten einrichten

Konten-Tab → **+** → Hoster wählen:

- **ddownload** – zwei Wege, weil das Login-Formular ein
  Cloudflare-Turnstile-Captcha enthält:
  1. **API-Key** (empfohlen): Schlüssel unter my.ddownload.com → API
     erzeugen und eintragen.
  2. **Im Browser anmelden**: Benutzername und Passwort im eingebetteten
     Browser eingeben und das Captcha lösen; die App übernimmt die
     Session-Cookies und löscht danach die Browser-Cookies.
- **Rapidgator** – Benutzername (E-Mail) und Passwort; die App holt sich
  per API einen Session-Token.
- **1fichier** – API-Key aus den Kontoeinstellungen (Bereich „API“). Ein
  Zugang mit API-Berechtigung ist nötig; `user/info` wird höchstens alle
  fünf Minuten abgefragt, weil 1fichier sonst mit einer Flood-Sperre reagiert.

Alle Zugangsdaten werden AES-GCM-verschlüsselt gespeichert; der Schlüssel
liegt im Android-Keystore. Ist der Keystore nicht nutzbar, wird das Konto
nicht gespeichert (kein Klartext-Fallback), die App meldet es. Konten werden
nicht synchronisiert und wandern wegen `allowBackup="false"` nicht in
Cloud-Backups.

## Click'n'Load im Browser

Click'n'Load funktioniert nur mit einem Browser auf **demselben Gerät**, weil
der Server ausschließlich an `127.0.0.1` gebunden ist und aus dem WLAN nicht
erreichbar ist.

1. Einstellungen → Click'n'Load einschalten; mit „Verbindung testen“ prüfen,
   ob der Server antwortet.
2. Im Browser auf der Webseite den Click'n'Load-Knopf drücken. Der Status
   der letzten Anfrage erscheint in den Einstellungen, der Eingang zusätzlich
   als Benachrichtigung mit Herkunft; die Herkunftsseite steht am Paket.

Browser-Besonderheiten:

- **Chrome** (ab Version 138, Local Network Access): Beim ersten
  Click'n'Load fragt Chrome, ob die Seite auf das lokale Netzwerk zugreifen
  darf – das muss erlaubt werden. Wurde die Anfrage abgelehnt, lässt sie sich
  über das Schloss-Symbol → Berechtigungen → „Lokales Netzwerk“ wieder
  freigeben. Die App beantwortet den CORS-Preflight mit
  `Access-Control-Allow-Private-Network` und `Access-Control-Allow-Local-Network`.
- **Brave**: Brave blockiert Zugriffe auf `localhost` standardmäßig. Beim
  ersten Click'n'Load erscheint eine Abfrage „Localhost-Zugriff“; wird sie
  abgelehnt oder erscheint sie nicht, in den Website-Einstellungen der Seite
  den Localhost-Zugriff erlauben (bzw. den Schild für die Seite senken).
- Erscheint nach dem Klick in den Einstellungen kein Anfragestatus, hat der
  Browser die Verbindung zu `127.0.0.1` blockiert – die App hat die Anfrage
  dann nie gesehen.

## Build aus dem Quelltext

Voraussetzungen: JDK 17, Android SDK mit Platform 36. Toolchain: Gradle
8.14.5 (Wrapper mit SHA-256-Prüfsumme), AGP 8.13.2, Kotlin 2.3.21,
KSP 2.3.11, Compose BOM 2026.06.01, Room 2.8.4.

```bash
git clone https://github.com/xtrars/Jdandroid.git
cd Jdandroid
./gradlew testDebugUnitTest              # Unit-Tests (Linkparser, Entpacker, CnL-Server, DLC, Hoster)
./gradlew lintDebug                      # Android-Lint
./gradlew compileDebugAndroidTestKotlin  # Migrationstest nur kompilieren
./gradlew connectedDebugAndroidTest      # Migrationstest auf Gerät/Emulator
./gradlew assembleRelease                # Release-APK (signiert, wenn ein Keystore konfiguriert ist)
# Ergebnis: app/build/outputs/apk/release/JDAndroid-<version>.apk
#           ohne Keystore: app-release-unsigned.apk
```

Hinweise:

- Unit-Tests und Release-Build **nacheinander** ausführen, wenn eine neue
  Room-Schema-Version entsteht: parallele KSP-Läufe legen sonst eine leere
  `app/schemas/com.jdandroid.data.AppDatabase/N.json` an (dann löschen und neu bauen).
- Abhängigkeiten werden über `gradle/verification-metadata.xml` per
  SHA-256-Prüfsumme verifiziert; nach einem Versionswechsel die Datei mit
  `./gradlew --write-verification-metadata sha256 <tasks>` neu erzeugen.
- R8/Minify ist im Release-Build bewusst **aus**: der Shrinker entfernte die
  Rückrufklassen des RAR-Entpackers, die nur aus nativem 7-Zip-Code per JNI
  aufgerufen werden. Keep-Regeln dafür liegen in `app/proguard-rules.pro`.
- **Signierung:** Der Keystore liegt nicht im Repository. `app/build.gradle.kts`
  liest zuerst die Umgebungsvariablen `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS` und `KEY_PASSWORD`, sonst die Datei `keystore.properties` im
  Projektstamm (gitignored; Schlüssel `storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`). Fehlt beides, entsteht eine unsignierte
  Release-APK (`app-release-unsigned.apk`); Debug-Builds sind davon
  unberührt. Der bis 0.0.16 eingecheckte Keystore ist außer Dienst,
  Hintergrund und Fingerabdruck des neuen Zertifikats in
  [SECURITY.md](SECURITY.md).

Dasselbe läuft als GitHub-Actions-Workflow
([`.github/workflows/android.yml`](.github/workflows/android.yml)) bei jedem
Push auf `main`/`master` bzw. `claude/**`, bei Pull Requests und manuellem
Start: Unit-Tests, Lint (Artefakt `lint-report`), Kompilieren des
Migrationstests und Release-APK (Artefakt `JDAndroid-release`); ein zweiter
Job führt die instrumentierten Tests auf einem Android-Emulator (API 34,
x86_64) aus (Artefakt `androidTest-report`).
Ein weiterer Workflow ([`.github/workflows/release.yml`](.github/workflows/release.yml))
läuft bei einem Git-Tag `v<versionName>` (z. B. `v0.1.0`): Sind die
Repository-Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` und
`KEY_PASSWORD` gesetzt, baut und signiert er die APK in der CI; ohne Secrets
nimmt er die im Repository eingecheckte, lokal signierte Datei
`release/JDAndroid-<version>.apk`. In beiden Fällen prüft er die Signatur
und legt ein [GitHub-Release](https://github.com/xtrars/Jdandroid/releases) mit APK und SHA-256-Prüfsumme an.

## Veröffentlichung

Die App ist noch nicht in einem Store. Die Unterlagen dafür liegen vor:

- [`docs/RELEASE.md`](docs/RELEASE.md) – Checkliste: Versionsschema
  `0.1.x`, Tag `v*`, CI, Signierung aus Repository-Secrets (Keystore ist
  aus dem Repo entfernt, Stand und offene Punkte dort), APK/AAB,
  Play-Hinweise samt Richtlinienrisiko, F-Droid (Anti-Feature `NonFreeDep`).
- [`docs/DATENSCHUTZ.md`](docs/DATENSCHUTZ.md) – Datenschutzerklärung
  (deutsch, mit englischer Fassung): keine Telemetrie, Zugangsdaten nur
  lokal und verschlüsselt, Verbindungen nur zu den eingerichteten Hostern
  und beim DLC-Import zum JDownloader-Dienst.
- [`docs/PLAY_DATA_SAFETY.md`](docs/PLAY_DATA_SAFETY.md) – Antworten für
  das Play-Formular „Datensicherheit“, Begründung der Vordergrunddienst-Typen
  und Berechtigungen.
- [`docs/NFS.md`](docs/NFS.md) – Einrichtung des NAS-Ziels (NFSv3) auf
  Synology, QNAP, TrueNAS und Linux, Fehlerbilder, Sicherheitshinweise.

## Projektstruktur

```
app/src/main/java/com/jdandroid/
├── JdApp.kt            Application: Datenbank, Einstellungen, Benachrichtigungskanal
├── CrashReporter.kt    unbehandelte Ausnahmen → Einstellungen „Letzter Absturz“
├── data/               Room (Db.kt: packages/downloads/accounts, Schema v11, Migrationen),
│                       DataStore-Einstellungen, Secrets (AES-GCM/Keystore),
│                       LinkChecker (Online-Prüfung), AccountRefresher, PackageNaming
├── hoster/             Hoster-Interface, Registry, LinkParser, OkHttp-Client;
│                       DdownloadHoster, RapidgatorHoster, OneFichierHoster
├── container/          Click'n'Load-Server (eigener Mini-HTTP-Server), DLC-Entschlüsselung, CnL-Status
├── engine/             DownloadService (Vordergrund), DownloadEngine (Warteschlange,
│                       Range-Resume, Prüfsummen), Extractor, SpeedLimiter, BootReceiver,
│                       StorageTarget (SAF/MediaStore), nfs/ (NFS-Ziel)
└── ui/                 Compose: Downloads, Linksammler, Konten, Einstellungen,
                        Browser-Login, Dialoge, ViewModels, Theme (Material You)
```

Datenfluss: Text / Teilen / DLC / Click'n'Load → `LinkParser` → Linksammler
(`LinkChecker`) → Paket + Downloads in Room → `DownloadService` /
`DownloadEngine` (Hoster `resolve()` → OkHttp mit Range-Resume) → `Extractor`
(sobald alle Teile fertig sind) → Export auf das NAS (NFS), in den
SAF-Zielordner oder nach `Downloads/JDAndroid/`.
Ausführlicher in [`docs/ARCHITEKTUR.md`](docs/ARCHITEKTUR.md); das
Room-Schema liegt exportiert unter `app/schemas/`, die Prüf-Checkliste für
Reviews unter [`docs/PRUEFUNG.md`](docs/PRUEFUNG.md).

## Beitragen

Fehlerberichte, Hoster-Wünsche und Pull Requests sind willkommen – die
Vorlagen unter `.github/` fragen die nötigen Angaben ab (App- und
Android-Version, Hoster, Konto-Typ, Archivtyp). Die Regeln stehen in
[CONTRIBUTING.md](CONTRIBUTING.md), kurz:

- Projektsprache ist Deutsch (Oberfläche, Kommentare, Commit-Texte,
  Dokumentation).
- Vor einem Pull Request: `testDebugUnitTest` und `lintDebug` grün; bei
  Änderungen an Room-Entities Migration ergänzen, Schema exportieren und
  `MigrationTest` erweitern; bei Versionswechseln von Abhängigkeiten
  `gradle/verification-metadata.xml` nachziehen.
- Neuer Hoster: `Hoster`-Interface in `app/src/main/java/com/jdandroid/hoster/`
  implementieren, in `HosterRegistry` registrieren, Unit-Tests für
  Link-Erkennung und API-Antworten ergänzen.
- Sicherheitslücken bitte nicht als öffentliches Issue melden, sondern wie in
  [SECURITY.md](SECURITY.md) beschrieben.

## Bekannte Grenzen

- Kein Captcha-Handling, daher keine Free-Downloads.
- NAS-Ziel nur über NFSv3 mit uid/gid (kein NFSv4, kein Kerberos, kein
  SMB); Download und Entpacken bleiben lokal, das NAS ist reines
  Ablageziel.
- Der DLC-Webdienst ist ein Fremddienst; seine Verfügbarkeit liegt außerhalb
  der App.
- Android 15 begrenzt `dataSync`-Vordergrunddienste auf 6 Stunden am Tag.
  Läuft die App in dieses Limit, pausiert sie und meldet es per
  Benachrichtigung („Fortsetzen“ startet den Dienst neu); im Leerlauf läuft
  der Dienst als `specialUse`.
- Alle Oberflächentexte sind auf Deutsch fest im Code (keine Lokalisierung).
- Die Hoster-Antworten sind nach offizieller Dokumentation umgesetzt und mit
  Plausibilitätsprüfungen abgesichert (etwa gegen fehlerhaft beschriftete
  Kontingente bei ddownload); Abweichungen wären in der jeweiligen Datei
  unter `app/src/main/java/com/jdandroid/hoster/` schnell korrigiert.

## Lizenz

JDAndroid steht unter der [Apache-Lizenz 2.0](LICENSE).

Eingebundene Bibliotheken und ihre Lizenzen:

| Bibliothek | Lizenz | Zweck |
|---|---|---|
| 7-Zip-JBinding-4Android (Release-16.02-2.03) | LGPL-2.1, enthält 7-Zip/unRAR-Code mit unRAR-Einschränkung; nur dynamisch als Bibliothek eingebunden | RAR4/RAR5 |
| zip4j 2.11.6 | Apache-2.0 | ZIP inkl. AES |
| Apache Commons Compress 1.28.0, XZ for Java 1.12 | Apache-2.0, 0BSD | 7z |
| OkHttp 5.4.0 (mit Okio) | Apache-2.0 | HTTP |
| nfs-client 1.1.0 (com.emc.ecs; mit Netty 3.10.6.Final, commons-lang3 3.12.0) | Apache-2.0 | NFSv3-Ziel (NAS) |
| slf4j-api 1.7.36 | MIT | Logging-Fassade von nfs-client |
| Kotlin, kotlinx-coroutines, AndroidX, Jetpack Compose, Room | Apache-2.0 | Sprache, Plattform, UI, Datenbank |
| JUnit 4, androidx.test | EPL-1.0, Apache-2.0 | nur Tests |

## Haftungsausschluss

JDAndroid ist ein Werkzeug zum Herunterladen von Dateien aus dem eigenen
Hoster-Konto. Verwende es ausschließlich für Inhalte, zu deren Download und
Nutzung du berechtigt bist. Die Entwickler stellen keine Inhalte bereit,
betreiben keine Hoster und übernehmen keine Verantwortung dafür, was mit der
App heruntergeladen wird. Die Nutzung der Hoster-APIs unterliegt den
Bedingungen des jeweiligen Anbieters. Die Software wird ohne jede
Gewährleistung bereitgestellt (siehe [LICENSE](LICENSE)).
