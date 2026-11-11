package de.ayutv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppTimeTest {
    @Test
    fun utcOnGermanDeviceBecomesBerlin() {
        assertEquals("Europe/Berlin", AppTime.replacement("GMT", Locale.GERMANY))
        assertEquals("Europe/Berlin", AppTime.replacement("Etc/UTC", Locale("de")))
        assertEquals("Europe/Vienna", AppTime.replacement("UTC", Locale("de", "AT")))
    }

    @Test
    fun realZonesStay() {
        assertNull(AppTime.replacement("Europe/Berlin", Locale.GERMANY))
        assertNull(AppTime.replacement("Europe/London", Locale.GERMANY))
        assertNull(AppTime.replacement("GMT", Locale.UK))
    }
}
