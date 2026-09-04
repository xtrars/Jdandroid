plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jdandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jdandroid"
        minSdk = 26
        targetSdk = 35
        // versionCode muss bei jedem Release steigen, sonst verweigert der
        // Paketinstaller das Update ("App nicht installiert").
        versionCode = 8
        versionName = "1.3.4"
    }

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
            // Shrinking/Optimierung an, Obfuskation aus (siehe proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
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
    testImplementation("junit:junit:4.13.2")
}
