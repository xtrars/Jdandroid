# Sicherheitshinweise

JDAndroid ist ein Android-Download-Manager für Eigenbedarf, der Zugangsdaten
zu Filehostern verwaltet, Links aus Containern (DLC, Click'n'Load)
entgegennimmt und Archive entpackt. Diese Datei beschreibt, wie
Sicherheitslücken gemeldet werden, welche Versionen Korrekturen erhalten und
welche bewussten Entscheidungen im Repository Nutzer kennen sollten.

## Sicherheitslücke melden

Bitte **nicht** als öffentliches Issue, solange die Lücke nicht behoben ist.

1. Bevorzugt über **GitHub Security Advisories**: Im Repository
   [xtrars/Jdandroid](https://github.com/xtrars/Jdandroid) unter
   „Security“ → „Report a vulnerability“ eine private Meldung anlegen.
2. Falls private Meldungen dort nicht aktiviert sind: ein Issue ohne
   technische Details mit dem Titel „Sicherheitsmeldung, bitte um
   vertrauliche Kontaktaufnahme“ eröffnen. Die Projektverantwortlichen
   melden sich dann direkt für den weiteren Austausch.

Hilfreiche Angaben: betroffene App-Version (Einstellungen zeigen
`JDAndroid <version> (<versionCode>)`), Android-Version, betroffener
Bereich (Hoster, Click'n'Load-Server, DLC-Import, Entpacken, Browser-Login,
Speicherung der Zugangsdaten), Schritte zum Nachstellen und, wenn
vorhanden, ein Vorschlag zur Behebung. Bitte keine echten Zugangsdaten oder
Sitzungs-Cookies mitschicken.

Das Projekt wird von einer Einzelperson gepflegt. Eine Antwort ist innerhalb
von zwei Wochen zu erwarten, eine Korrektur, sobald sie möglich ist. Es gibt
kein Bug-Bounty-Programm.

## Unterstützte Versionen

Seit 0.1.0 (erste veröffentlichte Version, `versionCode` 41) gilt das
Versionsschema `0.1.x`; `1.0.0` folgt später. Korrekturen fließen
ausschließlich in die jeweils neueste Version; es gibt keine gepflegten
älteren Zweige.

| Version | Unterstützt |
|---|---|
| neueste `0.1.x` (derzeit 0.1.1, `versionCode` 42) | ja |
| ältere `0.1.x` | nein, bitte aktualisieren |
| `0.0.x` (unveröffentlichte Vorstufen, alter Schlüssel) | nein, siehe [Signatur](#signatur) |
| `1.x` (Versionsstand vor dem Reset am 04.09.2026) | nein |

Aktuelle APKs stehen unter [GitHub Releases](https://github.com/xtrars/Jdandroid/releases) mit SHA-256-Prüfsumme;
die fünf neuesten liegen zusätzlich unter `release/`. Ältere Dateien dienen
nur dem Nachvollziehen und erhalten keine Korrekturen.

## Signatur

Der Signatur-Keystore liegt **nicht** im Repository. Bis 0.0.16 war
`app/keystore/release.jks` samt Passwort eingecheckt; dieser Schlüssel war
damit öffentlich, gilt als kompromittiert und ist außer Dienst. Die
Git-Historie wurde bewusst nicht umgeschrieben, der alte Schlüssel ist dort
weiterhin zu finden; er signiert nur keine Version mehr, und eine damit
signierte APK ist keine Version dieses Projekts.

Seit 0.1.0 sind alle APKs mit einem neuen Schlüssel signiert (PKCS12,
RSA 4096, Alias `jdandroid`, gültig bis 2056), der außerhalb des
Repositorys verwahrt wird. Fingerabdruck des Zertifikats (SHA-256):

```
86:EB:89:53:D0:23:A3:ED:04:6E:CC:1F:08:B3:6B:B6:D5:27:D5:ED:EA:2B:9C:BC:79:5C:6B:45:1B:1A:BD:2E
```

Prüfen mit `apksigner` aus den Android-Build-Tools:

```bash
apksigner verify --print-certs JDAndroid-<version>.apk
```

Weicht der Fingerabdruck ab, stammt die APK nicht aus diesem Projekt, auch
wenn Android sie als Update anbietet.

Was das für Nutzer bedeutet:

- **Signaturwechsel:** Wer eine `0.0.x`-APK installiert hat, muss die App
  vor 0.1.0 einmal deinstallieren; Android verweigert ein Update mit
  anderer Signatur. Warteschlange, Konten und Einstellungen gehen dabei
  verloren. Ab 0.1.0 installieren sich Updates wieder übereinander.
- Signierte APKs gibt es aus zwei Quellen: den
  [GitHub Releases](https://github.com/xtrars/Jdandroid/releases) (mit SHA-256-Prüfsumme) und dem Ordner
  `release/` im Repository. Der Release-Workflow baut und signiert in der
  CI, wenn die Repository-Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS` und `KEY_PASSWORD` gesetzt sind; sonst übernimmt er die lokal
  signierte Datei aus `release/`. Ohne Keystore entsteht lokal nur eine
  unsignierte Release-APK.
- Wer die App unter eigenem Namen weiterverteilen möchte, verwendet einen
  eigenen Keystore und ändert die `applicationId`.

## Wie die App mit sensiblen Daten umgeht

Zum Einordnen von Meldungen der aktuelle Stand (Details im Quellcode, Pfade
unter `app/src/main/`):

- **Zugangsdaten** (Passwörter, API-Keys, Sitzungs-Cookies der Hoster)
  werden mit AES-GCM verschlüsselt in der Room-Datenbank abgelegt; der
  Schlüssel liegt im Android-Keystore (`data/Secrets.kt`). Ist der Keystore
  nicht nutzbar, wird das Konto nicht gespeichert; einen Klartext-Fallback
  gibt es nicht.
- **Backups:** `android:allowBackup="false"`. Konten und Download-Liste
  wandern nicht in Cloud-Backups; ein Backup wäre ohne den Keystore-Schlüssel
  ohnehin unbrauchbar.
- **Netzwerk:** Die Network-Security-Config erlaubt nur HTTPS. Klartext-HTTP
  ist nur zu `127.0.0.1` und `localhost` zugelassen, für den Selbsttest des
  eigenen Click'n'Load-Servers.
- **Click'n'Load** (`container/ClickNLoadServer.kt`) lauscht ausschließlich
  auf Loopback `127.0.0.1:9666` und ist aus dem WLAN nicht erreichbar.
  Nur ein Browser auf demselben Gerät kann Links einliefern; jeder Eingang
  wird mit Herkunft gemeldet. Eingaben werden mit Größenlimits gelesen.
- **DLC-Container** (`container/ContainerDecrypter.kt`) werden über den
  Webdienst `service.jdownloader.org/dlcrypt` entschlüsselt, weil der
  DLC-Schlüssel serverseitig liegt. **Die verschlüsselten Container-Daten
  verlassen dabei das Gerät**; wer das nicht möchte, nutzt Click'n'Load
  (rein lokale Entschlüsselung) oder gibt Links direkt ein.
- **Browser-Login für ddownload** (`ui/WebLoginScreen.kt`): Eine WebView
  mit aktiviertem JavaScript (nötig für Cloudflare Turnstile), ohne
  Dateizugriff und ohne Drittanbieter-Cookies. Nach der Übernahme der
  Sitzung werden die WebView-Cookies gelöscht; die Sitzung lebt danach nur
  verschlüsselt in der Datenbank.
- **Geteilte oder geöffnete Dateien** (DLC, Textlisten) werden mit einem
  Größenlimit von 2 MiB gelesen; ob eine Datei wirklich ein DLC ist, wird am
  Inhalt geprüft, nicht an der Endung.
- **Entpacken:** Archive werden in einen Unterordner mit dem Paketnamen
  unterhalb des vom Nutzer gewählten Zielordners entpackt. Pfadangaben aus
  Archiven werden vom jeweiligen Entpacker (zip4j, commons-compress,
  7-Zip-JBinding) verarbeitet; Meldungen zu Pfadausbrüchen („Zip Slip“) sind
  ausdrücklich willkommen.
- **Release-Build:** R8 ist aus (`isMinifyEnabled = false`, siehe Kommentar
  in `app/build.gradle.kts`). Der Code in der APK ist damit unverschleiert;
  das ist bei einem Open-Source-Projekt beabsichtigt und keine Lücke.
- **Absturzberichte** werden nur lokal gespeichert und in den Einstellungen
  angezeigt (`CrashReporter.kt`); es gibt keine Telemetrie und keine
  automatische Übermittlung.
- **Berechtigungen:** Internet, Netzwerkstatus, Vordergrunddienst
  (`dataSync|specialUse`), Benachrichtigungen, WakeLock, Boot-Empfang.
  Speicherzugriff läuft über das Storage Access Framework und MediaStore,
  ohne pauschale Speicherberechtigung.

## Was außerhalb des Einflussbereichs liegt

- Die Sicherheit der Konten bei den Hostern selbst (ddownload, Rapidgator,
  1fichier) und die Verfügbarkeit des JDownloader-DLC-Dienstes.
- Inhalte heruntergeladener Dateien: JDAndroid prüft Prüfsummen, wenn der
  Hoster sie liefert, aber nicht, ob eine Datei schädlich ist.
- Schwachstellen in Abhängigkeiten werden über die wöchentliche CI und
  Aktualisierungen in `gradle/libs.versions.toml` nachgezogen; Hinweise auf
  betroffene Versionen sind willkommen.
