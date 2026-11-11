package de.ayutv.data.repo

import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatchupTest {
    // 2024-01-01 20:00 UTC, Sendung 60 Minuten, "jetzt" 2 Stunden später
    private val start = 1_704_139_200_000L
    private val stop = start + 3_600_000L
    private val now = start + 7_200_000L

    private fun channel(url: String, days: Int = 3, type: String? = null, source: String? = null) =
        ChannelEntity(key = "k", playlistId = 1, name = "Test", groupTitle = "G", logo = null, url = url, epgId = null, number = 1,
            catchupDays = days, catchupType = type, catchupSource = source)

    @Test
    fun xtreamUsesServerTimezone() {
        val playlist = PlaylistEntity(id = 1, name = "X", type = PlaylistType.XTREAM, url = "http://h:8080", timezone = "Europe/Berlin")
        assertEquals(
            "http://h:8080/timeshift/user/pass/60/2024-01-01:21-00/123.ts",
            Catchup.url(playlist, channel("http://h:8080/live/user/pass/123.ts"), start, stop, now),
        )
    }

    @Test
    fun appendWithPlaceholders() = assertEquals(
        "http://h/ch.m3u8?utc=1704139200&lutc=1704146400",
        Catchup.url(null, channel("http://h/ch.m3u8", type = "append", source = "?utc={utc}&lutc={lutc}"), start, stop, now),
    )

    @Test
    fun defaultWithFullUrlAndDateFields() = assertEquals(
        "http://a/arch/2024/01/01/20-00/3600.ts",
        Catchup.url(null, channel("http://h/ch", type = "default", source = "http://a/arch/{Y}/{m}/{d}/{H}-{M}/{duration}.ts"), start, stop, now),
    )

    @Test
    fun durationInMinutes() = assertEquals(
        "http://h/ch?dur=60",
        Catchup.url(null, channel("http://h/ch", type = "append", source = "?dur=\${duration:60}"), start, stop, now),
    )

    @Test
    fun shift() = assertEquals(
        "http://h/ch.ts?utc=1704139200&lutc=1704146400",
        Catchup.url(null, channel("http://h/ch.ts", type = "shift"), start, stop, now),
    )

    @Test
    fun flussonicHls() = assertEquals(
        "http://h/ch/archive-1704139200-3600.m3u8",
        Catchup.url(null, channel("http://h/ch/index.m3u8", type = "flussonic"), start, stop, now),
    )

    @Test
    fun noArchiveOrTooOld() {
        assertNull(Catchup.url(null, channel("http://h/live/u/p/1.ts", days = 0), start, stop, now))
        assertNull(Catchup.url(null, channel("http://h/live/u/p/1.ts", days = 1), start, stop, start + 3 * 86_400_000L))
        assertNull(Catchup.url(null, channel("http://h/live/u/p/1.ts"), now + 1, now + 2, now))
    }
}
