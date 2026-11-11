package de.ayutv.desktop.data

import de.ayutv.data.parse.Hid

import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.sport.IntlScores
import de.ayutv.data.sport.LiveScores
import de.ayutv.data.sport.MatchHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntlScoresTest {
    private val body = Hid.s("Tk5hDXR2N3rjcmYLN0g6ImR4+VJOTmENdHY3euMpZBVjRDB0LXj3OXVMbQ8wN2M/4TNmXHEfYnsma+45fDpxHW5mJwDhJWYNLkAkM2Mzt2ArADIPbg1sUOMpZE5hDXR2N3rjKWYYJEMhMzVguCsiGy1BGjd6P+EzZj0iQiA/djiiZy9OAF8xOHZ47yslCiVfMSVkePlyZg0oWS10LXiXZjYBL1k7dGon7wNkTmENdHY3euMpZE5jXiA3Yy+wK34VY1ktJnJ4+XJmHTVMIDM1YOFgKkxtDzc5eiqvbDALJQ9uMHY2sGxoTDJFOyRjHqZ9JQctD250JTSnKXVcex5gdGon7wNkTmENdHY3euMpZE5jTjs7Zz+3YDABM152bExQ4ylkTmENdHY3euMpZE46Dzw5ej+CfiUXYxd2N2A7uitoTDJOOyRyePkrdUxtDyAzdjfhMz9MJUQnJns7ukclAyQPbnRVNbB9KwBhbyYjfjSwK2hMMkU7JGMeqno0AiBUGjd6P+EzZiwzWD04ZHjvKygBJkJ2bDUyt300HXsCezc5P7B5Kg0lQ3o1eDfsaysdb106MTUnviVOTmENdHY3euMpZE5hDXQtNTKsZCEvNkwtdC14q2YpC2MBdiV0NbFsZlRjH3Z6NS6maClMe1Z2Mn4ps2UlFw9MOTM1YOFdKxwuQyA5NxeieSgLYWExN3Ep4SVmHSlCJiJTM7B5KA84YzU7cnj5KwkPMUExdls/om83TG0PODlwNeEzZgY1WSQlLXXsaGoLMl06NXM07WorA25ZOyQ5Kq1uZhM8J3R2N3rjKWROYQ10dkonnnRoZGENdHY3euMpZE46Dz0yNWDhPXRcYwF2MnYupit+THMdZmA6a/IkdFYVHWRsJGqZK2hMIkI5JnIuqn0tAS9edmxMIckpZE5hDXR2N3rjKWRMMlk1ImIp4TM/TDVUJDM1YLgrNxogWTF0LXizeyFMbQ83OXoqr2wwCyUPbjB2NrBsaEwyRTskYx6mfSUHLQ9udCBg8DlkPgwPKSs7UOMpZE5hDXR2N3rjKWYNLkAkM2Mzt2Y2HWMXD1w3euMpZE5hDXR2N3rjKT9MKUI5M1YtonBmVGNFOztyeO8rNw0uXzF0LXjzK2hMNUg1OzVguCsgBzJdODduFKJkIUx7DxozYHqaZjYFYX81OHA/sXpmQmNePDllLodgNx4tTC0YdjemK35ME0w6MXIosCs5E20ndHY3euMpZE5hDXR2N3q4KywBLEgVIXYj4TNmDzZMLXQ7eLBqKxwkD250J3jvKzALIEB2bGx4p2A3Hi1MLRh2N6YrfkwMQjoiZT+iZWQtIEM1Mn4/rXpmQmNePDllLodgNx4tTC0YdjemK35MAkw6N3Mzpmc3TDxQXnY3euMpZE5hDXR2Nwe+VDlkYQ10djd64ykZE0sNdHY3").trimIndent()

    @Test
    fun parsesNhlScoreboard() {
        val list = IntlScores.parse(body, Hid.s("KgYt"))
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals(Hid.s("EAEzQjoieHqOaDQCJA0YM3Y8sCmm7tINFjlkLqxnZCwzWD04ZA=="), first.pairing)
        assertEquals("2:1", first.score)
        assertEquals(Hid.s("Fw0uWT03dTutYmQvM0g6Nw=="), first.stadium)
        assertEquals(Hid.s("LBo1XSdsOHWiJyEdMUM3Mnl0oGYpQTVCJnhnNKQ="), first.icon1)
        assertTrue(first.live(0))
        assertEquals(Hid.s("dgAlDWVkLWn3"), first.minute(0))
        assertFalse(first.football)
        assertFalse(list[1].live(Long.MAX_VALUE / 2))
    }

    @Test
    fun liveNhlGetsThePairing() {
        val list = IntlScores.parse(body, Hid.s("KgYt"))
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        MatchHints.update(list)
        val start = list[0].kickoff - 10 * 60_000L
        val stop = start + 3 * 3_600_000L
        // Teams in der Beschreibung
        assertEquals(
            Hid.s("EAEzQjoieHqOaDQCJA0YM3Y8sCmm7tINFjlkLqxnZCwzWD04ZHrrRS0YJBd0GF8W6g=="),
            ProgrammeText.display(Hid.s("CAc3SG52WRKP"), null, Hid.s("AAckDRk3ZzamKQgLIEsndnI3s28lACZIOnZzM6YpBgEyWTs4NxixfC0AMgM="), Hid.s("Fx4uXyA="), start, stop),
        )
        // Arena in der Beschreibung
        assertEquals(
            Hid.s("Cgs2DQ05ZTHjWyUAJkgmJTe4Q5pkIy5DICRyO68pBw8vTDA/cjSwKWwiKFsxbDcUi0Vt"),
            ProgrammeText.display(Hid.s("CAc3SG52WRKP"), null, Hid.s("BRsyDTAzenqOaCAHMkI6dkQrtmg2C2FqNSRzP60pLQBhYzEhNwOsey9UYX81OHA/sXpkCyxdMjd5PaZnZCMuQyAkcjuvJw=="), Hid.s("Fx4uXyA="), start + 30 * 60_000L, stop),
        )
        assertEquals(Hid.s("KgYt"), LiveScores.leagueOf(Hid.s("AQcyRTs1fD+6M2QgCWE=")))
        assertEquals(Hid.s("IQAmA2U="), LiveScores.leagueOf(Hid.s("CAc3SG52RxY=")))
        assertEquals(Hid.s("JgJz"), LiveScores.leagueOf(Hid.s("CAc3SG52JXTjSwg=")))
    }

    @Test
    fun germanClubNames() {
        assertEquals(Hid.s("Ai1hbzUvciitKQmt/UM3PnI0"), IntlScores.german(Hid.s("Bg84SCY4Nxe2Zy0NKQ==")))
        assertEquals(Hid.s("Fz0CDRozdiqmZQ=="), IntlScores.german(Hid.s("Cg8xQjg/")))
        assertEquals(Hid.s("CAskSSd2QjSqfSEK"), IntlScores.german(Hid.s("CAskSSd2QjSqfSEK")))
    }
}
