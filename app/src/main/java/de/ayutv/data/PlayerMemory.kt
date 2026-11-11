package de.ayutv.data

import android.content.Context

/** Bildformat im Player: ganz zeigen, füllen (Ränder abschneiden) oder auf den Bildschirm strecken. */
enum class AspectMode(val label: String) {
    FIT("Ganzes Bild"),
    ZOOM("Füllen (Ränder abschneiden)"),
    STRETCH("Strecken (4:3 auf 16:9)"),
}

/**
 * Was im Player zuletzt gewählt wurde, je Sender, Serie oder Film: Tonsprache, Untertitel (Sprache oder aus)
 * und Bildformat. Beim nächsten Mal stellt der Player es wieder so ein.
 */
class PlayerMemory(context: Context) {
    private val prefs = context.getSharedPreferences("player_memory", Context.MODE_PRIVATE)

    /**
     * [load]: Untertitel, die von außen kamen (OpenSubtitles, Übersetzung) – bei einer Serie lädt die nächste
     * Folge sie von selbst wieder, auch wenn „Untertitel automatisch an“ aus ist.
     */
    data class Choice(val audio: String? = null, val text: String? = null, val aspect: AspectMode? = null, val load: String? = null) {
        /** Untertitel bewusst ausgeschaltet. */
        val textOff: Boolean get() = text == OFF
    }

    fun get(key: String): Choice = decode(prefs.getString(key, null))

    fun update(key: String, transform: (Choice) -> Choice) {
        val next = transform(get(key))
        prefs.edit().putString(key, encode(next)).apply()
    }

    private val sources = context.getSharedPreferences("subtitle_source", Context.MODE_PRIVATE)

    /**
     * Woher die Untertitel kommen sollen: [SOURCE_FILE] (OpenSubtitles) oder [SOURCE_TRANSLATE] (Spur des Films
     * übersetzen). Von Hand gewählt je Film oder Serie; automatisch entschieden je Film oder Folge ([autoKey]).
     */
    fun subtitleSource(key: String): String? = sources.getString(key, null)

    fun setSubtitleSource(key: String, source: String?) {
        sources.edit().apply { if (source == null) remove(key) else putString(key, source) }.apply()
    }

    private val rates = context.getSharedPreferences("frame_rates", Context.MODE_PRIVATE)

    /**
     * Bildrate, die bei [key] zuletzt gemessen oder gemeldet wurde (0 = unbekannt). Damit schaltet der Fernseher
     * beim nächsten Start schon während des Ladens um, statt mitten im laufenden Bild schwarz zu werden.
     */
    fun frameRate(key: String): Float = rates.getFloat(key, 0f)

    /** Zuletzt bei einem Sender gesehene Bildrate: gute Schätzung für einen noch unbekannten Sender. */
    val lastLiveFrameRate: Float get() = rates.getFloat(LAST_LIVE, 0f)

    fun rememberFrameRate(key: String, fps: Float, live: Boolean) {
        if (fps <= 0f || (rates.getFloat(key, 0f) == fps && (!live || lastLiveFrameRate == fps))) return
        rates.edit().putFloat(key, fps).apply { if (live) putFloat(LAST_LIVE, fps) }.apply()
    }

    companion object {
        const val OFF = "-"
        const val SOURCE_FILE = "os"
        const val SOURCE_TRANSLATE = "tr"
        fun autoKey(key: String) = "auto:$key"
        private const val LAST_LIVE = "~live"

        fun encode(c: Choice): String = listOf(c.audio.orEmpty(), c.text.orEmpty(), c.aspect?.name.orEmpty(), c.load.orEmpty()).joinToString("|")

        fun decode(raw: String?): Choice {
            if (raw.isNullOrEmpty()) return Choice()
            val parts = raw.split('|')
            return Choice(
                audio = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
                text = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
                aspect = parts.getOrNull(2)?.let { name -> AspectMode.entries.firstOrNull { it.name == name } },
                load = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
