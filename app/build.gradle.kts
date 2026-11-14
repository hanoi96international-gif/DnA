plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "de.ayutv"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.ayutv"
        // Fire OS 6 entspricht API 25: alle Fire TV Sticks ab 2018. ONNX Runtime verlangt API 24.
        minSdk = 24
        targetSdk = 35
        versionCode = 156
        versionName = "0.61.23"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Fire TV: ARM (32/64 Bit). x86_64 nur für den Emulator-Test in der CI.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        // Bewusst KEIN eingebauter Schlüssel (OpenSubtitles, DeepL o. Ä.): Die App enthält keinerlei Zugangsdaten.
        // Schlüssel kommen nur vom Nutzer (Einrichtung per Handy) oder aus seiner übertragenen Sicherung.
    }

    // Fester Schlüssel für alle Builds (Debug, Release, CI): Jede neue Version lässt sich über die
    // alte installieren, Playlists und Einstellungen bleiben erhalten. Zum Sideloaden gedacht;
    // für einen Store einen eigenen, geheimen Schlüssel verwenden.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/ayutv.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Ohne R8: Mit R8 stürzt der Übersetzer in ONNX Runtime ab („JNI DETECTED ERROR … java_class == null“,
            // Fire TV 01.11. und wieder 12.11. mit 0.61.16 – trotz Keep-Regeln), und die Menüs wurden dadurch nicht
            // messbar flüssiger (Kurzbericht 12.11.: 31,6 % statt 28 % langsame Bilder).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        // Handy-Fassung (DnA-TV-Handy.apk): wie release, aber ohne das Recht, Apps zu installieren. Selbst
        // aktualisierende Apps außerhalb des Play Store stuft Google Play Protect oft als Bedrohung ein; auf dem
        // Handy öffnet ein Update deshalb nur den Download im Browser. Fire TV hat kein Play Protect.
        create("phone") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }

    // Native Bibliotheken komprimiert ablegen: hält die APK klein (ONNX Runtime, FFmpeg).
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Mehrere native Teile (ONNX Runtime, FFmpeg u. a.) bringen dieselbe C++-Laufzeit mit: nur eine einpacken.
            pickFirsts += "**/libc++_shared.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Release-Builds nicht an Lint-Hinweisen scheitern lassen.
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val compose = "1.12.1"
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.ui:ui:$compose")
    // Die Material-Icons werden seit 1.7.8 nicht mehr weiterentwickelt.
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Kindersicherung: sperren, sobald die App in den Hintergrund geht
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    // Playlists und TV-Guide im Hintergrund aktuell halten
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Kompiliert beim Sideloaden die mitgelieferten Baseline-Profile von Compose: flüssigere UI ab dem ersten Start.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val media3 = "1.11.1"
    // DNA Player: eigene Engine (dna/engine); von Media3 nur die gemeinsame Player-Schnittstelle und die Bildfläche/
    // Untertitel-Anzeige – keine ExoPlayer-Engine mehr.
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Chromecast vom Handy aus (nur mit Google-Play-Diensten; auf Fire TV unbenutzt).
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    // Gerätewahl (MediaRouteChooserDialog) ist ein AppCompat-Dialog: AppCompat direkt einbinden.
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Datenbank (Room) im eigenen Modul, siehe db/build.gradle.kts
    implementation(project(":db"))

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    // Untertitel-Übersetzung auf dem Gerät: kostenlos, ohne Konto, Sprachpakete werden einmalig geladen.
    // QR-Code für die Einrichtung per Handy
    implementation("com.google.zxing:core:3.5.4")
    // Übersetzung auf dem Gerät (OPUS-MT als ONNX)
    // 1.22: neuere Versionen (1.30) stürzen auf 32-Bit-ARM (Fire TV Stick 4K) beim Laden jedes Modells ab;
    // 1.16–1.22 laufen dort (CI-Job arm32). Per -PortVersion überschreibbar.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:${(project.findProperty("ortVersion") as String?) ?: "1.22.0"}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val coil = "3.6.3"
    implementation("io.coil-kt.coil3:coil-compose:$coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coil")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
