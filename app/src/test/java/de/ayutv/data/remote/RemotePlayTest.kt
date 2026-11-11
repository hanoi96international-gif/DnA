package de.ayutv.data.remote

import de.ayutv.data.parse.Hid

import de.ayutv.data.db.HistoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemotePlayTest {
    @Test
    fun roundTrip() {
        val film = RemotePlay(false, "http://p.example/movie/u/p/12.mkv", "Der \"Film\"", "1:v:12", HistoryKind.EPISODE, "S1 E2", null, 123_456, "VLC/3.0")
        assertEquals(film, RemotePlay.parse(film.encode()))
        val live = RemotePlay(true, "http://p.example/live/u/p/7.ts", Hid.s("FwU4DQcmeCi3KXA="))
        assertEquals(live, RemotePlay.parse(live.encode()))
    }

    @Test
    fun rejectsMissingAddress() {
        assertThrows(IllegalArgumentException::class.java) { RemotePlay.parse("""{"live":true,"title":"x"}""") }
    }
}
