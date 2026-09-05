# Veröffentlichung: Checkliste

Stand und offene Punkte der Veröffentlichung (GitHub-Release seit 0.1.0,
später Play Store, ggf. F-Droid). Mit 0.1.0 (`versionCode` 41, 05.09.2026)
gilt das Schema `0.1.x`; `1.0.0` folgt, sobald die App reif dafür ist. Die
APKs erscheinen als GitHub-Release, die fünf neuesten liegen vorerst
zusätzlich in `release/`. Erledigtes ist abgehakt, Offenes für den
Projektinhaber steht in Abschnitt 2 gesammelt.

## 1. Version

- [x] Versionsschema `0.1.x` seit 0.1.0 (`versionName` in
      `app/build.gradle.kts`; Semantic Versioning: Fehlerbehebung → Patch,
      Funktion → Minor). `1.0.0` später. `versionCode` zählt einfach weiter
      hoch (aktueller Stand + 1); er darf nie kleiner werden, sonst verweigern
      Android und Play das Update.
- [x] `CHANGELOG.md` mit dem Eintrag zur Version, README-Badge und
      Installationsabschnitt angepasst (0.1.0).
- [ ] `git tag v<versionName>` auf dem Release-Commit setzen und pushen; der
      Tag muss exakt `v` + `versionName` sein, sonst bricht `release.yml` ab.
      Bei jeder weiteren Version wiederholen.

## 2. Signierung und Keystore

Erledigt (0.1.0):

- [x] Alter Keystore `app/keystore/release.jks` (Passwort `jdandroid`) aus
      dem Repository entfernt. Er war öffentlich, gilt als kompromittiert
      und ist außer Dienst; keine Version wird mehr damit signiert.
- [x] Neuer Schlüssel erzeugt (PKCS12, RSA 4096, Alias `jdandroid`, gültig
      bis 2056), liegt **nicht** im Repository. Fingerabdruck (SHA-256) in
      `SECURITY.md` veröffentlicht:
      `86:EB:89:53:D0:23:A3:ED:04:6E:CC:1F:08:B3:6B:B6:D5:27:D5:ED:EA:2B:9C:BC:79:5C:6B:45:1B:1A:BD:2E`
- [x] `app/build.gradle.kts`: Signierung zuerst aus den Umgebungsvariablen
      `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
      sonst aus `keystore.properties` im Projektstamm (gitignored; Schlüssel
      `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Fehlt beides,
      wird die Release-APK unsigniert gebaut (`app-release-unsigned.apk`);
      Debug-Builds sind unberührt. Kein Fallback auf einen Keystore im Repo
      mehr.
