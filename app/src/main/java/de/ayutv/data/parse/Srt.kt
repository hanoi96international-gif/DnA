package de.ayutv.data.parse

data class SrtCue(val start: String, val end: String, val text: String)

/** Liest und schreibt SubRip (.srt). Robust gegen BOM, Windows-Zeilenenden und fehlende Nummern. */
object Srt {
    private val timing = Regex("""^\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}).*$""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(content: String): List<SrtCue> {
        val lines = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<SrtCue>()
        var i = 0
        while (i < lines.size) {
            val match = timing.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val text = StringBuilder()
            i++
            while (i < lines.size && lines[i].isNotBlank()) {
                // Eine Zeile, die nur aus einer Zahl besteht und von einer Zeitangabe gefolgt wird, ist die nächste Nummer.
                if (i + 1 < lines.size && lines[i].trim().all(Char::isDigit) && timing.matches(lines[i + 1])) break
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[i].trimEnd())
                i++
            }
            cues += SrtCue(match.groupValues[1].replace('.', ','), match.groupValues[2].replace('.', ','), text.toString())
        }
        return cues
    }

    fun write(cues: List<SrtCue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(cue.start).append(" --> ").append(cue.end).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    /** Formatierungs-Tags (<i>, {\an8}) stören Übersetzer und werden vorher entfernt. */
    fun plainText(text: String): String = text.replace(tags, "").trim()
}
