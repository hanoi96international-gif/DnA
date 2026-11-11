package de.ayutv.data.parse

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelQualityTest {
    private data class Ch(val key: String, val name: String)

    @After
    fun reset() {
        ChannelQuality.clear()
        ChannelQuality.preferUhd = false
        ChannelQuality.minHeight = 0
        ChannelQuality.hideBroken = false
    }

    private fun best(vararg channels: Ch) =
        ChannelVariants.merge(channels.toList(), { it.name }, { 1L }, { ChannelQuality.score(it.key, it.name) }).single().name

    @Test
    fun bestVariantByNameAndMeasurement() {
        assertEquals(Hid.s("ACt7DQYCW3qFQQA="), best(Ch("a", Hid.s("ACt7DQYCW3qQTQ==")), Ch("b", Hid.s("ACt7DQYCW3qLTQ==")), Ch("c", Hid.s("ACt7DQYCW3qFQQA=")), Ch("d", Hid.s("ACt7DQYCW3r3Qg=="))))
        // 4K nur, wenn gewünscht
        ChannelQuality.preferUhd = true
        assertEquals(Hid.s("ACt7DQYCW3r3Qg=="), best(Ch("c", Hid.s("ACt7DQYCW3qFQQA=")), Ch("d", Hid.s("ACt7DQYCW3r3Qg=="))))
        ChannelQuality.preferUhd = false
        // Gemessen schlägt Name: „HD“ liefert in Wahrheit 1080p, „FHD“ fällt aus
        ChannelQuality.recordPlayback("b", 1080, 900)
        ChannelQuality.recordCheck("c", 0, 0)
        ChannelQuality.recordCheck("c", 0, 0)
        assertEquals(Hid.s("ACt7DQYCW3qLTQ=="), best(Ch("b", Hid.s("ACt7DQYCW3qLTQ==")), Ch("c", Hid.s("ACt7DQYCW3qFQQA="))))
        // Backup nur, wenn sonst nichts
        assertEquals(Hid.s("FjoN"), best(Ch("x", Hid.s("FjoN")), Ch("y", Hid.s("FjoNDXwUdjmofDRH"))))
    }

    @Test
    fun runningAgainClearsFailures() {
        // 4K fiel (am Handy-Decoder) zweimal aus und galt als kaputt; läuft er (etwa im VLC-Player), zählt er wieder.
        ChannelQuality.preferUhd = true
        ChannelQuality.recordFailure("d")
        ChannelQuality.recordFailure("d")
        assertEquals(Hid.s("ACt7DQYCW3qFQQA="), best(Ch("c", Hid.s("ACt7DQYCW3qFQQA=")), Ch("d", Hid.s("ACt7DQYCW3r3Qg=="))))
        ChannelQuality.recordWorks("d")
        assertEquals(Hid.s("ACt7DQYCW3r3Qg=="), best(Ch("c", Hid.s("ACt7DQYCW3qFQQA=")), Ch("d", Hid.s("ACt7DQYCW3r3Qg=="))))
        // Unbekannt: nichts zu vergessen, nichts angelegt.
        ChannelQuality.recordWorks(Hid.s("Kgs0"))
        assertEquals(1, ChannelQuality.size)
    }

    @Test
    fun minimumQualityHidesOnlyKnownLowerOnes() {
        ChannelQuality.minHeight = 1080
        assertFalse(ChannelQuality.passes("a", Hid.s("ACt7DQYCW3qQTQ==")))
        assertFalse(ChannelQuality.passes("b", Hid.s("ACt7DQYCW3qLTQ==")))
        assertTrue(ChannelQuality.passes("c", Hid.s("ACt7DQYCW3qFQQA=")))
        assertTrue(ChannelQuality.passes("u", Hid.s("ACt7DQYCWw=="))) // unbekannt bleibt sichtbar
        ChannelQuality.recordPlayback("u", 576, 500)
        assertFalse(ChannelQuality.passes("u", Hid.s("ACt7DQYCWw==")))
        ChannelQuality.recordPlayback("b", 1080, 500) // „HD“, gemessen 1080p
        assertTrue(ChannelQuality.passes("b", Hid.s("ACt7DQYCW3qLTQ==")))
        ChannelQuality.hideBroken = true
        ChannelQuality.minHeight = 0
        ChannelQuality.recordFailure("z"); ChannelQuality.recordFailure("z")
        assertFalse(ChannelQuality.passes("z", Hid.s("Dw8xWCAi")))
    }

    @Test
    fun stabilityRanksWatchedChannelsWithoutDropsFirst() {
        ChannelQuality.recordPlayback(Hid.s("Jw8tQA=="), 1080, 800)
        ChannelQuality.recordWatch(Hid.s("Jw8tQA=="), 3 * 3_600_000L, 0)
        ChannelQuality.recordPlayback(Hid.s("NwYgRi0="), 1080, 800)
        ChannelQuality.recordWatch(Hid.s("NwYgRi0="), 2 * 3_600_000L, 8)
        ChannelQuality.recordCheck(Hid.s("JwYkTj8zcw=="), 1080, 1_200) // nur im Check gemessen
        ChannelQuality.recordFailure(Hid.s("IAsgSQ==")); ChannelQuality.recordFailure(Hid.s("IAsgSQ=="))
        val order = listOf(Hid.s("NwYgRi0="), Hid.s("IAsgSQ=="), Hid.s("JwYkTj8zcw=="), Hid.s("Jw8tQA=="), Hid.s("MQAqQzsheQ==")).filter { ChannelQuality.stability(it) != null }
            .sortedBy { ChannelQuality.stability(it) }
        assertEquals(listOf(Hid.s("Jw8tQA=="), Hid.s("JwYkTj8zcw=="), Hid.s("NwYgRi0=")), order)
    }

    @Test
    fun savedAndLoaded() {
        val file = java.io.File.createTempFile(Hid.s("NRsgQT0ibg=="), Hid.s("ahoyWw=="))
        ChannelQuality.recordPlayback("k1", 720, 1200, now = 5)
        ChannelQuality.saveIfChanged(file)
        ChannelQuality.clear()
        ChannelQuality.load(file)
        ChannelQuality.recordWatch("k1", 600_000, 1, now = 6)
        ChannelQuality.saveIfChanged(file)
        ChannelQuality.clear()
        ChannelQuality.load(file)
        assertEquals(ChannelQuality.Info(720, 1200, 0, 5, 600_000, 1), ChannelQuality.info("k1"))
        file.delete()
    }

    @Test
    fun sameResolutionHigherBitrateWins() {
        ChannelQuality.preferUhd = true
        // Zwei 4K-Fassungen: die mit mehr Daten (schärfer) gewinnt, eine Full-HD-Fassung mit viel Daten nicht gegen 4K.
        ChannelQuality.recordPlayback("a", 2160, 900)
        ChannelQuality.recordPlayback("b", 2160, 900)
        ChannelQuality.recordPlayback("c", 1080, 900)
        ChannelQuality.recordBitrate("a", 10_000)
        ChannelQuality.recordBitrate("b", 22_000)
        ChannelQuality.recordBitrate("c", 40_000)
        assertEquals(Hid.s("ACt7DQcdTnqQWQs8FQ0BHlN660wSKw95fQ=="), best(Ch("a", Hid.s("ACt7DQcdTnqQWQs8FQ0BHlM=")), Ch("b", Hid.s("ACt7DQcdTnqQWQs8FQ0BHlN660wSKw95fQ==")), Ch("c", Hid.s("ACt7DQcdTnqQWQs8FQ0SHlM="))))
        // Gespeichert und wieder geladen.
        val file = java.io.File.createTempFile(Hid.s("NRsgQT0ibg=="), Hid.s("ahoyWw=="))
        ChannelQuality.saveIfChanged(file)
        ChannelQuality.clear()
        ChannelQuality.load(file)
        assertEquals(22_000, ChannelQuality.info("b")?.kbps)
        file.delete()
    }
}
