package de.ayutv.data.parse

import de.ayutv.data.parse.SportKinds.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SportKindsTest {
    private fun k(title: String, sub: String? = null, cat: String? = null, desc: String? = null, channel: String = "", match: Boolean = false) =
        SportKinds.kind(title, sub, cat, desc, channel, match)

    @Test
    fun footballOnlyWhenClear() {
        assertEquals(Kind.FOOTBALL, k(Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg="), Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgREOiJlO6BhME4HXzU4fDy2ezA=")))
        assertEquals(Kind.FOOTBALL, k(Hid.s("CAc3SG52JXTjSwg="), channel = Hid.s("ACt7DQcdTnqQWQs8FQ0WA1kehloIJwZsdGQ3Eoc=")))
        assertEquals(Kind.FOOTBALL, k(Hid.s("Ai1hfiB4NwqifCgHYVsneDdr7SkCLWFjl+plNKFsNgk="), channel = Hid.s("ACt7DQcZVBmGW2Q+EXt0ZCY=")))
        assertEquals(Kind.FOOTBALL, k(Hid.s("CAc3SG52UxyBJBQBKkw4")))
        assertEquals(Kind.FOOTBALL, k(Hid.s("DAszWTw3NxiQSmSMwb50Zzl6hUpkJYKbODg="), match = true))
        // Bundesliga anderer Sportarten ist kein Fußball
        assertEquals(Kind.HANDBALL, k(Hid.s("DA8vSTY3ezbuSzEAJUgnOn49og=="), Hid.s("ECYWDR8/cjbj68T9YX4TdlE2pmc3DDRfM3tfO61tIRkoWSA=")))
        assertEquals(Kind.VOLLEYBALL, k(Hid.s("EgEtQTEvdTuvZWQsNEMwM2Q2qm4lVGF7Fxk3HrFsNwokQ3S0l8njRyUZIF87dkQusWgxDChDMw==")))
        assertEquals(Kind.BASKETBALL, k(Hid.s("BiwNF3QEeCm3ZicFYX4xN2A1r38hHWHP1MU3HIApBg84SCY4NxcAtSoNKUg6dlU7sGIhGiNMODo=")))
        assertEquals(Kind.ICE_HOCKEY, k(Hid.s("ACsNF3QTfimhyuAcJEN0FHIor2AqTqOtx3ZWPq9sNk4MTDo4fz+qZA==")))
    }

    @Test
    fun eventNamesFromTheUsersPlaylist() {
        // Dyn-Events ohne Sportart im Namen: keine Fußball-Vermutung wegen „SV“/„HC“
        assertEquals(Kind.HOCKEY, k(Hid.s("Ca3dYxceWR+RKRctYQUQfzd3400BPGFuGANVeoJHZCoEf3QXWwmXTBZOaWl9"), channel = Hid.s("ACt7DRAPWXqTWRJOcw==")))
        assertEquals(Kind.HANDBALL, k(Hid.s("DD0GDQMTQwCPSBZObA0ZAjcXhkUXOw9qERg="), channel = Hid.s("ACt7DRAPWXqTWRJOcBQ=")))
        assertEquals(Kind.OTHER, k(Hid.s("Fy0UDREbWxOAQQwrCGB0ezcJlSkIIQllGxA="), channel = Hid.s("ACt7DRAPWXqTWRJOcxw=")))
        assertEquals(Kind.US_SPORTS, k(Hid.s("EysEZnRuN7hDmmQ8AHsRGER6gl1kLAhhGAU="), channel = Hid.s("ACt7DQYCW3HjWRQ4YR4=")))
        assertEquals(Kind.SNOOKER, k(Hid.s("FwAuQj8zZWDjQCoaJF86N2MzrGclAmFuPDd6KqpmKh0pRCR6N2vtKRYbL0kx")))
        assertEquals(Kind.TENNIS, k(Hid.s("dl5zG3QTZSm3bGQsIEM/dlgqpmdkOiRDOj9k")))
        assertEquals(Kind.MOTORSPORT, k(Hid.s("CAc3SA=="), channel = Hid.s("cCV7DQcdTnqQWQs8FX50ECZ6IrzFj/WZteKnegG6pu/5z9Xi9dtz6PDQ")))
        assertEquals(Kind.WINTER, k(Hid.s("FyUAeRF2VBuNSAAvYc/UxTcckUgRKw8NttaEeojK2Dw=")))
        assertEquals(Kind.DARTS, k(Hid.s("FCoCDQM5ZTanKRcLM0QxJTc1pSkADzNZJw==")))
    }
}
