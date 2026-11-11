package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLogTest {
    private fun entry(at: Long, channel: String = Hid.s("ACt7DQQkeAmqbCYLLw=="), drops: Map<String, Int> = emptyMap(), stalls: Int = 0, route: String = Hid.s("IAczSD8i")) =
        WatchLog.Entry(
            at = at, channel = channel, watchedS = 1800, height = 1080, fps = 25f, displayHz = 60f, codec = Hid.s("LAs3Tg=="),
            avgKbps = 9_800, lowKbps = 2_100, minBufferS = 4, stalls = stalls, stallS = stalls * 3, drops = drops,
            droppedFrames = 0, startMs = 749, errors = 0, route = route, net = Hid.s("EyIAY3RjNx2Lc2Rach50G3UztyY3"), worstRssi = -67,
            audio = Hid.s("IQ8iHnRgdDLjYClOBkgmlbMu"),
        )

    @Test
    fun roundTripAndLimits() {
        val e = entry(1_000L, channel = Hid.s("FwU4JAcmeCi3A3U="), drops = mapOf(WatchLog.KIND_SILENT to 3))
        val back = WatchLog.decode(WatchLog.encode(e))!!
        assertEquals(Hid.s("FwU4DQcmeCi3KXU="), back.channel)
        assertEquals(mapOf(WatchLog.KIND_SILENT to 3), back.drops)
        assertEquals(e.copy(channel = Hid.s("FwU4DQcmeCi3KXU=")), back)
        // Älter als 48 h fällt weg, höchstens MAX Einträge
        val now = WatchLog.KEEP_MS + 10_000L
        var lines = listOf(WatchLog.encode(entry(1_000L)))
        repeat(WatchLog.MAX + 5) { lines = WatchLog.append(lines, entry(now - 1_000L + it), now) }
        assertEquals(WatchLog.MAX, lines.size)
        assertTrue(lines.mapNotNull(WatchLog::decode).all { now - it.at <= WatchLog.KEEP_MS })
        // Kaputte Zeilen werden übergangen
        assertEquals(null, WatchLog.decode(Hid.s("Lw8xWCAi")))
    }

    @Test
    fun reasonsAndSummary() {
        assertEquals(WatchLog.KIND_SILENT, WatchLog.kindOf(Hid.s("BQAjRDEicijjZS0LJ0gmIjc0qmosGjINOTN/KOMhHgsoWZfqdT+xeicGM0g9ImI0pCA=")))
        assertEquals(WatchLog.KIND_SILENT, WatchLog.kindOf(Hid.s("Lg83THo4ci7tWisNKkggAn43pmYxGgRVNzNnLqpmKlRhWT07cjW2fQ==")))
        assertEquals(WatchLog.KIND_CLOSED, WatchLog.kindOf(Hid.s("BQAjRDEicijjYSUaYUk9MzcMpnsmBy9JIThweqFsIQAlSCA=")))
        assertEquals(WatchLog.KIND_OTHER, WatchLog.kindOf(Hid.s("BwEvQzE1YzOsZ2QcJF4xIg==")))
        val now = 10_000_000L
        val text = WatchLog.summary(
            listOf(
                entry(now - 1000, drops = mapOf(WatchLog.KIND_SILENT to 3), stalls = 1, route = Hid.s("h9IjSCZ2RyiscT1OaWMYfw==")),
                entry(now - 2000, channel = Hid.s("ACt7DQYCWw==")),
            ),
            now,
        )
        assertTrue(text, text.contains(Hid.s("dk4SSDoycijvKXVCcQ08dnA/sGosDzRZ")))
        assertTrue(text, text.contains(Hid.s("Cgs0DSIzZTi2ZyALLxd0ZTdyqGwtACQNEDdjP60pd0c=")))
        assertTrue(text, text.contains(Hid.s("FhsiRjgzZXrrSy0CJQ08P3k97ykUGydLMSQ3NqZsNkd7DWU=")))
        assertTrue(text, text.contains(Hid.s("dltuGGR2cSqwKSUbJw1iZjcSuSB+TnMNIjl5evE=")))
        assertTrue(text, text.contains(Hid.s("Cgs0DSIzZTi2ZyALLw0kJHh6kH0xACVI")))
        assertTrue(text, text.contains(Hid.s("BRsnS5fyezaqbiFOEkg6MnIo")))
        assertTrue(text, text.contains(Hid.s("EAEvDTE3dGnjPycGYUQ5dlA/scrgGg==")))
        // Viele verworfene Bilder allein machen einen Sender auffällig, getrennt nach Tonweg gezählt
        val drops = WatchLog.summary(listOf(entry(now - 1000, channel = Hid.s("ACt7DQc9bnqQeSscNQ1l")).copy(fps = 50f, displayHz = 50f, droppedFrames = 7190)), now)
        assertTrue(drops, drops.contains(Hid.s("EgszWjskcT+tbGQsKEEwM2V6qWxkOi5DbnZDNa0pLQNhajEk1P63KSALKkIwP3IotylzX3gddH4mepBsKgokX30=")))
        assertTrue(drops, drops.contains(Hid.s("ACt7DQc9bnqQeSscNQ1l")))
        // Ältere Einträge ohne Ton-Feld lassen sich weiter lesen
        val old = WatchLog.encode(entry(1L)).split('\t').dropLast(2).joinToString("\t")
        assertEquals("", WatchLog.decode(old)!!.audio)
        val failed = entry(now - 500).copy(errors = 4, lastError = Hid.s("DSEeeBoFRx+AQAInBGludk0tqnonBiRDJyZyM6BhIRxhTzE9eDeufWQFJEQ6Mzceon0hAA=="))
        assertEquals(failed, WatchLog.decode(WatchLog.encode(failed)))
        assertTrue(WatchLog.line(failed).contains(Hid.s("AgspQTEkN27jIT4bLUggLGN6ikYbOw9+BBNUE4VAASp7DQ4hfimgYSEAMl0xP3QypntkDCRGOzt6LuNiIQcvSHQSdi6mZ20=")))
        assertEquals(Hid.s("CgEiRXQ4fjmrfTdOJkgnNX87tn1kRiZILpWzMq99ZBkoXzB2djjjOnROMg0+MzcJpmcgCzMEelw="), WatchLog.summary(emptyList(), now))
    }
}
