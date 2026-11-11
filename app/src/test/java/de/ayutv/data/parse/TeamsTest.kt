package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamsTest {
    @Test
    fun teamsFromPairing() {
        assertEquals(listOf("Borussia Dortmund", "Eintracht Frankfurt"), Teams.ofPairing("Borussia Dortmund – Eintracht Frankfurt, tipico Topspiel der Woche, 7. Spieltag"))
        assertEquals(listOf("Hamburger SV", "1. FC Union Berlin"), Teams.ofPairing("8. Spieltag: Hamburger SV – 1. FC Union Berlin"))
        assertEquals(listOf("Genk", "Standard Liège"), Teams.ofPairing("Genk - Standard Liège"))
        assertEquals(emptyList<String>(), Teams.ofPairing("Sonntags-Konferenz, 9. Spieltag"))
    }

    @Test
    fun followedTeamIsFound() {
        assertTrue(Teams.plays("Borussia Dortmund", "Borussia Dortmund – Eintracht Frankfurt, 7. Spieltag"))
        assertTrue(Teams.plays("Borussia Dortmund", "1. FC Köln - Bor. Dortmund"))
        assertTrue(Teams.plays("Eintracht Frankfurt", "Borussia Dortmund – Eintracht Frankfurt"))
        assertTrue(Teams.plays("1. FC Köln", "1. FC Köln – FC Schalke 04, 7. Spieltag"))
        assertTrue(Teams.plays("FC Bayern München", "SC Freiburg – FC Bayern München"))
        assertTrue(Teams.plays("Hannover 96", "Eintracht Braunschweig – Hannover 96"))
    }

    @Test
    fun otherTeamsAndYouthAreNot() {
        assertFalse(Teams.plays("Borussia Dortmund", "Borussia Mönchengladbach – VfL Wolfsburg"))
        assertFalse(Teams.plays("Eintracht Frankfurt", "Eintracht Braunschweig – Hannover 96"))
        assertFalse(Teams.plays("1. FC Köln", "1. FC Nürnberg – 1. FC Kaiserslautern"))
        assertFalse(Teams.plays("Borussia Dortmund", "FC Schalke 04 U19 – Borussia Dortmund U19, 9. Spieltag"))
        assertFalse(Teams.plays("SV Werder Bremen", "SV Werder Bremen – SC Freiburg", "Fußball: Bundesliga der Frauen"))
        assertTrue(Teams.plays("SV Werder Bremen", "SV Werder Bremen – SC Freiburg", "Fußball: Bundesliga"))
        assertTrue(Teams.plays("Borussia Dortmund U19", "FC Schalke 04 U19 – Borussia Dortmund U19"))
    }
}
