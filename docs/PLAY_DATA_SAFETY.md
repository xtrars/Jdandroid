# Google Play: Datensicherheit, Vordergrunddienst, Berechtigungen

Vorbereitete Antworten für die Play Console. Grundlage ist der Stand der App
in diesem Repository (siehe [`DATENSCHUTZ.md`](DATENSCHUTZ.md)); vor dem
Ausfüllen gegen den aktuellen Code prüfen (Manifest, `Secrets.kt`,
`network_security_config.xml`, `DlcDecrypter`).

## 1. Formular „Datensicherheit“

**Erhebt oder teilt die App Nutzerdaten?** Ja – „erheben“ im Sinne von Play
bedeutet jede Übertragung vom Gerät, auch wenn sie nicht beim Entwickler
landet. Die App überträgt Zugangsdaten an den jeweiligen Hoster,
DLC-Inhalte an den JDownloader-Dienst und – nur wenn der Nutzer ein
NFS-Ziel einträgt – fertige Dateien an dessen eigenes NAS im lokalen Netz.

| Frage | Antwort |
|---|---|
| Werden alle Nutzerdaten bei der Übertragung verschlüsselt? | Internet: Ja (HTTPS erzwungen, Klartext gesperrt; Loopback-Ausnahme betrifft nur das Gerät selbst). Das optionale NFS-Ziel überträgt heruntergeladene Dateien unverschlüsselt (NFSv3) an das vom Nutzer selbst eingetragene NAS im eigenen Netz; keine Zugangsdaten, keine personenbezogenen Daten. Je nach Formularversion „Nein“ mit dieser Begründung wählen oder die NFS-Übertragung als Übertragung im lokalen Netz zum Gerät des Nutzers erläutern. |
| Können Nutzer die Löschung ihrer Daten verlangen? | Nicht zutreffend: der Entwickler speichert keine Daten. Im Formular „Ja“ wählen und auf das Löschen in der App (Konto löschen) und beim Hoster verweisen, oder „Nein“ mit Begründung, dass keine Daten beim Entwickler liegen – die zulässige Antwort hängt von der aktuellen Formularversion ab. |
| Unabhängige Sicherheitsprüfung | Nein |
| Werden Daten zwischen Android-Geräten übertragen (Backup)? | Nein (`allowBackup="false"`) |

**Datentypen** (nur die zutreffenden):

| Kategorie | Datentyp | Erhoben | Geteilt | Zweck | Optional | Verarbeitung |
|---|---|---|---|---|---|---|
| Persönliche Daten | Nutzer-IDs (Benutzername/E-Mail des Hoster-Kontos) | Ja | Nein (geht nur an den Hoster, dessen Dienst der Nutzer selbst gewählt hat – Play zählt das als „Übertragung an Dienstanbieter“, nicht als Teilen) | App-Funktionen | Ja (Free-Modus ohne Konto möglich) | Nur bei Bedarf übertragen, nicht beim Entwickler gespeichert |
| Persönliche Daten | Sonstige (Passwort, API-Schlüssel) | Ja | Nein | App-Funktionen | Ja | wie oben, lokal verschlüsselt |
| Dateien und Dokumente | DLC-Container-Inhalt (Links) | Ja | Nein (JDownloader-Dienst als Verarbeiter) | App-Funktionen | Ja (nur bei DLC-Import) | Übertragung zur Entschlüsselung, keine Speicherung beim Entwickler |
| Dateien und Dokumente | Heruntergeladene und entpackte Dateien an das NAS des Nutzers (NFS-Ziel) | Ja | Nein (Ziel ist ein Gerät des Nutzers im eigenen Netz) | App-Funktionen | Ja (nur mit eingetragenem NFS-Ziel) | Unverschlüsselt (NFSv3) im lokalen Netz; gespeichert werden lokal nur Server, Pfad und uid/gid, keine Zugangsdaten |
| App-Aktivität, Absturzprotokolle | – | Nein | Nein | – | – | Absturzbericht bleibt auf dem Gerät |
| Geräte- oder andere IDs | – | Nein | Nein | – | – | – |
| Standort, Kontakte, Fotos, Audio | – | Nein | Nein | – | – | – |

