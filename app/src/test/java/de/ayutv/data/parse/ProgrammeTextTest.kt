package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Titel aus dem echten EPG (tvspielfilm über epgshare01, Oktober 2026). */
class ProgrammeTextTest {
    @Test
    fun footballPairingComesFirst() {
        assertEquals(
            Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgREOiJlO6BhME4HXzU4fDy2ezBOaWshlYg4omUoVGFvIThzP7BlLQkgAXQifiqqaitOFUIkJWczpmVkCiRfdAF4OatsaE52A3QFZzOmZTAPJgQ="),
            ProgrammeText.headline(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg="), Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgREOiJlO6BhME4HXzU4fDy2ezBCYVk9Jn45rCkQATFeJD9yNuNtIRxhejs1fz/vKXNAYX4kP3I2t2gj")),
        )
        assertEquals(
            Hid.s("ED0GDRw5cTymZywLKEB0tJfJ4zhqTgd+AnZaO6pnPk5xGHR+US8AliYPLUFudlUvrW0hHS1EMzc3PqZ7ZCgzTCEzeXM="),
            ProgrammeText.headline(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGhkCiRfdBBlO7ZsKg=="), Hid.s("ED0GDRw5cTymZywLKEB0tJfJ4zhqTgd+AnZaO6pnPk5xGA==")),
        )
        assertEquals(Hid.s("AwsvRnR7Nwm3aCoKIF8wdlszAKEjC2EFEiPUxaFoKAJ7DR4jZzOvbDZOEV87dls/om4xC2g="), ProgrammeText.headline(Hid.s("AhuCsjY3ezb5KQ4bMUQ4M2V6k3srTg1INTFiPw=="), Hid.s("AwsvRnR7Nwm3aCoKIF8wdlszAKEjCw==")))
    }

    @Test
    fun otherSubtitlesFollowTheTitle() {
        assertEquals(Hid.s("AhuCsjY3ezb5KXZAYW8hOHM/sGUtCSANttaEepBmKgA1TDMlOhGsZyILM0g6LDt6+idkPTFEMTpjO6Q="), ProgrammeText.headline(Hid.s("AhuCsjY3ezb5KXZAYW8hOHM/sGUtCSA="), Hid.s("FwEvQyA3cCnuQisAJ0gmM3kg7yl9QGF+JD9yNrdoIw==")))
        assertEquals(Hid.s("AA8zWSdsNx+2eyseIEAxP2Qupns3DSlMMiI3uEOaZDgoSCYicjalYCoPLUg="), ProgrammeText.headline(Hid.s("AA8zWSdsNx+2eyseIEAxP2Qupns3DSlMMiI="), Hid.s("EgckXyAzezyqZyUCJA==")))
        assertEquals(Hid.s("EA8mSCcldDKifA=="), ProgrammeText.headline(Hid.s("EA8mSCcldDKifA=="), null))
        assertEquals(Hid.s("EA8mSCcldDKifA=="), ProgrammeText.headline(Hid.s("EA8mSCcldDKifA=="), " "))
        assertEquals(Hid.s("AgEtSjF2JA=="), ProgrammeText.headline("", Hid.s("AgEtSjF2JA==")))
        // Untertitel wiederholt nur den Titel
        assertEquals(Hid.s("EA81QiYiLXqHbDZOB0EhNX8="), ProgrammeText.headline(Hid.s("EA81QiYiLXqHbDZOB0EhNX8="), Hid.s("AAszDRI6Yjmr")))
    }

    @Test
    fun descriptionWithoutSourceNote() {
        assertEquals(Hid.s("AAckDTYzZC6mZ2Q9O0g6M3l0"), ProgrammeText.description(Hid.s("AAckDTYzZC6mZ2Q9O0g6M3l04yEXCy9JMSR+NKVmbUA=")))
        assertNull(ProgrammeText.description("  "))
    }

