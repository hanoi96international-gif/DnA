package de.ayutv.player.dna.engine

/**
 * Protokoll der Engine; auf dem Gerät geht es an Logcat (gesetzt von [DnaEnginePlayer]), in Tests nirgendwohin.
 * Die letzten Meldungen stehen zusätzlich im [journal] – für den Fehlerbericht (Decoder, HEVC-Kopf, erstes Bild, Fehler).
 */
internal object DnaLog {
    @Volatile var sink: (tag: String, text: String) -> Unit = { _, _ -> }
    private val lines = ArrayDeque<String>()
    private const val MAX_LINES = 150

    fun i(tag: String, text: String) {
        remember(text)
        runCatching { sink(tag, text) }
    }

    /** Warnung: auch ins Journal, im Logcat als Warnung. */
    fun w(tag: String, text: String) {
        remember("⚠ $text")
        runCatching { sink(tag, "⚠ $text") }
    }

    private fun remember(text: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.GERMAN).format(java.util.Date())
        synchronized(lines) {
            lines.addLast("$time $text")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** Die letzten Meldungen (älteste zuerst). */
    fun journal(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
