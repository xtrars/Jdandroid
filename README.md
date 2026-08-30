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
| ddownload (ddownload.com, ddl.to) | API-Key (my.ddownload.com → API) | offizielle API |

Die Downloads laufen über die offiziellen APIs der Hoster und benötigen einen
**Premium-Account** bzw. API-Key. Free-Downloads (Captcha + Wartezeit) werden
nicht unterstützt.

## Funktionen

- **Linkgrabber**: beliebigen Text einfügen (oder aus dem Browser "Teilen"),
  unterstützte Links werden automatisch erkannt
- **Download-Engine**: Foreground-Service, mehrere gleichzeitige Downloads
  (einstellbar 1–99), globale Geschwindigkeitsbegrenzung (KB/s),
  Pause/Fortsetzen mit HTTP-Range-Resume, Fortschritt/Geschwindigkeit,
  Benachrichtigung, WakeLock gegen Doze-Stillstand
- **Konten-Verwaltung**: Accounts pro Hoster hinterlegen und prüfen
  (Premium-Status, Ablaufdatum, Rest-Traffic)
- **Automatisches Entpacken**: ZIP (inkl. AES-Verschlüsselung), 7z (inkl.
  Passwort) und RAR; mehrteilige Archive (`.part1.rar`, `.r00`, `.z01`,
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
- **ddownload**: API im XFileSharing-Format, dokumentiert unter
  <https://ddownload.com/api> (`api-v2.ddownload.com/api/account/info`,
  `file/info`, `file/direct_link`, API-Key aus den Kontoeinstellungen)

## Architektur

```
app/src/main/java/com/jdandroid/
├── JdApp.kt                  Application (DB, Settings, Notification-Channel)
├── data/                     Room-Entities/DAOs, DataStore-Settings
├── hoster/                   Plugin-System: Hoster-Interface + Registry
│   ├── RapidgatorHoster.kt
│   ├── OneFichierHoster.kt
│   └── DdownloadHoster.kt
├── engine/                   DownloadService (Foreground), DownloadEngine
│   └── Extractor.kt          ZIP/7z/RAR-Entpacker mit Passwortliste
└── ui/                       Compose-UI (Downloads, Konten, Einstellungen)
```

Neue Hoster lassen sich ergänzen, indem das `Hoster`-Interface implementiert
und die Klasse in `HosterRegistry` registriert wird.

## Bekannte Grenzen

- RAR5-Archive mit Verschlüsselung werden von der verwendeten Bibliothek
  (junrar) nicht unterstützt; RAR4 (auch mehrteilig) funktioniert.
- Kein Captcha-Handling, daher keine Free-Downloads.
- Accounts werden in der lokalen App-Datenbank gespeichert (kein Sync,
  keine zusätzliche Verschlüsselung über die Android-App-Sandbox hinaus).
- Die Hoster-API-Antwortformate sind nach offizieller Dokumentation
  implementiert, aber nicht mit echten Premium-Accounts live getestet –
  Feldabweichungen wären in der jeweiligen Datei unter
  `app/src/main/java/com/jdandroid/hoster/` schnell korrigiert.
- R8/Minify ist im Release-Build deaktiviert (bewusst, zugunsten von
  Stabilität; die APK ist dadurch etwas größer).
