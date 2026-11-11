package de.ayutv.data.remote

import de.ayutv.data.remote.SyncMerge.PlaylistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMergeTest {
    @Test
    fun playlistsMatchByProviderAndUser() {
        val remote = listOf(PlaylistRef(1, "XTREAM", "http://a.tv/", "max"), PlaylistRef(2, "M3U", "http://b/list.m3u", null), PlaylistRef(3, "XTREAM", "http://a.tv", "eva"))
        val local = listOf(PlaylistRef(7, "M3U", "http://B/list.m3u", null), PlaylistRef(9, "XTREAM", "http://a.tv", "max"))
        assertEquals(mapOf(1L to 9L, 2L to 7L), SyncMerge.idMap(remote, local))
    }

    @Test
    fun keysAndNewerEntries() {
        assertEquals("9:s:5:e:77", SyncMerge.remapKey("1:s:5:e:77", mapOf(1L to 9L)))
        assertNull(SyncMerge.remapKey("catchup:1:c", mapOf(1L to 9L)))
        val incoming = listOf("a" to 10L, "b" to 5L, "c" to 1L)
        val result = SyncMerge.newer(mapOf("a" to 20L, "b" to 4L), incoming, { it.first }, { it.second })
        assertEquals(listOf("b" to 5L, "c" to 1L), result)
    }

    @Test
    fun missingCustomGroupsComeOverWithLocalKeys() {
        val ids = mapOf(1L to 9L)
        val incoming = listOf(
            SyncMerge.Group("Sport DE", listOf("1:c:5", "1:c:6", "2:c:1")),
            SyncMerge.Group("Kinder", listOf("1:c:7")),
            SyncMerge.Group("Nur fremd", listOf("2:c:3")),
        )
        val result = SyncMerge.newGroups(listOf(" kinder "), incoming, ids)
        // „Kinder“ gibt es hier schon (bleibt unverändert), „Nur fremd“ hat hier keinen Sender.
        assertEquals(listOf(SyncMerge.Group("Sport DE", listOf("9:c:5", "9:c:6"))), result)
    }

    @Test
    fun hiddenGroupsOnlyWhereNothingIsHiddenYet() {
        val ids = mapOf(1L to 9L, 2L to 8L)
        val local = listOf(SyncMerge.Hidden(9, "LIVE", "Eigene Wahl"))
        val incoming = listOf(
            SyncMerge.Hidden(1, "LIVE", "UK Sport"),
            SyncMerge.Hidden(1, "VOD", "Bollywood"),
            SyncMerge.Hidden(2, "LIVE", "FR News"),
            SyncMerge.Hidden(3, "LIVE", "Unbekannt"),
        )
        assertEquals(
            listOf(SyncMerge.Hidden(9, "VOD", "Bollywood"), SyncMerge.Hidden(8, "LIVE", "FR News")),
            SyncMerge.hiddenToTake(local, incoming, ids),
        )
    }
}
