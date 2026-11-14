package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLinesTest {
    @Test
    fun layeredCopiesShownOnce() {
        // ASS: dieselbe Zeile als Schatten- und Textebene.
        assertEquals("¿Dónde está?", SubtitleLines.distinctLines(listOf("¿Dónde está?", "¿Dónde está?")))
        assertEquals("Wo ist er?", SubtitleLines.distinctLines(listOf("<i>Wo ist er?</i>", "Wo  ist er?")).removePrefix("<i>").removeSuffix("</i>"))
    }

    @Test
    fun translatedTextWithDuplicateLines() {
        assertEquals("Wo ist er?", SubtitleLines.dedupe("Wo ist er?\nWo ist er?"))
    }

    @Test
    fun dialogueAndDifferentLinesStay() {
        assertEquals("- Nein.\n- Nein.", SubtitleLines.distinctLines(listOf("- Nein.", "- Nein.")))
        assertEquals("Erste Zeile\nZweite Zeile", SubtitleLines.distinctLines(listOf("Erste Zeile\nZweite Zeile")))
    }
}
