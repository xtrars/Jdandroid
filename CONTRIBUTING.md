# Mitwirken an JDAndroid

Danke für dein Interesse. JDAndroid ist ein Android-Download-Manager nach dem
Vorbild von JDownloader (Hoster ddownload, Rapidgator und 1fichier;
Linksammler, Click'n'Load, DLC, Entpacken). Diese Datei beschreibt, wie du
das Projekt baust, testest und Änderungen einreichst. Die Projektsprache ist
Deutsch: Oberfläche, Kommentare, Commit-Texte, Issues und Pull Requests.

## Entwicklungsumgebung

| Werkzeug | Version | Bemerkung |
|---|---|---|
| JDK | 17 (Temurin) | `sourceCompatibility`/`jvmTarget` 17 |
| Gradle | 8.14.5 | über den Wrapper `./gradlew`, keine eigene Installation nötig |
| Android Gradle Plugin | 8.13.2 | in `gradle/libs.versions.toml` |
| Kotlin / KSP | 2.3.21 / 2.3.11 | Compose-Compiler-Plugin aus dem Kotlin-Release |
| Android SDK | compileSdk 36, minSdk 26 | Pfad in `local.properties` (`sdk.dir=...`) |
| Android Studio | aktuelle stabile Version | optional, das Projekt lässt sich rein per Gradle bauen |

Nach dem Klonen reicht ein vorhandenes Android-SDK und JDK 17; der Wrapper
lädt Gradle selbst. Alle Abhängigkeiten werden über
`gradle/verification-metadata.xml` per SHA-256 geprüft (siehe unten).

Der Signatur-Keystore liegt **nicht** im Repository (der früher
eingecheckte gilt als kompromittiert und ist außer Dienst, siehe
`SECURITY.md`). Für eine signierte Release-APK legst du im Projektstamm eine
`keystore.properties` an (gitignored) mit `storeFile`, `storePassword`,
`keyAlias` und `keyPassword`; die Umgebungsvariablen `KEYSTORE_FILE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` und `KEY_PASSWORD` haben Vorrang. Ohne
beides baut `assembleRelease` eine unsignierte APK
(`app-release-unsigned.apk`); zum Ausprobieren auf einem Gerät reicht ein
Debug-Build (`assembleDebug`), der davon unberührt ist. Die offiziellen
Releases signiert nur der Projektinhaber. Die letzten fünf Release-APKs in
`release/` sind gewollt; bitte nicht entfernen oder ersetzen.

## Bauen

```bash
# Unit-Tests, danach Release-APK (signiert, wenn ein Keystore konfiguriert ist)
./gradlew --offline -q testDebugUnitTest
./gradlew --offline -q assembleRelease
# Ergebnis: app/build/outputs/apk/release/JDAndroid-<version>.apk
```

Hinweise:

- `--offline` funktioniert, sobald der Gradle-Cache einmal gefüllt ist; beim
  ersten Lauf ohne `--offline` bauen.
- Wenn eine Änderung eine **neue Room-Schema-Version** erzeugt, die beiden
  Tasks **nacheinander** ausführen, nicht in einem Aufruf: parallele
  KSP-Läufe legen sonst eine leere `app/schemas/com.jdandroid.data.AppDatabase/N.json`
  an. Passiert es doch: leere Datei löschen, neu bauen.
- R8/Shrinking ist im Release-Build bewusst **aus** (`isMinifyEnabled = false`).
  Der Shrinker hatte Rückrufklassen des RAR-Entpackers entfernt, die nur aus
  nativem 7-Zip-Code per JNI aufgerufen werden. Bitte nicht wieder einschalten,
  ohne das Entpacken von RAR auf einem echten Gerät zu prüfen.
- Kotlin-Blockkommentare sind verschachtelbar: ein `/*` in KDoc (etwa in
  einem Pfad wie `proof/*`) öffnet einen weiteren Kommentar und bricht den
  Build.
- Die CI (`.github/workflows/android.yml`) läuft bei Push, Pull Request und
  wöchentlich: `testDebugUnitTest`, `lintDebug`, `compileDebugAndroidTestKotlin`,
  `assembleRelease`. Lokal vor dem PR mindestens Tests und Lint ausführen.

### Abhängigkeiten ändern

Versionen stehen ausschließlich in `gradle/libs.versions.toml`. Nach jeder
Änderung die Prüfsummen nachziehen, sonst schlägt der Build ab:

```bash
./gradlew --write-verification-metadata sha256 testDebugUnitTest assembleRelease lintDebug compileDebugAndroidTestKotlin
```

Die aktualisierte `gradle/verification-metadata.xml` gehört mit in den
Commit. Signaturprüfung ist absichtlich aus (`verify-signatures=false`), nur
Prüfsummen werden verifiziert.

### Room-Schema und Migrationen

Die Datenbank (`app/src/main/java/com/jdandroid/data/Db.kt`) hat aktuell
Schema-Version 11; die Schemata werden nach `app/schemas/` exportiert. Bei
jeder Änderung an einer Entity:

