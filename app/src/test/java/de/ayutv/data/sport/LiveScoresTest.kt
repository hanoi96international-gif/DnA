package de.ayutv.data.sport

import de.ayutv.data.parse.Hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScoresTest {
    // Gekürzt aus https://api.openligadb.de/getmatchdata/bl1
    private val body = Hid.s("Tk5hDXR2N3rjUj9MLEwgNX8Thyt+WXMdZGc7eK5oMA0paTUicg6qZCE7FW52bDVo8ztyQ3AdeWUmDvI9fl1xF2RmTXjvKygLIEohM0QyrHswDTRZdmw1OK84ZkJLDXR2N3rjKWROYQ8gM3Y38it+FWNZMTd6E6crflltDyAzdjeNaCkLYxd2FHgotno3ByANEDllLq58KgpjAXYlfzWxfQoPLEh2bDUerHswAzRDMHQ7eLdsJQMITjs4QiivK35MKVkgJmRg7CYxHi1CNTI5LapiLQMkST03OTWxbmsMN096Jnk94XRoZGENdHY3euMpZE5jWTE3emjhMz9MNUg1O14+4TN9X20PIDN2N41oKQtjF3YTfjS3eyUNKVl0EGU7rWIiGzNZdno1KatmNhoPTDkzNWDhTzYPL0YyI2Uu4SVmGiRMOR90Na1cNgJjF3Y+Yy6zen5BblgkOng7pyczBypEOTNzM6InKxwmAicxcnSwfyNMPAFedjd64ylkTmENdHR6O7dqLCcyaz04fimrbCBMe0s1OmQ/7yspDzVOPARyKbZlMB1jFw8LO1DjKWROYQ10djd64W4rDy1edmxMIeFuKw8tZBB0LWvvKzcNLl8xAnI7rjhmVHABdiV0NbFsEAsgQGZ0LWrvKykPNU48G340tn0hTHscZno1PaxoKCkkWSAzZRSiZCFMew8TI34oono3F2NQeFw3euMpZE5hDXR2N3rjKWROYQ10LTU9rGgoJwUPbmU7eLBqKxwkeTE3emvhM3ZCY143OWU/l2wlA3MPbmc7eK5oMA0pYD04Yi6mK35YdgF2MXg7r04hGjVIJhh2N6YrfkwASTEvcjeqKzlCSw10djd64ylkTmENdHY3euMpZE46DzM5djaKTWZUcwF2JXQ1sWwQCyBAZXQta+8rNw0uXzECcjuuO2ZUcAF2O3YuoGEJBy9YIDM1YPc5aEwmQjU6UD+3fSEcD0w5MzVg4UQlHCxCISV/eL5UOUJLDXR2N3rjKWROOg85N2M5q0AATHsaZmYnaO8rKQ81TjwSdi6mXS0DJHgAFTVg4Tt0XHcAZWY6afNddVd7HmRsJ2qZK2hkYQ10djd64ylkTmNZMTd6a+EzP0w1SDU7WTuubGZUY2sXdlU7umw2AGFgl+p5OatsKkxtDyc+eCi3RyUDJA9udFU7umw2AGNQeFw3euMpZE5hDXR2NS6maClcYxcvdGM/omQKDyxIdmw1a+0pAi1hZpfgezThJWYdKUImIlk7rmxmVGNml+B7NOF0aGRhDXR2N3rjKWROY0A1InQyinoCBy9EJz5yPuEzMBw0SHhcN3rjKWROYQ10djU3on0nBhNIJyN7LrArfjU6DyYzZC+vfRAXMUgdEjVg8iVmHi5EOiJkDqZoKV9jF2V6NSqsYCoaMnkxN3po4TN0E21WdiRyKbZlMDo4XTEfU3j5O2hMMUI9OGMpl2wlA3APbmU7eLNmLQA1XgAzdjfxK35ePHB4XDd64ylkTmENdHY1PaxoKB1jFw8LanbJKWROYQ10djd6uCsmHC5GMTg1YLd7MQs8cF52N3rj").trimIndent()

    @Test
    fun parsesScoresAndScorers() {
        val matches = LiveScores.parse(body, Hid.s("JgJw"))
        assertEquals(2, matches.size)
        val bvb = matches[0]
        assertEquals("2:1", bvb.score)
        assertEquals(3, bvb.goalCount)
        assertEquals(Hid.s("BQokVDE7fnr1PmM="), bvb.lastGoal)
        assertFalse(bvb.finished)
        assertEquals("3:0", matches[1].score)
        assertTrue(matches[1].finished)
        // Wappen: Rastergrafik ja, SVG nein (zeigt der Bildlader nicht an), fehlend = null
        assertEquals(Hid.s("LBo1XSdsOHW2eSgBIEl6IX4xqmQhCihMejllPexrMgxvXTox"), bvb.icon1)
        // Wikimedia-SVG → fertiges PNG-Vorschaubild
        assertEquals(
            Hid.s("LBo1XSdsOHW2eSgBIEl6IX4xqmQhCihMejllPex+LQUoXTEyfjvsaisDLEI6JTguq3wpDG4be2AgdYFmNhsyXj03SB6sezADNEMwCXs1pGZqHTdKe2clarNxaSwuXyElZDOiVgABM1k5I3k+nGUrCS4DJyBwdLNnIw=="),
            LiveScores.crest(Hid.s("LBo1XSdsOHW2eSgBIEl6IX4xqmQhCihMejllPex+LQUoXTEyfjvsaisDLEI6JThs7D9zQQNCJiNkKapoGyouXyA7YjSnVigBJkJ6JWE9")),
        )
        // Schon ein Vorschaubild: unverändert; fremdes SVG: keines
        val thumb = Hid.s("LBo1XSdsOHW2eSgBIEl6IX4xqmQhCihMejllPex+LQUoXTEyfjvsaisDLEI6JTguq3wpDG4Ye2MkdYVKGy0uQTsxeT+cZSsJLgMnIHB18Tl0HjkAEhVIGaxlKwkvSAs6eD2sJzcYJgMkOHA=")
        assertEquals(thumb, LiveScores.crest(thumb))
        assertNull(LiveScores.crest(Hid.s("LBo1XSdsOHWmcSUDMUExeHgopCYzDzFdMTg5KbVu")))
        assertNull(bvb.icon2)
        assertNull(matches[1].icon1)
    }

    @Test
    fun minuteAndMatchingToTvPairings() {
        val bvb = LiveScores.parse(body, Hid.s("JgJw"))[0]
        val kickoff = bvb.kickoff
        assertEquals("30'", bvb.minute(kickoff + 30 * 60_000))
        assertEquals(Hid.s("DDQ="), bvb.minute(kickoff + 52 * 60_000))
        assertEquals("70'", bvb.minute(kickoff + 85 * 60_000))
        assertNull(bvb.minute(kickoff - 60_000))
        assertTrue(bvb.matches(Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgREOiJlO6BhME4HXzU4fDy2ezA=")))
        assertTrue(bvb.matches(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGh+TgVCJiJ6L61tZENhayY3eTGlfDYa")))
        assertFalse(bvb.matches(Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgdudBR2I6Z7Kk4M7ug4dDKmZw==")))
        val bayern = LiveScores.parse(body, Hid.s("JgJw"))[1]
        assertTrue(bayern.matches(Hid.s("Ai1hbzUvciitKabu0g1leDccgCkPrfdBOg==")))
        val scores = LiveScores(okhttp3.OkHttpClient())
        assertNotNull(scores.find(listOf(bvb), Hid.s("AAEzWTkjeT7j68T9YWsmN3kxpXw2Gg=="), kickoff - 30 * 60_000))
        assertNull(scores.find(listOf(bvb), Hid.s("AAEzWTkjeT7j68T9YWsmN3kxpXw2Gg=="), kickoff + 2 * 86_400_000L))
        // Frauenspiel derselben Vereine passt nicht
        assertFalse(bvb.matches(Hid.s("BgEzWCclfjvjTSscNUAhOHN6hXslGyRDdLSXyeNMLQA1XzU1fy7jTzYPL0YyI2Uu4082DzRIOg==")))
    }

    /** Sender nennt Einzelspiele oft nur „Live: 2. BL“ und in der Beschreibung das Stadion. */
    @Test
    fun findsMatchByVenue() {
        val kickoff = 1_761_850_800_000L // 18:30
        fun m(id: Long, t1: String, t2: String, city: String?, stadium: String?, league: String = Hid.s("JgJz")) = LiveScores.Match(
            id, league, t1, t1, t2, t2, kickoff, 0, 0, false, 0, null, city, stadium,
        )
        val matches = listOf(
            m(1, Hid.s("BRwsRDo/dnqBYCECJEsxOnM="), Hid.s("Fy1hfTUyciihZjYAYR1j"), Hid.s("BgckQTEwcjan"), Hid.s("Fw0p7ug1eBuxbCoP")),
            m(2, Hid.s("DAszWTw3NxiQSg=="), Hid.s("dUBhaxd2XDuqeiEcMkE1I2M/sWc="), Hid.s("BgszQT04"), Hid.s("CwI4QCQ/dim3aCAHLkM=")),
            m(3, Hid.s("DA8vQzsgcijjMHI="), Hid.s("Dw8zQSckYjKme2Q9Ag=="), Hid.s("DA8vQzsgcig="), Hid.s("DAsoQy52YTWtKQwLKEkxODcbsWwqDw==")),
            m(4, Hid.s("Ai1hbzUvciitKQmt/UM3PnI0"), Hid.s("BgEzWCclfjvjTSscNUAhOHM="), Hid.s("Ca39Qzc+cjQ="), Hid.s("BQItRDU4bXqCeyEAIA=="), league = Hid.s("JgJw")),
        )
        val start = kickoff - 30 * 60_000L
        val stop = kickoff + 2 * 3_600_000L
        val scores = LiveScores(okhttp3.OkHttpClient())
        assertEquals(1L, scores.byVenue(matches, Hid.s("CAc3SG52JXTjSwg="), null, Hid.s("BRsyDTAzZXqQaiyt/U47e1YopmclTihDdBR+P69sIgstSXo="), start, stop)?.id)
        assertEquals(3L, scores.byVenue(matches, Hid.s("CAc3SG52JXTjSwg="), null, Hid.s("BRsyDTAzZXqLbC0AOw0iOXl6i2wtCiRDdBdlP61oag=="), start, stop)?.id)
        // Nur nach Verein, ohne Stadion
        assertEquals(2L, scores.byVenue(matches, Hid.s("CAc3SG52JXTjSwg="), null, Hid.s("AAszDWV4NxyAKQ8PKF4xJGQ2onwwCzNDdDF2KbdgIRw1DT04NximeygHLwM="), start, stop)?.id)
        // Konferenz: mehrere Spiele, keine Zuordnung
        assertNull(scores.byVenue(matches, Hid.s("CAc3SG52JXTjSwhUYWsmM34uom43QwpCOjByKKZnPk6jrcd2JmrtKRceKEg4InY9"), null, Hid.s("BRsyDRY/cjambyECJQ0hOHN6gWw2AihDeg=="), start, stop))
        // Andere Liga: nicht das Zweitligaspiel
        assertNull(scores.byVenue(matches, Hid.s("CAc3SG52VS+tbSEdLUQzNw=="), null, Hid.s("BRsyDTAzZXqQaiyt/U47e1YopmclTihDdBR+P69sIgstSXo="), start, stop))
        // Kein Hinweis im Text: keins
        assertNull(scores.byVenue(matches, Hid.s("CAc3SG52JXTjSwg="), null, Hid.s("Fx4gQzojeT3jeTEcYUw5dlEopmAwDyZMNjN5Pu0="), start, stop))
        assertEquals(Hid.s("JgJz"), LiveScores.leagueOf(Hid.s("CAc3SG52JXTjSwg=")))
        assertEquals(Hid.s("JgJy"), LiveScores.leagueOf(Hid.s("d0BhYT0xdmDjWjQHJEEgN3A=")))
        assertEquals(Hid.s("JgJw"), LiveScores.leagueOf(Hid.s("BhsvSTElezOkaGQCKFsx")))
    }

    /** Foto vom 1.11.: drei Zweitliga-Spiele um 13:30, eines nur mit „Aus dem Millerntor-Stadion“ ohne Ort im Spielplan. */
    @Test
    fun remainingMatchByElimination() {
        val kickoff = 1_761_999_000_000L
        fun m(id: Long, t1: String, t2: String, league: String = Hid.s("JgJz")) = LiveScores.Match(id, league, t1, t1, t2, t2, kickoff, 0, 0, false, 0, null)
        val hannover = m(1, Hid.s("DA8vQzsgcijjMHI="), Hid.s("ABcvTDk5Nx6xbDcKJEM="))
        val fuerth = m(2, Hid.s("Fx4XSjN2UCimfDAGJF90ENTmsX0s"), Hid.s("DAEtXiAzfjTjQi0LLQ=="))
        val third = m(3, Hid.s("Ai1hfiB4NwqifCgH"), Hid.s("DAszWTw3NxiQSg=="))
        val bl1 = m(4, Hid.s("Ai1hbzUvciitKQmt/UM3PnI0"), Hid.s("BgEzWCclfjvjTSscNUAhOHM="), league = Hid.s("JgJw"))
        val matches = listOf(hannover, fuerth, third, bl1)
        val scores = LiveScores(okhttp3.OkHttpClient())
        val start = kickoff - 30 * 60_000L
        val stop = kickoff + 2 * 3_600_000L
        val open = listOf(
            LiveScores.Open(Hid.s("CAc3SG52JXTjSwg="), null, start, stop),
            LiveScores.Open(Hid.s("CAc3SG52JXTjSwhUYX47OHkuom43QwpCOjByKKZnPk6jrcd2JmrtKRceKEg4InY9"), null, start, stop),
        )
        // Hannover und Fürth sind über ihre Paarung zugeordnet: das übrige Zweitliga-Spiel gehört zur offenen Sendung.
        assertEquals(mapOf(0 to third), scores.byElimination(matches, open, setOf(hannover, fuerth)))
        // Zwei offene Einzelspiel-Sendungen, zwei übrige Spiele: nicht raten
        val two = listOf(LiveScores.Open(Hid.s("CAc3SG52JXTjSwg="), null, start, stop), LiveScores.Open(Hid.s("CAc3SG52JXTjSwg="), Hid.s("EAExXiQ/cjY="), start, stop))
        assertEquals(emptyMap<Int, LiveScores.Match>(), scores.byElimination(matches, two, setOf(hannover)))
        // Zwei offene Sendungen, ein übriges Spiel: auch nicht (beide könnten es sein)
        assertEquals(emptyMap<Int, LiveScores.Match>(), scores.byElimination(matches, two, setOf(hannover, fuerth)))
        // Ohne Liga im Titel: nichts
        assertEquals(emptyMap<Int, LiveScores.Match>(), scores.byElimination(matches, listOf(LiveScores.Open(Hid.s("AhuCsjY3ezbjZS0YJA=="), null, start, stop)), setOf(hannover, fuerth)))
    }
}
