package de.ayutv.player.dna.engine

/**
 * Protokoll der Engine; auf dem Gerät geht es an Logcat (gesetzt von [DnaEnginePlayer]), in Tests nirgendwohin.
 * Die letzten Meldungen stehen zusätzlich im [journal] – für den Fehlerbericht (Decoder, HEVC-Kopf, erstes Bild, Fehler).
 */
internal object DnaLog {
    @Volatile var sink: (tag: String, text: String) -> Unit = { _, _ -> }
    private val lines = ArrayDeque<Pair<Long, String>>()
    /** Wiederkehrende Meldungen (alle paar Sekunden): eigener, kleiner Vorrat – sie verdrängen sonst nach 12 Minuten alles andere. */
    private val routineLines = ArrayDeque<Pair<Long, String>>()
    private var seq = 0L
    private const val MAX_LINES = 150
    private const val MAX_ROUTINE = 40

    fun i(tag: String, text: String) {
        remember(text)
        runCatching { sink(tag, text) }
    }

    /**
     * Regelmäßige Statuszeile (Bild-Statistik alle 5 s): Im Journal bleiben nur die letzten [MAX_ROUTINE], damit Decoder,
     * Start und Warnungen einer langen Wiedergabe erhalten bleiben (Kurzbericht 15.11.: nur noch Statuszeilen, womit
     * gespielt wurde, war nicht mehr zu sehen).
     */
    fun routine(tag: String, text: String) {
        remember(text, routine = true)
        runCatching { sink(tag, text) }
    }

    /** Warnung: auch ins Journal, im Logcat als Warnung. */
    fun w(tag: String, text: String) {
        remember("⚠ $text")
        runCatching { sink(tag, "⚠ $text") }
    }

    private fun remember(text: String, routine: Boolean = false) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.GERMAN).format(java.util.Date())
        synchronized(lines) {
            val target = if (routine) routineLines else lines
            target.addLast(seq++ to "$time $text")
            while (target.size > (if (routine) MAX_ROUTINE else MAX_LINES)) target.removeFirst()
        }
    }

    /** Die letzten Meldungen (älteste zuerst). */
    fun journal(): List<String> = synchronized(lines) { (lines + routineLines).sortedBy { it.first }.map { it.second } }

    fun clear() = synchronized(lines) {
        lines.clear()
        routineLines.clear()
    }
}
