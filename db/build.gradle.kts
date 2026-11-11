// Datenbank (Room) als eigenes Modul: Die Code-Erzeugung (KSP) läuft nur, wenn sich hier etwas ändert.
// Achtung: Room prüft jede @Query beim Bauen mit einem SQL-Parser, der bei langen OR-Ketten exponentiell langsam
// wird (40 Bedingungen: über eine Stunde). Solche Abfragen als @RawQuery schreiben (siehe sportBetween).
plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "de.ayutv.data.db"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Das Room-Plugin verwaltet die Schema-Dateien je Build-Variante.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val room = "2.8.5"
    api("androidx.room:room-runtime:$room")
    api("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
