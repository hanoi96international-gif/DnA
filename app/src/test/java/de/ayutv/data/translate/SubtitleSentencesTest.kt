package de.ayutv.data.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSentencesTest {
    private fun cue(start: Int, text: String, length: Int = 2_000) = SubtitleSentences.Cue(start * 1000L, start * 1000L + length, text)

    @Test
    fun sentencesOverSeveralCuesStayTogether() {
        val cues = listOf(
            cue(0, "I told you we should"),
            cue(2, "never have come here."),
            cue(5, "Where is he?"),
            cue(7, "- I don't know.\n- Find him!"),
            cue(9, "Because when you"),
            cue(11, "think about it,"),
            cue(13, "it makes no sense."),
            cue(15, "And this one"), // nächste Zeile kommt erst 10 s später: nicht zusammen
            cue(28, "starts much later."),
        )
        val groups = SubtitleSentences.groups(cues)
        assertEquals(listOf(0..1, 2..2, 3..3, 4..6, 7..7, 8..8), groups)
        assertEquals("I told you we should never have come here.", SubtitleSentences.joined(cues, 0..1))
    }

    @Test
    fun continuationRules() {
        assertTrue(SubtitleSentences.continues("I was going to tell you...", "...that I'm leaving."))
        assertFalse(SubtitleSentences.continues("I was going to tell you...", "Forget it."))
        assertFalse(SubtitleSentences.continues("He said \"no.\"", "and left"))
        assertFalse(SubtitleSentences.continues("♪ Hey Jude ♪", "don't make it bad"))
        assertFalse(SubtitleSentences.continues("<i>Who's there</i>", "- Me."))
        assertTrue(SubtitleSentences.continues("<i>Who's</i>", "<i>there?</i>"))
    }

    @Test
    fun translationIsSplitByLengthAtGoodPlaces() {
        val parts = SubtitleSentences.split(
            "Ich habe dir gesagt, wir hätten nie hierherkommen sollen.",
            listOf("I told you we should".length, "never have come here.".length),
        )
        assertEquals(listOf("Ich habe dir gesagt,", "wir hätten nie hierherkommen sollen."), parts)
        val three = SubtitleSentences.split("Denn wenn man darüber nachdenkt, ergibt es keinen Sinn.", listOf(16, 14, 18))
        assertEquals(3, three.size)
        assertEquals("Denn wenn man darüber nachdenkt, ergibt es keinen Sinn.", three.joinToString(" "))
        assertTrue(three.none { it.isBlank() })
        // Zu wenige Wörter: der ganze Satz bleibt stehen
        assertEquals(listOf("Hallo!", "Hallo!"), SubtitleSentences.split("Hallo!", listOf(5, 5)))
    }

    @Test
    fun longLinesAreWrapped() {
        assertEquals("Ich habe dir gesagt,\nwir hätten nie hierherkommen sollen.", SubtitleSentences.wrap("Ich habe dir gesagt, wir hätten nie hierherkommen sollen."))
        assertEquals("Kurz.", SubtitleSentences.wrap("Kurz."))
        assertEquals("- Ja.\n- Nein.", SubtitleSentences.wrap("- Ja.\n- Nein."))
    }
}
