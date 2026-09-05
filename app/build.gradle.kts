import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Signaturdaten fuer den Release-Build: zuerst die Umgebungsvariablen
 * KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD, sonst die Datei
 * keystore.properties im Projektstamm (Schluessel storeFile, storePassword,
 * keyAlias, keyPassword; storeFile relativ zum Projektstamm oder absolut).
 * Fehlt beides, liefert die Funktion null und die APK bleibt unsigniert.
 */
fun releaseSigning(): Map<String, String>? {
    val envStore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    if (envStore != null) {
        return mapOf(
            "storeFile" to envStore,
            "storePassword" to (System.getenv("KEYSTORE_PASSWORD") ?: ""),
            "keyAlias" to (System.getenv("KEY_ALIAS") ?: ""),
            "keyPassword" to (System.getenv("KEY_PASSWORD") ?: ""),
        )
    }
    val datei = rootProject.file("keystore.properties")
    if (!datei.isFile) return null
    val p = Properties().apply { datei.inputStream().use { p -> load(p) } }
    val store = p.getProperty("storeFile")?.trim().orEmpty()
    if (store.isEmpty()) return null
    val storeDatei = File(store).let { f -> if (f.isAbsolute) f else rootProject.file(store) }
    return mapOf(
        "storeFile" to storeDatei.absolutePath,
        "storePassword" to p.getProperty("storePassword", ""),
        "keyAlias" to p.getProperty("keyAlias", ""),
        "keyPassword" to p.getProperty("keyPassword", ""),
    )
}

android {
    namespace = "com.jdandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jdandroid"
        minSdk = 26
        targetSdk = 36
        // versionCode muss bei jedem Release steigen, sonst verweigert der
        // Paketinstaller das Update ("App nicht installiert").
        versionCode = 41
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Exportierte Room-Schemata als Test-Assets: Grundlage fuer MigrationTest
    sourceSets["androidTest"].assets.srcDirs("$projectDir/schemas")

    // Room-Schema exportieren: Grundlage fuer nachvollziehbare Migrationen
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // Signierung der Release-APK. Der Keystore liegt nicht im Repository.
    // Reihenfolge: Umgebungsvariablen (CI mit Secrets, siehe
    // .github/workflows/release.yml) -> keystore.properties im Projektstamm
    // (lokal, in .gitignore) -> ohne beides entsteht eine unsignierte APK.
    val signing = releaseSigning()
    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 bewusst AUS: der Shrinker hat die RAR-Rueckrufklasse
            // (Extractor.RarOpenCallback, ISequentialOutStream-Lambda) entfernt,
            // weil sie nur aus nativem 7-Zip-Code per JNI aufgerufen wird -
            // Ergebnis: Release-Builds entpackten kein RAR mehr. Die Keep-Regeln
            // in proguard-rules.pro decken das inzwischen ab, aber ohne Geraete-
            // test bleibt der sichere Weg ein unveraenderter Build.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        // Versionshinweise absichtlich aus: Abhaengigkeiten werden gezielt
        // aktualisiert, nicht bei jedem Lint-Lauf.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    androidResources {
        // Nur die eigenen Sprachen einpacken (AndroidX-Uebersetzungen anderer
        // Sprachen bleiben draussen); der Release-Lint prueft, dass jeder
        // Schluessel in values/ und values-en/ steht.
        localeFilters += listOf("de", "en")
    }

    // Release-APK nach Version benennen (statt app-release.apk)
    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "JDAndroid-${versionName}.apk"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zip4j)
    implementation(libs.sevenzipjbinding)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
