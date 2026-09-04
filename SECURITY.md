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

Die App befindet sich vor der ersten Veröffentlichung im Versionsschema
`0.0.x`. Korrekturen fließen ausschließlich in die jeweils neueste Version;
es gibt keine gepflegten älteren Zweige.

| Version | Unterstützt |
|---|---|
| neueste `0.0.x` (derzeit 0.0.4, `versionCode` 28) | ja |
| ältere `0.0.x` | nein, bitte aktualisieren |
| `1.x` (Versionsstand vor dem Reset am 04.09.2026) | nein |

Aktuelle APKs liegen unter `release/`; die neueste ist
`release/JDAndroid-0.0.4.apk`. Ältere Dateien dort dienen nur dem
Nachvollziehen und erhalten keine Korrekturen.

## Keystore und APKs im Repository

Das Repository enthält bewusst:

- den Signatur-Keystore `app/keystore/release.jks` samt Passwörtern im Klartext
  in `app/build.gradle.kts` (Store- und Schlüsselpasswort `jdandroid`,
  Alias `jdandroid`),
- die fünf neuesten signierten Release-APKs unter `release/`.

Das ist eine Entscheidung des Repository-Inhabers, kein Versehen, und wird
nicht geändert. Hintergrund: Die App ist für den Eigenbedarf gedacht und
wird nicht über Google Play oder einen anderen Store verteilt. Mit dem
eingecheckten Keystore kann jeder Build, ob lokal, aus der CI oder aus
`release/`, mit derselben Signatur installiert und als Update übereinander
installiert werden, ohne dass Android „App nicht installiert“ meldet oder
eine Deinstallation mit Datenverlust nötig wird.

Was das für Nutzer bedeutet:

- **Die Signatur beweist keine Herkunft.** Jede Person mit Zugriff auf das
  Repository kann eine APK erzeugen, die für Android wie ein legitimes
  Update aussieht. Installiere daher nur APKs, die du selbst aus dem
  Quellcode gebaut hast oder die direkt aus diesem Repository (`release/`,
  GitHub-Releases aus `.github/workflows/release.yml`) beziehungsweise den
  CI-Artefakten (`JDAndroid-release`) stammen. APKs aus anderen Quellen
  mit derselben Signatur sind nicht vertrauenswürdiger als unsignierte.
- Der Keystore ist **kein Geheimnis** und wird nicht als solches behandelt.
  Meldungen, dass er öffentlich ist, sind keine Sicherheitslücke.
- Wer die App unter eigenem Namen weiterverteilen oder in einem Store
  veröffentlichen möchte, muss einen eigenen, geheimen Keystore verwenden
  und die `applicationId` ändern. Ein Wechsel der Signatur bei gleicher
  `applicationId` erfordert auf bestehenden Geräten eine Deinstallation, die
  Warteschlange und Konten löscht.

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
