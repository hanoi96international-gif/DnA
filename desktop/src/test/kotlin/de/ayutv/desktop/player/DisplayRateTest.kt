package de.ayutv.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRateTest {
    private val monitor = listOf(23, 24, 25, 29, 30, 50, 59, 60)

    @Test
    fun germanTvGoesTo50() {
        assertEquals(50, DisplayRate.choose(25.0, monitor))
        assertEquals(50, DisplayRate.choose(50.0, monitor))
    }

    @Test
    fun filmsGoTo24() {
        assertEquals(24, DisplayRate.choose(23.976, monitor))
        assertEquals(24, DisplayRate.choose(24.0, monitor))
        assertEquals(48, DisplayRate.choose(23.976, monitor + 48))
    }

    @Test
    fun ntscStaysAt60() {
        assertEquals(60, DisplayRate.choose(29.97, monitor))
        assertEquals(60, DisplayRate.choose(59.94, listOf(60)))
    }

    @Test
    fun nothingFits() {
        assertNull(DisplayRate.choose(25.0, listOf(60)))
        assertNull(DisplayRate.choose(23.976, listOf(60, 75)))
    }
}
