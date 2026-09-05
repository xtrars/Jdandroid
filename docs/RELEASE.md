# Veröffentlichung: Checkliste

Gilt für die erste öffentliche Version (Play Store, GitHub-Release, ggf.
F-Droid). Bis dahin bleibt das Schema `0.0.x` und der Ablauf aus `CLAUDE.md`
(APK in `release/`, Keystore im Repo).

## 1. Version

- [ ] Versionsschema auf `1.0.0` umstellen (`versionName` in
      `app/build.gradle.kts`, Semantic Versioning: Fehlerbehebung → Patch,
      Funktion → Minor, Bruch → Major). `versionCode` zählt einfach weiter
      hoch (aktueller Stand + 1); er darf nie kleiner werden, sonst verweigern
      Android und Play das Update.
- [ ] `CHANGELOG.md` mit dem Eintrag zur Version, README-Badge und
      Installationsabschnitt anpassen.
- [ ] `git tag v<versionName>` auf dem Release-Commit; der Tag muss exakt
      `v` + `versionName` sein, sonst bricht `release.yml` ab.

## 2. Signierung und Keystore

- [ ] **Vor der Veröffentlichung muss der bisherige Keystore aus dem Repo
      und aus der Git-Historie verschwinden** – ein öffentlicher Keystore mit
      bekanntem Passwort erlaubt jedem, „Updates“ mit derselben Signatur zu
      bauen. Vorgehen: neuen Keystore erzeugen (`keytool -genkeypair -v
      -keystore release.jks -alias jdandroid -keyalg RSA -keysize 4096
      -validity 10000`), alten mit `git rm app/keystore/release.jks`
      entfernen, Historie mit `git filter-repo --path app/keystore/release.jks
      --invert-paths` bereinigen, Force-Push, alle Klone neu ziehen,
      `SECURITY.md` und README anpassen. Nutzer bisheriger `0.0.x`-APKs
      müssen die App einmal deinstallieren (Signaturwechsel).
- [ ] Neuen Keystore **nur** als Repository-Secrets hinterlegen:
      `KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`,
      `KEY_ALIAS`, `KEY_PASSWORD`. `release.yml` dekodiert ihn in eine
      temporäre Datei und gibt ihn über `KEYSTORE_FILE` & Co. an
      `signingConfigs.release`; ohne Secrets fällt der Build auf den
      Keystore im Repo zurück (dann muss in `app/build.gradle.kts` der
      Fallback entfernt werden, sobald der Keystore weg ist).
- [ ] Keystore und Passwörter außerhalb von GitHub sichern (Passwort-
      Manager, Offline-Kopie). Ein verlorener Keystore bedeutet: keine
      Updates mehr für bestehende Installationen. Für Play alternativ
      „Play App Signing“ nutzen (Upload-Key lokal, App-Signing-Key bei
      Google); dann ist `KEYSTORE_*` der Upload-Key.
- [ ] Signatur der fertigen Datei prüfen: `apksigner verify --print-certs`
      (macht `release.yml`) und den Zertifikats-Fingerabdruck in
      `SECURITY.md` veröffentlichen.

## 3. CI

- [ ] `android.yml` grün: Unit-Tests, Lint, Kompilieren der
      instrumentierten Tests, Release-APK sowie der Emulator-Job
      (`connectedDebugAndroidTest`, API 34).
- [ ] `release.yml` läuft beim Tag `v*`: prüft Tag gegen `versionName`,
      baut, prüft die Signatur, legt APK und SHA-256 als GitHub-Release ab.
      Manuell erneut auslösbar über `workflow_dispatch` mit dem Tag.
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

- [ ] `CLAUDE.md`: Regeln „APK in `release/`“ und „Keystore im Repo“
      streichen bzw. auf GitHub-Releases umstellen; Versionsschema-Absatz
      ändern.
- [ ] `SECURITY.md`: Signatur-Fingerabdruck, Meldeweg.
- [ ] README-Installationsabschnitt: Play-, GitHub-Release- und F-Droid-Links.
