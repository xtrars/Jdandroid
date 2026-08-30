# JDAndroid

Ein Download-Manager für Android im Stil des JDownloaders – mit Hoster-Plugins,
Premium-Account-Verwaltung, automatischem Entpacken und modernem Material-3-Design.

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
  (einstellbar 1–6), Pause/Fortsetzen mit HTTP-Range-Resume,
  Fortschritt/Geschwindigkeit, Benachrichtigung
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
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Mindest-Android-Version: 8.0 (API 26), Ziel: Android 15 (API 35).

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
- Accounts werden in der lokalen App-Datenbank gespeichert (kein Sync).
