# Datenschutzerklärung für JDAndroid

Stand: September 2026. Gilt für die App „JDAndroid“ (Paketname
`com.jdandroid`). Eine englische Fassung steht am Ende dieser Datei
(*English version below*).

## Kurz gesagt

- JDAndroid sammelt keine Daten über dich und sendet nichts an den
  Entwickler. Es gibt keine Telemetrie, keine Absturzberichte an Dritte,
  keine Werbung und keine Analyse-Bibliotheken.
- Alles, was die App speichert, bleibt auf deinem Gerät.
- Die App verbindet sich nur mit den Diensten, die du selbst einrichtest
  oder ausdrücklich benutzt.

## Welche Daten die App speichert – nur lokal

| Daten | Zweck | Ort und Schutz |
|---|---|---|
| Zugangsdaten der Hoster-Konten (Benutzername, Passwort, API-Schlüssel, Session-Cookies) | Anmeldung beim jeweiligen Hoster für Downloads und Kontostand | Datenbank der App, AES-GCM-verschlüsselt; der Schlüssel liegt im Android-Keystore und verlässt das Gerät nie. Ohne nutzbaren Keystore wird das Konto nicht gespeichert (kein Klartext). |
| Links, Paketnamen, Dateinamen, Größen, Prüfsummen, Fortschritt | Download-Liste und Linksammler | Datenbank der App |
| Einstellungen (Zielordner, Geschwindigkeitslimit, Passwortliste fürs Entpacken, Ausschlussmuster) | Bedienung | DataStore der App |
| NFS-Ziel: Server, Export-Pfad, Unterordner, uid/gid | Ablage fertiger Dateien auf deinem NAS | DataStore der App; keine Zugangsdaten, NFSv3 kennt nur uid/gid |
| Heruntergeladene und entpackte Dateien | Ergebnis der Downloads | `Downloads/JDAndroid/`, der von dir gewählte Ordner oder dein NAS (NFS-Ziel) |
| Letzter Absturz (Stapelabzug) | Anzeige beim nächsten Start | Datei im App-Speicher; wird nirgendwohin gesendet |

Die App ist mit `allowBackup="false"` gebaut: Konten, Download-Liste und
Einstellungen wandern nicht in Cloud-Backups von Android. Beim Deinstallieren
löscht Android die App-Daten; heruntergeladene Dateien im öffentlichen
Download-Ordner bleiben erhalten.

## Mit wem die App Verbindungen aufbaut

JDAndroid hat keinen eigenen Server. Netzverbindungen entstehen nur zu:

1. **Den Hostern, für die du ein Konto eingerichtet hast oder deren Links du
   hinzufügst** – ddownload.com (inkl. ddl.to, api-v2.ddownload.com und
   Dateiserver), rapidgator.net (inkl. rg.to), 1fichier.com (inkl.
   api.1fichier.com, Alias-Domains und Dateiserver). Übertragen werden dabei
   deine Zugangsdaten zu diesem Hoster, die Links, die du lädst, und die
   Downloads selbst. Für diese Verbindungen gelten die Datenschutzhinweise
   des jeweiligen Hosters. Beim Browser-Login (ddownload) und bei der
   Captcha-Ansicht läuft ein eingebetteter Browser (WebView) gegen die
   Seiten des Hosters; dabei kann Cloudflare (Turnstile) eingebunden sein.
2. **Dem DLC-Dienst des JDownloader-Projekts** (`service.jdownloader.org`) –
   nur, wenn du einen DLC-Container importierst. Der Inhalt des Containers
   wird zur Entschlüsselung an diesen Dienst gesendet, weil der Schlüssel
   dort liegt. Ohne DLC-Import findet keine Verbindung statt.
3. **Deinem NAS (NFS-Ziel)** – nur, wenn du in den Einstellungen eine
   NFS-Freigabe eingetragen und eingeschaltet hast. Die App verbindet sich
   dann mit dem von dir angegebenen Server in deinem eigenen Netz (NFSv3,
   Ports 111/2049/mountd) und legt dort fertige Downloads und entpackte
   Dateien ab. Übertragen werden nur diese Dateien, ihre Namen und die
   eingetragene uid/gid; Zugangsdaten gibt es bei NFSv3 nicht. Die
   Übertragung ist **unverschlüsselt** und für das eigene, vertrauenswürdige
   Netz gedacht (siehe [`NFS.md`](NFS.md)).
4. **Click'n'Load**: Der lokale Server der App lauscht ausschließlich auf
   `127.0.0.1:9666` (Loopback). Er ist aus dem WLAN oder dem Internet nicht
   erreichbar; nur ein Browser auf demselben Gerät kann ihm Links übergeben.
   Die Entschlüsselung der Click'n'Load-Daten passiert vollständig auf dem
   Gerät.

