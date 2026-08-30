# JDAndroid

Ein Download-Manager für Android im Stil des JDownloaders – mit Hoster-Plugins,
Premium-Account-Verwaltung, automatischem Entpacken und modernem Material-3-Design.

**Fertige APK:** [`release/JDAndroid-1.0.0.apk`](release/JDAndroid-1.0.0.apk)
(signiert, direkt installierbar ab Android 8.0)

## Unterstützte Hoster

| Hoster | Zugangsdaten | API |
|---|---|---|
| Rapidgator (rapidgator.net, rg.to) | Benutzername + Passwort | offizielle API v2 |
| 1fichier (1fichier.com) | API-Key (Kontoeinstellungen → API) | offizielle REST-API |
| ddownload (ddownload.com, ddl.to) | Benutzername + Passwort | XFileSharing-Weblogin |

Die Downloads laufen über die offiziellen APIs bzw. den Weblogin der Hoster
und benötigen einen **Premium-Account** (bzw. API-Key bei 1fichier).
Free-Downloads (Captcha + Wartezeit) werden nicht unterstützt.

## Funktionen

- **Linkgrabber**: beliebigen Text einfügen (oder aus dem Browser "Teilen"),
  unterstützte Links werden automatisch erkannt
- **Download-Engine**: Foreground-Service, mehrere gleichzeitige Downloads
  (einstellbar 1–99), globale Geschwindigkeitsbegrenzung (KB/s),
  Pause/Fortsetzen mit HTTP-Range-Resume, Fortschritt/Geschwindigkeit,
  Benachrichtigung, WakeLock gegen Doze-Stillstand
- **Konten-Verwaltung**: Accounts pro Hoster hinterlegen und prüfen
  (Premium-Status, Ablaufdatum, Rest-Traffic)
- **Container-Import**: **DLC**-Dateien (über „Öffnen mit"/Teilen) und
  **Click'n'Load 2** (lokaler Server auf Port 9666, Browser auf demselben
  Gerät sendet Links direkt an die App)
- **Automatisches Entpacken**: ZIP (inkl. AES-Verschlüsselung), 7z (inkl.
  Passwort) und **RAR4 + RAR5** (inkl. Verschlüsselung, über natives
  7-Zip-Binding); mehrteilige Archive (`.part1.rar`, `.r00`, `.z01`,
  `.7z.001`) werden entpackt, sobald alle Teile fertig sind
- **Passwortliste** wie im JDownloader: ein Passwort pro Zeile, wird beim
  Entpacken der Reihe nach durchprobiert
- **Export**: fertige Dateien landen in `Downloads/JDAndroid/` (abschaltbar)
- **Design**: Material 3, dynamische Farben (Material You, ab Android 12),
  automatischer Hell-/Dunkel-Modus je nach Systemeinstellung

## Build

Voraussetzungen: JDK 17+, Android SDK (Platform 35).

```bash
./gradlew testDebugUnitTest   # Unit-Tests (Linkparser, Entpacker)
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Mindest-Android-Version: 8.0 (API 26), Ziel: Android 15 (API 35).

**Signierung:** Der Release-Build wird mit `app/keystore/release.jks` signiert
(Passwort `jdandroid`, siehe `app/build.gradle.kts`). Der Keystore liegt bewusst
im Repo, damit Updates dieselbe Signatur tragen – für eine Veröffentlichung im
Play Store müsste er durch einen geheimen Keystore ersetzt werden.

## API-Quellen der Hoster

- **Rapidgator**: API v2, dokumentiert unter <https://rapidgator.net/article/api/index>
  (`/api/v2/user/login`, `user/info`, `file/download`)
- **1fichier**: REST-API, dokumentiert unter <https://1fichier.com/api.html>
  (`/v1/user/info.cgi`, `file/info.cgi`, `download/get_token.cgi`, Bearer-API-Key)
- **ddownload**: XFileSharing-Weblogin (Benutzername/Passwort) mit
  Session-Cookie; Premium-Direktlink über die zweistufige Download-Form
  auf <https://ddownload.com/>. Ein API-Key wird nicht verwendet, da der
  ddownload-Login in der Praxis über Benutzername/Passwort läuft.

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

## Container (DLC & Click'n'Load)

- **DLC**: Eine `.dlc`-Datei über „Öffnen mit" oder Teilen an JDAndroid
  senden – die Links werden importiert. Die Entschlüsselung läuft über den
  JDownloader-DLC-Webdienst (der Schlüssel liegt serverseitig). Ist der
  Dienst nicht erreichbar, meldet die App das klar; dann bleibt CNL als
  zuverlässigerer, rein lokaler Weg.
- **Click'n'Load 2**: In den Einstellungen aktivieren. Die App öffnet einen
  lokalen Server auf `127.0.0.1:9666`. Ein Browser **auf demselben Gerät**
  mit CNL-Button sendet die (AES-verschlüsselten) Links dorthin; die
  Entschlüsselung passiert komplett offline in der App.

## Bekannte Grenzen

- Kein Captcha-Handling, daher keine Free-Downloads.
- Der JDownloader-DLC-Webdienst ist ein Fremddienst; seine Verfügbarkeit
  liegt außerhalb der App. Click'n'Load ist davon unabhängig.
- Accounts werden in der lokalen App-Datenbank gespeichert (kein Sync,
  keine zusätzliche Verschlüsselung über die Android-App-Sandbox hinaus).
- Die Hoster-API-Antwortformate sind nach offizieller Dokumentation
  implementiert, aber nicht mit echten Premium-Accounts live getestet –
  Feldabweichungen wären in der jeweiligen Datei unter
  `app/src/main/java/com/jdandroid/hoster/` schnell korrigiert.
- R8/Minify ist im Release-Build deaktiviert (bewusst, zugunsten von
  Stabilität; die APK ist dadurch etwas größer).
