# JDAndroid

Ein Download-Manager für Android im Stil des JDownloaders – mit Hoster-Plugins,
Premium-Account-Verwaltung, automatischem Entpacken und modernem Material-3-Design.

**Fertige APK:** [`release/JDAndroid-1.3.0.apk`](release/JDAndroid-1.3.0.apk)
(signiert, direkt installierbar ab Android 8.0; Update über ältere Versionen möglich)

## Unterstützte Hoster

| Hoster | Zugangsdaten | API |
|---|---|---|
| Rapidgator (rapidgator.net, rg.to) | Benutzername + Passwort | offizielle API v2 |
| 1fichier (1fichier.com) | API-Key (Kontoeinstellungen → API) | offizielle REST-API |
| ddownload (ddownload.com, ddl.to) | API-Key **oder** Browser-Login | offizielle API / Session-Cookies |

Die Downloads laufen über die offiziellen APIs bzw. den Weblogin der Hoster
und benötigen einen **Premium-Account** (bzw. API-Key bei 1fichier).
Free-Downloads (Captcha + Wartezeit) werden nicht unterstützt.

## Funktionen

- **Linksammler** wie im JDownloader: beliebigen Text einfügen (oder aus dem
  Browser "Teilen"), unterstützte Links werden erkannt, landen im Linksammler,
  werden dort **online geprüft** (verfügbar? Name? Größe?) und starten erst auf
  „Starten“ bzw. „Alle starten“ (abschaltbar: „Neue Links sofort starten“)
- **Suche und Filter** in der Download-Liste (Läuft, Wartend, Fertig, Fehler)
- **Zielordner wählbar** über das Storage Access Framework (auch SD-Karte);
  Standard bleibt Downloads/JDAndroid
- **Download-Engine**: Foreground-Service, mehrere gleichzeitige Downloads
  (einstellbar 1–99), globale Geschwindigkeitsbegrenzung (KiB/s),
  Pause/Fortsetzen mit HTTP-Range-Resume, geglättete Geschwindigkeitsanzeige
  (5-Sekunden-Mittel), Prüfsummen-Kontrolle (MD5 bei Rapidgator, SHA-1 bei
  1fichier), Benachrichtigung mit Fortschritt und „Alle pausieren“/„Fortsetzen“,
  WakeLock gegen Doze-Stillstand, Erinnerung an offene Downloads nach Neustart
- **Pakete** wie im JDownloader: zusammen hinzugefügte Links bilden ein Paket
  (automatisch benannt, umbenennbar, gemeinsam pausier-/löschbar); bei
  Click'n'Load wird die Herkunfts-Webseite am Paket angezeigt
- **Konten-Verwaltung**: Accounts pro Hoster hinterlegen und prüfen
  (Premium-Status, Ablaufdatum); **verbleibender Traffic** als eigene Zeile
  mit Balken (Rapidgator: Tageskontingent, ddownload: Restmenge, 1fichier:
  unbegrenzt), automatisch aktualisiert beim Öffnen der Kontenansicht und
  nach jedem fertigen Download
- **Container-Import**: **DLC**-Dateien (über „Öffnen mit"/Teilen oder den
  Ordner-Knopf in der App) und **Click'n'Load 2** (lokaler Server auf Port
  9666, Browser auf demselben Gerät sendet Links direkt an die App; Paketname
  und Entpack-Passwörter der Seite werden übernommen)
- **Automatisches Entpacken**: ZIP (inkl. AES-Verschlüsselung), 7z (inkl.
  Passwort) und **RAR4 + RAR5** (inkl. Verschlüsselung, über natives
  7-Zip-Binding); mehrteilige Archive (`.part1.rar`, `.r00`, `.z01`,
  `.7z.001`) werden entpackt, sobald alle Teile fertig sind
- **Passwortliste** wie im JDownloader: als Liste gepflegt (einzeln oder
  mehrere Zeilen einfügen), wird beim Entpacken der Reihe nach durchprobiert;
  Passwörter aus Click'n'Load werden automatisch ergänzt