1. Schema-Version in `Db.kt` erhöhen und eine `Migration` ergänzen. Keine
   `fallbackToDestructiveMigration`: bestehende Warteschlangen und Konten
   müssen erhalten bleiben.
2. Bauen, damit die neue `N.json` unter `app/schemas/` entsteht, und die
   Datei einchecken.
3. `app/src/androidTest/java/com/jdandroid/MigrationTest.kt` um den neuen
   Schritt erweitern. Der Test läuft auf einem Gerät oder Emulator
   (`./gradlew connectedDebugAndroidTest`); die CI kompiliert ihn nur.

## Tests

- Unit-Tests liegen in `app/src/test/java/com/jdandroid/` (JUnit 4) und
  laufen mit `./gradlew testDebugUnitTest`. Sie decken vor allem reine
  Logik ab: Link-Parsing, Hoster-Erkennung, Antworten der Hoster-APIs,
  DLC-/Click'n'Load-Entschlüsselung, Paketbenennung, Entpacker-Hilfsfunktionen,
  Geschwindigkeitslimit.
- `unitTests.isReturnDefaultValues = true` ist gesetzt, damit
  `android.util.Log` in Tests nicht wirft. Weitere Android-Klassen bitte nicht
  in Unit-Tests ziehen; was Android braucht, gehört unter `androidTest`.
- Neue Funktionen bringen Tests mit, wenn sich das Verhalten auf der JVM
  prüfen lässt. Für Hoster-Code eignen sich aufgezeichnete Antworten
  (HTML/JSON-Ausschnitte als Strings) wie in `DdownloadResponseTest`.
- Tests prüfen Verhalten, nicht Implementierung: ein Test, der auch bei
  kaputtem Code grün bliebe, hilft niemandem.
- Vor jedem Pull Request müssen alle Unit-Tests grün sein und `lintDebug`
  darf keine neuen Fehler melden.

## Codestil (Kotlin)

- `kotlin.code.style=official`: 4 Leerzeichen, keine Tabs, UTF-8, LF,
  Zeilen möglichst unter 120 Zeichen. Es gibt keinen Formatierer im Build;
  bitte die Kotlin-Standardformatierung von Android Studio verwenden.
- Schichten einhalten: `ui` (Compose, ViewModels), `data` (Room, DataStore,
  Secrets, Link-Prüfung), `engine` (Download-Service, Engine, Extractor),
  `hoster` (ein Objekt je Hoster), `container` (DLC, Click'n'Load). Kein
  Netzwerk- oder Datenbankzugriff aus `ui`, keine Compose-Abhängigkeit
  außerhalb von `ui`.
- Coroutines: keine blockierende IO auf dem Hauptthread, `runBlocking` nur
  dort, wo es bereits steht (Theme-Start). `NonCancellable` nur, wo ein
  Abbruch Daten beschädigen würde.
- Room: gezielte Abfragen statt Alles-laden-und-filtern, Transaktionen für
  zusammengehörige Schreibvorgänge.
- Größen und Geschwindigkeiten immer **1024-basiert** (KiB, MiB, GiB, TiB).
- Vorübergehende Fehler (HTTP 5xx, 403-Sperren wegen Flood- oder
  Tageslimit) dürfen ein Konto nie dauerhaft deaktivieren. HTML-Antworten
  werden nie als Dateiinhalt gespeichert.
- Kommentare auf Deutsch, dort, wo sie das *Warum* erklären (Beispiele
  stehen in `app/build.gradle.kts` und `AndroidManifest.xml`). Keine
  Modell- oder Werkzeugnamen in Code und Kommentaren.

### Oberfläche

- Alle Texte stehen direkt im Kotlin-Code auf Deutsch; `strings.xml` enthält
  nur `app_name`. Eine Lokalisierung ist derzeit nicht vorgesehen. Neue
  Texte bitte in derselben Tonlage: kurz, sachlich, ohne Anglizismen, wo ein
  deutsches Wort passt (Linksammler, Warteschlange, Entpacken, Konto).
- **Nur Material You**: dynamisches Farbschema ab Android 12, darunter das
  Material-Standardschema (`Theme.kt`). Keine eigenen Farbpaletten, keine
  hart kodierten Farben in Composables.
- Alle Schaltflächen müssen sichtbar bleiben (Fenster-Insets, eingeblendete
  Tastatur). Zeilen nicht mit Symbolen überladen: pro Zeile ein
  Drei-Punkte-Aktionsmenü; nur am Paket zusätzlich Start/Pause direkt.
- Passwort- und URL-Felder ohne Autokorrektur (`KeyboardType.Uri`,
  `autoCorrectEnabled = false`).
- Links werden nur im Linksammler hinzugefügt, nicht in der Download-Liste.
- Dialoge müssen Bildschirmdrehungen überstehen (`rememberSaveable`).
- Kontenübersicht zeigt die Restmenge mit Balken; Entpack-Status erscheint
  an allen Teilen eines Archivs und am Paket, mit Prozent.

### Fehlermeldungen statt Diagnose-Anhäufung

