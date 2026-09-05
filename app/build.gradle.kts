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
 * Release signing: environment variables KEYSTORE_FILE, KEYSTORE_PASSWORD,
 * KEY_ALIAS, KEY_PASSWORD first, else keystore.properties in the project root
 * (storeFile relative to the root or absolute). Without both the APK stays
 * unsigned.
 */
fun releaseSigning(): Map<String, String>? {
    // Values pasted into CI secrets often carry a trailing newline.
    fun env(name: String) = System.getenv(name)?.trim().orEmpty()
    val envStore = env("KEYSTORE_FILE")
    if (envStore.isNotEmpty()) {
        return mapOf(
            "storeFile" to envStore,
            "storePassword" to env("KEYSTORE_PASSWORD"),
            "keyAlias" to env("KEY_ALIAS"),
            "keyPassword" to env("KEY_PASSWORD"),
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
        // versionCode must grow with every release or the installer refuses the update.
        versionCode = 41
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Exported Room schemas as test assets for MigrationTest
    sourceSets["androidTest"].assets.srcDirs("$projectDir/schemas")

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

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
            // R8 off: the shrinker removed the RAR callback classes
            // (Extractor.RarOpenCallback, ISequentialOutStream lambda) that are
            // only reached from native 7-Zip code via JNI, so release builds
            // could not extract RAR.
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
        // Dependencies are updated deliberately, not on every lint run.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    androidResources {
        // Only the app's own languages; AndroidX translations of other
        // languages stay out.
        localeFilters += listOf("de", "en")
    }

    // Name the release APK by version instead of app-release.apk
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
    // Room 2.8 (room-migration/room-testing) needs kotlinx-serialization 1.8.1.
    // Without this constraint consistent resolution pulls the test classpath
    // back to the app's 1.7.3 and the migration tests fail with
    // AbstractMethodError (GeneratedSerializer).
    constraints {
        implementation(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
    }
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
