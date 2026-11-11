package de.ayutv.data.sport

import de.ayutv.data.parse.Hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vereinswappen von Dienst (aktuell, PNG) für die großen Ligen. */
class CrestsTest {
    private val teams = Hid.s("Tk5hDXR2N3rjcmYdMUImImR4+VI/TC1INTFiP7ArfjU6DyAzdjewK341Sw10djd64ylkTmFWdiJyO64rfhVjRDB0LXjyOnxMbQ84OXQ7t2ArAGMXdgF4NqV6JhszSnZ6NTSiZCFMew8DOXs8sGsxHCYPeHRzM7B5KA84YzU7cnj5KxIIDQ0DOXs8sGsxHCYPeHRkMqx7MCooXiQ6diONaCkLYxd2AXg2pXomGzNKdnodeuMpZE5hDXR2N3rjKygBJkIndC0BuCssHCRLdmw1Mrd9NB17Ans3OT+weSoNJUN6NXg37GBrGiRMOTp4Pax6ax0uTjczZXX2OXRBcB5seGc0pCs5MzxQeFw3euMpZE5hDXR2bHi3bCUDYxcvdH4+4TNmX3Ifdno1NqxqJRooQjp0LXiBaD0LM0N0G2I0qmosTG0POjd6P+EzZiwgVDEkeXqOfCoHIkV2ejU+qno0AiBUGjd6P+EzZiwgVDEkeXqOfCoHIkV2ejUpq2Y2GgVEJyZ7O7pHJQMkD250VTu6bDYAYwFedjd64ylkTmENdHY3eK9mIwEyD24NbHireyEIYxd2PmMus3p+QW5MejNkKq1qIABvTjs7ODPsfSEPLEE7MXgp7HorDSJIJnkiavMmdV1zAyQ4cHi+VDkTbSd0djd64ylkTmENL3RjP6JkZlQ6Dz0yNWDhMH1XeA94dHs1oGgwBy5Ddmw1CbVuZCEvQS10O3itaCkLYxd2BWE94SVmCiheJDp2I41oKQtjF3YFYT3jRioCOA0SFTV24WUrCS5edmxMIeFhNgsnD250fy63eTdUbgIseW50sH8jTDxwKSs7UOMpZE5hDXR2N3q4KzALIEB2bGx4qm1mVGMcdno1Pqp6NAIgVBo3ej/hM2YhKUMxdls1pGZmEzwndHY3euMpZE4cUAkrSifJKWROYQ==").trimIndent()

    @Test
    fun readsIntlTeamList() {
        val list = Crests.parseIntlTeams(teams)
        assertEquals(Hid.s("LBo1XSdsOHWiJyEdMUM3Mnl0oGYpQSgCIDN2N69mIwEyAic5dDmme2tbcR17ZyRi7XkqCQ=="), Crests.match(Hid.s("EggNDQM5ezywazEcJg=="), list))
        // Deutscher Name wie im TV-Guide und in der Tabelle.
        assertEquals(Hid.s("LBo1XSdsOHWiJyEdMUM3Mnl0oGYpQSgCIDN2N69mIwEyAic5dDmme2tbcR17ZyRo7XkqCQ=="), Crests.match(Hid.s("Ai1hbzUvciitKQmt/UM3PnI0"), list))
        // SVG kann die App nicht anzeigen, ohne Logo gibt es nichts.
        assertTrue(list.none { it.second.endsWith(Hid.s("ah03Sg==")) })
        assertNull(Crests.match(Hid.s("CwYvSHQaeD2s"), list))
    }

    @Test
    fun coversTopLeagues() {
        for (league in listOf(Hid.s("IwszA2U="), Hid.s("IwszA2Y="), Hid.s("IwszA2c="), Hid.s("IQAmA2U="), Hid.s("IQAmA2Y="), Hid.s("IR0xA2U="), Hid.s("LRogA2U="), Hid.s("IhwgA2U="))) {
            assertTrue(league, league in Crests.INTL_LEAGUES)
        }
    }

    @Test
    fun brokenAnswer() {
        assertEquals(emptyList<Pair<String, String>>(), Crests.parseIntlTeams(Hid.s("eAY1QDho")))
        assertEquals(emptyList<Pair<String, String>>(), Crests.parseIntlTeams(Hid.s("P0wyXTskYynhMx8zPA==")))
    }
}
