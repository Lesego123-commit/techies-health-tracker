import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)

    // Room's compiler. KSP rather than kapt: it is the supported processor for
    // Kotlin 2.x and roughly twice as fast on this module.
    alias(libs.plugins.ksp)
}

// The Google Services plugin hard-fails the build when google-services.json is absent, which
// would stop anyone cloning this repository from compiling it before they have set up Firebase.
// Applying it only when the file is present keeps the project buildable out of the box and
// lights Firebase up the moment the file is dropped in. See README section 2.
val googleServicesConfigured = file("google-services.json").exists()

if (googleServicesConfigured) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

// The API base URL is read from local.properties so each developer can point at their own
// machine, the emulator loopback, or the deployed Azure instance without editing a tracked file.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun apiBaseUrl(default: String): String =
    (localProperties.getProperty("apiBaseUrl") ?: default).let {
        // Retrofit requires a trailing slash on the base URL and throws at construction without
        // one. Normalising here means a teammate cannot lose an afternoon to that message.
        if (it.endsWith("/")) it else "$it/"
    }

// The shared debug keystore, committed to the repository on purpose.
//
// Google Sign-In authorises an app by the SHA-1 fingerprint of the certificate that signed it.
// Left to itself, the Android Gradle plugin generates a debug keystore per machine — so Lesego's
// builds, Senamiso's builds and every CI run would each have a different fingerprint, and only
// whichever one happened to be registered in Firebase could sign in. The others fail with
// ApiException: 10, which says nothing about the real cause and is miserable to diagnose.
//
// One shared keystore gives every build the same fingerprint, so a single SHA-1 registered in
// Firebase covers all of us and the APK that CI produces.
//
// Committing a *debug* keystore is safe and common practice: it signs nothing that can be
// installed over a real release, and its password is the well-known Android default. A RELEASE
// keystore is a different matter entirely and must never be committed — .gitignore still excludes
// every other *.jks and *.keystore for exactly that reason.
val sharedDebugKeystore = rootProject.file("keystore/techies-debug.keystore")

// Release signing.
//
// Everything needed to sign a release is supplied from outside the repository: a keystore.properties
// file for a local build, or the matching environment variables in CI. Neither is committed, and
// .gitignore already excludes both plus every *.jks and *.keystore other than the debug one.
//
// When nothing is configured the release build still runs and produces an unsigned APK, which is
// what lets CI verify that R8 has not broken anything without anyone handing it the upload key.
// Deliberately not a hard failure: a teammate should be able to check that the release build
// compiles without being given the signing material to do it.
val releaseKeystoreProperties: Properties? =
    rootProject.file("keystore.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

fun signingValue(propertyName: String, environmentName: String): String? =
    releaseKeystoreProperties?.getProperty(propertyName) ?: System.getenv(environmentName)

val releaseStorePath = signingValue("storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")

val releaseSigningConfigured =
    !releaseStorePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "za.co.techies.healthtracker"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        getByName("debug") {
            if (sharedDebugKeystore.exists()) {
                storeFile = sharedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                // Falling back keeps the build working, but Google Sign-In will not, so it is
                // said loudly rather than left to be discovered on a handset.
                logger.warn(
                    "WARNING: keystore/techies-debug.keystore is missing. Falling back to the " +
                        "per-machine debug keystore, which has a different SHA-1 — Google Sign-In " +
                        "will fail on this build. Restore the file from version control."
                )
            }
        }

        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword

                // Both signature schemes. v1 is what lets the APK install on older devices, and
                // minSdk 26 is still inside the range where leaving it off matters.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "za.co.techies.healthtracker"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("Boolean", "FIREBASE_CONFIGURED", googleServicesConfigured.toString())
    }

    buildTypes {
        debug {
            // The deployed API, so a fresh clone builds and runs without any local setup — which
            // is what a teammate or a marker gets when they open this for the first time.
            //
            // To develop against an API running on your own machine, add a line to
            // local.properties instead of editing this file:
            //     apiBaseUrl=http://10.0.2.2:5192/
            // 10.0.2.2 is the emulator's alias for the host's localhost; a physical phone needs
            // the machine's LAN address.
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${apiBaseUrl("https://techies-health-api.azurewebsites.net/")}\""
            )
        }

        release {
            // Applied only when the signing material was supplied; otherwise the build produces
            // an unsigned APK rather than failing, so CI can still check that R8 left the app
            // working without holding the upload key.
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle(
                    "No release signing configured (keystore.properties or RELEASE_STORE_FILE). " +
                        "Producing an unsigned release APK."
                )
                null
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${apiBaseUrl("https://techies-health-api.azurewebsites.net/")}\""
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true

            // Local unit tests run against a stubbed android.jar whose methods throw by default.
            // The network layer logs through android.util.Log, so without this every test that
            // exercises an error path fails on the logging rather than on the behaviour it is
            // checking. Returning defaults makes those calls no-ops instead.
            isReturnDefaultValues = true
        }
    }
}

// Room writes the database schema here as JSON, and it is committed. A migration can only be
// tested against the version it migrates from, and that version has to be recorded somewhere —
// without this the export flag is silently a no-op.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ---- AndroidX and Compose ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---- Networking ----
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // ---- Firebase and Google sign-in ----
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    // ---- Biometric unlock (POE requirement) ----
    implementation(libs.androidx.biometric)

    // ---- Offline storage and background sync (POE requirement) ----
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)

    // ---- Unit tests ----
    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.retrofit)
    testImplementation(libs.retrofit.serialization)

    // ---- Instrumented tests ----
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Supplies the empty activity createComposeRule launches into.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
