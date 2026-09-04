# Lizenzen der eingebundenen Bibliotheken

JDAndroid selbst steht unter der [Apache-Lizenz 2.0](LICENSE). Diese Datei
nennt alle Bibliotheken, die in die App eingebunden werden (Laufzeit, Tests
und Build), mit der im Projekt verwendeten Version und ihrer Lizenz. Die
Angaben stammen aus `gradle/libs.versions.toml`, `app/build.gradle.kts` und
den POM-Dateien der jeweiligen Artefakte; die Prüfsummen aller Artefakte
stehen in `gradle/verification-metadata.xml`.

## Lizenzverträglichkeit

Alle Abhängigkeiten stehen unter permissiven Lizenzen (Apache-2.0, BSD-3-Clause,
0BSD; EPL-1.0 nur in den Unit-Tests, die nicht in die APK gelangen). Die einzige
Copyleft-Komponente ist 7-Zip-JBinding-4Android unter der LGPL-2.1: Sie wird
unverändert als eigenständige Bibliothek (Java-Klassen plus native `.so`, per
JNI geladen) eingebunden, ihr Quelltext ist öffentlich verfügbar, und weil R8
im Release-Build ausgeschaltet ist (`isMinifyEnabled = false`), lässt sich die
Bibliothek in der APK unverändert austauschen – damit ist die LGPL-2.1 mit der
Apache-2.0-Projektlizenz vereinbar, ohne dass JDAndroid selbst unter die LGPL
oder GPL fiele. GPL-lizenzierte Bestandteile gibt es nicht. Zu beachten bleibt
die unRAR-Einschränkung des in 7-Zip enthaltenen RAR-Codes: Aus diesem Code darf
kein RAR-kompatibler Packer gebaut werden; JDAndroid entpackt nur.

## Laufzeit-Abhängigkeiten (in der APK enthalten)

| Bibliothek | Koordinate / Version | Lizenz | Zweck in JDAndroid |
|---|---|---|---|
| 7-Zip-JBinding-4Android | `com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03` (JitPack) | LGPL-2.1; enthält 7-Zip 16.02 (LGPL-2.1) und unRAR-Code mit unRAR-Einschränkung | RAR4/RAR5 entpacken (`engine/Extractor.kt`), inkl. Multipart und Passwörter |
| zip4j | `net.lingala.zip4j:zip4j:2.11.6` | Apache-2.0 | ZIP entpacken, auch AES-verschlüsselt |
| Apache Commons Compress | `org.apache.commons:commons-compress:1.28.0` | Apache-2.0 | 7z-Archive entpacken |
| XZ for Java | `org.tukaani:xz:1.12` | 0BSD | LZMA/XZ-Streams für Commons Compress |
| NanoHTTPD | `org.nanohttpd:nanohttpd:2.3.1` | BSD-3-Clause | Click'n'Load-Server auf 127.0.0.1:9666 (`container/ClickNLoadServer.kt`) |
| OkHttp | `com.squareup.okhttp3:okhttp:4.12.0` | Apache-2.0 | HTTP-Zugriff auf Hoster-APIs und Dateien (`hoster/Hoster.kt`) |
| Okio (transitiv) | `com.squareup.okio:okio:3.9.1` (OkHttp verlangt 3.6.0, AndroidX DataStore hebt auf 3.9.1 an) | Apache-2.0 | I/O-Grundlage von OkHttp und DataStore |
| kotlinx.coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0` | Apache-2.0 | Nebenläufigkeit in Engine und UI |
| Kotlin-Standardbibliothek | `org.jetbrains.kotlin:kotlin-stdlib:2.3.21` | Apache-2.0 | Sprache |
| AndroidX Core KTX | `androidx.core:core-ktx:1.18.0` | Apache-2.0 | Plattform-Erweiterungen |
| AndroidX Lifecycle | `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose:2.10.0` | Apache-2.0 | ViewModels, Lifecycle-Bindung |
| AndroidX Activity Compose | `androidx.activity:activity-compose:1.13.0` | Apache-2.0 | Compose-Einstieg in `MainActivity` |
| AndroidX Navigation Compose | `androidx.navigation:navigation-compose:2.9.8` | Apache-2.0 | Navigation zwischen den Bildschirmen |
| Jetpack Compose | BOM `androidx.compose:compose-bom:2026.06.01` mit `ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `material-icons-core` | Apache-2.0 | Oberfläche, Material 3 / Material You |
| AndroidX Room | `androidx.room:room-runtime:2.8.4` | Apache-2.0 | Datenbank für Pakete, Downloads, Konten (`data/Db.kt`) |
| AndroidX DataStore | `androidx.datastore:datastore-preferences:1.2.1` | Apache-2.0 | Einstellungen (`data/SettingsRepository.kt`) |
| AndroidX DocumentFile | `androidx.documentfile:documentfile:1.1.0` | Apache-2.0 | Zielordner über Storage Access Framework |

