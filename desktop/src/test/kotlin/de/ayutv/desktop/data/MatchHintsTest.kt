package de.ayutv.desktop.data

import de.ayutv.data.parse.Hid

import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.sport.LiveScores
import de.ayutv.data.sport.MatchHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchHintsTest {
    private val hour = 3_600_000L
    private val t0 = 1_800_000_000_000L
    private fun match(id: Long, league: String, a: String, b: String, kickoff: Long, stadium: String? = null) =
        LiveScores.Match(id, league, a, a, b, b, kickoff, 0, 0, false, 0, null, stadium = stadium)

    @Test
    fun pairingFromSubtitleComesFirst() {
        assertEquals(
            Hid.s("DA8vQzsgcijjMHJOo63HdlEZ41onBiBBPzM3avcpbCg07ss0djavM2Rcbw0WI3k+pnooByZMfQ=="),
            ProgrammeText.display(Hid.s("AhuCsjY3ezb5KXZAYW8hOHM/sGUtCSA="), Hid.s("DA8vQzsgcijjMHJOo63HdlEZ41onBiBBPzM3avc="), null, Hid.s("Fx4uXyA="), t0, t0 + 2 * hour),
        )
    }

    @Test
    fun pairingFromDescription() {
        assertEquals(
            Hid.s("DAszWTw3NxiQSmSMwb50Zzl6hUpkJYKbODg3co9gMgt7DRAQVXeTZi8PLQQ="),
            ProgrammeText.display(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, Hid.s("DAszWTw3NxiQSmSMwb50Zzl6hUpkJYKbODg5eodgIU4DSCY6fjSme2QLLF0yN3k9pmdkjMGL"), Hid.s("Fx4uXyA="), t0, t0 + 2 * hour),
        )
    }

    @Test
    fun pairingFromScheduleWhenOnlyOneMatch() {
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        MatchHints.update(listOf(
            match(1, Hid.s("JgJz"), Hid.s("DA8vQzsgcijjMHI="), Hid.s("Ai1hfjc+djaobGRedQ=="), t0 + 15 * 60_000L),
            match(2, Hid.s("JgJw"), Hid.s("BgEzWCclfjvjTSscNUAhOHM="), Hid.s("Fy1hayYzfji2eyM="), t0 + 15 * 60_000L),
        ))
        assertEquals(
            Hid.s("DA8vQzsgcijjMHJOo63HdlEZ41onBiBBPzM3avcpbCIoWzFsN2jtKQYiaA=="),
            ProgrammeText.display(Hid.s("CAc3SG52JXTjSwg="), null, null, Hid.s("Fx4uXyA="), t0, t0 + 2 * hour),
        )
        // Konferenz: mehrere Spiele, keine Partie; einheitlich benannt.
        assertEquals(Hid.s("dkBhbyE4cz+wZS0JIA221oR6iGYqCCRfMTht"), ProgrammeText.display(Hid.s("CAc3SG52JXTjSwhOCkI6MHIopmc+"), null, null, Hid.s("Fx4uXyA="), t0, t0 + 2 * hour))
        // Kein Sport: unverändert.
        assertEquals(Hid.s("EA8mSCcldDKifA=="), ProgrammeText.display(Hid.s("EA8mSCcldDKifA=="), null, null, Hid.s("Cg8iRSY/dDK3bCo="), t0, t0 + hour))
    }

    @Test
    fun noGuessWhenSeveralMatchesAtOnce() {
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        MatchHints.update(listOf(
            match(1, Hid.s("JgJz"), Hid.s("DA8vQzsgcijjMHI="), Hid.s("Ai1hfjc+djaobGRedQ=="), t0),
            match(2, Hid.s("JgJz"), Hid.s("DAszWTw3NxiQSg=="), Hid.s("dUBhaxd2XJl1ZSo="), t0),
        ))
        assertNull(MatchHints.find(Hid.s("CAc3SG52JXTjSwg="), null, null, t0, t0 + 2 * hour))
        // Mit Stadion in der Beschreibung: eindeutig.
        MatchHints.update(listOf(
            match(1, Hid.s("JgJz"), Hid.s("DA8vQzsgcijjMHI="), Hid.s("Ai1hfjc+djaobGRedQ=="), t0, stadium = Hid.s("DAsoQy57YTWtJAwLKEkxODobsWwqDw==")),
            match(2, Hid.s("JgJz"), Hid.s("DAszWTw3NxiQSg=="), Hid.s("dUBhaxd2XJl1ZSo="), t0, stadium = Hid.s("CwI4QCQ/dim3aCAHLkM=")),
        ))
        assertEquals(1L, MatchHints.find(Hid.s("CAc3SG52JXTjSwg="), null, Hid.s("BRsyDTAzZXqLbC0AOwAiOXl3i2wtCiRDeRdlP61oZAcvDRw3eTSsfyEcbw=="), t0, t0 + 2 * hour)?.id)
    }

    /** Fußball-Sender am Samstag wie auf dem Foto: 381, 383, 384 mit Partie, 382 nur „Live: BL“, 385/386 Multiview. */
    @Test
    fun leagueChannelsAllGetTheirMatch() {
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        val kick = t0 + 15 * 60_000L
        MatchHints.update(listOf(
            match(1, Hid.s("JgJw"), Hid.s("dUBhawcANxeiYCoUYR1h"), Hid.s("Ai1hbzUvciitKQmt/UM3PnI0"), kick),
            match(2, Hid.s("JgJw"), Hid.s("BgEzWCclfjvjTSscNUAhOHM="), Hid.s("FzhhHWN2Uja1bDYdI0gmMQ=="), kick),
            match(3, Hid.s("JgJw"), Hid.s("Fy1hfTUyciihZjYAYR1j"), Hid.s("AQcvWSY3dDK3KQIcIEM/MGIotw=="), kick),
            match(4, Hid.s("JgJw"), Hid.s("DA8sTyEkcD+xKRc4"), Hid.s("dUBhaxd2XJl1ZSo="), kick),
        ))
        // Ohne Spielort bei OpenLigaDB: das Stadion des Heimvereins aus der eigenen Liste genügt.
        assertEquals(1L, MatchHints.find(Hid.s("CAc3SG52VRY="), null, Hid.s("BRsyDTAzZXqOTBMvYWwmM3k742AqTgxMPThtduNbLAsoQzg3eT7uWSIPLVd4dlM/tn03DSlBNThzdA=="), t0, t0 + 2 * hour)?.id)
        assertEquals(4L, MatchHints.find(Hid.s("CAc3SG52VRY="), null, Hid.s("BRsyDTAzenqVZigFMl01JHwpt2ggBy5DdD95eotoKQw0XzN4"), t0, t0 + 2 * hour)?.id)
        // Ohne jeden Hinweis: per Ausschluss, weil die anderen drei Spiele schon Sendern gehören.
        val stop = t0 + 2 * hour + 30 * 60_000L
        MatchHints.learn(listOf(
            LiveScores.Show(Hid.s("CAc3SG52VRY="), null, Hid.s("dUBhawcANxeiYCoUYR1hdvXaUCkCLWFvNS9yKK0pCa39Qzc+cjTtKQAPMg0AOWcps2AhAm8="), t0, stop, Hid.s("dUBhawcANxeiYCoUYR1hdvXaUCkCLWFvNS9yKK0pCa39Qzc+cjQ=")),
            LiveScores.Show(Hid.s("CAc3SG52VRY="), null, null, t0, stop, null),
            LiveScores.Show(Hid.s("CAc3SG52VRY="), Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXThJ7dGYgeoZlMgszXjYzZT0="), null, t0, stop, Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXThJ7dGYgeoZlMgszXjYzZT0=")),
            LiveScores.Show(Hid.s("CAc3SG52VRY="), Hid.s("Fy1hfTUyciihZjYAYR1jdvXaUCkBBy9ZJjd0MrcpAhwgQz8wYii3"), null, t0, stop, Hid.s("Fy1hfTUyciihZjYAYR1jdvXaUCkBBy9ZJjd0MrcpAhwgQz8wYii3")),
            LiveScores.Show(Hid.s("BiJ7DRkjey6qfy0LNg=="), null, null, t0, stop, null),
        ))
        assertEquals(Hid.s("DA8sTyEkcD+xKRc4Yc/UxTdr7SkCLWFml+B7NOMhCAc3SG52VRbq"), ProgrammeText.display(Hid.s("CAc3SG52VRY="), null, null, Hid.s("Fx4uXyA="), t0, stop))
        // Multiview zeigt mehrere Spiele: keine Partie.
        assertEquals(Hid.s("BhsvSTElezOkaGSMwb50G2I2t2AyByRa"), ProgrammeText.display(Hid.s("BiJ7DRkjey6qfy0LNg=="), null, null, Hid.s("Fx4uXyA="), t0, stop))
    }
}
