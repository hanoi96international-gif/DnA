package de.ayutv.data.repo

import de.ayutv.data.db.HistoryEntity
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.parse.Episodes

/**
 * „Weiter schauen“: Filme, die angefangen und nicht zu Ende sind, und je Serie nur die zuletzt geschaute Folge. Bisher
 * stand jede angefangene Folge einzeln da – eine abgebrochene Folge aus Staffel 5 blieb stehen, auch wenn man längst
 * bei Staffel 9 war (fertig geschaute Folgen fallen heraus). Ist die letzte Folge zu Ende geschaut, bleibt die Serie mit
 * dieser Folge in der Liste ([isFinished]): Beim Antippen geht es mit der nächsten Folge weiter.
 */
object ContinueWatching {
    private const val MIN_POSITION_MS = 60_000L

    /** Zu Ende geschaut (wie beim Fortsetzen: über 95 % oder weniger als 2 Minuten übrig). */
    fun isFinished(entry: HistoryEntity): Boolean =
        entry.durationMs > 0 && (entry.positionMs >= entry.durationMs * 0.95 || entry.durationMs - entry.positionMs < 120_000)

    /** [history] neueste zuerst (watchedAt absteigend). */
    fun select(history: List<HistoryEntity>, limit: Int = 20): List<HistoryEntity> {
        val seenSeries = HashSet<String>()
        val out = ArrayList<HistoryEntity>()
        for (entry in history.sortedByDescending { it.watchedAt }) {
            when (entry.kind) {
                HistoryKind.EPISODE -> {
                    val series = Episodes.seriesKeyOf(entry.key) ?: entry.key
                    // Nur die zuletzt geschaute Folge der Serie zählt; ältere (auch abgebrochene) fallen weg.
                    if (!seenSeries.add(series)) continue
                    if (entry.positionMs > MIN_POSITION_MS) out += entry
                }
                HistoryKind.VOD -> if (entry.positionMs > MIN_POSITION_MS && !isFinished(entry)) out += entry
                else -> Unit
            }
            if (out.size >= limit) break
        }
        return out
    }
}