Fehler werden als **eine klare Meldung** an der betroffenen Stelle angezeigt
(Download, Paket, Konto, Click'n'Load-Statuszeile, `Messages.kt`). Es werden
keine Protokolle oder Diagnose-Ausgaben in den Einstellungen gesammelt; die
einzige Ausnahme ist der Absturzbericht „Letzter Absturz“ aus
`CrashReporter.kt`. Wer beim Fehlersuchen `Log`-Ausgaben ergänzt, entfernt
sie vor dem PR wieder. Zugangsdaten, Cookies und API-Keys dürfen nie in
Logs oder Meldungen erscheinen.

## Einen Hoster hinzufügen

1. Neue Klasse in `app/src/main/java/com/jdandroid/hoster/`, die das
   Interface `Hoster` implementiert (`id`, `displayName`, `accountType`,
   `accountHint`, `matches()`, `checkAccount()`, `resolve()`, optional
   `checkLink()` und `webLoginUrl`). HTTP über das gemeinsame `Http`-Objekt
   (OkHttp); ein eigener `OkHttpClient` nur, wenn der Hoster wie ddownload
   Sitzungs-Cookies je Konto braucht.
2. In `HosterRegistry.hosters` eintragen.
3. Unit-Tests für URL-Erkennung (`HosterMatchTest`) und für das Parsen der
   Server-Antworten anlegen. Annahmen über Antwortformate großzügig halten
   (Felder fehlen, Zahlen als Strings, Einheiten falsch beschriftet).
4. `HosterException(permanent = true)` nur für endgültige Zustände
   (Datei gelöscht, Konto abgelaufen), alles andere ist vorübergehend.

## Versionierung und Commits

- Versionsschema `0.1.x` seit der ersten Veröffentlichung (0.1.0,
  `versionCode` 41); `1.0.0` folgt später. Beides steht in
  `app/build.gradle.kts`: `versionName` und `versionCode`. Der
  `versionCode` steigt bei **jedem** Release-Build um eins, sonst verweigert
  Android das Update.
- Ein Feature- oder Fix-PR ändert die Version nicht selbst; das geschieht
  beim Zusammenführen zusammen mit der neuen APK in `release/` (nur die
  fünf neuesten Versionen bleiben dort, ältere werden mit `git rm`
  entfernt).
- Ein Git-Tag `v<versionName>` (z. B. `v0.1.0`) auf den Versions-Commit
  setzen und pushen: `.github/workflows/release.yml` baut und signiert die
  APK mit den Repository-Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`); fehlen sie, nimmt er die eingecheckte
  `release/JDAndroid-<version>.apk`. In beiden Fällen prüft er die Signatur
  und legt ein GitHub-Release mit APK und SHA-256-Prüfsumme an. Der Tag
  muss zum `versionName` passen, sonst bricht der Lauf ab.
- Commit-Texte auf Deutsch, erste Zeile als knappe Zusammenfassung, bei
  Versionssprüngen mit Präfix, z. B.
  `0.0.4: Entpack-Status für alle Archivteile und das Paket`. Bei
  Bedarf ein Absatz mit dem Warum. Die im Projekt vorgeschriebenen
  Schlusszeilen stehen in `CLAUDE.md`.
- Kleine, thematisch geschlossene Commits sind willkommen; Umformatierungen
  bitte nicht mit fachlichen Änderungen mischen.

## Pull Requests

1. Fork oder Branch anlegen. Das Projekt wird derzeit auf dem Branch
   `claude/android-jdownloader-app-1zqi1n` entwickelt; PRs bitte gegen
   diesen Branch stellen, solange kein anderer Standardbranch angekündigt ist.
2. Vor dem Einreichen lokal: `./gradlew testDebugUnitTest lintDebug
   compileDebugAndroidTestKotlin`. Bei Entity-Änderungen zusätzlich
   Migration, Schema-Export und `MigrationTest`.
3. Im PR beschreiben: Was wurde geändert, warum, wie wurde es geprüft
   (welcher Hoster, welches Android, echtes Gerät oder Emulator). Für
   Änderungen an Hoster-Code sind Beobachtungen zu Server-Antworten
   (Statuscodes, Weiterleitungen, Feldnamen) besonders wertvoll.
4. Keine Zugangsdaten, Konto-Screenshots mit Nutzernamen, API-Keys oder
   `cf_clearance`-Cookies in Issues, PRs oder Testdaten.
5. Die CI muss grün sein. Lint-Warnungen, die nichts mit der Änderung zu
   tun haben, müssen nicht behoben werden, dürfen aber.

## Fehler melden und Vorschläge

Bitte über GitHub-Issues. Hilfreich sind: App-Version (Einstellungen),
Android-Version und Gerät, betroffener Hoster und Konto-Typ (API-Key oder
Browser-Login), bei Entpack-Problemen der Archivtyp (ZIP, 7z, RAR4/RAR5,
mehrteilig, Passwort) und der Text der angezeigten Fehlermeldung. Wünsche
nach weiteren Hostern sind willkommen; nenne dabei, ob der Hoster eine
dokumentierte API hat.

Sicherheitsrelevante Funde bitte nicht als öffentliches Issue, sondern wie
in `SECURITY.md` beschrieben melden.