    @Test
    fun liveAndSportEvents() {
        fun event(title: String, sub: String?, category: String?, flags: Int = 0) = ProgrammeText.isSportEvent(title, sub, category, flags)
        assertEquals(true, event(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg="), Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgREOiJlO6BhME4HXzU4fDy2ezBCYVk9Jn45rCkQATFeJD9yNuNtIRxhejs1fz/vKXNAYX4kP3I2t2gj"), Hid.s("AhuCsjY3ezY=")))
        assertEquals(true, event(Hid.s("CAc3SHRkOXqBRX5OEkI6OGM7pHppJS5DMjNlP61zaE54A3QFZzOmZTAPJg=="), null, null))
        assertEquals(true, event(Hid.s("dUBhaxd2XJl1ZSpObA0SFTcJoGElAipIdGYj"), null, Hid.s("Fx4uXyA=")))
        assertEquals(true, event(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGhkCiRfdBBlO7ZsKg=="), Hid.s("fEBhfiQ/cja3aCNUYWU1O3UvsW4hHGF+Anb12lApdUBhaxd2QjSqZipOA0gmOn40"), Hid.s("AhuCsjY3ezY=")))
        // Keine Spiele: Zusammenfassungen, Magazine, Talks, Wiederholungen, Nicht-Sport
        assertEquals(false, event(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg="), Hid.s("DAcmRTg/cDK3emQ2GWFudiZ0408HTgru4jp5eiGJ104HbnQFdDKiZS8LYR1gejdt7SkXHihIOCJ2PQ=="), Hid.s("AhuCsjY3ezY=")))
        assertEquals(false, event(Hid.s("AhuCsjY3ezb5KXZAYW8hOHM/sGUtCSABdBd7NqYpFx4oSDgzO3qiZSgLYXk7JHI="), Hid.s("AAckDQI5czulZioLYWU9MX82qm4sGmx+PDlgduMwak4SXT0zey6ibg=="), Hid.s("AhuCsjY3ezY=")))
        assertEquals(false, event(Hid.s("BhsvSTElezOkaGQeNF90tJfJ4zhqTmcNZng3GLZnIAsyQT0xdg=="), Hid.s("c0BhfiQ/cja3aCNCYRx6dlUW4y9kV28NByZ+P699JQltDWZ4NxiP"), Hid.s("AhuCsjY3ezauaCMPO0Q6")))
        assertEquals(false, event(Hid.s("AAExXTE6ZzuwemQiKFsx"), null, Hid.s("AhuCsjY3ezawfSUDLFk9JXQy")))
        assertEquals(false, event(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg="), Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgREOiJlO6BhME4HXzU4fDy2ezA="), Hid.s("AhuCsjY3ezY="), de.ayutv.data.db.ProgrammeFlags.REPEAT))
        assertEquals(false, event(Hid.s("EA81QiYi"), Hid.s("AAszDRI6YjmrKabu0g0AM3424zs="), Hid.s("DxwoQD0=")))
        assertEquals(Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgREOiJlO6BhME4HXzU4fDy2ezA="), ProgrammeText.pairing(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg="), Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgREOiJlO6BhME4HXzU4fDy2ezBCYRp6dkQqqmwoGiBK")))
        // Andere Schreibweisen der Paarung
        assertEquals(true, event(Hid.s("FBwkQD0zZXqPbCUJNEhudlsztWw2Hi5COHZhKe0pBwYkQSczdg=="), null, Hid.s("AgEuWTY3ezY=")))
        assertEquals(true, event(Hid.s("CAc3SCYmeDWvKTJOAkUxOmQ/og=="), null, Hid.s("Fx4uXyA=")))
        assertEquals(true, event(Hid.s("Ai1hbzUvciitKQmt/UM3PnI0424hCSRDdGc5eoVKZCMgSjAzdS+xbg=="), null, Hid.s("AhuCsjY3ezY=")))
        // Sportsender ohne Sport-Kategorie im TV-Guide: Paarung genügt, Magazine bleiben draußen
        fun onSportChannel(title: String, sub: String?, category: String?) = ProgrammeText.isSportEvent(title, sub, category, 0, sportChannel = true)
        assertEquals(true, onSportChannel(Hid.s("Ai1hYT0gciizZisCYc/UxTccgCkHBiRBJzN2"), null, null))
        assertEquals(false, onSportChannel(Hid.s("FwU4DQcmeCi3KQoLNl4="), null, Hid.s("Cg8iRSY/dDK3bCo=")))
        assertEquals(false, onSportChannel(Hid.s("DAcmRTg/cDK3en5OB250Gn4spns0AS5BdLSXyeNPB04CRTE6ZD+i"), null, null))
        assertEquals(true, ProgrammeText.isSportChannel(Hid.s("OCoEUXQFXAPjWhQhE3l0BkUfjkABPGFhERdQD4YpAiYF")))
        assertEquals(true, ProgrammeText.isSportChannel(Hid.s("AC8bY3RnNxKH")))
        assertEquals(false, ProgrammeText.isSportChannel(Hid.s("AA8yDREkZC6mKQwq")))
        assertEquals(listOf(Hid.s("CAc3SCYmeDWv"), Hid.s("BwYkQSczdg==")), Teams.ofPairing(Hid.s("CAc3SCYmeDWvKTIdbw0XPnI2sGwl")))
    }

    @Test
    fun gamesWithTheSameTitleStaySeparate() {
        // Sender: parallele Pokalspiele heißen alle „Live: DFB-Pokal“, die Paarung steht in der Beschreibung.
        val hertha = Hid.s("DAszWTw3NxiQSmSMwb50Zzl6hUpkJYKbODg5eodgIU4DSCY6fjSme2QLLF0yN3k9pmdkBywNFTV/LqZlIgcvTDgzNz6mZ2QsNEMwM2Q2qm4tHTVIOng=")
        val bayern = Hid.s("Ai1hbzUvciitKQmt/UM3PnI04383QGEcenZRGeNEJQklSDYjZT3tKQALMw0GM3w1sW00ASpMOCV+P6RsNk4mSDMzeXqnbCpOG1oxP2M2qm4tHTVIOng=")
        assertEquals(Hid.s("DAszWTw3NxiQSmSMwb50Zzl6hUpkJYKbODg="), ProgrammeText.descriptionPairing(hertha))
        assertEquals(Hid.s("Ai1hbzUvciitKQmt/UM3PnI04383QGEcenZRGeNEJQklSDYjZT0="), ProgrammeText.descriptionPairing(bayern))
        assertEquals(Hid.s("DAszWTw3NxiQSmSMwb50Zzl6hUpkJYKbODg3co9gMgt7DRAQVXeTZi8PLQQ="), ProgrammeText.sportHeadline(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, hertha))
        // Kein Satz als Paarung
        assertNull(ProgrammeText.descriptionPairing(Hid.s("AAszDRIVNxiicCEcLw0gJH4utyksCzRZMXZ+N+NIJwY1SDgwfjSiZSFOJkgzM3l6p2wqThtaMT9jNqpuLR01SDp2di+wKQkPJkkxNGIopCklAG8=")))
        assertNull(ProgrammeText.descriptionPairing(Hid.s("BRsyDTAzenqQbCgGNF8nIjcKonsvTihDdBp4NKdmKkJhaiY51MWhey0aIEM6P3I07Q==")))

        fun key(title: String, sub: String?, desc: String?, channel: String) = ProgrammeText.sportKey(title, sub, desc, channel)
        // Verschiedene Spiele, gleicher Titel: getrennt
        assertNotEquals(key(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, hertha, Hid.s("FwU4AwcmeCi3J3dAJUg=")), key(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, bayern, Hid.s("FwU4AwcmeCi3J3BAJUg=")))
        // Ohne Beschreibung (oder nur Platzhalter) bleibt jeder Sender für sich
        assertNotEquals(key(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, null, Hid.s("FwU4AwcmeCi3J3dAJUg=")), key(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, null, Hid.s("FwU4AwcmeCi3J3BAJUg=")))
        assertNotEquals(
            key(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, Hid.s("DQAnQiY7di6qZioLLw0uIzc+qmw3CywNBCR4PbFoKQNhSzs6cD+tKS0AYWaX6mUgpig="), Hid.s("FwU4AwcmeCi3J3dAJUg=")),
            key(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, Hid.s("DQAnQiY7di6qZioLLw0uIzc+qmw3CywNBCR4PbFoKQNhSzs6cD+tKS0AYWaX6mUgpig="), Hid.s("FwU4AwcmeCi3J3BAJUg=")),
        )
        // Dasselbe Spiel auf zwei Sendern, anders geschrieben: zusammen
        assertEquals(
            key(Hid.s("CAc3SG52UxyBJBQBKkw4"), null, hertha, Hid.s("FwU4AwcmeCi3J3dAJUg=")),
            key(Hid.s("AhuCsjY3ezb5KQAoAwAEOXw7rw=="), Hid.s("DAszWTw3NxiQSmRDYRx6dlEZ40KH2C1DeHZWOat9IQInRDo3ez8="), null, Hid.s("FwU4AwcmeCi3JxABMQMRIHI0tycgCw==")),
        )
        assertEquals(key(Hid.s("AgEzQDE6N2v5KQMcLu7LM2V6k3shBzINIjl5eo5sPAcqQg=="), Hid.s("FgsvQzE4"), Hid.s("CAc3SHQ3YinjRCEWKEY7e0Quom0wQA=="), Hid.s("FjoNAzAz")), key(Hid.s("AgEzQDE6N2v5KQMcLu7LM2V6k3shBzINIjl5eo5sPAcqQg=="), Hid.s("FgsvQzE4"), Hid.s("CAc3SHQ3YinjRCEWKEY7e0Quom0wQA=="), Hid.s("FwU4AwcmeCi3JwJfb0kx")))
        // Spiel nur in der Beschreibung genügt auf Sportsendern; Vorschauen „Es folgt“ zählen nicht
        assertEquals(true, ProgrammeText.isLive(Hid.s("ACgDAAQ5fDuv"), null, null, 0, true, hertha))
        assertEquals(false, ProgrammeText.isSportEvent(Hid.s("AR1hSzs6cC75KQgnF2h0FHYpqGwwDCBBOHZZGII="), null, null, 0, sportChannel = true))
        assertEquals(true, ProgrammeText.isSportEvent(Hid.s("CAc3SHQGW2DjSjYXMlk1OjcKomUlDSQNeXZRGeNPMQIpTDl6N2v6J2Q9MUQxOmM7pA=="), null, null, 0, sportChannel = true))
    }

    @Test
    fun conferenceAndMultiviewAreLive() {
        fun chanB(title: String, sub: String?, cat: String?, flags: Int = 0) = ProgrammeText.isSportEvent(title, sub, cat, flags, sportChannel = ProgrammeText.isSportChannel(Hid.s("AC8bY3RnNxKH")))
        // Sender/Sender: Konferenz ohne Paarung und ohne „Live“ im Titel ist trotzdem live.
        assertEquals(true, chanB(Hid.s("AAckDR85eTymeyEAOw=="), Hid.s("BhsvSTElezOkaGhOcB96dkQqqmwoGiBK"), null))
        // So auf Sender 1 (Foto): Saison und Spieltag im Titel.
        assertEquals(true, ProgrammeText.isSportEvent(Hid.s("BhsvSTElezOkaGQlLkMyM2U/rXNkXHcCZmE3uEOaZFdvDQcmfj+vfSUJ"), null, null, 0, sportChannel = ProgrammeText.isSportChannel(Hid.s("ACt7DRAXTRTjOGQ8AHp0flwbgUwIRw=="))))
        assertEquals(true, ProgrammeText.isLive(Hid.s("BhsvSTElezOkaGQlLkMyM2U/rXNkXHcCZmE3uEOaZFdvDQcmfj+vfSUJ"), null, null, 0))
        assertEquals(Hid.s("BhsvSTElezOkaGSMwb50HXg0pWw2Cy9XdH4udONaNAckQSA3cHM="), ProgrammeText.eventTitle(Hid.s("BhsvSTElezOkaGQlLkMyM2U/rXNkXHcCZmE3uEOaZFdvDQcmfj+vfSUJ"), null, null))
        assertEquals(true, chanB(Hid.s("BhsvSTElezOkaA=="), Hid.s("DwEvSzEkcjS5KSUDYX41O2Quom4="), Hid.s("AhuCsjY3ezY=")))
        assertEquals(true, chanB(Hid.s("ESsHbHQVfzuueS0BL150GnI7pHwhTqOtx3ZTM6YpDwEvSzEkcjS5"), null, null))
        assertEquals(true, ProgrammeText.isSportEvent(Hid.s("dkBhbyE4cz+wZS0JIA0fOXk8pnshADs="), null, Hid.s("AhuCsjY3ezY="), 0))
        assertEquals(true, chanB(Hid.s("CigNDQYzcwCsZyFODFg4In4sqmwz"), null, null))
        assertEquals(true, ProgrammeText.isSportEvent(Hid.s("BhsvSTElezOkaGSP9ZW14LG7dpKl28g="), Hid.s("Ai1hbzUvciitKQmt/UM3PnI0"), null, 0, sportChannel = true))
        // Zusammenfassungen, Wiederholungen und Pressekonferenzen bleiben draußen.
        assertEquals(false, chanB(Hid.s("DAcmRTg/cDK3emQKJF90HXg0pWw2Cy9X"), Hid.s("BhsvSTElezOkaA=="), null))
        assertEquals(false, chanB(Hid.s("AAckDR85eTymeyEAOw=="), Hid.s("BhsvSTElezOkaA=="), null, flags = de.ayutv.data.db.ProgrammeFlags.REPEAT))
        assertEquals(false, ProgrammeText.isSportEvent(Hid.s("FBwkXiczfDWtbyEcJEMu"), null, Hid.s("Cg8iRSY/dDK3bCo="), 0))
        // Ein Fußball-Sender gilt als Sportsender.
        assertEquals(true, ProgrammeText.isSportChannel(Hid.s("FwU4DRYjeT6meigHJkx0ZzcShw==")))
        assertEquals(true, ProgrammeText.isSportEvent(Hid.s("DwEvSzEkcjS5"), null, null, 0, sportChannel = ProgrammeText.isSportChannel(Hid.s("FwU4DRYjeT6meigHJkx0ZzcShw=="))))
    }

    @Test
    fun sportGroupsKeepDifferentGamesApart() {
        val desc = Hid.s("CAc3SHmVizimezAcIEohOHB6p2w2TnMDdBRiNKdsNwIoSjV4")
        // Gleicher Allgemeintext auf zwei verschiedenen Sendern: zwei Einträge
        val a = ProgrammeText.sportGroupKey(Hid.s("CAc3SG52JXTjSwg="), null, desc, Hid.s("NwU4AzY6JXSnbA=="), Hid.s("NwU4XiQ5ZS6hfCoKJF44P3A78Q=="))
        val b = ProgrammeText.sportGroupKey(Hid.s("CAc3SG52JXTjSwg="), null, desc, Hid.s("NwU4AzY6JHSnbA=="), Hid.s("NwU4XiQ5ZS6hfCoKJF44P3A78A=="))
        assertNotEquals(a, b)
        // Dieselbe Sendung in HD und 4K desselben Senders: ein Eintrag
        assertEquals(a, ProgrammeText.sportGroupKey(Hid.s("CAc3SG52JXTjSwg="), null, desc, Hid.s("NwU4AzY6JXS2YSBAJUg="), Hid.s("NwU4XiQ5ZS6hfCoKJF44P3A78Q==")))
        // Mit Paarung: über alle Sender ein Eintrag
        assertEquals(
            ProgrammeText.sportGroupKey(Hid.s("DA8vQzsgcijjMHJOo63HdlMjrWgpAWFpJjNkPqZn"), null, null, "x", Hid.s("NwU4XiQ5ZS6hfCoKJF44P3A78Q==")),
            ProgrammeText.sportGroupKey(Hid.s("DA8vQzsgcijjMHJOo63HdlMjrWgpAWFpJjNkPqZn"), null, null, "y", Hid.s("NwU4XiQ5ZS63ZjQLN0g6Ig==")),
        )
    }

    /** Titel aus dem echten EPG von Sender und Sender (Januar 2026). */
    @Test
    fun realSportGuideTitles() {
        fun on(channel: String, title: String, category: String? = Hid.s("AhuCsjY3ezY=")) =
            ProgrammeText.isSportEvent(title, null, category, 0, sportChannel = ProgrammeText.isSportChannel(channel))
        // Einzelspiele, wie Sender und Sender sie nennen
        assertEquals(true, on(Hid.s("AC8bY3Rn"), Hid.s("FwszRDF2VnrxPGtcdxd0GnYgqmZkPC5AdHs3CZBKZCAkTCQzew==")))
        assertEquals(true, on(Hid.s("AC8bY3Rk"), Hid.s("BhsvSTElezOkaH5OcAN0EFR6i2wtCiRDPDN+N+MkZCgCDRY3bj+xZ2QjgpE6NX8/rQ=="), Hid.s("BhsvSTElezOkaA==")))
        assertEquals(true, on(Hid.s("FwU4DQcmeCi3KXc="), Hid.s("FCJ7DRUQVHqBZjEcL0g5OWIuqylpThVCICJyNKtoKU4JQiAlZy+xJWRccAN0BWczpmUwDyY=")))  // „Hotspur“ ist kein „pur“
        assertEquals(true, on(Hid.s("FwU4DQcmeCi3KRQcJEA9M2V6j2wlCTRI"), Hid.s("FCJ7DQMzZC7jQSUDYXg6P2M/pylpTg9CICJ+NKRhJQNhazskcim3JWRccAN0BWczpmUwDyY=")))
        assertEquals(true, on(Hid.s("Fx4uXyAyfj2qfSUCYWshJWQ4omUoTnM="), Hid.s("BUMNSDUxYj/jIXVebw0HJn4/r30lCWgXdBtyNqFmMRwvSHQVfi66KWlOEUgmIn96hGUrHDg="), null))
        // Konferenzen, auch mit „alle Spiele“ im Titel
        assertEquals(true, on(Hid.s("FwU4DQcmeCi3KRQcJEA9M2V6j2wlCTRI"), Hid.s("FCJ7DQc5eTS3aCMdbGY7OHE/sWwqFG0NZmY5epB5LQstWTUx")))
        assertEquals(true, on(Hid.s("AC8bY3Rn"), Hid.s("ESsHbHQVfzuueS0BL150GnI7pHwhTnMYe2QhYONNLQthZjs4cT+xbCoUYQB0N3s2pikXHihIODM7eqJlKAtheTskcg==")))
        assertEquals(true, on(Hid.s("AC8bY3Rn"), Hid.s("ESsHbHQVfzuueS0BL150GnI7pHwhVGFmOzhxP7FsKhQ="), Hid.s("AhuCsjY3ezY=")))
        // Kein Live-Spiel: Rückblicke, Kurzfassungen, Dokus, Vereinssender, Serien mit Bindestrich
        assertEquals(false, on(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGg="), Hid.s("dkBhbxhsNxKiZyoBN0gmdi5s4yRkJihDJiN5PqYpdltsH2I=")))
        assertEquals(false, on(Hid.s("FwU4DQcmeCi3KRQcJEA9M2V6j2wlCTRI"), Hid.s("FCJ7DW1mfjTwOX5ODG4ddjp6gEEBQmEfZHg3CbNgIQI1TDM=")))
        assertEquals(false, on(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGg="), Hid.s("BiJ7DRkvYzKsemQsLl8hJWQzoilpTgREOjM3FqZuIQAlSHQhfiinKXVcdAF0M2d04zg=")))
        assertEquals(false, on(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGg="), Hid.s("BiJ7DR86dimwYC8LMw0wM2V6lGYnBiQXdAVQH+MkZCwXb3R+Jm3sOHxH")))
        assertEquals(false, on(Hid.s("FwU4DQcmeCi3KRQcJEA9M2V6j2wlCTRI"), Hid.s("FCJ7DRc6dimwYCdODEwgNX9g408HTgdYOD52N+MkZCgCDRc+cjawbCU=")))
        assertEquals(false, on(Hid.s("FwU4DQcmeCi3KRQcJEA9M2V6j2wlCTRI"), Hid.s("FCJ7DRIzZT2qbGRDYX89JXJ6rG9kD2FpLTh2Kbdw")))
        assertEquals(false, on(Hid.s("Fx4uXyAyfj2qfSUCYWshlYg4omUo"), Hid.s("CCgCDQAAN3fjTwVOAlgk")))
        assertEquals(false, on(Hid.s("Fx4uXyB2JnqzZTEd"), Hid.s("DA80Xjkzfim3bDZOCl81I2Q/4yRkITNJOiN5PeNkMR0yDSczfjQ="), null))
        assertEquals(false, on(Hid.s("AC8bY3Rk"), Hid.s("CigNDRA5ej+wfS0New0aEFt6hkcANA8NeXZANaBhIU5wFQ=="), null))
        assertEquals(false, on(Hid.s("AC8bY3Rn"), Hid.s("AAExXTE6ZzugYmQrNF87JnZ67ikMByZFOD9wMrd6ZAokX3QFciiqbGQvYVg6MjcWqm4xC2EcdGQnaPYmdlh7DWVuOXXyPmpOEl09M3suom4="), Hid.s("Fx4uXyA=")))
        assertEquals(false, on(Hid.s("FwU4DQcmeCi3KRQcJEA9M2V6j2wlCTRI"), Hid.s("FCJ7DRw/cDKvYCMGNV50BXY3sH0lCW0NZmY5epB5LQstWTUx")))
    }

    /** Wie auf dem Foto vom Fernseher (November 2026): verschiedene Anbieter, verschiedene Titel für dasselbe Spiel. */
    @Test
    fun sportTitlesAreUniform() {
        assertEquals(Hid.s("BhsvSTElezOkaA=="), ProgrammeText.eventTitle(Hid.s("CAc3SG52VRY="), null, Hid.s("BRsyDTAzZXqRbCBOA1g4OjcbsWwqD20NEDNiLrBqLAIgQzB4")))
        assertEquals(Hid.s("dUBhawcANxeiYCoUYR1hdvXaUCkCLWFvNS9yKK0pCa39Qzc+cjQ="), ProgrammeText.eventTitle(Hid.s("CAc3SG52VRY="), Hid.s("dUBhawcANxeiYCoUYR1hdjp6hUpkLCBUMSR5eo7K+AAiRTE4"), null))
        assertEquals(Hid.s("FixhYTE/ZyCqbmSMwb50AHEW414rAideNiNlPQ=="), ProgrammeText.eventTitle(Hid.s("CAc3SG52VRY="), null, null, pairing = Hid.s("FixhYTE/ZyCqbmRDYXsyGjcNrGUiHSNYJjE=")))
        assertEquals(Hid.s("CA87RDt2RTWuKabu0g0HBVR6jWwlHiRB"), ProgrammeText.eventTitle(Hid.s("FwszRDF2VnrxPGtcdxd0GnYgqmZkPC5AdHs3CZBKZCAkTCQzew=="), null, null))
        assertEquals(Hid.s("dkBhbyE4cz+wZS0JIA221oR6iGYqCCRfMTht"), ProgrammeText.eventTitle(Hid.s("CAc3SG52JXTjSwhOo63Hdlw1rW8hHCRDLg=="), null, null))
        assertEquals(Hid.s("AgEzQDE6N2vjJGQjEm50FWUvqnohHWFqJjd5PuNZNgc5DSI5eXqQaCtOEUwhOnh6IYnXTnIDdBBlP6psN04VXzU/eTOtbg=="),
            ProgrammeText.eventTitle(Hid.s("AgEzQDE6N2vjJGQjEm50FWUvqnohHWFqJjd5PuNZNgc5DSI5eXqQaCtOEUwhOng="), Hid.s("d0BhayYzfj+wKRAcIEQ6P3k9"), null))
        assertEquals(Hid.s("BhsvSTElezOkaA=="), ProgrammeText.competition(Hid.s("CAc3SG52VRY=")))
        assertEquals(Hid.s("FBwkQD0zZXqPbCUJNEg="), ProgrammeText.competition(Hid.s("FCJ7DRUQVHqBZjEcL0g5OWIuqylpThVCICJyNKtoKU4JQiAlZy+xJWRccAN0BWczpmUwDyY=")))
        assertEquals(Hid.s("FwszRDF2Vg=="), ProgrammeText.competition(Hid.s("FwszRDF2VnrxPGtcdxd0GnYgqmZkPC5AdHs3CZBKZCAkTCQzew==")))
        assertEquals(Hid.s("BhsvSTElezOkaA=="), ProgrammeText.competition(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg=")))
    }

    @Test
    fun sameMatchFromTwoGuidesOnce() {
        assertEquals(true, ProgrammeText.samePairing(Hid.s("dUBhawcANxeiYCoUYR1hdvXaUCkCLWFvNS9yKK0pCa39Qzc+cjQ="), Hid.s("CQ8oQy52J2/jJGQsIFQxJHl6jsr4ACJFMTg=")))
        assertEquals(false, ProgrammeText.samePairing(Hid.s("dUBhawcANxeiYCoUYR1hdvXaUCkCLWFvNS9yKK0pCa39Qzc+cjQ="), Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXThJ7dGYgeoZlMgszXjYzZT0=")))
        data class E(val start: Long, val pairing: String?, val channels: List<String>)
        val hour = 3_600_000L
        val merged = ProgrammeText.mergeSameMatch(
            listOf(
                E(0, Hid.s("dUBhawcANxeiYCoUYR1hdvXaUCkCLWFvNS9yKK0pCa39Qzc+cjQ="), listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkOwlp"))),
                E(15 * 60_000L, Hid.s("CQ8oQy52J2/jJGQsIFQxJHl6jsr4ACJFMTg="), listOf(Hid.s("FwU4DRM5Nxi2ZyALMkE9MXZ68ilwJQ=="))),   // anderer Anbieter, 15 min später
                E(5 * 60_000L, null, listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXA=="))),                           // ohne Paarung: bleibt
                E(5 * hour, Hid.s("CQ8oQy52J2/jJGQsIFQxJHl6jsr4ACJFMTg="), listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXw=="))),       // Wiederholung: entfällt
            ),
            { it.start }, { it.pairing },
        ) { a, b -> a.copy(channels = a.channels + b.channels) }
        assertEquals(2, merged.size)
        assertEquals(listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkOwlp"), Hid.s("FwU4DRM5Nxi2ZyALMkE9MXZ68ilwJQ==")), merged[0].channels)
    }

    @Test
    fun channelNamesUniform() {
        assertEquals(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXGHv43YjEQ=="), ProgrammeText.channelLabel(Hid.s("FyUYahtsNwmIUGQ9EWIGAjcYlkcAKxJhHRFWevEpcCU=")))
        assertEquals(Hid.s("AC8bY3RnN5h0KRYvFg=="), ProgrammeText.channelLabel(Hid.s("ACt7DRAXTRTjOGSP9ZK14ru7dotkRhJsAH8=")))
        assertEquals(Hid.s("AC8bY3RnN5h0KRYvFg=="), ProgrammeText.channelLabel(Hid.s("ACt7DRAXTRTjOGQ8AHp0flwbgUwIRw==")))
        assertEquals(Hid.s("FwU4DQcmeCi3KYbZYRkf"), ProgrammeText.channelLabel(Hid.s("cCV7DQcdTnqQWQs8FQ2145a7d72l2vENluX123vrxdqjrOS3o+TjIQgnF2h5E0EfjV1t")))
        assertEquals(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXw=="), ProgrammeText.channelLabel(Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdGc3Eoc=")))
        assertEquals(Hid.s("FwU4DQcmeCi3KRABMQ0RIHI0tw=="), ProgrammeText.channelLabel(Hid.s("FwU4DQcmeCi3KRABMQ0RIHI0tw==")))
    }
}
