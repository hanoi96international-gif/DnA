package de.ayutv.desktop.data

import de.ayutv.data.parse.CatchupUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Dieselben Archiv-Regeln wie auf dem Fire TV (gemeinsamer Code in CatchupUrls). */
class CatchupUrlsTest {
    // 2024-01-01 20:00 UTC, Sendung 60 Minuten, "jetzt" 2 Stunden später
    private val start = 1_704_139_200_000L
    private val stop = start + 3_600_000L
    private val now = start + 7_200_000L

    private fun url(channel: String, days: Int = 3, type: String? = null, source: String? = null, xtream: Boolean = false, tz: String? = null, from: Long = start, to: Long = stop, at: Long = now) =
        CatchupUrls.url(channel, days, type, source, xtream, tz, from, to, at)

    @Test
    fun xtreamUsesServerTimezone() = assertEquals(
        "http://h:8080/timeshift/user/pass/60/2024-01-01:21-00/123.ts",
        url("http://h:8080/live/user/pass/123.ts", xtream = true, tz = "Europe/Berlin"),
    )

    @Test
    fun m3uVariants() {
        assertEquals("http://h/ch.m3u8?utc=1704139200&lutc=1704146400", url("http://h/ch.m3u8", type = "append", source = "?utc={utc}&lutc={lutc}"))
        assertEquals("http://a/arch/2024/01/01/20-00/3600.ts", url("http://h/ch", type = "default", source = "http://a/arch/{Y}/{m}/{d}/{H}-{M}/{duration}.ts"))
        assertEquals("http://h/ch.ts?utc=1704139200&lutc=1704146400", url("http://h/ch.ts", type = "shift"))
        assertEquals("http://h/ch/archive-1704139200-3600.m3u8", url("http://h/ch/index.m3u8", type = "flussonic"))
    }

    @Test
    fun noArchiveOrTooOld() {
        assertNull(url("http://h/live/u/p/1.ts", days = 0))
        assertNull(url("http://h/live/u/p/1.ts", days = 1, at = start + 3 * 86_400_000L))
        assertNull(url("http://h/live/u/p/1.ts", from = now + 1, to = now + 2))
    }
}
