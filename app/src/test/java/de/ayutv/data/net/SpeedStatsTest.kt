package de.ayutv.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedStatsTest {
    /** Gleichmäßig [kbpsPerSecond] je Sekunde, alle 100 ms ein Block. */
    private fun reads(vararg kbpsPerSecond: Int): List<Pair<Long, Int>> = kbpsPerSecond.flatMapIndexed { second, kbps ->
        (0 until 10).map { tick -> (second * 1000L + tick * 100L) to (kbps * 1000 / 8 / 10) }
    }

    @Test
    fun averageAndWeakestSecond() {
        // Sekunde 0 ist Anlauf (zählt nicht als schwächste), die letzte ist angebrochen.
        val d = SpeedStats.download(reads(5_000, 50_000, 20_000, 48_000, 50_000))!!
        assertEquals(20_000, d.lowKbps)
        assertTrue("Mittel ${d.kbps}", d.kbps in 34_000..40_000)
        assertEquals("Full HD sicher, 4K knapp (4K-Sender mit 40 Mbit/s können hängen)", SpeedStats.verdict(d.copy(lowKbps = 30_000)))
        assertEquals("reicht für 4K", SpeedStats.verdict(d.copy(lowKbps = 60_000)))
        assertEquals("reicht für Full HD, zu langsam für 4K", SpeedStats.verdict(d))
        assertEquals("zu langsam für Live-TV in HD", SpeedStats.verdict(d.copy(lowKbps = 2_000)))
    }

    @Test
    fun tooLittleAndPing() {
        assertNull(SpeedStats.download(listOf(0L to 1000, 10L to 1000)))
        assertNull(SpeedStats.download(emptyList()))
        // Kurze Messung (unter drei Sekunden): schwächste Sekunde = Mittel.
        val short = SpeedStats.download(reads(20_000, 20_000))!!
        assertEquals(short.kbps, short.lowKbps)
        // Erste Antwort enthält den Verbindungsaufbau.
        assertEquals(45L to 20L, SpeedStats.ping(listOf(900L, 30L, 50L, 40L, 45L)))
        assertEquals(30L to 0L, SpeedStats.ping(listOf(30L)))
        assertNull(SpeedStats.ping(emptyList()))
        assertEquals("12,5 Mbit/s", SpeedStats.mbit(12_500))
    }
}
