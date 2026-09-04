import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
        versionCode = 32
        versionName = "0.0.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Exportierte Room-Schemata als Test-Assets: Grundlage fuer MigrationTest
    sourceSets["androidTest"].assets.srcDirs("$projectDir/schemas")

    // Room-Schema exportieren: Grundlage fuer nachvollziehbare Migrationen
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    signingConfigs {
        create("release") {
            // Keystore fuer Eigenbedarf, liegt bewusst im Repo (kein Play-Store-Release).
            storeFile = file("keystore/release.jks")
            storePassword = "jdandroid"
            keyAlias = "jdandroid"
            keyPassword = "jdandroid"
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
            signingConfig = signingConfigs.getByName("release")
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
    testOptions {
        // Noetig: ClickNLoadServerTest laeuft gegen android.util.Log (ohne
        // Stub wirft die Methode "not mocked").
        unitTests.isReturnDefaultValues = true
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
    implementation(libs.nanohttpd)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zip4j)
    implementation(libs.sevenzipjbinding)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
