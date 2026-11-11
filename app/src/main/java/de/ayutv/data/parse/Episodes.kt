package de.ayutv.data.parse

/** Serienfolgen: Schlüssel, Reihenfolge und Titel. Folgen haben den Schlüssel „<Serie>:e:<Folge>“. */
object Episodes {
    private const val SEPARATOR = ":e:"

    fun key(seriesKey: String, episodeId: String) = "$seriesKey$SEPARATOR$episodeId"

    fun seriesKeyOf(episodeKey: String): String? =
        episodeKey.substringBeforeLast(SEPARATOR, "").takeIf { it.isNotEmpty() }

    fun episodeIdOf(episodeKey: String): String? =
        episodeKey.substringAfterLast(SEPARATOR, "").takeIf { it.isNotEmpty() && SEPARATOR in episodeKey }

    fun title(seriesName: String, episode: XtreamEpisode) = "$seriesName – S${episode.season} E${episode.episode}"

    /** Folge nach [currentId] (nach Staffel und Folgennummer, über das Staffelende hinweg), oder null. */
    fun next(episodes: List<XtreamEpisode>, currentId: String): XtreamEpisode? {
        val ordered = episodes.sortedWith(compareBy<XtreamEpisode> { it.season }.thenBy { it.episode })
        val index = ordered.indexOfFirst { it.id == currentId }
        return if (index < 0) null else ordered.getOrNull(index + 1)
    }
}

/** Serienfortschritt: welche Folgen gesehen sind und mit welcher es weitergeht. */
object SeriesProgress {
    /** Gesehen: bis auf die letzten 5 % (Abspann) oder 2 Minuten. */
    fun watched(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && (positionMs >= durationMs * 0.95 || durationMs - positionMs < 120_000 && positionMs > durationMs / 2)

    fun fraction(positionMs: Long, durationMs: Long): Float? =
        if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * Die Folge, mit der es weitergeht: eine angefangene (die zuletzt geordnete), sonst die nach der letzten
     * gesehenen. Null, wenn noch nichts geschaut wurde oder alles gesehen ist.
     * [progress]: Folgen-Kennung → (Position, Länge).
     */
    fun upNext(episodes: List<XtreamEpisode>, progress: Map<String, Pair<Long, Long>>): XtreamEpisode? {
        if (progress.isEmpty()) return null
        val ordered = episodes.sortedWith(compareBy<XtreamEpisode> { it.season }.thenBy { it.episode })
        var lastSeen = -1
        var lastStarted = -1
        ordered.forEachIndexed { index, e ->
            val (position, duration) = progress[e.id] ?: return@forEachIndexed
            when {
                watched(position, duration) -> lastSeen = index
                position > 60_000 -> lastStarted = index
            }
        }
        if (lastStarted > lastSeen) return ordered[lastStarted]
        return if (lastSeen >= 0) ordered.getOrNull(lastSeen + 1) else null
    }
}
