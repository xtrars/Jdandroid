# Architektur von JDAndroid

Diese Beschreibung erklärt, wie die App aufgebaut ist und warum bestimmte
Entscheidungen so getroffen wurden. Sie bezieht sich auf den Stand 0.0.4
(Datenbank-Schema 9). Wer Code ändert, findet die Prüf-Checkliste für
Reviews in [`PRUEFUNG.md`](PRUEFUNG.md) und die Arbeitsregeln in
[`../CONTRIBUTING.md`](../CONTRIBUTING.md); Sicherheitsfragen behandelt
[`../SECURITY.md`](../SECURITY.md).

## Überblick

JDAndroid ist eine einzelne Android-App (`applicationId com.jdandroid`,
minSdk 26, targetSdk 36) in Kotlin mit Jetpack Compose, Room, DataStore,
OkHttp und kotlinx.coroutines. Es gibt keine Server-Komponente; alles läuft
auf dem Gerät. Der Quelltext liegt unter `app/src/main/java/com/jdandroid/`
und ist in fünf Pakete gegliedert, die jeweils eine Schicht bilden:

```
com.jdandroid
├── JdApp.kt              Application: Datenbank, Einstellungen, Benachrichtigungskanäle
├── CrashReporter.kt      unbehandelte Ausnahmen → Datei → Einstellungen „Letzter Absturz“
├── ui/                   Compose-Oberfläche, ViewModels, Theme, Meldungen
├── data/                 Room (Db.kt), DataStore (SettingsRepository), Secrets,
│                         LinkSink, LinkChecker, AccountRefresher, PackageNaming
├── engine/               DownloadService, DownloadEngine, Extractor, SpeedLimiter, BootReceiver
├── hoster/               Hoster-Interface, HosterRegistry, LinkParser, Http;
│                         DdownloadHoster, RapidgatorHoster, OneFichierHoster
└── container/            ClickNLoadServer, ContainerDecrypter, ContainerFiles, CnlStatus
```

Abhängigkeitsrichtung: `ui` kennt `data`, `engine` (nur über Intents an den
Dienst) und `hoster` (Registry für Kontodialoge). `engine` kennt `data`,
`hoster` und `container`. `container` kennt `hoster` nur für den
OkHttp-Client des DLC-Dienstes. `hoster` kennt `data` nur für die
`Account`-Entity und die entschlüsselten Zugangsdaten. `data` kennt keine
höhere Schicht – mit einer Ausnahme: `LinkSink` stößt nach dem Einfügen den
Dienst an (`DownloadService.send`) bzw. die Linkprüfung.

## Die Schichten

### `ui` – Oberfläche

- `MainActivity` ist die einzige Activity (`launchMode="singleTask"`). Sie
  nimmt drei Arten von Intents an: `ACTION_SEND` mit `text/plain` (Links
  teilen), `ACTION_VIEW`/`ACTION_SEND` mit `application/octet-stream` oder
  Endung `.dlc` (DLC-Container) sowie das Extra `EXTRA_RESUME_ALL` aus der
  Zeitlimit-Benachrichtigung. Intents werden nur beim ersten Start
  (`savedInstanceState == null`) und in `onNewIntent` verarbeitet, sonst
  würde ein DLC beim Drehen erneut importiert.
- Vier Tabs: Downloads (`DownloadsScreen`), Linksammler
  (`LinkGrabberScreen`), Konten (`AccountsScreen`), Einstellungen
  (`SettingsScreen`). Dazu `WebLoginScreen` (eingebetteter Browser für den
  ddownload-Login mit Cloudflare Turnstile), `AddLinksDialog`, `CrashDialog`.
- `ViewModels.kt`: `DownloadViewModel` kombiniert `downloads` und `packages`
  aus Room zu `DownloadGroup`-Listen (Downloads ohne `COLLECTED`, Linksammler
  nur `COLLECTED`) und leitet Aktionen als Intents an den `DownloadService`
  weiter. `AccountViewModel` verwaltet Konten und den Browser-Login-Zustand.
