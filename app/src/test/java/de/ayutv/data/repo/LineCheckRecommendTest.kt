package de.ayutv.data.repo

import de.ayutv.data.BufferProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class LineCheckRecommendTest {
    @Test
    fun uhdNeverGetsTheFastBuffer() {
        // Schnelle, saubere Leitung: bei Full HD „Schnell“, bei 4K trotzdem „Stabil“.
        assertEquals(BufferProfile.FAST, LineCheck.recommend(250, 0, 300, 5_000, null))
        assertEquals(BufferProfile.STABLE, LineCheck.recommend(250, 0, 300, 10_000, null, uhd = true))
    }

    @Test
    fun highBitrateAtLeastBalanced() {
        assertEquals(BufferProfile.BALANCED, LineCheck.recommend(250, 0, 300, 12_000, null))
    }
}
