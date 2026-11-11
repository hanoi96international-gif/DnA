package de.ayutv.player

import de.ayutv.data.AspectMode
import de.ayutv.data.PlayerMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMemoryTest {
    @Test
    fun roundTrip() {
        val c = PlayerMemory.Choice(audio = "de", text = PlayerMemory.OFF, aspect = AspectMode.STRETCH)
        val back = PlayerMemory.decode(PlayerMemory.encode(c))
        assertEquals(c, back)
        assertTrue(back.textOff)
    }

    @Test
    fun emptyAndPartial() {
        assertEquals(PlayerMemory.Choice(), PlayerMemory.decode(null))
        assertEquals(PlayerMemory.Choice(audio = "en"), PlayerMemory.decode("en"))
        assertEquals(PlayerMemory.Choice(text = "de"), PlayerMemory.decode("|de|UNKNOWN"))
        // Ältere Einträge ohne vierte Spalte, neue mit geladenen Untertiteln
        assertEquals(PlayerMemory.Choice(audio = "de", aspect = AspectMode.STRETCH), PlayerMemory.decode("de||STRETCH"))
        val loaded = PlayerMemory.Choice(audio = "en", load = "de")
        assertEquals(loaded, PlayerMemory.decode(PlayerMemory.encode(loaded)))
    }
}
