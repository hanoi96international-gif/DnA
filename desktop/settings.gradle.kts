// Eigener Build für die Windows-App: braucht kein Android-Plugin und stört den Fire-TV-Build nicht.
// Bauen: ./gradlew -p desktop run | test | packageMsi
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "DnA-TV-Windows"
