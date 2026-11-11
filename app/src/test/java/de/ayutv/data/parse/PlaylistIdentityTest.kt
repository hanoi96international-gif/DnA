package de.ayutv.data.parse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistIdentityTest {
    @Test
    fun sameXtreamAccountDespiteSpelling() {
        assertTrue(PlaylistIdentity.same("XTREAM", "http://Example.com:8080", "max", "XTREAM", "https://example.com:8080/player_api.php", " max "))
        assertTrue(PlaylistIdentity.same("XTREAM", "example.com/", "max", "XTREAM", "http://example.com", "max"))
        assertTrue(PlaylistIdentity.same("XTREAM", "http://example.com:80", "max", "XTREAM", "http://example.com", "max"))
    }

    @Test
    fun otherUserOrServerIsAnotherAccount() {
        assertFalse(PlaylistIdentity.same("XTREAM", "http://example.com", "max", "XTREAM", "http://example.com", "moritz"))
        assertFalse(PlaylistIdentity.same("XTREAM", "http://example.com:8080", "max", "XTREAM", "http://example.com:8081", "max"))
        assertFalse(PlaylistIdentity.same("XTREAM", "http://example.com", "max", "M3U", "http://example.com", "max"))
    }

    @Test
    fun m3uComparesTheAddressIncludingPathAndQuery() {
        assertTrue(PlaylistIdentity.same("M3U", "http://host/get.php?u=a&p=b", null, "M3U", "http://HOST/get.php?u=a&p=b", null))
        assertFalse(PlaylistIdentity.same("M3U", "http://host/get.php?u=a&p=b", null, "M3U", "http://host/get.php?u=c&p=d", null))
        // Pfade sind bei M3U Groß-/Kleinschreibung-genau.
        assertFalse(PlaylistIdentity.same("M3U", "http://host/List.m3u", null, "M3U", "http://host/list.m3u", null))
    }
}
