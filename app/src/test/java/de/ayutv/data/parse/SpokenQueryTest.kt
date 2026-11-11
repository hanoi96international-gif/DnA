package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenQueryTest {
    @Test
    fun numberWords() {
        assertEquals(Hid.s("AC8bY3Rk"), SpokenQuery.normalize(Hid.s("AC8bY3QsYD+q")))
        assertEquals(Hid.s("FwU4DRYjeT6meigHJkx0Zw=="), SpokenQuery.normalize(Hid.s("ZD0qVHR2VS+tbSEdLUQzNzcfqmc3Tg==")))
    }

    @Test
    fun spokenChannelPicksBestVariant() {
        val channels = listOf(Hid.s("ACt7DQYCW3qQTQ=="), Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("ACt7DQYCW3r3Qg=="), Hid.s("ACt7DQYCWwCUTA1OCWk="), Hid.s("ACt7DQYCW3qWWQ=="))
        assertEquals(Hid.s("ACt7DQYCW3qLTQ=="), SpokenQuery.channel(Hid.s("FjoN"), channels) { it })
        assertEquals(Hid.s("ACt7DQYCWwCUTA1OCWk="), SpokenQuery.channel(Hid.s("FjoNDS4hcjM="), channels) { it })
        assertNull(SpokenQuery.channel(Hid.s("FBwuDQc/cjimZw=="), channels) { it })
    }

    @Test
    fun spokenTitle() {
        val movies = listOf(Hid.s("ACt7DRAzZXqTaDALYQVlbyBo6g=="), Hid.s("AAszDQQ3Yz/jOw=="), Hid.s("AgI0Tjx2cz+xKQ8PM0Q2P3w="))
        assertEquals(Hid.s("ACt7DRAzZXqTaDALYQVlbyBo6g=="), SpokenQuery.title(Hid.s("IAszDSQ3Yz8="), movies) { it })
        assertEquals(Hid.s("AAszDQQ3Yz/jOw=="), SpokenQuery.title(Hid.s("AAszDQQ3Yz/jczMLKA=="), movies) { it })
        assertNull(SpokenQuery.title(Hid.s("FA81SA=="), movies) { it })
    }
}
