plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val keystore = env("KS_PATH")?.let { file(it) }?.takeIf { it.exists() }
// pannello Kairos (lo stesso di KAIROS TV); la variabile KAIROS_SERVER_URL del repository, se c'è, lo sostituisce
val serverUrl = (env("KAIROS_SERVER_URL") ?: "https://kairos-panel.mariopiccolo8562.workers.dev").trimEnd('/').replace("\"", "")
// aggiornamenti: direttamente dalle release di questo repository (Kairos XC è separata dalle altre app Kairos)
val updateUrl = "https://github.com/" + (env("GITHUB_REPOSITORY") ?: "mariopiccolo8562-lang/kairos-xc") + "/releases/latest/download/kairos-xc.json"

android {
    namespace = "com.kairos.xc"
    compileSdk = 35

    defaultConfig {
        // app separata da Kairos Pro e Kairos Lite: si installa accanto, con aggiornamenti suoi
        applicationId = env("XC_APP_ID") ?: "com.kairos.xc"
        // 22 = Android 5.1: Fire TV Stick di 1ª e 2ª generazione (Fire OS 5) comprese
        minSdk = 22
        targetSdk = 35
        versionCode = env("VERSION_CODE")?.toInt() ?: 2
        versionName = env("VERSION_NAME") ?: "1.0.1"
        resValue("string", "app_name", env("XC_APP_NAME") ?: "Kairos XC")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        // Fire TV / Android TV / telefoni sono ARM: niente x86, APK più piccolo
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // testi dell'app solo in italiano (sono nel codice): delle librerie restano le risorse predefinite e quelle italiane,
        // via le traduzioni in altre 70 lingue (risorse dell'APK più leggere)
        resourceConfigurations += setOf("it")
    }

    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = keystore
                storePassword = env("KS_PASS")
                keyAlias = env("KEY_ALIAS")
                keyPassword = env("KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // la libreria WireGuard usa java.time / Optional / stream: servono anche su Android 5-7
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // poche librerie, tutte compatibili con Android 5.1
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.videolan.android:libvlc-all:3.6.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // avvio più rapido: Android compila subito le parti usate all'apertura (regole in src/main/baseline-prof.txt)
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // 1.0.20250531 = ultima con minSdk 21 (stesso motore wireguard-go della versione successiva)
    implementation("com.wireguard.android:tunnel:1.0.20250531")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
