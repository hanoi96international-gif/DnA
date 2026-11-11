plugins {
    // AGP 9 bringt Kotlin selbst mit ("built-in Kotlin"); die Kotlin-Version kommt über die Compose-/Serialization-Plugins.
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.5" apply false
}