- [x] `release.yml`: Mit den Repository-Secrets `KEYSTORE_BASE64`
      (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
      `KEY_PASSWORD` baut und signiert die CI; ohne Secrets nimmt der
      Workflow die eingecheckte, lokal signierte
      `release/JDAndroid-<version>.apk` als Release-Asset. In beiden Fällen
      Signaturprüfung mit `apksigner verify --print-certs` und
      GitHub-Release mit APK und SHA-256.
- [x] `SECURITY.md`, README, CONTRIBUTING, ARCHITEKTUR und CHANGELOG auf
      den Signaturwechsel umgestellt (Hinweis: Nutzer von `0.0.x` müssen
      die App einmal deinstallieren).

**Offen für den Projektinhaber:**

- [ ] Die vier Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
      `KEY_PASSWORD` unter GitHub → Settings → Secrets and variables →
      Actions anlegen. Bis dahin veröffentlicht `release.yml` die lokal
      signierte APK aus `release/`.
- [ ] Keystore und Passwörter außerhalb von GitHub sichern (Passwort-
      Manager, Offline-Kopie). Ein verlorener Keystore bedeutet: keine
      Updates mehr für bestehende Installationen. Für Play alternativ
      „Play App Signing“ nutzen (Upload-Key lokal, App-Signing-Key bei
      Google); dann ist `KEYSTORE_*` der Upload-Key.
- [ ] Git-Historie bereinigen: **bewusst nicht gemacht.** Der alte Keystore
      bleibt in alten Commits auffindbar, wird aber nicht mehr verwendet.
      Wer die Historie trotzdem säubern will: `git filter-repo --path
      app/keystore/release.jks --invert-paths`, Force-Push, alle Klone neu
      ziehen; Links auf alte Commit-Hashes im CHANGELOG brechen dabei.

## 3. CI

- [ ] `android.yml` grün: Unit-Tests, Lint, Kompilieren der
      instrumentierten Tests, Release-APK sowie der Emulator-Job
      (`connectedDebugAndroidTest`, API 34).
- [x] `release.yml` läuft beim Tag `v*`: prüft Tag gegen `versionName`,
      baut und signiert mit Secrets (sonst `release/`-APK), prüft die
      Signatur, legt APK und SHA-256 als GitHub-Release ab. Manuell erneut
      auslösbar über `workflow_dispatch` mit dem Tag.
- [ ] `gradle/verification-metadata.xml` aktuell (Build bricht sonst).

## 4. Artefakte: APK und AAB

- [ ] GitHub-Release / F-Droid: APK aus `release.yml`.
- [ ] Play: **AAB** ist Pflicht (`./gradlew bundleRelease` →
      `app/build/outputs/bundle/release/app-release.aab`, mit derselben
      signingConfig). Bei Bedarf einen Schritt in `release.yml` ergänzen,
      der zusätzlich `bundleRelease` baut und das AAB hochlädt.
- [ ] R8 bleibt aus (siehe `app/build.gradle.kts`); die APK ist dadurch
      größer, aber der RAR-Entpacker funktioniert. Wer Minify einschaltet,
      muss RAR auf einem Gerät testen.
- [ ] Native Bibliotheken (7-Zip-JBinding) für alle ABIs enthalten; bei
      Play übernimmt das AAB die Aufteilung.

## 5. Play Store

- [ ] Entwicklerkonto, Identitätsprüfung, App anlegen (Paketname
      `com.jdandroid` ist danach fest).
- [ ] Store-Eintrag: Titel, Kurz- und Langbeschreibung (deutsch, englische
      Fassung aus der README-Zusammenfassung), Screenshots (siehe
      `docs/screenshots/README.md`), Icon 512×512, Feature-Grafik.
- [ ] Datenschutzerklärung als öffentliche URL (Inhalt:
      `docs/DATENSCHUTZ.md`), Formulare „Datensicherheit“, „Vordergrunddienst“
      und Berechtigungen nach `docs/PLAY_DATA_SAFETY.md`.
- [ ] targetSdk entspricht der aktuellen Play-Vorgabe (derzeit 36).
- [ ] Interner Test → geschlossener Test → Produktion; erste
      Veröffentlichung braucht bei neuen Konten einen Testlauf mit mehreren
      Testern über mindestens zwei Wochen.
- [ ] **Richtlinienrisiko**: Play kann Downloader für One-Click-Hoster als
      Beihilfe zu Urheberrechtsverletzungen einstufen (Richtlinie
      „Geistiges Eigentum“ / „Unangemessene Inhalte“) oder wegen des
      eingebetteten Browsers mit Captcha-Lösung („Umgehung von
      Sicherheitsmaßnahmen“) beanstanden. Gegenmaßnahmen: Store-Eintrag
      neutral halten (Download-Manager für eigene Hoster-Konten, keine
      Inhaltsquellen nennen, keine Warez-Begriffe), Haftungsausschluss aus
      der README in die Beschreibung, keine Links zu Inhaltsseiten; Free-
      Modus und Captcha-Ansicht im Eintrag nicht bewerben. Eine Ablehnung
      oder spätere Entfernung ist trotzdem möglich – daher GitHub-Release
      und F-Droid als unabhängige Verteilwege beibehalten.
- [ ] Marke: „JDownloader“ gehört der AppWork GmbH. Im Store-Eintrag nur
      als Vergleich („nach dem Vorbild von“) und mit dem Hinweis aus der
      README, dass keine Verbindung besteht; nicht im Titel.

## 6. F-Droid

- [ ] Reproduzierbarer Build aus dem Quelltext; F-Droid signiert selbst
      (eigener Schlüssel) oder übernimmt bei reproduzierbaren Builds die
      Entwicklersignatur.
- [ ] Anti-Features im Metadaten-Eintrag: **`NonFreeDep`** wegen
      7-Zip-JBinding (enthält unRAR-Code mit der unRAR-Lizenzeinschränkung,
      nicht frei im Sinne der FSF) und wegen des DLC-Dienstes des
      JDownloader-Projekts (proprietärer Netzdienst, `NonFreeNet`). Ohne
      diese Kennzeichnung lehnt F-Droid die Aufnahme ab; alternativ RAR-
      Unterstützung und DLC-Import in einem F-Droid-Flavor abschaltbar
      machen.
- [ ] Vorgefertigte Binärdateien (natives 7-Zip) müssen aus dem Quelltext
      gebaut werden oder der Eintrag bleibt bei `NonFreeDep`.
- [ ] Metadaten (`fdroiddata`, YAML): Lizenz Apache-2.0, Quelle, Issues,
      Changelog-Link, Build-Rezept mit `gradle: [yes]`, Tag-Auslöser
      `v*`.
- [ ] Alternativ eigenes F-Droid-Repo (z. B. über GitHub Pages) mit den
      selbst signierten APKs – keine Anti-Feature-Pflicht, aber Nutzer müssen
      das Repo manuell hinzufügen.

## 7. Nach der Veröffentlichung

- [ ] `CLAUDE.md`: Regel „Keystore im Repo“ streichen, Versionsschema
      `0.1.x`, GitHub-Release als Verteilweg; `release/` bleibt vorerst.
- [x] `SECURITY.md`: Signatur-Fingerabdruck, Meldeweg.
- [x] README-Installationsabschnitt: GitHub-Release-Link.
- [ ] README-Installationsabschnitt: Play- und F-Droid-Links, sobald
      vorhanden.
