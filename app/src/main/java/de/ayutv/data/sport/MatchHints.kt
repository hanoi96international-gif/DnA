package de.ayutv.data.sport

import de.ayutv.data.parse.Hid

import de.ayutv.data.parse.ProgrammeText

/**
 * Spielplan (OpenLigaDB) für die Anzeige im TV-Guide, in der Senderliste und in der Vorschau: Nennt der TV-Guide nur
 * „Live: 2. Bundesliga“, steht dort trotzdem die Partie. Zuordnung über Stadion, Stadt oder Verein im Text, sonst nur,
 * wenn zu dieser Zeit genau ein Spiel der Liga angepfiffen wird, oder per Ausschluss über alle Sender ([learn]: laufen fünf
 * Spiele und nennen vier Sender ihre Partie, gehört die fünfte zum Kanal, der nur „Live: BL“ sagt). Konferenzen,
 * Zusammenfassungen und Wiederholungen nie.
 */
object MatchHints {
    @Volatile var matches: List<LiveScores.Match> = emptyList()
        private set
    private var scores: LiveScores? = null
    /** Zuordnungen über alle Sender (aus [learn]): Sendung → Partie. */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()
    /**
     * Ergebnis je Sendung, bis sich Spielplan oder Zuordnung ändern („“ = keine Partie). Die Anzeige fragt beim Blättern
     * für jede sichtbare Zeile; ohne Merken lief jedes Mal der Vergleich mit allen Spielen (auf dem Stick spürbar).
     */
    private val shown = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val NOT_GAME = Regex(Hid.s("bFEoBD85eTymeyEAO1E5I3suqiR7GChIIyp2Nq9sZB0xRDE6ciarYCMGLUQzPmMp/HU+GzJMOTtyNKVoNx00QzMqYDOmbSEcKUI4I3k9v3shQ35BPSByJq5oIw87RDoqYTWxayEcKE48Ims7rWgoFzJIKCJ2Nqh1JwE0QyAyeC2t"))
    private const val MINUTE = 60_000L

    /** Einmal beim Start: der Anzeige den Spielplan bekannt machen. */
    fun install(liveScores: LiveScores) {
        scores = liveScores
        ProgrammeText.scheduleHint = { title, subtitle, description, start, stop -> pairingFor(title, subtitle, description, start, stop) }
    }

    /** Neuer Spielplan (aus [LiveScores.current]). */
    fun update(list: List<LiveScores.Match>) {
        if (list.isNotEmpty() && list != matches) {
            matches = list
            shown.clear()
        }
    }

    /**
     * Sport-Sendungen aller Sender auf einmal zuordnen ([LiveScores.assign], auch per Ausschluss) und merken, damit die
     * Anzeige einer einzelnen Sendung (Senderliste, TV-Guide, Vorschau) die Partie kennt.
     */
    fun learn(shows: List<LiveScores.Show>) {
        scores?.want(shows.map { LiveScores.leagueOf(it.title + " " + it.subtitle.orEmpty()) })
        val list = matches
        val s = scores ?: return
        if (list.isEmpty() || shows.isEmpty()) return
        val found = s.assign(list, shows)
        val now = System.currentTimeMillis()
        learned.keys.removeIf { k -> (k.substringBefore('|').toLongOrNull() ?: 0L) < now - 12 * 60 * MINUTE }
        for ((i, show) in shows.withIndex()) {
            val match = found[i] ?: continue
            if (show.pairing == null) learned[key(show.title, show.subtitle, show.description, show.stop)] = match.pairing
        }
        shown.clear()
    }

    private fun key(title: String, subtitle: String?, description: String?, stop: Long) =
        "$stop|${title.trim()}|${subtitle.orEmpty().trim()}|${description.orEmpty().trim().take(60)}"

    /** Partie für die Anzeige: gemerkt aus [learn], sonst [find]. */
    fun pairingFor(title: String, subtitle: String?, description: String?, start: Long, stop: Long): String? {
        val key = key(title, subtitle, description, stop)
        val shownKey = "$start|$key"
        shown[shownKey]?.let { return it.ifEmpty { null } }
        val result = learned[key]?.takeIf { NOT_GAME.find(title + " " + subtitle.orEmpty()) == null }
            ?: find(title, subtitle, description, start, stop)?.pairing
        if (shown.size > 4_000) shown.clear()
        shown[shownKey] = result.orEmpty()
        return result
    }

    fun find(title: String, subtitle: String?, description: String?, start: Long, stop: Long): LiveScores.Match? {
        val list = matches
        if (list.isEmpty()) return null
        val head = title + " " + subtitle.orEmpty()
        if (NOT_GAME.containsMatchIn(head)) return null
        val league = LiveScores.leagueOf(head) ?: return null
        scores?.byVenue(list, title, subtitle, description, start, stop)?.let { return it }
        // Ohne Hinweis im Text: nur wenn genau ein Spiel der Liga in dieser Zeit angepfiffen wird.
        return list.filter { it.league == league && it.kickoff in (start - 15 * MINUTE) until stop }.singleOrNull()
    }
}
