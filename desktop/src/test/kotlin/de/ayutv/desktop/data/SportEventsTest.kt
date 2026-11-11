package de.ayutv.desktop.data

import de.ayutv.data.parse.Hid

import org.junit.Assert.assertEquals
import org.junit.Test

class SportEventsTest {
    private val now = 1_800_000_000_000L
    private fun channel(id: Int, name: String) = Channel("1:c:$id", 1, name, Hid.s("Fx4uXyA="), url = "u", epgId = "e$id")

    @Test
    fun groupsSameMatchAcrossChannelsAndSkipsRepeats() {
        val chanA = channel(1, Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkX2FlEA=="))
        val uhdA = channel(2, Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkOwlp"))
        val chanD = channel(3, Hid.s("HioH"))
        val game = Programme(now - 600_000, now + 6_000_000, Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg="), Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgdudBR2I6Z7Kk4M7ug4dDKmZw=="), Hid.s("CAc3SHQ3YinjTSscNUAhOHM="), Hid.s("Fx4uXyA="))
        val repeat = Programme(now + 7_200_000, now + 9_000_000, Hid.s("AhuCsjY3ezb5KQYbL0kxJXszpGg="), Hid.s("EwszSTEkNxixbCkLLw221oR6lW8IThZCODBkOLZ7Iw=="), null, Hid.s("Fx4uXyA="), flags = 1)
        val news = Programme(now, now + 1_800_000, Hid.s("LAs0WTF2fTW2eyoPLQ=="), null, null, Hid.s("Cg8iRSY/dDK3bCo="))
        val events = SportEvents.of(listOf(chanA, uhdA, chanD), mapOf(chanA.key to listOf(game, repeat), uhdA.key to listOf(game), chanD.key to listOf(news)), favorites = emptySet(), now = now)
        assertEquals(1, events.size)
        assertEquals(Hid.s("BgEzWCclfjvjTSscNUAhOHN6IYnXTgdudBR2I6Z7Kk4M7ug4dDKmZw=="), events.single().pairing)
        // Sport immer in bester Qualität: die UHD-Fassung zuerst, HD als Ausweichsender.
        assertEquals(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkOwlp"), events.single().channel.name)
        assertEquals(listOf(Hid.s("FwU4DQcmeCi3KQYbL0kxJXszpGhkX2FlEA==")), events.single().alsoOn.map { it.name })
    }
}
