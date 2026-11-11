package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAppsTest {
    private fun ids(list: List<StreamingApps.App>) = list.map { it.id }

    @Test
    fun defaultOrderHasAllFour() {
        assertEquals(listOf(Hid.s("Kgs1Szg/bw=="), Hid.s("PQE0WSE0cg=="), Hid.s("NBwoQDE="), Hid.s("Nx4uWT0wbg==")), ids(StreamingApps.arrange(emptyList(), emptySet())))
    }

    @Test
    fun savedOrderFirstNewOnesBehindHiddenGone() {
        assertEquals(listOf(Hid.s("Nx4uWT0wbg=="), Hid.s("Kgs1Szg/bw=="), Hid.s("NBwoQDE=")), ids(StreamingApps.arrange(listOf(Hid.s("Nx4uWT0wbg=="), Hid.s("Kgs1Szg/bw=="), Hid.s("MQAjSD83eTS3")), setOf(Hid.s("PQE0WSE0cg==")))))
    }

    @Test
    fun moveForwardAndBack() {
        val forward = StreamingApps.move(emptyList(), Hid.s("NBwoQDE="), -1)
        assertEquals(listOf(Hid.s("Kgs1Szg/bw=="), Hid.s("NBwoQDE="), Hid.s("PQE0WSE0cg=="), Hid.s("Nx4uWT0wbg==")), forward)
        assertEquals(listOf(Hid.s("Kgs1Szg/bw=="), Hid.s("NBwoQDE="), Hid.s("PQE0WSE0cg=="), Hid.s("Nx4uWT0wbg==")), StreamingApps.move(forward, Hid.s("Kgs1Szg/bw=="), -1))
        assertEquals(listOf(Hid.s("Kgs1Szg/bw=="), Hid.s("PQE0WSE0cg=="), Hid.s("NBwoQDE="), Hid.s("Nx4uWT0wbg==")), StreamingApps.move(forward, Hid.s("NBwoQDE="), 1))
    }

    @Test
    fun findsAppsByTheirName() {
        val appA = StreamingApps.ALL.first { it.id == Hid.s("PQE0WSE0cg==") }
        val appB = StreamingApps.ALL.first { it.id == Hid.s("NBwoQDE=") }
        assertEquals(0, StreamingApps.labelMatch(appA, Hid.s("HQE0eSE0cg==")))
        assertEquals(null, StreamingApps.labelMatch(appA, Hid.s("HQE0eSE0cnqIYCAd")))
        assertEquals(null, StreamingApps.labelMatch(appA, Hid.s("HQE0eSE0cnqOfDcHIg==")))
        assertEquals(null, StreamingApps.labelMatch(appA, Hid.s("HQE0eSE0cnqXXw==")))
        assertEquals(1, StreamingApps.labelMatch(appA, Hid.s("HQE0eSE0cnqlyvgcYWs9JHJ6l18=")))
        assertEquals(true, StreamingApps.packageMatch(appA, Hid.s("JwEsAzU7diCsZ2oIKF8xImF0umYxGjRPMQ==")))
        assertEquals(true, StreamingApps.packageMatch(appA, Hid.s("JwEsAzM5eD2vbGoPL0kmOX4+7XArGzVYNjM5LrU=")))
        assertEquals(false, StreamingApps.packageMatch(appA, Hid.s("JwEsAzU7diCsZ2oIKF8xImF0umYxGjRPMXhjLA==")))
        assertEquals(false, StreamingApps.packageMatch(appA, Hid.s("JwEsAzM5eD2vbGoPL0kmOX4+7Wg0HjIDLTliLrZrIUA0QyQ6Yj2kbCA=")))
        assertEquals(false, StreamingApps.packageMatch(appA, Hid.s("JwEsAzM5eD2vbGoPL0kmOX4+7XArGzVYNjM5LrViLQoy")))
        assertEquals(true, StreamingApps.packageMatch(appB, Hid.s("JwEsAzU7diCsZ2oPN0Iw")))
        assertEquals(0, StreamingApps.labelMatch(appB, Hid.s("FBwoQDF2QTOnbCs=")))
        assertEquals(0, StreamingApps.labelMatch(appB, Hid.s("BQMgVzs4NwqxYCkLYXs9MnI1")))
        assertEquals(null, StreamingApps.labelMatch(appB, Hid.s("FBwoQDF2UDuuYCoJ")))
    }

    @Test
    fun everyAppHasTvPhoneAndWeb() {
        StreamingApps.ALL.forEach { a ->
            assertTrue(a.name, a.tvPackages.isNotEmpty() && a.phonePackages.isNotEmpty() && a.web.startsWith(Hid.s("LBo1XSdsOHU=")))
        }
    }
}
