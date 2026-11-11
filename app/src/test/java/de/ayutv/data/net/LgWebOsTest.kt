package de.ayutv.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LgWebOsTest {
    @Test
    fun recognisesLgTvs() {
        assertTrue(LgWebOs.isLg("[LG] webOS TV NANO867NA"))
        assertTrue(LgWebOs.isLg("LG OLED55C3"))
        assertFalse(LgWebOs.isLg("Samsung Q80 Series"))
        assertFalse(LgWebOs.isLg("Fire TV Stick"))
    }
}
