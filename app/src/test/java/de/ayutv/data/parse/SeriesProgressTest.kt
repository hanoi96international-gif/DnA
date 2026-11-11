package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesProgressTest {
    private fun ep(id: String, season: Int, episode: Int) = XtreamEpisode(id, season, episode, "T$id", "mp4", null, null, 2_400)
    private val list = listOf(ep("3", 1, 3), ep("1", 1, 1), ep("2", 1, 2), ep("4", 2, 1))
    private val min = 60_000L

    @Test
    fun watchedNearEnd() {
        assertTrue(SeriesProgress.watched(39 * min, 40 * min))
        assertFalse(SeriesProgress.watched(10 * min, 40 * min))
        assertFalse(SeriesProgress.watched(10 * min, 0))
    }

    @Test
    fun nextAfterLastSeenAcrossSeasons() {
        val progress = mapOf("1" to (40 * min to 40 * min), "3" to (40 * min to 40 * min))
        assertEquals("4", SeriesProgress.upNext(list, progress)?.id)
    }

    @Test
    fun startedEpisodeWins() {
        val progress = mapOf("1" to (40 * min to 40 * min), "2" to (12 * min to 40 * min))
        assertEquals("2", SeriesProgress.upNext(list, progress)?.id)
    }

    @Test
    fun nothingOrEverything() {
        assertNull(SeriesProgress.upNext(list, emptyMap()))
        assertNull(SeriesProgress.upNext(list, list.associate { it.id to (40 * min to 40 * min) }))
    }
}
