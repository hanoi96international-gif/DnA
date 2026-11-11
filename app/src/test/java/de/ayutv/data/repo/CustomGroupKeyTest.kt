package de.ayutv.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomGroupKeyTest {
    @Test
    fun keyRoundTrip() {
        assertEquals(42L, customGroupId(customGroupKey(42)))
        // Gruppen des Anbieters (auch solche mit Doppelpunkt oder Zahl im Namen) sind keine eigenen Gruppen.
        assertNull(customGroupId("DE: Sport"))
        assertNull(customGroupId("42"))
        assertNull(customGroupId("eigene:42"))
    }
}
