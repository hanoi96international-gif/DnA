package de.ayutv.data.translate

import de.ayutv.data.repo.Translator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationNamesTest {
    @Test
    fun learnsNamesThatAreAlsoWords() {
        val lines = listOf(
            "Will you come with me?",          // Satzanfang: kein Name
            "I told Will about it.",           // mitten im Satz: Name
            "Will, wait for me!",              // Anrede: Name
            "Where is Hope?",
            "- Hope!\n- I'm here.",
            "Will's car is gone.",
            "I hope it works.",                // klein: Wort
            "Ask John.", "Ask John again.",    // Name, aber kein Wort: braucht keinen Schutz
        )
        assertEquals(setOf("Will", "Hope"), TranslationNames.learn(lines))
    }

    @Test
    fun protectsOnlyWhereItIsSurelyAName() {
        val names = setOf("Will", "Hope")
        val p = TranslationNames.protect("Will you tell Will that Hope is here?", names)
        assertEquals("Will you tell Zorvan that Kelmor is here?", p.text)
        assertEquals("Will du es Will sagen, dass Hope hier ist?", TranslationNames.restore("Will du es Zorvan sagen, dass Kelmor hier ist?", p))
        // Genitiv-Endung bleibt
        val g = TranslationNames.protect("That is Will's car.", names)
        assertEquals("Das ist Wills Auto.", TranslationNames.restore("Das ist Zorvans Auto.", g))
        // Platzhalter verändert oder verloren: lieber ohne Schutz übersetzen
        assertNull(TranslationNames.restore("Sag es Zorwan.", TranslationNames.protect("Tell Will.", names)))
        assertNull(TranslationNames.restore("Sag es ihm.", TranslationNames.protect("Tell Will.", names)))
        // Nichts zu schützen
        assertEquals("Hallo.", TranslationNames.restore("Hallo.", TranslationNames.protect("Hello.", names)))
    }

    @Test
    fun contextPrefixIsCutOff() {
        assertEquals("Ja, ihn.", SubtitleContext.stripPrefix("Hast du den Schlüssel gesehen? Ja, ihn.", "Hast du den Schlüssel gesehen?"))
        // Satzzeichen anders gesetzt: zählt trotzdem
        assertEquals("Zum Auto!", SubtitleContext.stripPrefix("Wir müssen weg, schnell. Zum Auto!", "Wir müssen weg, schnell!"))
        // Anfang anders übersetzt: kein Zusammenhang
        assertNull(SubtitleContext.stripPrefix("Wir sollten gehen. Zum Auto!", "Wir müssen weg."))
        assertNull(SubtitleContext.stripPrefix("Wir müssen weg.", "Wir müssen weg."))
        assertTrue(SubtitleContext.isShort("Yes, him."))
        assertFalse(SubtitleContext.isShort("I told you we should never have come here."))
        assertFalse(SubtitleContext.isShort("- Yes.\n- No."))
    }

    /** Übersetzt Wort für Wort aus einem kleinen Wörterbuch (wie ein Modell, das Namen mitübersetzt). */
    private class FakeTranslator : Translator {
        val calls = mutableListOf<List<String>>()
        private val words = mapOf("where" to "wo", "is" to "ist", "will" to "Wille", "hope" to "Hoffnung", "the" to "der", "key" to "Schlüssel", "my" to "mein", "old" to "alter", "friend" to "Freund", "yes" to "ja", "him" to "ihn")
        override val name = "fake"
        override fun supports(language: String) = true
        override suspend fun translate(texts: List<String>, target: String): List<String> {
            calls += texts
            return texts.map { t -> t.split(' ').joinToString(" ") { w -> val core = w.trimEnd('?', '.', ',', '!'); (words[core.lowercase()] ?: core) + w.substring(core.length) } }
        }
    }

    @Test
    fun wrapperKeepsNamesAndUsesContext() = runBlocking {
        val fake = FakeTranslator()
        val smart = SubtitleTranslator(fake, setOf("Will"))
        val out = smart.translate(listOf("Where is Will?", "Where is the key, my old friend?", "Yes, him."), "de", "en")
        assertEquals("wo ist Will?", out[0])
        assertEquals("wo ist der Schlüssel, mein alter Freund?", out[1])
        // kurze Zeile: mit dem vorherigen Satz übersetzt, dessen Übersetzung abgeschnitten
        assertEquals("ja, ihn.", out[2])
        assertTrue(fake.calls.any { batch -> batch.any { it.startsWith("Where is the key, my old friend? Yes") } })
    }
}
