package de.ayutv.desktop.input

/**
 * Tasten einer Fernbedienung (Fire TV und andere Bluetooth-Fernbedienungen) und was Windows davon liefert.
 * Reines Kotlin ohne Windows- und Compose-Abhängigkeit, damit sich die Zuordnung testen lässt.
 */
enum class RemoteButton(val label: String, val repeats: Boolean = false) {
    UP("▲", repeats = true),
    DOWN("▼", repeats = true),
    LEFT("◀", repeats = true),
    RIGHT("▶", repeats = true),
    OK("OK"),
    BACK("Zurück"),
    HOME("Home"),
    MENU("Menü ☰"),
    PLAY_PAUSE("Play/Pause ⏯"),
    PLAY("Play"),
    PAUSE("Pause"),
    STOP("Stopp"),
    REWIND("Zurückspulen ⏪", repeats = true),
    FAST_FORWARD("Vorspulen ⏩", repeats = true),
    NEXT("Nächster ⏭"),
    PREVIOUS("Vorheriger ⏮"),
    CHANNEL_UP("Sender +", repeats = true),
    CHANNEL_DOWN("Sender −", repeats = true),
    LAST_CHANNEL("Letzter Sender"),
    INFO("Info"),
    GUIDE("TV-Guide / Live"),
    CAPTIONS("Untertitel"),
    SEARCH("Suche"),
    SETTINGS("Einstellungen"),
    RED("Rot"),
    GREEN("Grün"),
    YELLOW("Gelb"),
    BLUE("Blau"),
    ;

    companion object {
        /**
         * HID-Seite „Consumer“ (0x0C): So melden die Fire-TV-Fernbedienung und die meisten anderen Bluetooth-
         * Fernbedienungen ihre Sondertasten. Steuerkreuz und OK kommen je nach Modell als Tastatur (Pfeiltasten,
         * Enter) oder als „Menu Up/Down/Left/Right/Pick“ hier an.
         */
        fun fromConsumerUsage(usage: Int): RemoteButton? = when (usage) {
            0x40 -> MENU            // Menu
            0x41 -> OK              // Menu Pick
            0x42 -> UP              // Menu Up
            0x43 -> DOWN            // Menu Down
            0x44 -> LEFT            // Menu Left
            0x45 -> RIGHT           // Menu Right
            0x46 -> BACK            // Menu Escape
            0x60 -> INFO            // Data On Screen
            0x61 -> CAPTIONS        // Closed Caption
            0x69 -> RED             // Red Menu Button
            0x6A -> GREEN
            0x6B -> BLUE
            0x6C -> YELLOW
            0x83 -> LAST_CHANNEL    // Recall Last
            0x89 -> GUIDE           // Media Select TV (Taste „Live“)
            0x8D -> GUIDE           // Media Select Program Guide
            0x9C -> CHANNEL_UP      // Channel Increment
            0x9D -> CHANNEL_DOWN    // Channel Decrement
            0xB0 -> PLAY
            0xB1 -> PAUSE
            0xB3 -> FAST_FORWARD
            0xB4 -> REWIND
            0xB5 -> NEXT            // Scan Next Track
            0xB6 -> PREVIOUS        // Scan Previous Track
            0xB7 -> STOP
            0xCD -> PLAY_PAUSE
            0x209 -> INFO           // AC Properties
            0x221 -> SEARCH         // AC Search
            0x223 -> HOME           // AC Home
            0x224 -> BACK           // AC Back
            else -> null
        }

        /**
         * Virtuelle Windows-Tastencodes, die Windows selbst aus Consumer-Tasten macht (und sonst an andere Programme
         * weitergibt: Browser öffnet die Startseite, Streaming-App pausiert). Solange DnA-TV vorne ist, fängt die App sie ab.
         */
        fun fromWindowsVirtualKey(vk: Int): RemoteButton? = when (vk) {
            0xA6 -> BACK            // VK_BROWSER_BACK
            0xAC -> HOME            // VK_BROWSER_HOME
            0xAA -> SEARCH          // VK_BROWSER_SEARCH
            0xB0 -> NEXT            // VK_MEDIA_NEXT_TRACK
            0xB1 -> PREVIOUS        // VK_MEDIA_PREV_TRACK
            0xB2 -> STOP            // VK_MEDIA_STOP
            0xB3 -> PLAY_PAUSE      // VK_MEDIA_PLAY_PAUSE
            0xFA -> PLAY            // VK_PLAY
            else -> null
        }

        /** Consumer-Tasten, aus denen Windows einen Tastencode macht (die kommen über [fromWindowsVirtualKey]). */
        val consumerUsagesWithVirtualKey = setOf(0xB5, 0xB6, 0xB7, 0xCD, 0x221, 0x223, 0x224)

        /** Amazon (Fire-TV-Fernbedienungen) in der Gerätekennung „VID_1949“. */
        fun isAmazon(deviceName: String) = deviceName.contains("VID_1949", ignoreCase = true) ||
            deviceName.contains("VID&021949", ignoreCase = true) || deviceName.contains("VID&01949", ignoreCase = true)

        /**
         * Gedrückte Consumer-Tasten aus einem HID-Bericht, wenn Windows den Bericht nicht selbst zerlegen kann:
         * die meisten Fernbedienungen schicken die Taste als 16-Bit-Zahl (niedriges Byte zuerst) nach der Berichtsnummer.
         */
        fun usagesFromReport(report: ByteArray, hasReportId: Boolean = true): Set<Int> {
            val start = if (hasReportId) 1 else 0
            val result = HashSet<Int>()
            var i = start
            while (i + 1 < report.size) {
                val usage = (report[i].toInt() and 0xFF) or ((report[i + 1].toInt() and 0xFF) shl 8)
                if (usage != 0) result += usage
                i += 2
            }
            return result
        }
    }
}

/** Wer eine Taste geschickt hat (für die Anzeige „Fernbedienung erkannt“ in den Einstellungen). */
data class RemoteSource(val deviceName: String, val amazon: Boolean) {
    val label: String get() = if (amazon) "Fire-TV-Fernbedienung" else "Bluetooth-/HID-Gerät"
}
