package de.ayutv.player

/**
 * Varianten eines Senders („DE: RTL ZWEI HD“, „DE: RTL ZWEI SD“, „DE: RTL ᴿᴬᵂ“, „DE: RTL HD (720P)“) unter einem Namen
 * zusammenfassen: ohne Qualitäts- und Technik-Angaben, Klammern und Sonderzeichen.
 */
object SubtitleVariants {
    private val QUALITY = setOf("hd", "sd", "uhd", "fhd", "raw", "hevc", "h265", "h264", "4k", "8k", "720p", "1080p", "1080i",
        "2160p", "50fps", "60fps", "sat", "mobil", "backup", "low", "high")

    fun baseName(name: String): String = name.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9äöüß]+"), " ")
        .split(' ')
        .filter { it.isNotEmpty() && it !in QUALITY }
        .joinToString(" ")
}
