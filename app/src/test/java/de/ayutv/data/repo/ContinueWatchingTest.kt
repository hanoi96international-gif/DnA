package de.ayutv.data.repo

import de.ayutv.data.db.HistoryEntity
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.parse.Episodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingTest {
    private val min = 60_000L
    private fun episode(series: String, id: String, title: String, position: Long, duration: Long, at: Long) =
        HistoryEntity(Episodes.key(series, id), HistoryKind.EPISODE, title, null, null, "http://x/$id.mkv", null, position, duration, 1, at)
    private fun movie(key: String, position: Long, duration: Long, at: Long) =
        HistoryEntity(key, HistoryKind.VOD, key, null, null, "http://x/$key.mp4", null, position, duration, 1, at)

    @Test
    fun onlyLatestEpisodePerSeries() {
        val history = listOf(
            // Abgebrochene Folge aus Staffel 5 (vor Wochen) …
            episode("s:1", "501", "Serie – S5 E3", 20 * min, 45 * min, at = 1_000),
            // … danach Staffel 8 und 9 durchgeschaut, zuletzt S9 E2 angefangen
            episode("s:1", "801", "Serie – S8 E1", 44 * min, 45 * min, at = 2_000),
            episode("s:1", "902", "Serie – S9 E2", 12 * min, 45 * min, at = 3_000),
            movie("film", 30 * min, 120 * min, at = 2_500),
        )
        val shown = ContinueWatching.select(history)
        assertEquals(listOf("Serie – S9 E2", "film"), shown.map { it.title })
    }

    @Test
    fun finishedLatestEpisodeStaysForNextEpisode() {
        val history = listOf(
            episode("s:1", "501", "Serie – S5 E3", 20 * min, 45 * min, at = 1_000),
            episode("s:1", "903", "Serie – S9 E3", 44 * min, 45 * min, at = 3_000),
        )
        val shown = ContinueWatching.select(history)
        assertEquals(listOf("Serie – S9 E3"), shown.map { it.title })
        assertTrue("zu Ende: beim Antippen nächste Folge", ContinueWatching.isFinished(shown.single()))
    }

    @Test
    fun finishedMoviesAndBarelyStartedDisappear() {
        val history = listOf(movie("fertig", 118 * min, 120 * min, at = 2), movie("kurz", 30_000, 120 * min, at = 1))
        assertEquals(emptyList<String>(), ContinueWatching.select(history).map { it.title })
    }
}
