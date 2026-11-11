package de.ayutv.desktop.player

import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerOptionsTest {
    @Test
    fun defaultsChangeNothing() {
        // Grundeinstellung: kein Kompressor, keine Lautstärke-Angleichung, keine Klangregelung.
        assertEquals(emptyList<String>(), PlayerOptions.engine(UserData()))
        assertNull(PlayerOptions.equalizer(UserData()))
    }

    @Test
    fun sound() {
        assertEquals(listOf("compressor", "normvol"), PlayerOptions.engine(UserData(audioMode = AudioMode.NIGHT, normalizeVolume = true)))
        assertEquals(10, PlayerOptions.equalizer(UserData(audioMode = AudioMode.DIALOG))?.size)
    }
}
