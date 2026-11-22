package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleVariantsTest {
    @Test
    fun variantsOfOneChannelShareTheName() {
        val rtl = SubtitleVariants.baseName("DE: RTL ᴿᴬᵂ")
        assertEquals("de rtl", rtl)
        assertEquals(rtl, SubtitleVariants.baseName("DE: RTL HD (720P)"))
        assertEquals(rtl, SubtitleVariants.baseName("DE: RTL HEVC"))
        assertEquals(rtl, SubtitleVariants.baseName("DE: RTL SD"))
        assertEquals("de rtl zwei", SubtitleVariants.baseName("DE: RTL ZWEI HD"))
        assertEquals("de prosieben", SubtitleVariants.baseName("DE: PROSIEBEN HD"))
    }

    @Test
    fun differentChannelsStayApart() {
        assertNotEquals(SubtitleVariants.baseName("DE: RTL HD"), SubtitleVariants.baseName("DE: RTL ZWEI HD"))
        assertNotEquals(SubtitleVariants.baseName("DE: SKY SPORT BUNDESLIGA 3 HD (SAT)"), SubtitleVariants.baseName("DE: SKY SPORT BUNDESLIGA 5 HD (SAT)"))
    }
}
