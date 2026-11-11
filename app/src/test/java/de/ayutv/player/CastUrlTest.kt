package de.ayutv.player

import de.ayutv.player.cast.Cast
import org.junit.Assert.assertEquals
import org.junit.Test

class CastUrlTest {
    @Test
    fun xtreamLiveBecomesHls() {
        assertEquals("http://h:80/live/u/p/123.m3u8", Cast.castUrl("http://h:80/live/u/p/123.ts", live = true))
        assertEquals("http://h/movie/u/p/5.mp4", Cast.castUrl("http://h/movie/u/p/5.mp4", live = false))
        assertEquals("http://other/stream.ts", Cast.castUrl("http://other/stream.ts", live = true))
    }

    @Test
    fun contentTypes() {
        assertEquals("application/x-mpegURL", Cast.contentType("http://h/a.m3u8?x=1"))
        assertEquals("video/x-matroska", Cast.contentType("http://h/a.MKV"))
        assertEquals("video/mp4", Cast.contentType("http://h/play"))
    }
}
