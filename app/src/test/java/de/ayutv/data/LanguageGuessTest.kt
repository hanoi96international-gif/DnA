package de.ayutv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageGuessTest {
    @Test
    fun germanLiveSubtitles() {
        assertEquals("de", Languages.guess(listOf("aber nicht zu stark", "Das ist doch klar, ich weiß es nicht.")))
    }

    @Test
    fun englishSubtitles() {
        assertEquals("en", Languages.guess(listOf("I don't know what you mean.", "Where is my brother? It's not here.")))
    }

    @Test
    fun tooShortOrUnclear() {
        assertNull(Languages.guess(listOf("Hallo")))
        assertNull(Languages.guess(listOf("♪ ♪ ♪")))
    }
}
