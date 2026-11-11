package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtTest {

    @Test
    fun parsesMultilineCuesWithBomAndCrlf() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\nWorld\r\n\r\n2\r\n00:00:03.000 --> 00:00:04.000 X1:0\r\n<i>Bye</i>\r\n"
        val cues = Srt.parse(srt)
        assertEquals(2, cues.size)
        assertEquals("Hello\nWorld", cues[0].text)
        assertEquals("00:00:03,000", cues[1].start)
        assertEquals("Bye", Srt.plainText(cues[1].text))
    }

    @Test
    fun handlesMissingBlankLineBetweenCues() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nA\n2\n00:00:03,000 --> 00:00:04,000\nB\n"
        assertEquals(listOf("A", "B"), Srt.parse(srt).map { it.text })
    }

    @Test
    fun roundTrips() {
        val cues = listOf(SrtCue("00:00:01,000", "00:00:02,000", "Hallo\nWelt"))
        assertEquals(cues, Srt.parse(Srt.write(cues)))
    }

    @Test
    fun stripsAssOverrideTags() {
        assertEquals("Oben", Srt.plainText("{\\an8}<b>Oben</b>"))
    }
}
