package de.ayutv.data.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Flaggen für Länderspiele. */
class NationalTeamsTest {
    @Test
    fun countries() {
        assertEquals("de", NationalTeams.code("Deutschland"))
        assertEquals("de", NationalTeams.code("Germany"))
        assertEquals("de", NationalTeams.code("Deutschland U21"))
        assertEquals("de", NationalTeams.code("Deutschland Frauen"))
        assertEquals("fr", NationalTeams.code("Frankreich (F)"))
        assertEquals("gb-eng", NationalTeams.code("England"))
        assertEquals("gb-sct", NationalTeams.code("Schottland"))
        assertEquals("tr", NationalTeams.code("Türkei"))
        assertEquals("ci", NationalTeams.code("Côte d'Ivoire"))
        assertEquals("https://flagcdn.com/w160/at.png", NationalTeams.flag("Österreich"))
    }

    @Test
    fun clubsAreNoCountries() {
        assertNull(NationalTeams.code("FC Bayern München"))
        assertNull(NationalTeams.code("Real Madrid"))
        assertNull(NationalTeams.code("Austria Wien"))
        assertNull(NationalTeams.code("Holland Park FC"))
        assertNull(NationalTeams.code("Hertha BSC"))
    }
}
