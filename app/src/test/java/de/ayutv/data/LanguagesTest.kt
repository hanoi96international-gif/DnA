package de.ayutv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagesTest {
    @Test
    fun normalizesTrackLanguages() {
        assertEquals("de", Languages.normalize("ger"))
        assertEquals("de", Languages.normalize("de-DE"))
        assertEquals("en", Languages.normalize("eng"))
        // Indonesische Spuren: Media3 macht aus „ind“ „ms-ind“, ältere Java-Versionen aus „id“ „in“.
        assertEquals("id", Languages.normalize("ms-ind"))
        assertEquals("id", Languages.normalize("ind"))
        assertEquals("id", Languages.normalize("in"))
        assertEquals("no", Languages.normalize("no-nob"))
        assertNull(Languages.normalize("und"))
        assertNull(Languages.normalize(""))
    }
}
