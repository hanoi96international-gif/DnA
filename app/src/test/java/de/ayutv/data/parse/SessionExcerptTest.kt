package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExcerptTest {
    @Test
    fun keepsHeadAndNotableLinesOfLongSession() {
        val journal = listOf("  16:07:01.000 Video: decoder-a video/avc 1280×720 8 Bit", "  16:07:02.000 Start: erstes Bild gezeigt nach 900 ms") +
            (1..90).map { "  16:%02d:%02d.000 Bild: im Mittel $it ms spät, gezeigt ${it * 120}, verworfen 0, Decoder 96 %% Echtzeit".format(7 + it / 12, it % 12 * 5) } +
            listOf("  16:15:48.000 ⚠ Bild 171 ms hinter dem Ton – Ton kurz angehalten, bis das Bild aufgeholt hat")
        val text = (listOf("15.11. 16:16 · Sender", "Engine: DNA Engine", "Technik: x", "Abbrüche: keine", "Engine-Protokoll:") + journal).joinToString("\n")
        val out = WatchLog.sessionExcerpt(text).lines()
        assertEquals("15.11. 16:16 · Sender", out.first())
        assertTrue(out.any { it.startsWith("Technik:") })
        assertTrue(out.any { "Video: decoder-a" in it })
        assertTrue(out.any { "hinter dem Ton" in it })
        assertEquals(12, out.count { " Bild: im Mittel " in it })
        // Die letzten Bild-Zeilen, in der ursprünglichen Reihenfolge, und die Warnung danach.
        assertTrue(out.last().contains("hinter dem Ton"))
        assertTrue(out.any { "im Mittel 90 ms" in it })
        assertFalse(out.any { "im Mittel 1 ms" in it })
        assertTrue(out.any { it.startsWith("Engine-Protokoll (gekürzt, 78 Zeilen ausgelassen)") })
    }

    @Test
    fun shortSessionStaysWhole() {
        val text = "15.11. 16:16 · Film\nEngine: DNA Engine\nEngine-Protokoll:\n  16:00:00.000 Video: decoder-a"
        assertEquals("15.11. 16:16 · Film\nEngine: DNA Engine\nEngine-Protokoll:\n  16:00:00.000 Video: decoder-a", WatchLog.sessionExcerpt(text))
        assertEquals("a\nb", WatchLog.sessionExcerpt("a\n\nb"))
    }
}
