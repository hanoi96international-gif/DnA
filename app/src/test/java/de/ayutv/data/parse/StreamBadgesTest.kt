package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamBadgesTest {
    @Test
    fun videoBadges() {
        assertEquals(listOf("4K UHD", "HDR10", "50 fps"), StreamBadges.video(2160, 50f, "HDR10"))
        assertEquals(listOf("Full HD"), StreamBadges.video(1080, 25f, ""))
        assertEquals(emptyList<String>(), StreamBadges.video(0, 0f, ""))
    }

    @Test
    fun audioBadges() {
        assertEquals("Dolby Atmos", StreamBadges.audio("audio/eac3-joc", 6))
        assertEquals("Dolby Digital+ 5.1", StreamBadges.audio("audio/eac3", 6))
        assertEquals("Dolby Digital", StreamBadges.audio("audio/ac3", 2))
        assertEquals("5.1", StreamBadges.audio("audio/mp4a-latm", 6))
        assertNull(StreamBadges.audio("audio/mp4a-latm", 2))
    }

    @Test
    fun remainingTime() {
        assertEquals("noch 23 Min.", StreamBadges.remaining(23 * 60_000L, 0))
        assertEquals("noch 1 Std. 5 Min.", StreamBadges.remaining(65 * 60_000L, 0))
        assertNull(StreamBadges.remaining(0, 10))
    }
}
