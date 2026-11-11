package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodesTest {
    private fun ep(id: String, season: Int, episode: Int) = XtreamEpisode(id, season, episode, "Folge $episode", "mkv", null, null, 0)

    @Test
    fun keysRoundTrip() {
        val key = Episodes.key("3:s:77", "1234")
        assertEquals("3:s:77", Episodes.seriesKeyOf(key))
        assertEquals("1234", Episodes.episodeIdOf(key))
        assertNull(Episodes.seriesKeyOf("3:v:99"))
        assertNull(Episodes.episodeIdOf("3:v:99"))
    }

    @Test
    fun nextEpisodeAcrossSeasons() {
        val list = listOf(ep("c", 2, 1), ep("a", 1, 1), ep("b", 1, 2))
        assertEquals("b", Episodes.next(list, "a")?.id)
        assertEquals("c", Episodes.next(list, "b")?.id)
        assertNull(Episodes.next(list, "c"))
        assertNull(Episodes.next(list, "x"))
    }
}
