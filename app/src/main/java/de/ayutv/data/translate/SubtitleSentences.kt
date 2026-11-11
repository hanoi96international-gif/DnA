package de.ayutv.data.translate

/**
 * Untertitel teilen Sätze oft auf zwei, drei Einblendungen auf („I told you we should“ / „never have come here.“).
 * Zeile für Zeile übersetzt, kommt Unsinn heraus, weil dem Modell die andere Hälfte fehlt. Hier werden solche
 * Einblendungen zu einem Satz zusammengefasst, als Ganzes übersetzt und die Übersetzung wieder auf die
 * Einblendungen verteilt (nach Länge, bevorzugt an Satzzeichen).
 */
object SubtitleSentences {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Satzende: Punkt, Frage-, Ausrufezeichen, auch mit Anführungszeichen oder Klammer dahinter. Nicht „...“. */
    private val TERMINAL = Regex("""(?<!\.\.)[.!?…♪]["'”»)\]]*$""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /** Text einer Einblendung ohne Formatierung, Zeilen zu einer zusammengefasst. */
    fun plain(text: String): String = text.replace(TAGS, "").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** Wechselgespräch („- Wo ist er?\n- Keine Ahnung.“): wird nie mit Nachbarn zusammengefasst. */
    fun isDialog(text: String): Boolean {
        val lines = text.replace(TAGS, "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.size > 1 && lines.count { it.startsWith("-") || it.startsWith("–") } >= 2
    }

    /** Geht der Satz in [next] weiter? */
    fun continues(text: String, next: String): Boolean {
        val current = plain(text)
        val following = plain(next)
        if (current.isEmpty() || following.isEmpty() || isDialog(text) || isDialog(next)) return false
        if (following.startsWith("-") || following.startsWith("–")) return false
        if (current.endsWith("♪") || following.startsWith("♪")) return false
        // „Ich wollte dir sagen...“ / „...dass ich gehe.“: ausdrücklich fortgesetzt
        if (current.endsWith("...") || current.endsWith("…")) return following.startsWith("...") || following.startsWith("…") || following.first().isLowerCase()
        return !TERMINAL.containsMatchIn(current)
    }

    /** Gruppen von Einblendungen, die zusammen einen Satz bilden (höchstens [maxCues], Lücke höchstens [maxGapMs]). */
    fun groups(cues: List<Cue>, maxCues: Int = 3, maxGapMs: Long = 1_500): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < cues.size) {
            var end = i
            while (end + 1 < cues.size && end - i + 1 < maxCues &&
                cues[end + 1].startMs - cues[end].endMs.coerceAtLeast(cues[end].startMs) <= maxGapMs &&
                continues(cues[end].text, cues[end + 1].text)
            ) end++
            out += i..end
            i = end + 1
        }
        return out
    }

    /** Die Texte einer Gruppe als ein Satz (für das Modell). */
    fun joined(cues: List<Cue>, group: IntRange): String =
        group.joinToString(" ") { plain(cues[it].text) }.replace(Regex("""\.\.\.\s*\.\.\.|…\s*…"""), " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * Verteilt [translation] auf so viele Teile, wie [sourceLengths] Einträge hat, im Verhältnis der Längen der
     * Vorlage. Getrennt wird zwischen Wörtern, nahe der idealen Stelle bevorzugt nach einem Satzzeichen.
     * Reicht die Übersetzung nicht für alle Teile, steht sie ganz in jedem (die Einblendung bleibt einfach länger stehen).
     */
    fun split(translation: String, sourceLengths: List<Int>): List<String> {
        val parts = sourceLengths.size
        val text = translation.replace(Regex("""\s+"""), " ").trim()
        if (parts <= 1) return listOf(text)
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < parts) return List(parts) { text }
        val total = sourceLengths.sumOf { it.coerceAtLeast(1) }.toDouble()
        // Zeichenposition nach jedem Wort (ohne das folgende Leerzeichen)
        val ends = IntArray(words.size)
        var position = 0
        words.forEachIndexed { k, w -> position += w.length; ends[k] = position; position += 1 }
        val length = position - 1
        val cuts = ArrayList<Int>() // Index des letzten Worts jedes Teils (ohne den letzten Teil)
        var cumulative = 0.0
        var previous = -1
        for (p in 0 until parts - 1) {
            cumulative += sourceLengths[p].coerceAtLeast(1)
            val ideal = cumulative / total * length
            val first = previous + 1
            val last = words.size - (parts - 1 - p) - 1 // genug Wörter für die übrigen Teile lassen
            var best = first
            var bestScore = Double.MAX_VALUE
            for (k in first..last) {
                var score = kotlin.math.abs(ends[k] - ideal)
                if (words[k].last() in ",;:—–") score -= length * 0.12
                if (words[k].last() in ".!?") score -= length * 0.2
                if (score < bestScore) {
                    bestScore = score
                    best = k
                }
            }
            cuts += best
            previous = best
        }
        val out = ArrayList<String>()
        var from = 0
        for (cut in cuts) {
            out += words.subList(from, cut + 1).joinToString(" ")
            from = cut + 1
        }
        out += words.subList(from, words.size).joinToString(" ")
        return out
    }

    /** Lange einzeilige Übersetzung auf zwei ausgewogene Zeilen umbrechen (wie bei richtigen Untertiteln). */
    fun wrap(text: String, maxLine: Int = 42): String {
        if ('\n' in text || text.length <= maxLine) return text
        val middle = text.length / 2
        val spaces = text.indices.filter { text[it] == ' ' }
        if (spaces.isEmpty()) return text
        val best = spaces.minBy { i ->
            var score = kotlin.math.abs(i - middle).toDouble()
            if (i > 0 && text[i - 1] in ",;:.!?") score -= text.length * 0.15
            score
        }
        return text.substring(0, best) + "\n" + text.substring(best + 1)
    }
}
