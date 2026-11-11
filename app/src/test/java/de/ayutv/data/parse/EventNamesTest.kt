package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class EventNamesTest {
    // So., 01.11.2026, 15:32 deutscher Zeit (MEZ)
    private val now = ZonedDateTime.of(2026, 11, 1, 14, 32, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun utc(h: Int, m: Int, day: Int = 1) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun providerFormats() {
        val a = EventNames.parse(Hid.s("CisZeXQqNxKCRAY7E2oRBDcJlSkNJ2F7B3ZfG41HCzgEf3RvIXqKQGQSYX4hODdq8ikKATcNZWItavMpBysVDXwSUnPjdWRWCg0RDlQWlloNOAQNKHZTH/kpCCsAagETRHqFRgs6A2wYGjcKk19kWw=="), now)!!
        assertEquals(Hid.s("DC8MbwEEUB+RKRc4YWQddkEJ40EFIA9iAhNFevo/ZCcI"), a.title)
        assertEquals(utc(13, 0), a.start)
        assertEquals(EventNames.State.NEXT, a.state)

        val b = EventNames.parse(Hid.s("Cgs5WXQqNxyAKRcabw0EN2I2qikyHW8NZXg3HIApCq39Xzo0ciikKThOIEE4dmt68zhpX3AAZmYlbON1ZF9yF2RmN3KERBBHYVF0blx6hlEHIhR+HQBSer8pACt7DQcZVBmGW2Q+EXt0ZCY="), now)!!
        assertEquals(Hid.s("Ai1hfiB4NwqifCgHYVsneDdr7SkCLWFjl+plNKFsNgk="), b.title)
        assertEquals(utc(13, 0), b.start)

        val c = EventNames.parse(Hid.s("Cgs5WXQqNxC2fyEANVgndjp6jWwlHiRBdCo3CaZ7LQthbHQqN2jzO3JDcBx5ZiZ6vyl1V3sZYXY/HY5dbU49DWwdNx+bSgg7EmQCEzcm400BVGFpFQxZepNZEk5w"), now)!!
        assertEquals(Hid.s("Dhs3SDoiYinjJGQgJEwkM3t6IYnXThJIJj9yeoI="), c.title)
        assertEquals(utc(19, 45), c.start)

        val d = EventNames.parse(Hid.s("CisZeXQqNw2GTA9OeQ0odkUblUwKPWFsAHZVE49FF049DQcjeXrzOGQgLlt0Zy5g8zlkLQR5dH5TH+opOE55ZnQTTxmPXBcnF2h0KjcehjNkPBVhf3ZHCpUpdw=="), now)!!
        assertEquals(Hid.s("EysEZnRuN7hDmmQ8AHsRGER6gl1kLAhhGAU="), d.title)
        assertEquals(utc(18, 0), d.start)

        // Live ohne Uhrzeit: läuft jetzt
        val e = EventNames.parse(Hid.s("CAc3SHQqNwmtZisFJF9udl40t2w2ACBZPTl5O68pBwYgQCQ/eDSwYS0ebQ1leDcItmcgC2FRdDd7NuN1ZFYKDREOVBaWWg04BA0odlMf+SkXIQJuEQQ3CpNfZF9x"), now)!!
        assertEquals(Hid.s("FwAuQj8zZWDjQCoaJF86N2MzrGclAmFuPDd6KqpmKh0pRCR6N2vtKRYbL0kx"), e.title)
        assertTrue(e.start <= now && e.stop > now)
        assertEquals(EventNames.State.LIVE, e.state)

        // Live mit uraltem Datum (Name nicht aktualisiert): kein tagelanger Block
        val f = EventNames.parse(Hid.s("CCcXaHQqNw2GTA9Odg0odkMSllsXKgB0dBheHYtdfk4RbAAEXhWXWmQvFQ0WE1YIkCk4TgdfPXYlaeNGJxphHWZsJmrjSgE9FQ18ElJz43VkVgoNEQ5UFpZaDTgEDSh2Ux/5KRY6DQZ0BkcM4zg="), now)!!
        assertTrue(f.stop - f.start < 4 * 3_600_000L)
        assertTrue(f.start <= now && f.stop > now)
    }

    @Test
    fun nothingForEndedIdleOrNormalChannels() {
        assertNull(EventNames.parse(Hid.s("ASAFaBB2a3qGWhJOA3gGEVYP418XTgRudAxeCI8pOE4SWDp2J2vjRysYYRxkbCVv40oBOmEFEBM+er8pfCVhaAwVWw+QQBIrYVF0ElJg41oQLwhqEXZHCpUpdQ=="), now))
        assertNull(EventNames.parse(Hid.s("AQAlDSh2RD+sfChOcx1mYDd341otACZBMSU3HKpnJQJhAHRuNybjaCgCYVF0ZiZ38jhpXHEfYnZrevM/fl5xDXwRWg7qKThOeWZ0E08Zj1wXJxdodCo3HoYzZD0ObhcTRXqTWRJOcA=="), now))
        assertNull(EventNames.parse(Hid.s("aU4PYnQTQR+NXWQ9FX8RF1oTjU5kQ2FRdG5ceoZRByIUfh0AUnq/KQArew0QD1l6k1kSTnIU"), now))
        assertNull(EventNames.parse(Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdGc3Eoc="), now))
        assertNull(EventNames.parse(Hid.s("ACs9DQcdTnqLTBItew0QEy16kEIdTgJkGhNaG+NLAT0VDRsQNxKGXwc="), now))
        assertTrue(EventNames.isIdle(Hid.s("aU4PYnQTQR+NXWQ9FX8RF1oTjU5kQ2FRdG5ceoZRByIUfh0AUnq/KQArew0QD1l6k1kSTnIU")))
        assertTrue(EventNames.isSeparator(Hid.s("Z01iDnd2RBGaKQkhF2QRBTcShyZwJWEOd3U0eQ==")))
        assertTrue(EventNames.isSeparator(Hid.s("Z01iDQMZQHqQWQs8FQ214qO7d7lkj/WdteKru3expdrv5+x29u5v6PHvoJnkt6PvIr34TmIOdw==")))
        assertFalse(EventNames.isSeparator(Hid.s("ACt7DQYCW3oivMWP9Zm14qd68DFwXhE=")))
        assertFalse(EventNames.isSeparator(Hid.s("aU4PYnQTQR+NXWQ9FX8RF1oTjU5kQ2FRdBJSYONNHSBhfQQAN2n6")))
    }

    @Test
    fun eventsAreSportForTheSportScreen() {
        val a = EventNames.parse(Hid.s("CCcXaHQqNxcAlQotCWMRBDcJgClsKmgNeXZTH5EpByIUb3QXWXqHTBZOAGEHAlII4yEAR2FRdAViNOM5dU4PQiJ2Jmv5O3FOAmgAdj8ehiBkEmEVH3ZSAoBFET0IexF2a3qHTH5OBXQadkcKlSl2"), now)!!
        assertEquals(Hid.s("Ca3dYxceWR+RKRctYQUQfzd3400BPGFuGANVeoJHZCoEf3QXWwmXTBZOaWl9"), a.title)
        assertTrue(ProgrammeText.pairing(a.title, null) != null)
    }

    @Test
    fun uhdVersionsShareTheEventOfTheirSibling() {
        assertEquals(Hid.s("IBcvXSQgJQ=="), EventNames.baseKey(Hid.s("CCcXaHQqNxcAlQotCWMRBDcJgClsKmgNeXZTH5EpByIUb3QXWXqHTBZOAGEHAlII4yEAR2FRdAViNOM5dU4PQiJ2Jmv5O3FOAmgAdj8ehiBkEmEVH3ZSAoBFET0IexF2a3qHTH5OBXQadkcKlSl2")))
        assertEquals(Hid.s("IBcvXSQgJQ=="), EventNames.baseKey(Hid.s("ACt7DRAPWXqTWRJOcw1gHQ==")))
        assertEquals(Hid.s("IBcvXSQgJQ=="), EventNames.baseKey(Hid.s("ACt7DRAPWXqTWRJOcw2145a7d72l2vE=")))
        assertEquals(Hid.s("IA87QyQmYWvx"), EventNames.baseKey(Hid.s("Cgs5WXQqNxC2fyEANVgndjp6jWwlHiRBdCo3CaZ7LQthbHQqN2jzO3JDcBx5ZiZ6vyl1V3sZYXY/HY5dbU49DWwdNx+bSgg7EmQCEzcm400BVGFpFQxZepNZEk5wHw==")))
        // Ohne Nummer kein Event-Sender: nie das Spiel eines anderen übernehmen
        assertNull(EventNames.baseKey(Hid.s("cCV7DQcdTnqQWQs8FX50t6LbIr3wj/WddJSkuEKxpu/1z9Xm9u59KWwrF2gaAj4=")))
        assertNull(EventNames.baseKey(Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdLei2yK98I/1nXSUpLhCsabv9c/V5vbufQ==")))
    }

    @Test
    fun channelsThatCannotHaveAGuide() {
        // Kurzbericht 01.11.2026: nicht als „fehlt“ zählen
        assertEquals(Hid.s("ARgkQyB7RD+tbSEcYUI8OHJ6r2gxCCRDMDNkepB5LQst"), EventNames.noGuideReason(Hid.s("ASAFaBB2a3qLWgNOFmgADFsbkSlpTgx5dBtSFpBcCikEY3QqNwm2Z2RecA0aOWF68j1+WnQNFxNDeutNAUdhUXRuXHqGUQciFH4dAFJ6vykAK3sNEA9ZepNZEk5wFA=="), Hid.s("ACs9DRAPWXqTWRJOBHUXGkIJil8B")))
        assertEquals(Hid.s("ARgkQyB7RD+tbSEcYUI8OHJ6r2gxCCRDMDNkepB5LQst"), EventNames.noGuideReason(Hid.s("aU4PYnQTQR+NXWQ9FX8RF1oTjU5kQ2FRdG5ceoZRByIUfh0AUnq/KQArew0QD1l6k1kSTnIU"), Hid.s("ACs9DRAPWXqTWRJOBHUXGkIJil8B")))
        assertEquals(Hid.s("dlpuGnkFcjSnbDZOaWk1I3IosGosAiREMjM+"), EventNames.noGuideReason(Hid.s("ACt7DRMXWh/jRgJOFWUGGVkfkCkXXnANYB0="), Hid.s("ACs9DWZiOG3jRAUpBGMAFzcJhlsNK2EZHw==")))
        assertEquals(Hid.s("ARgkQyB7RD+tbSEcYUI8OHJ6kHktCy1MOjF2OKY="), EventNames.noGuideReason(Hid.s("ACt7DRkXUB+NXQVOEn0bBEN6k1kSTnINteKju3e5"), Hid.s("ACs9DRkXUB+NXQVOEX0Cdvbud+jw3mHM4OX27n/o8NagmeQ=")))
        assertEquals(Hid.s("ARgkQyB7RD+tbSEcYUI8OHJ6kHktCy1MOjF2OKY="), EventNames.noGuideReason(Hid.s("ACthahtsNx6CUwpOcg0cEjcBj0ASK2xoAhNZDp4="), Hid.s("ACs9DRAXTRTjWRQ4YX4fDzcdjA==")))
        assertEquals(Hid.s("ARgkQyB7RD+tbSEcYUI8OHJ6kHktCy1MOjF2OKY="), EventNames.noGuideReason(Hid.s("cCV7DQAYQ3qQWQs8FX50t6LbIr3wj/WddJSkuEKxpu/1z9Xm9u59KWwrF2gaAj4="), "4K| ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals(Hid.s("EBwkQzoscjOvbCo="), EventNames.noGuideReason(Hid.s("Z01iDnd2RBGaKQkhF2QRBTcShyZwJWEOd3U0eQ=="), Hid.s("ACs9DQcdTnqORhInBH50HlN190I=")))
        // Echte Sender fehlen wirklich
        assertNull(EventNames.noGuideReason(Hid.s("ACt7DQcdTnqQWQs8FQ1lZzcShw=="), Hid.s("ACs9DQcGWAiXKQwqbhkf")))
        assertNull(EventNames.noGuideReason(Hid.s("ACt7DRMTWHqLTQ=="), Hid.s("ACs9DRMTWR+RSAhOCWl7Ylw=")))
        assertNull(EventNames.noGuideReason(Hid.s("cCV7DQcdTnqQWQs8FX50G1YTjSkBOARjAAU3u3aIpdr1zODmN5hw68XWo6zgtJbqIr36"), "4K| ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
    }
}
