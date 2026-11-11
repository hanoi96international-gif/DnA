package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieInfoParsingTest {
    private fun parse(json: String) = XtreamParsing.movieInfo(json.byteInputStream())

    @Test
    fun typicalPanel() {
        val info = parse(
            Hid.s("P0woQzI5NWC4KzQCLll2bDUfqmdkKChBOXg1duFqJR01D250VnbjS2ZCY0k9JHI5t2Y2THsPF3Q7eKRsKhwkD250UyiiZCVMbQ8mM3s/onohCiBZMXQtePE5dVdsHWF7J2vhJU5OYQ10djd64ylkTmENdHY1PrZ7JRooQjoJZD+gemZUYxtjZCd47ysgGzNMID94NOEzZl5wF2FkLWrzK2hMM0wgP3k94TNmWW8VYHQ7eLpmMRo0TzEJYyiiYCgLMw9udH8ut3k3VG4CIyFgdLpmMRo0TzF4dDWuJjMPNU48aWFnp1gzWjYUAzFPOZIraGRhDXR2N3rjKWROYQ10djd4oWgnBSVfOyZIKqJ9LEx7dnY+Yy6zen5BbkQ5MTg47WM0CWNweHR6NbVgITEoQDUxcnj5KywaNV0nbDh1qmQjQTEDPiZweL4lZgMuWz0zSD6ifSVMe1Z2JWMopmgpMShJdmwmJ74=")
        )!!
        assertEquals(Hid.s("AQcvDRI/ezft"), info.plot)
        assertEquals(2019, info.year)
        assertEquals(112, info.durationMinutes)
        assertEquals("7.8", info.rating)
        assertEquals(Hid.s("ID82GSNvQD2bahU="), info.trailer)
        assertEquals(Hid.s("LBo1XSdsOHWqZCNBIwM+JnA="), info.backdrop)
    }

    @Test
    fun sparsePanel() {
        val info = parse(Hid.s("P0woQzI5NWC4KyAbM0wgP3g04TNmV3QNOT95eO8rNg81RDoxNWDhOWZCY1Q7I2MvoWwbGjNMPTpyKOEzZkxtDzY3dDGneyseHl01In94+VIZEzw="))!!
        assertEquals(95, info.durationMinutes)
        assertNull(info.rating)
        assertNull(info.trailer)
        assertNull(info.backdrop)
        assertNull(parse(Hid.s("P0woQzI5NWCYVDk=")))
    }

    @Test
    fun helpers() {
        assertEquals(Hid.s("ID82GSNvQD2bahU="), XtreamParsing.trailerId(Hid.s("ID82GSNvQD2bahU=")))
        assertEquals(Hid.s("ID82GSNvQD2bahU="), XtreamParsing.trailerId(Hid.s("LBo1XSdsOHW6ZjEaNAM2Mzg+kn5wGXh6Mw50C/x9eV0=")))
        assertEquals(125, XtreamParsing.parseMinutes("02:05:10"))
        assertEquals("1", de.ayutv.data.repo.XtreamClient.movieIdOf(Hid.s("LBo1XW55ODL5MXRBLEIiP3J1tiY0QXADOT1h")))
    }
}