## Nur im Debug-Build

| Bibliothek | Koordinate / Version | Lizenz | Zweck |
|---|---|---|---|
| Compose UI Tooling | `androidx.compose.ui:ui-tooling` (BOM 2026.06.01) | Apache-2.0 | Vorschau in Android Studio |

## Nur in Tests (nicht in der APK)

| Bibliothek | Koordinate / Version | Lizenz | Zweck |
|---|---|---|---|
| JUnit 4 | `junit:junit:4.13.2` | EPL-1.0 | Unit-Tests unter `app/src/test` |
| AndroidX Test Runner | `androidx.test:runner:1.7.0` | Apache-2.0 | Instrumentierter `MigrationTest` |
| AndroidX Test Ext JUnit | `androidx.test.ext:junit:1.3.0` | Apache-2.0 | JUnit-Anbindung für androidTest |
| Room Testing | `androidx.room:room-testing:2.8.4` | Apache-2.0 | `MigrationTestHelper` für Schema-Migrationen |

## Nur im Build (Werkzeuge, nicht in der APK)

| Werkzeug | Version | Lizenz | Zweck |
|---|---|---|---|
| Android Gradle Plugin | 8.13.2 | Apache-2.0 | Android-Build |
| Kotlin Gradle Plugin, Compose Compiler Plugin | 2.3.21 | Apache-2.0 | Kotlin- und Compose-Kompilierung |
| Kotlin Symbol Processing (KSP) | 2.3.11 | Apache-2.0 | Room-Annotation-Verarbeitung |
| AndroidX Room Compiler | 2.8.4 | Apache-2.0 | Generiert DAOs und Schema-Export nach `app/schemas/` |
| Gradle | 8.14.3 (Wrapper) | Apache-2.0 | Build-System |

## Fremddienste und Protokolle

Keine Bibliotheken, aber lizenz- und namensrechtlich erwähnenswert:

- **JDownloader** ist ein Produkt der AppWork GmbH. JDAndroid verwendet keinen
  Quelltext von JDownloader und ist weder ein Fork noch ein offizieller Ableger.
  Übernommen sind nur zwei offene Schnittstellen: das **DLC-Containerformat**
  (Entschlüsselung über den Webdienst `service.jdownloader.org/dlcrypt`, wobei
  die Container-Inhalte das Gerät verlassen) und das
  **Click'n'Load-2-Protokoll** (`/flash/addcrypted2`, `/flash/add`).
- Die Hoster-APIs von ddownload, Rapidgator und 1fichier werden gemäß deren
  öffentlicher Dokumentation und Nutzungsbedingungen angesprochen.

## Pflege

Bei jeder Änderung an `gradle/libs.versions.toml` oder an den Abhängigkeiten in
`app/build.gradle.kts` diese Datei und die Lizenztabelle in der README
nachziehen. Die Lizenz eines Artefakts steht im `<licenses>`-Block seiner
POM-Datei im Gradle-Cache
(`~/.gradle/caches/modules-2/files-2.1/<Gruppe>/<Name>/<Version>/…/*.pom`).

## Hoster-Symbole

Die Symbole der Hoster in der Kontenansicht (`app/src/main/res/drawable-nodpi/hoster_*.png`)
stammen aus den öffentlichen Website-Icons von rapidgator.net, 1fichier.com und
ddownload.com. Sie sind Kennzeichen der jeweiligen Betreiber und werden
ausschließlich zur Identifikation des Dienstes verwendet; sie stehen nicht
unter der Projektlizenz. Auf Wunsch eines Betreibers werden sie entfernt.