- `Messages.kt` (`AppMessages`): ein `SharedFlow` mit `replay = 1`, damit
  eine Meldung, die vor dem Aufbau der Oberfläche entsteht (etwa „keine
  DLC-Datei“ beim Kaltstart per Intent), nicht verloren geht. Fehler sollen als
  eine klare Meldung erscheinen, nie als Protokoll.
- `Theme.kt`: ausschließlich Material You – `dynamicLightColorScheme` /
  `dynamicDarkColorScheme` ab Android 12, darunter das
  Material-Standardschema. Hell/Dunkel folgt dem System oder der Einstellung.
  Der gespeicherte Modus wird beim Start einmal mit `runBlocking` gelesen,
  damit der erste Frame nicht im falschen Schema erscheint; das ist der
  einzige blockierende Aufruf in der App.

### `data` – Daten

- `Db.kt`: Room-Datenbank `jdandroid.db` mit drei Entities (siehe
  [Datenbank](#datenbank-und-migrationen)).
- `SettingsRepository`: DataStore-Preferences für alle Einstellungen
  (parallele Downloads, Export, Auto-Entpacken, Archiv löschen, Links nach
  dem Entpacken entfernen, Passwortliste, Ausschlussmuster,
  Geschwindigkeitslimit, Click'n'Load an/aus, Nur-WLAN, Sofortstart,
  SAF-Zielordner, Hell/Dunkel). Eine beschädigte Datei wird durch leere
  Voreinstellungen ersetzt statt die App beim Start abstürzen zu lassen.
- `Secrets`: AES-GCM mit einem Schlüssel aus dem Android-Keystore (Alias
  `jdandroid_credentials`); Chiffrat trägt das Präfix `enc1:`. Werte ohne
  Präfix stammen aus frühen Installationen und werden von
  `AccountRefresher.upgradeSecrets()` einmalig verschlüsselt zurückgeschrieben.
- `LinkSink`: die eine Stelle, an der Links in die Datenbank gelangen
  (Oberfläche, Click'n'Load, DLC). Sie filtert über `LinkParser` auf
  unterstützte Hoster, prüft Duplikate und legt Paket plus Einträge in einer
  Transaktion an.
- `LinkChecker`: Online-Prüfung im Linksammler (höchstens drei Anfragen
  parallel, `Semaphore(3)`), läuft im `appScope` der Application, damit sie
  auch ohne offenen Bildschirm durchläuft.
- `AccountRefresher`: Kontoprüfung mit Drosselung (15 Minuten „veraltet“,
  nach einem Download frühestens alle 3 Minuten). Nur endgültige Fehler
  (`HosterException.permanent`, nicht entschlüsselbare Zugangsdaten) setzen
  ein Konto auf ungültig – ein Netzausfall im Minutentakt darf nicht alle
  Downloads eines Hosters in „kein Premium-Konto“ laufen lassen.
- `PackageNaming`: Paketname aus dem gemeinsamen Namensteil der Dateien
  („film.part1.rar“, „film.part2.rar“ → „film“).

### `hoster` – Anbindung der One-Click-Hoster

`Hoster` ist das Plugin-Interface:

```kotlin
interface Hoster {
    val id: String; val displayName: String; val accountType: AccountType
    val accountHint: String; val webLoginUrl: String? get() = null
    fun matches(url: String): Boolean
    suspend fun checkAccount(account: Account): AccountInfo
    suspend fun resolve(url: String, account: Account?): ResolvedLink
    suspend fun checkLink(url: String, account: Account?): LinkInfo
}
```

`HosterRegistry.hosters` listet die drei Implementierungen; `forUrl()` und
`byId()` sind die einzigen Zugriffswege. `LinkParser` zieht URLs aus
beliebigem Text und ordnet sie über die Registry zu. `Http` hält den
gemeinsamen `OkHttpClient` und liest Textantworten nie unbegrenzt
(`peekBody(2 MiB)`), weil ein Server statt JSON auch eine Datei liefern kann.
`HosterException(permanent = true)` bedeutet: erneuter Versuch ohne
Nutzeraktion ist sinnlos (Datei offline, kein Premium); alles andere gilt als
vorübergehend und wird von der Engine wiederholt.

Die Eigenheiten der drei Hoster (ddownload: Turnstile, Kontoseite mit falscher
Einheit, Weiterleitungsketten; 1fichier: 403 als Flood-Sperre, `user/info`
nur alle fünf Minuten; Rapidgator: Tageslimit) stehen in `CLAUDE.md`,
Abschnitt „Hoster-Besonderheiten“.

### `container` – Click'n'Load und DLC

Siehe [Click'n'Load-Server](#clicknload-server) und
[DLC-Container](#dlc-container).

### `engine` – Dienst, Warteschlange, Entpacken

Siehe [Download-Dienst](#download-dienst-und-vordergrund-typen) und
[Download-Engine](#download-engine).

## Datenfluss: Link → Linksammler → Download → Entpacken → Export

```
Text einfügen / Teilen ──┐
DLC-Datei (Öffnen mit) ──┤  ContainerDecrypter.decryptDlc()  ┐
Click'n'Load (Browser) ──┘  ContainerDecrypter.decryptClickNLoad() ┘
        │
        ▼
LinkParser.parse()  → unterstützte URLs + Hoster
        │
        ▼
LinkSink.addFromText()  → Paket + DownloadItems (Status COLLECTED)  [Room-Transaktion]
        │                                   │
        │ Sofortstart an?                   ▼
        │                       LinkChecker.schedule()  → Hoster.checkLink()
        │                       online/offline, Name, Größe, Paketname verfeinern
        ▼
„Starten“ → Status QUEUED → DownloadService.send(ACTION_PUMP)
        │
        ▼
DownloadEngine.pump()  → freie Slots füllen (maxConcurrent), Nur-WLAN prüfen
        │
        ▼
DownloadEngine.run(id)  → Hoster.resolve() → direkte URL, Name, Größe, Prüfsumme
        │
        ▼
DownloadEngine.download()  → OkHttp mit Range-Resume in <id>-<name>.part
        │                      Fortschritt/Geschwindigkeit alle 2 s in die DB
        ▼
verifyHash()  (MD5/SHA-1, wenn der Hoster eine lieferte)
        │
        ▼
completeDownload()  [NonCancellable]
   ├── kein Archiv oder Auto-Entpacken aus → finish() → Export → COMPLETED
   ├── Archiv, weitere Teile ausstehend    → COMPLETED „Warte auf weitere Archiv-Teile“
   └── Archiv, alle Teile da               → setExtractingSet() → launchExtraction()
                                                    │
                                                    ▼
                          Extractor.extract()  [NonCancellable, nur einer gleichzeitig]
                          Passwortliste, Ausschlussmuster, Fortschritt in %
                                                    │
                                                    ▼
                          exportDirectory()  → SAF-Zielordner oder Downloads/JDAndroid/<Paket>/
                          Archiv löschen (Option), Einträge entfernen (Option)
                                                    │
                                                    ▼
                          completeExtractingSet()  → alle Teile COMPLETED mit Zielpfad
```

Zustände eines `DownloadItem` (`DownloadStatus`): `COLLECTED` (im
Linksammler), `QUEUED`, `RUNNING`, `PAUSED`, `EXTRACTING`, `COMPLETED`,
`FAILED`, `OFFLINE`. Die Ergebnisse der Online-Prüfung stehen getrennt davon
in der Spalte `online` (`UNKNOWN`, `ONLINE`, `OFFLINE`, `CHECKING`).

Wichtige Übergänge:

- Beim Start des Dienstes und in `BootReceiver` setzt `requeueRunning()`
  hängen gebliebene `RUNNING`/`EXTRACTING`-Einträge zurück auf `QUEUED`.
  Die Engine wartet mit `startGate` darauf, sonst konnte ein früher `pump()`
  (Netzwerk-Callback) denselben Download doppelt starten.
- Vorübergehende Fehler: `handleTransientFailure()` plant bis zu fünf
  Wiederholversuche mit exponentiellem Backoff (10 s · 2^(n−1), höchstens
  5 Minuten, Spalten `attempts`/`retryAt`). Nach 4 MiB echtem Fortschritt
  werden die Versuche zurückgesetzt, damit ein wackliges Netz einen
  fortsetzbaren Download nicht aufgibt.
- Statusänderungen sind bedingt formuliert (`completeIfActive`,
  `pauseIfActive`, `requeueIfRunning`): ein Eintrag, der zwischenzeitlich
  pausiert oder gelöscht wurde, wird nicht nachträglich „fertig“.

## Download-Dienst und Vordergrund-Typen

`DownloadService` ist ein Foreground-Service (`exported="false"`) mit
`foregroundServiceType="dataSync|specialUse"`. Er hält die `DownloadEngine`,
die Benachrichtigung mit Gesamtfortschritt und den Click'n'Load-Server.
Alle Aktionen kommen als Intents (`ACTION_PUMP`, `ACTION_PAUSE`,
`ACTION_DELETE`, `ACTION_EXTRACT`, `ACTION_PAUSE_ALL`, `ACTION_RESUME_ALL`,
`ACTION_START_CNL`, `ACTION_STOP_CNL`); `DownloadService.send()` fällt auf
`startService()` zurück, wenn `startForegroundService()` aus dem Hintergrund
verboten ist (Android 12+).

Warum zwei Vordergrund-Typen: Android 15 begrenzt `dataSync`-Dienste auf
sechs Stunden je Tag und zählt auch den Leerlauf. Deshalb wechselt
`ensureForegroundType()` bei jedem `refresh()`:

| Zustand | Typ | Grund |
|---|---|---|
| mindestens ein Download oder Entpackvorgang aktiv | `dataSync` | eigentliche Datenübertragung |
| nichts lädt, aber Click'n'Load lauscht oder es wird auf WLAN gewartet | `specialUse` (Subtyp im Manifest beschrieben) | das 6-h-Kontingent nicht verbrauchen |
| nichts lädt, kein Click'n'Load | Dienst beendet sich (`stopSelfResult`) | kein Grund, im Vordergrund zu bleiben |

Läuft das Kontingent trotzdem ab, ruft Android `onTimeout()`: die Engine
pausiert alles sauber, eine Benachrichtigung erklärt es, „Fortsetzen“ öffnet
die App und startet den Dienst aus dem Vordergrund (nur so ist der Neustart
nach dem Limit erlaubt). Wird `startForeground` vom System abgelehnt, merkt
sich der Dienst `foregroundRefused` und stößt nichts mehr an, sonst blieben
Einträge als `RUNNING` zurück.

Weitere Bausteine: ein `PARTIAL_WAKE_LOCK` nur, solange wirklich geladen wird;
ein `registerDefaultNetworkCallback`, der bei Netzwechsel `onNetworkChanged()`
auslöst (Nur-WLAN: laufende Downloads zurück in die Warteschlange, sie starten
bei WLAN automatisch); `BootReceiver` (`BOOT_COMPLETED`,
`MY_PACKAGE_REPLACED`) darf ab Android 15 keinen `dataSync`-Dienst mehr aus
dem Hintergrund starten und erinnert stattdessen per Benachrichtigung an
offene Downloads. Zwei Benachrichtigungskanäle: `downloads` (Fortschritt,
niedrige Priorität) und `events` (Click'n'Load-Eingang, Neustart, Zeitlimit).

## Download-Engine

`DownloadEngine` verwaltet die Jobs in einer `ConcurrentHashMap<Long, Job>`
unter einem `Mutex`. Kernpunkte:

- **Range-Resume**: Die Teildatei heißt `<id>-<name>.part` im privaten
  App-Ordner. Bei vorhandenem Rest sendet die Engine `Range: bytes=<offset>-`
  und `Accept-Encoding: identity` (sonst fehlt `Content-Length` und die
  Vollständigkeitsprüfung). Antwortet der Server nicht mit 206, beginnt der
  Download von vorn; 416 bei passender Größe gilt als „war schon vollständig“.
- **HTML statt Datei** (abgelaufener Link, Sitzungsseite) wird nie als
  Dateiinhalt gespeichert, sondern als vorübergehender Fehler behandelt, der
  den Link neu auflöst.
- **Speicherplatz** wird vor dem Download geprüft, nicht erst nach Minuten
  per IO-Fehler.
- **Geschwindigkeit** als gleitender 5-Sekunden-Durchschnitt; Fortschritt
  alle 2 s in die Datenbank, Benachrichtigung höchstens jede Sekunde.
  `SpeedLimiter` verteilt ein globales Kontingent in 1-Sekunden-Fenstern und
  wartet außerhalb des Locks, sonst würde das Limit alle Downloads
  serialisieren.
- **Abbruch**: Pause und Löschen kappen die OkHttp-Verbindung sofort über
  `invokeOnCompletion`, sonst wirkt der Abbruch erst nach dem nächsten
  Socket-Read (bis zu 60 s).
- **Abschluss und Entpacken laufen unter `NonCancellable`** und der
  Entscheid „alle Teile da?“ unter `completionMutex`: zwei gleichzeitig fertige
  Teile desselben Archivs dürfen sich nicht gegenseitig als „noch ausstehend“
  sehen. Entpacken läuft in einem eigenen Job (`extractLimiter`, immer nur
  eines), damit der Download-Slot sofort frei wird.
- **Export**: Ein per SAF gewählter Zielordner (`DocumentFile`) hat Vorrang;
  sonst `Downloads/JDAndroid/` über `MediaStore.Downloads` mit `IS_PENDING`.
  Vorhandene Dateien werden nicht überschrieben, sondern durchnummeriert.
- **Nachträgliches Entpacken** (`extractNow`) holt Archivteile bei Bedarf aus
  dem Zielordner oder dem MediaStore zurück in den App-Ordner.

`Extractor` erkennt Archive an der Endung (`archiveBase()`), repariert von
ddownload zerstückelte Namen (`repairName()`, „name part1 rar“) und Namen ohne
Endung über Magic Bytes (`sniffExtension()`). Formate: ZIP über zip4j (Datei
für Datei, damit Ausschlussmuster greifen), 7z über commons-compress (auch
`.7z.001`-Volumes), RAR4/RAR5 über 7-Zip-JBinding (native `.so`, wird mit
`System.loadLibrary("7-Zip-JBinding")` und `SevenZip.initLoadedLibraries()`
von Hand initialisiert, weil die Auto-Initialisierung auf Android eine
Platform-JAR sucht). Passwörter werden der Reihe nach aus der Passwortliste
probiert (zuerst ohne). `safeChild()` verhindert Pfad-Traversal aus
Archiveinträgen.

## Datenbank und Migrationen

`AppDatabase` (Room, Version 9, `exportSchema = true`, Schemata unter
`app/schemas/com.jdandroid.data.AppDatabase/`) mit drei Tabellen:

| Tabelle | Entity | Inhalt |
|---|---|---|
| `packages` | `DownloadPackage` | Name, `autoNamed` (darf aus Dateinamen verfeinert werden), `source` (Herkunft bei Click'n'Load), `addedAt` |
| `downloads` | `DownloadItem` | URL (eindeutiger Index), `hosterId`, `packageId`, Name, Größe, Fortschritt, Geschwindigkeit, `status`, Fehlertext, `localPath`, `attempts`, `retryAt`, `online`, `extractProgress` |
| `accounts` | `Account` | `hosterId`, Benutzername, Passwort/API-Key/Cookies (verschlüsselt), `premiumUntil`, `trafficLeft`, `trafficTotal`, `trafficUnlimited`, `valid`, `lastChecked`, `statusText` |

Migrationen stehen in `Db.kt` als `ALL_MIGRATIONS` und sind bewusst echte
Migrationen statt `fallbackToDestructiveMigration()` – bei einem Update
bleiben Konten und Downloadliste erhalten. Nur bei einem Downgrade wird die
Datenbank neu aufgebaut.

| Migration | Änderung | Version |
|---|---|---|
| 1→2 | `accounts.cookies` (Browser-Login) | 1.0.0 |
| 2→3 | `downloads.attempts`, `retryAt` (Wiederholversuche) | 1.0.0 |
| 3→4 | Tabelle `packages`, `downloads.packageId` | 1.0.0 |
| 4→5 | `packages.source` | 1.1.0 |
| 5→6 | `downloads.online` (Linksammler) | 1.2.0 |
| 6→7 | `accounts.trafficTotal`, `trafficUnlimited` | 1.3.0 |
| 7→8 | Duplikate bereinigen, eindeutiger Index auf `downloads.url` | 1.5.0 |
| 8→9 | `downloads.extractProgress` | 0.0.4 |

Der instrumentierte `MigrationTest` (`app/src/androidTest/`) prüft die
Migrationen ab Version 5 gegen die exportierten Schemata mit
`MigrationTestHelper`; im CI wird er nur kompiliert, ausgeführt wird er mit
`connectedDebugAndroidTest`. Wer eine Entity ändert, muss die Version
erhöhen, eine Migration ergänzen, das Schema exportieren (KSP-Argument
`room.schemaLocation`) und den Test erweitern. Unit-Tests und Release-Build
dabei nacheinander ausführen: parallele KSP-Läufe legen sonst eine leere
`N.json` an.

Die DAOs sind so geschnitten, dass die Engine gezielte, bedingte Updates
ausführt (`nextQueued` schließt laufende Kennungen aus, `setExtractingSet`
und `completeExtractingSet` arbeiten auf ganzen Archiv-Sets). `Flow`-Abfragen
(`observeAll`) speisen die Oberfläche.

## Click'n'Load-Server

`ClickNLoadServer` ist ein NanoHTTPD-Server auf Port 9666, gestartet vom
`DownloadService`, sobald die Einstellung aktiv ist (und beim App-Start
erneut, weil er keinen Prozessneustart überlebt). Der Start ist
synchronisiert, weil `onCreate` und `ACTION_START_CNL` früher parallel
denselben Port banden.

Bindung: **ausschließlich Loopback**. Zuerst `127.0.0.1`, ersatzweise
`localhost` (IPv6-Loopback), nie ohne Hostnamen. Ein Server auf allen
Schnittstellen wäre für jedes Gerät im WLAN erreichbar und könnte von dort
mit Links gefüttert werden. Die Konsequenz ist gewollt: Click'n'Load
funktioniert nur mit einem Browser auf demselben Gerät, wie beim JDownloader
am Desktop.

Endpunkte:

| Pfad | Zweck |
|---|---|
| `/jdcheck.js` | Erkennung durch die Webseite (`jdownloader=true`), als `text/javascript` |
| `/crossdomain.xml` | Altlast des Protokolls |
| `/flash/addcrypted2` | Click'n'Load 2: `crypted` (AES-128-CBC, Base64) + `jk` (JS-Funktion mit Hex-Schlüssel) – wird lokal entschlüsselt |
| `/flash/addcrypted` | kompletter DLC-Inhalt als Formularfeld |
| `/flash/add`, `/flashgot` | Klartext-Links (`urls`/`links`) |
| `OPTIONS *` | CORS-Preflight |

Jede Antwort trägt CORS-Header: der `Origin` wird zurückgespiegelt (sonst
lehnt der Browser Antworten mit Credentials ab), angefragte Header werden
gespiegelt, und für Chrome werden sowohl
`Access-Control-Allow-Private-Network` (älterer Name) als auch
`Access-Control-Allow-Local-Network` (Local Network Access ab Chrome 138)
gesetzt.

Grenzen gegen böswillige Seiten: Anfragekörper höchstens 2 MiB, höchstens
5000 Links, 50 Passwörter à 200 Zeichen, Paketname 200 Zeichen. Bei leerem
Ergebnis antwortet der Server mit `failed`, damit die Seite nicht fälschlich
Erfolg meldet. `CnlStatus` hält den sichtbaren Zustand (läuft?, gebunden an?,
Fehlergrund, letzte Anfrage als eine Zeile), den die Einstellungen anzeigen;
der Selbsttest dort ruft `http://127.0.0.1:9666/jdcheck.js` auf – der einzige
erlaubte Klartext-Aufruf der App. Jede angenommene Anfrage erzeugt eine
Benachrichtigung mit Herkunft, damit eine Webseite den Server nicht unbemerkt
füttern kann.

### DLC-Container

`ContainerDecrypter.decryptDlcPackages()` zerlegt eine `.dlc`-Datei in Daten
und den 88 Zeichen langen Schlüsselteil, holt den Container-Schlüssel (`rc`)
vom Webdienst `service.jdownloader.org/dlcrypt` (HTTPS), leitet daraus mit
dem öffentlich bekannten statischen Schlüssel/IV den echten Schlüssel ab und
entschlüsselt das XML (Pakete mit Namen und URLs, jeweils Base64). Dieser
Schritt braucht den Fremddienst; der Schlüsselteil des Containers verlässt
dafür das Gerät. `ContainerFiles` liest geteilte Dateien mit fester
Obergrenze (2 MiB) und prüft am Inhalt, ob es überhaupt ein DLC ist, weil
Android `.dlc` nicht kennt und die Dateien als `application/octet-stream`
liefert. Click'n'Load 2 kommt ohne den Dienst aus.

## Nebenläufigkeit

- `JdApp.appScope` (`SupervisorJob + Dispatchers.IO`) für Arbeit ohne
  Bildschirm (Linkprüfung, Kontoprüfung). Der `DownloadService` hat einen
  eigenen Scope, der in `onDestroy` aufgeräumt wird.
- Beide Scopes tragen `JdApp.backgroundErrors()`: eine unbehandelte Ausnahme
  (etwa `SQLiteFullException`) wird als Meldung angezeigt statt die App zu
  beenden.
- `NonCancellable` nur an drei Stellen: Abschluss eines Downloads
  (`completeDownload`), Entpacken/Export (`extractAndExport`) und das
  Austragen eines Jobs im `finally` von `run()`. Alles andere ist abbrechbar.
- Hoster-Clients halten Session-Token und Cookies je Konto in
  `ConcurrentHashMap`s, weil mehrere Downloads parallel darauf zugreifen.

## Sicherheitsentscheidungen

| Entscheidung | Wo | Warum |
|---|---|---|
| Click'n'Load nur auf Loopback | `DownloadService.startClickNLoadServer()`, `ClickNLoadServer` | Server auf `0.0.0.0` wäre aus dem WLAN erreichbar |
| Klartext-HTTP app-weit gesperrt, Ausnahme nur `127.0.0.1`/`localhost` | `res/xml/network_security_config.xml` | Hoster-APIs, DLC-Dienst und Browser-Login laufen über HTTPS; die Ausnahme braucht der CnL-Selbsttest |
| Zugangsdaten AES-GCM mit Keystore-Schlüssel, kein Klartext-Fallback | `data/Secrets.kt` | Schlägt der Keystore fehl, wird das Konto nicht gespeichert und der Fehler gemeldet, statt still Klartext abzulegen |
| `allowBackup="false"` | `AndroidManifest.xml` | Konten und Download-Liste gehören nicht in Cloud-Backups; der Keystore-Schlüssel würde ohnehin nicht mitgenommen |
| Browser-Login: JavaScript an (Turnstile braucht es), Drittanbieter-Cookies aus, nur Login-Domain und Cloudflare erlaubt, Cookies nach Übernahme gelöscht | `ui/WebLoginScreen.kt` | Session liegt danach verschlüsselt in der Datenbank, nicht im WebView |
| Antwortkörper begrenzt lesen (`peekBody`, 2 MiB), HTML nie als Datei speichern | `hoster/Hoster.kt`, `DownloadEngine.download()` | Ein Server, der statt JSON eine Datei schickt, würde sonst den Heap füllen (OutOfMemoryError bei ddownload) |
| Eingaben des CnL-Servers und geteilte Dateien mit Größen- und Anzahlgrenzen | `ClickNLoadServer`, `ContainerFiles` | Jede geöffnete Webseite darf an 9666 senden |
| Pfad-Traversal abgefangen | `Extractor.safeChild()`, `DownloadEngine.sanitizeFileName()` | Archiveinträge und Server-Dateinamen sind fremde Eingaben |
| R8/Shrinking **aus**, `-dontobfuscate` | `app/build.gradle.kts`, `proguard-rules.pro` | Der Shrinker entfernte `Extractor.RarOpenCallback` und das `ISequentialOutStream`-Lambda, die nur per JNI aus nativem 7-Zip-Code aufgerufen werden – Release-Builds entpackten kein RAR mehr (per dexdump belegt). Keep-Regeln liegen bereit, aber ohne Gerätetest bleibt der unveränderte Build der sichere Weg. Ohne Obfuskation bleiben Absturzberichte lesbar |
| Absturzbericht nur lokal | `CrashReporter.kt` | Stacktrace in `files/last_crash.txt`, sichtbar in den Einstellungen; nichts wird gesendet |
| Nur eine exportierte Activity, Dienst nicht exportiert, `BootReceiver` nur für Systemaktionen | `AndroidManifest.xml` | Kleine Angriffsfläche |
| Keystore und APKs im Repository | `app/keystore/release.jks`, `release/` | Bewusste Entscheidung für Eigenbedarf ohne Store-Release, damit jede APK dieselbe Signatur trägt; Einordnung in [`../SECURITY.md`](../SECURITY.md) |

## Build und Tests

- Toolchain: JDK 17, Gradle 8.14.3 (Wrapper mit SHA-256-Prüfsumme), AGP
  8.13.2, Kotlin 2.3.21, KSP 2.3.11, Compose BOM 2026.06.01, Room 2.8.4.
  Versionen in `gradle/libs.versions.toml`; alle Artefakte werden über
  `gradle/verification-metadata.xml` per SHA-256 verifiziert.
- Release-APK heißt `JDAndroid-<versionName>.apk` und wird mit
  `app/keystore/release.jks` signiert.
- 85 Unit-Tests in 13 Dateien (`app/src/test/`): Linkparser, Hoster-Erkennung,
  ddownload-Formulare/Antworten/Kontingent, 1fichier-Normalisierung,
  Linkprüfung, DLC-Entschlüsselung (mit echtem `rc`-Wert), Click'n'Load
  Schlüssel und Server, Entpacker (Namen, Volumes, Ausschlussmuster),
  Paketbenennung, Geschwindigkeitsbegrenzung. Sie laufen auf der JVM
  (`unitTests.isReturnDefaultValues = true` für `android.util.Log`).
- CI (`.github/workflows/android.yml`): bei Push, Pull Request und
  wöchentlich montags `testDebugUnitTest`, `lintDebug`,
  `compileDebugAndroidTestKotlin`, `assembleRelease`. Ein Tag
  `v<versionName>` löst zusätzlich `.github/workflows/release.yml` aus
  (APK bauen, Signatur prüfen, GitHub-Release mit SHA-256-Prüfsumme).

## Bewusst nicht umgesetzt

- Lokalisierung: alle Texte sind auf Deutsch fest im Code (`strings.xml`
  enthält nur `app_name`).
- Umbau des Dienstes auf User-Initiated Data Transfer Jobs (Android 14+):
  der Vordergrunddienst mit Typwechsel deckt den Bedarf, das 6-h-Limit wird
  sauber behandelt.
- Free-Downloads mit Captcha und Wartezeit.
- Ein Free-Fallback für die DLC-Entschlüsselung ohne den JDownloader-Dienst
  ist technisch nicht möglich (der Schlüssel liegt serverseitig).
