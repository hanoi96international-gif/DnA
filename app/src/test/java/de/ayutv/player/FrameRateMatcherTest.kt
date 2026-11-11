package de.ayutv.player

import de.ayutv.player.FrameRateMatcher.Companion.pickMode
import de.ayutv.player.FrameRateMatcher.DisplayModeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Typische Modi eines 4K-Fernsehers am Fire TV Stick 4K. */
class FrameRateMatcherTest {
    private val modes = listOf(
        DisplayModeInfo(1, 3840, 2160, 60f),
        DisplayModeInfo(2, 3840, 2160, 59.94f),
        DisplayModeInfo(3, 3840, 2160, 50f),
        DisplayModeInfo(4, 3840, 2160, 24f),
        DisplayModeInfo(5, 3840, 2160, 23.976f),
        DisplayModeInfo(6, 3840, 2160, 25f),
        DisplayModeInfo(7, 1920, 1080, 50f),
    )
    private val current = modes[0]

    @Test
    fun germanTv25fpsUses50Hz() = assertEquals(3, pickMode(modes, current, 25f))

    @Test
    fun germanTv50fpsUses50Hz() = assertEquals(3, pickMode(modes, current, 50f))

    @Test
    fun filmUsesExact23976() = assertEquals(5, pickMode(modes, current, 23.976f))

    @Test
    fun film24UsesExact24() = assertEquals(4, pickMode(modes, current, 24f))

    @Test
    fun ntsc2997Uses5994() = assertEquals(2, pickMode(modes, current, 29.97f))

    @Test
    fun neverChangesResolution() = assertNull(pickMode(modes.filter { it.width == 3840 && it.refreshRate != 50f && it.refreshRate != 25f }, current, 50f))

    @Test
    fun unknownFrameRate() = assertNull(pickMode(modes, current, 0f))
}
