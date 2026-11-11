package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelVariantsTest {
    private data class Ch(val name: String, val playlist: Long = 1)

    private fun merge(vararg names: String) = ChannelVariants.merge(names.map { Ch(it) }, { it.name }, { it.playlist }).map { it.name }

    @Test
    fun variantsBecomeOneEntryInBestQuality() {
        assertEquals(
            listOf(Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("ACt7DQIZT3qFQQA="), Hid.s("ACt7DQ4SUQ==")),
            merge(Hid.s("ACt7DQYCW3qQTQ=="), Hid.s("ACt7DQYCW3qLTQ=="), Hid.s("ACt7DQIZT3r3Qg=="), Hid.s("ACt7DQIZT3qFQQA="), Hid.s("ACt7DQYCW3r3Qg=="), Hid.s("FDwIYBFsNwiXRWSP9ZK14ru7dos="), Hid.s("ACt7DQ4SUQ=="), Hid.s("ACt7DQYCW3rrSyUNKlgkfw==")),
        )
        assertEquals(listOf(Hid.s("FDwIYBFsN2mQSBBOoJnrt6P2IrzG")), merge(Hid.s("ACt7DWcFVg7jPQ8="), Hid.s("FDwIYBFsN2mQSBBOoJnrt6P2IrzG")))
        assertEquals(listOf(Hid.s("BTwVaHS3o+4ivfQ=")), merge(Hid.s("BTwVaHS3otsivfCP9Z0="), Hid.s("BTwVaA=="), Hid.s("BTwVaHS3o+4ivfQ=")))
    }

    @Test
    fun differentChannelsStaySeparate() {
        assertEquals(listOf(Hid.s("FjoN"), Hid.s("FjoNDX9n"), Hid.s("FjoNDWA="), Hid.s("FjoNDRckfjem")), merge(Hid.s("FjoN"), Hid.s("FjoNDX9n"), Hid.s("FjoNDWA="), Hid.s("FjoNDRckfjem")))
        assertEquals(listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXw=="), Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXA=="), Hid.s("AC8bYw=="), Hid.s("AC8bY3Rn")), merge(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXw=="), Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkXA=="), Hid.s("AC8bYw=="), Hid.s("AC8bY3Rn")))
        // Verschiedene Playlists bleiben getrennt, Namen ohne Inhalt werden nicht zusammengefasst
        assertEquals(2, ChannelVariants.merge(listOf(Ch(Hid.s("FjoN"), 1), Ch(Hid.s("FjoN"), 2)), { it.name }, { it.playlist }).size)
        assertEquals(listOf(Hid.s("DCo="), "4K"), merge(Hid.s("DCo="), "4K"))
    }

    @Test
    fun alternativesBestFirst() {
        val all = listOf(Ch(Hid.s("ACt7DQYCW3r3Qg==")), Ch(Hid.s("ACt7DQYCW3qLTQ==")), Ch(Hid.s("ACt7DQYCW3qQTQ==")), Ch(Hid.s("ACt7DQIZT3qLTQ==")), Ch(Hid.s("FjoNDXwUdjmofDRH")))
        val alt = ChannelVariants.alternatives(all[1], all, { it.name }, { it.playlist }, { a, b -> a == b }).map { it.name }
        assertEquals(listOf(Hid.s("ACt7DQYCW3r3Qg=="), Hid.s("ACt7DQYCW3qQTQ=="), Hid.s("FjoNDXwUdjmofDRH")), alt)
    }
}