Datenschutzerklärung: Link auf die veröffentlichte Fassung von
`docs/DATENSCHUTZ.md` (z. B. über GitHub Pages oder die Raw-Ansicht) – Play
verlangt eine öffentlich erreichbare URL.

## 2. Vordergrunddienst-Typen (Play-Formular „Foreground service“)

Ab targetSdk 34 verlangt Play für jeden deklarierten Typ eine Begründung
und optional ein Video.

**`dataSync`** – Zweck: Download von Dateien, die der Nutzer selbst in die
Warteschlange gestellt hat. Der Dienst läuft nur, solange Downloads aktiv
sind, zeigt eine Fortschrittsbenachrichtigung mit „Alle pausieren“ und
beendet sich danach. Nutzer starten ihn ausdrücklich („Starten“ / „Alle
starten“). Das 6-Stunden-Limit von Android 15 wird beachtet: läuft die App
hinein, pausiert sie, meldet es per Benachrichtigung und startet den Dienst
erst wieder auf Nutzeraktion („Fortsetzen“).

**`specialUse`** – Subtyp (Manifest-Property
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`): „Click'n'Load-Empfang auf
localhost:9666 und Warten auf WLAN zwischen Downloads“. Begründung: Der
lokale Click'n'Load-Server muss Verbindungen des Browsers auf demselben
Gerät annehmen, solange der Nutzer die Funktion eingeschaltet hat; und bei
„Nur über WLAN“ wartet die Warteschlange auf das WLAN, ohne das
`dataSync`-Kontingent zu verbrauchen. Keiner der vordefinierten Typen deckt
einen Loopback-Server ab. Der Dienst ist in diesem Zustand leerlaufend
(keine Netzlast) und wird vom Nutzer in den Einstellungen abgeschaltet.

Video: Benachrichtigung während eines Downloads (dataSync) und der
Click'n'Load-Status in den Einstellungen mit „Verbindung testen“
(specialUse) aufnehmen.

## 3. Berechtigungen (Deklaration und Begründung)

| Berechtigung | Sensibel | Begründung |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | nein | Downloads; WLAN-Erkennung für „Nur über WLAN“ |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_SPECIAL_USE` | Formular s. o. | Download-Dienst |
| `POST_NOTIFICATIONS` | Laufzeit (Android 13+) | Fortschritt, Pause/Fortsetzen, Click'n'Load-Eingang, Erinnerung |
| `WAKE_LOCK` | nein | Gerät während Downloads wach halten |
| `RECEIVE_BOOT_COMPLETED` | nein | Warteschlange nach Neustart herstellen und erinnern (kein automatischer Downloadstart) |

Nicht verwendet und daher nicht zu deklarieren: `MANAGE_EXTERNAL_STORAGE`
(Zielordner über SAF/MediaStore), `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`,
Standort, Kontakte, SMS, Anrufe, Kamera, Mikrofon.

## 4. Weitere Play-Angaben

- **Zielgruppe / Inhaltseinstufung**: Erwachsene bzw. „Alle“ nach
  IARC-Fragebogen; die App zeigt keine eigenen Inhalte, bietet aber Zugang
  zu nutzergenerierten Inhalten Dritter (Frage im Fragebogen entsprechend
  beantworten).
- **Werbung**: Nein. **In-App-Käufe**: Nein.
- **Kontodeaktivierung**: Die App hat keine eigenen Nutzerkonten; die
  Hoster-Konten werden lokal gelöscht (Konten-Tab → Aktionsmenü → Löschen).
- **Richtlinienrisiko**: siehe Abschnitt „Play“ in [`RELEASE.md`](RELEASE.md).
