package de.ayutv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Welche eingebettete Spur nachts übersetzt wird: wie im Player (Englisch zuerst, Text vor Bild, keine erzwungenen). */
class EmbeddedSourceTest {
    private fun track(id: String, language: String, original: String = MimeTypes.APPLICATION_SUBRIP, forced: Boolean = false, sdh: Boolean = false) =
        Format.Builder()
            .setId(id)
            .setLanguage(language)
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(original)
            .setSelectionFlags(if (forced) C.SELECTION_FLAG_FORCED else 0)
            .setRoleFlags(if (sdh) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
            .build()

    @Test
    fun englishTextFirst() {
        val formats = listOf(
            track("3", "de"),
            track("4", "en", original = MimeTypes.APPLICATION_PGS),
            track("5", "en", sdh = true),
            track("6", "en"),
        )
        assertEquals("6", EmbeddedReader.bestSource(formats, "id")?.id)
    }

    @Test
    fun bitmapWhenNothingElse() {
        assertEquals("4", EmbeddedReader.bestSource(listOf(track("4", "en", original = MimeTypes.APPLICATION_PGS)), "id")?.id)
        // Deutsch, wenn es kein Englisch gibt.
        assertEquals("3", EmbeddedReader.bestSource(listOf(track("3", "de")), "id")?.id)
    }

    @Test
    fun nothingToDo() {
        // Wunschsprache schon eingebettet: der Player zeigt sie direkt.
        assertNull(EmbeddedReader.bestSource(listOf(track("3", "id"), track("4", "en")), "id"))
        // Nur erzwungene Untertitel (einzelne fremdsprachige Stellen): keine Vorlage für den ganzen Film.
        assertNull(EmbeddedReader.bestSource(listOf(track("3", "en", forced = true)), "id"))
        assertNull(EmbeddedReader.bestSource(emptyList(), "id"))
    }
}