Alle Verbindungen ins Internet laufen über HTTPS; Klartext-HTTP ist in der
App gesperrt (Ausnahme: der eigene Click'n'Load-Server auf Loopback). Die
Verbindung zum NAS ist NFSv3 ohne Verschlüsselung und findet nur zu dem
Server statt, den du selbst eingetragen hast.

## Berechtigungen

| Berechtigung | Wozu |
|---|---|
| Internet, Netzwerkstatus | Downloads; „Nur über WLAN“ |
| Vordergrunddienst (`dataSync`, `specialUse`) und Benachrichtigungen | Downloads laufen mit sichtbarer Fortschrittsanzeige weiter, auch wenn die App nicht im Vordergrund ist; im Leerlauf (Click'n'Load lauscht, Warten auf WLAN) als `specialUse` |
| WakeLock | Gerät bleibt während eines Downloads wach |
| Boot abgeschlossen | Erinnerung an offene Downloads nach einem Neustart |

Die App fragt keine Standort-, Kontakt-, Kamera- oder Mikrofonberechtigung
ab und liest keine Geräte- oder Werbekennungen.

## Deine Rechte und Kontakt

Da keine Daten beim Entwickler anfallen, gibt es dort auch nichts zu
berichtigen oder zu löschen; alle Daten lassen sich in der App (Konto
löschen, Download-Liste leeren) oder über die Android-Einstellungen
(„Daten löschen“, Deinstallation) entfernen. Fragen zum Datenschutz bitte
über die Issues des Projekts oder wie in `SECURITY.md` beschrieben.

Änderungen an dieser Erklärung werden in dieser Datei und im
[CHANGELOG](../CHANGELOG.md) vermerkt.

---

# Privacy Policy (English)

Last updated: September 2026. Applies to the app “JDAndroid” (package
`com.jdandroid`).

**In short.** JDAndroid collects no data about you and sends nothing to the
developer: no telemetry, no third-party crash reporting, no advertising, no
analytics libraries. Everything the app stores stays on your device, and the
app only connects to services you set up or explicitly use.

**Data stored locally only.** Hoster account credentials (user name,
password, API key, session cookies) are stored in the app database encrypted
with AES-GCM; the key lives in the Android KeyStore and never leaves the
device (if the KeyStore is unusable the account is not saved – no plain-text
fallback). Links, package and file names, sizes, checksums and progress form
the download list; settings (target folder, speed limit, extraction password
list, exclude patterns, and the NFS target: server, export path, sub
folder, uid/gid – no credentials, NFSv3 has none) are kept in the app's
DataStore; downloaded and extracted files go to `Downloads/JDAndroid/`, the
folder you choose or your NAS (NFS target). A
crash trace, if any, is kept in app storage for display at the next start and
is never transmitted. The app is built with `allowBackup="false"`, so
accounts and lists are excluded from Android cloud backups.

**Network connections.** JDAndroid has no server of its own. It connects
only to (1) the hosters you configure or whose links you add – ddownload.com
(incl. ddl.to and its API and file servers), rapidgator.net (incl. rg.to),
1fichier.com (incl. api.1fichier.com, alias domains and file servers) –
transmitting your credentials for that hoster, the links you download and
the downloads themselves; the hoster's own privacy policy applies, and the
embedded browser used for browser login and captchas loads the hoster's
pages, which may include Cloudflare Turnstile; (2) the JDownloader project's
DLC service (`service.jdownloader.org`), only when you import a DLC container
– the container content is sent there for decryption because the key is held
server-side; (3) your NAS, only if you enter and enable an NFS share in the
settings – the app then connects to the server you specified on your own
network (NFSv3, ports 111/2049/mountd) and stores finished downloads and
extracted files there, transmitting only those files, their names and the
configured uid/gid, **unencrypted**, so this is meant for a trusted home
network only (see `NFS.md`); (4) nothing else. The Click'n'Load server
listens exclusively on `127.0.0.1:9666` (loopback), is unreachable from Wi-Fi
or the internet, and decrypts Click'n'Load data entirely on the device. All
internet traffic uses HTTPS; clear-text HTTP is blocked except for the app's
own loopback server. The NAS connection is NFSv3 without encryption and only
goes to the server you entered yourself.

**Permissions.** Internet and network state (downloads, Wi-Fi-only mode);
foreground service (`dataSync`, `specialUse`) and notifications (downloads
continue with a visible progress notification; idle listening and waiting for
Wi-Fi run as `specialUse`); wake lock (device stays awake during a download);
boot completed (reminder of pending downloads after a restart). No location,
contacts, camera, microphone, device or advertising identifiers.

**Your rights and contact.** Since no data reaches the developer, there is
nothing to correct or delete on our side; all data can be removed in the app
(delete account, clear list) or via Android settings (clear data, uninstall).
Privacy questions: project issues or the route described in `SECURITY.md`.
