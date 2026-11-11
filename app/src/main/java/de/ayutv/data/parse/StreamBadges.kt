package de.ayutv.data.parse

/**
 * Kurze Kennzeichen für die Infoleiste: Auflösung, HDR-Art, flüssige Bildrate, Tonformat. So sieht man auf einen
 * Blick, was der Sender gerade wirklich liefert (nicht, was im Namen steht).
 */
object StreamBadges {
    fun video(height: Int, fps: Float, hdr: String): List<String> = buildList {
        when {
            height >= 2_000 -> add("4K UHD")
            height >= 1_000 -> add("Full HD")
            height >= 700 -> add("HD")
            height > 0 -> add("SD")
        }
        hdr.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
        // Nur hohe Bildraten zeigen: 50/60 Bilder sind bei Sport der Unterschied, 25 ist normal.
        if (fps >= 47f) add("${Math.round(fps)} fps")
    }

    /** Tonformat aus Codec (MIME-Typ oder Name) und Kanälen, z. B. „Dolby Atmos“, „Dolby Digital+ 5.1“, „Stereo“. */
    fun audio(codec: String, channels: Int): String? {
        val c = codec.lowercase()
        val name = when {
            "joc" in c || "atmos" in c -> "Dolby Atmos"
            "truehd" in c || "mlp" in c -> "Dolby TrueHD"
            "eac3" in c || "e-ac-3" in c || "ec-3" in c -> "Dolby Digital+"
            "ac4" in c || "ac-4" in c -> "Dolby AC-4"
            "ac3" in c || "ac-3" in c -> "Dolby Digital"
            "dts" in c -> if ("hd" in c || "ma" in c) "DTS-HD" else "DTS"
            else -> null
        }
        val layout = when {
            channels >= 8 -> "7.1"
            channels >= 6 -> "5.1"
            channels == 2 -> "Stereo"
            else -> null
        }
        return when {
            name == "Dolby Atmos" -> name
            name != null && layout != null && layout != "Stereo" -> "$name $layout"
            name != null -> name
            layout != null && layout != "Stereo" -> layout
            else -> null
        }
    }

    /** „noch 23 Min.“ bis zum Ende der Sendung; null, wenn sie schon vorbei ist. */
    fun remaining(stopMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        val minutes = ((stopMs - nowMs + 59_999) / 60_000).toInt()
        return when {
            minutes <= 0 -> null
            minutes < 60 -> "noch $minutes Min."
            else -> "noch ${minutes / 60} Std. ${minutes % 60} Min."
        }
    }
}