- **Export**: fertige Dateien landen in `Downloads/JDAndroid/` (abschaltbar)
- **Design**: Material 3, dynamische Farben (Material You, ab Android 12),
  automatischer Hell-/Dunkel-Modus je nach Systemeinstellung

## Build

Voraussetzungen: JDK 17+, Android SDK (Platform 35).

```bash
./gradlew testDebugUnitTest   # Unit-Tests (Linkparser, Entpacker, CnL-Server, DLC)
./gradlew lintDebug
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Dasselbe läuft bei jedem Push als GitHub-Actions-Workflow
(`.github/workflows/android.yml`); die APK liegt dort als Build-Artefakt.
Das Room-Schema wird nach `app/schemas/` exportiert – bei jeder Änderung an
den Entities eine Migration ergänzen und den Versionssprung dort nachvollziehen.

Mindest-Android-Version: 8.0 (API 26), Ziel: Android 15 (API 35).

**Signierung:** Der Release-Build wird mit `app/keystore/release.jks` signiert
(Passwort `jdandroid`, siehe `app/build.gradle.kts`). Der Keystore liegt bewusst
im Repo, damit Updates dieselbe Signatur tragen – für eine Veröffentlichung im
Play Store müsste er durch einen geheimen Keystore ersetzt werden.

## Online-Prüfung im Linksammler

| Hoster | Prüfung | Konto nötig |
|---|---|---|
| Rapidgator | API `file/info` (Name, Größe, MD5) | ja (Session-Token) |
| 1fichier | öffentliches `check_links.pl` (Name, Größe) | nein |
| ddownload | API `file/info` mit Key, sonst die öffentliche Dateiseite | nein |

## API-Quellen der Hoster

- **Rapidgator**: API v2, dokumentiert unter <https://rapidgator.net/article/api/index>
  (`/api/v2/user/login`, `user/info`, `file/download`)
- **1fichier**: REST-API, dokumentiert unter <https://1fichier.com/api.html>
  (`/v1/user/info.cgi`, `file/info.cgi`, `download/get_token.cgi`, Bearer-API-Key)
- **ddownload**: Zwei Wege, weil das Login-Formular von
  <https://ddownload.com/login.html> ein **Cloudflare-Turnstile-CAPTCHA**
  enthält und headless deshalb nicht anmeldbar ist:
  1. **API-Key** (empfohlen, ohne CAPTCHA) über
     `api-v2.ddownload.com/api/account/info`, `file/info`, `file/direct_link`
     – Schlüssel aus dem Konto unter my.ddownload.com → API.
  2. **Im Browser anmelden**: Anmeldung mit Benutzername/Passwort in einem
     eingebetteten WebView, in dem das CAPTCHA gelöst wird; die
     Session-Cookies werden übernommen und für die Downloads verwendet.

## Architektur

```
app/src/main/java/com/jdandroid/
├── JdApp.kt                  Application (DB, Settings, Notification-Channel)
├── data/                     Room-Entities/DAOs, DataStore-Settings
├── hoster/                   Plugin-System: Hoster-Interface + Registry
│   ├── RapidgatorHoster.kt
│   ├── OneFichierHoster.kt
│   └── DdownloadHoster.kt
├── container/                DLC-Decrypt + Click'n'Load-Server
├── engine/                   DownloadService (Foreground), DownloadEngine
│   ├── Extractor.kt          ZIP/7z/RAR4+RAR5-Entpacker mit Passwortliste
│   └── SpeedLimiter.kt       Globale Geschwindigkeitsbegrenzung
└── ui/                       Compose-UI (Downloads, Konten, Einstellungen)
```

Neue Hoster lassen sich ergänzen, indem das `Hoster`-Interface implementiert
und die Klasse in `HosterRegistry` registriert wird.

## Sicherheit

- Zugangsdaten (Passwörter, API-Keys, Session-Cookies) liegen **AES-GCM-
  verschlüsselt** in der Datenbank; der Schlüssel steckt im Android-Keystore
  und verlässt das Gerät nicht. Ist der Keystore nicht nutzbar, wird das Konto
  **nicht** gespeichert (kein Klartext-Fallback), die App meldet es.
- `allowBackup="false"`: weder Konten noch Download-Liste wandern in
  Cloud-Backups.
- Sämtlicher Verkehr läuft über HTTPS (auch der DLC-Dienst); Klartext-HTTP
  ist per Network-Security-Config für die ganze App gesperrt.
- Der Click'n'Load-Server bindet **ausschließlich an Loopback**
  (`127.0.0.1`, ersatzweise `localhost`) und ist aus dem WLAN nicht
  erreichbar. Jeder Eingang wird mit Herkunft als Benachrichtigung gemeldet
  und am Paket angezeigt.
- Der Browser-Login (ddownload) darf nur die Login-Domain und die
  Cloudflare-Challenge laden, ohne Drittanbieter-Cookies; nach der Übernahme
  werden die Browser-Cookies gelöscht.
- Geteilte/geöffnete Dateien werden mit Größenlimit (2 MiB) gelesen.
- Löschen von Konten, Paketen und Downloads verlangt eine Bestätigung.

## Container (DLC & Click'n'Load)

- **DLC**: Eine `.dlc`-Datei über „Öffnen mit" oder Teilen an JDAndroid
  senden – die Links werden importiert. Die Entschlüsselung läuft über den
  JDownloader-DLC-Webdienst (der Schlüssel liegt serverseitig). Ist der
  Dienst nicht erreichbar, meldet die App das klar; dann bleibt CNL als
  zuverlässigerer, rein lokaler Weg.
- **Click'n'Load 2**: In den Einstellungen aktivieren. Die App öffnet einen
  lokalen Server auf `127.0.0.1:9666`. Ein Browser **auf demselben Gerät**
  mit CNL-Button sendet die (AES-verschlüsselten) Links dorthin; die
  Entschlüsselung passiert komplett offline in der App. Unterstützt werden
  `/flash/addcrypted2` (AES), `/flash/addcrypted` (DLC-Inhalt), `/flash/add`
  (Klartext) sowie der CORS-Preflight mit `Access-Control-Allow-Private-Network`
  für neuere Chrome-Versionen.
- **„Öffnen mit"**: Android kennt die Endung `.dlc` nicht und meldet solche
  Dateien als `application/octet-stream`. JDAndroid registriert sich daher
  für diesen MIME-Typ (content://-URIs aus Downloads, Chrome, Dateimanager)
  und zusätzlich für die Endung (`pathAdvancedPattern`); ob es wirklich ein
  DLC ist, wird am Inhalt geprüft.

## Bekannte Grenzen

- Kein Captcha-Handling, daher keine Free-Downloads.
- Der JDownloader-DLC-Webdienst ist ein Fremddienst; seine Verfügbarkeit
  liegt außerhalb der App. Click'n'Load ist davon unabhängig.
- Accounts werden in der lokalen App-Datenbank gespeichert (kein Sync);
  Zugangsdaten sind mit einem Keystore-Schlüssel verschlüsselt.
- Android 15 begrenzt dataSync-Vordergrunddienste auf 6 Stunden am Tag. Läuft
  die App in dieses Limit, pausiert sie sauber und meldet es per
  Benachrichtigung („Fortsetzen“ startet den Dienst neu).
- Alle Oberflächentexte sind auf Deutsch fest im Code (keine Lokalisierung).
- Die Hoster-API-Antwortformate sind nach offizieller Dokumentation
  implementiert, aber nicht mit echten Premium-Accounts live getestet –
  Feldabweichungen wären in der jeweiligen Datei unter
  `app/src/main/java/com/jdandroid/hoster/` schnell korrigiert.
- R8 läuft im Release-Build mit Shrinking und Optimierung, aber ohne
  Obfuskation (Absturzberichte bleiben lesbar); Keep-Regeln in
  `app/proguard-rules.pro`.
- Die native 7-Zip-Bibliothek (Release-16.02-2.03) ist 16-KB-Page-kompatibel
  (Android 15 auf neueren Geräten).
