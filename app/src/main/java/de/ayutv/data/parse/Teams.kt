package de.ayutv.data.parse

import java.text.Normalizer

/**
 * Vereine in Paarungen erkennen („Borussia Dortmund – Eintracht Frankfurt“). Wer einem Verein folgt,
 * soll seine Spiele finden, auch wenn das EPG ihn anders schreibt („BVB“ bleibt außen vor, aber
 * „Dortmund“, „Borussia Dortmund“ und „Bor. Dortmund“ passen). Frauen- und Jugendmannschaften zählen
 * nur, wenn man genau ihnen folgt.
 */
object Teams {
    private val SPLIT = Regex("""\s+(?:[–—-]|(?i:vs)\.?|v\.?|(?i:gegen)|@)\s+""")
    private val MARKS = Regex("""\p{M}+""")
    private val NON_WORD = Regex("""[^a-z0-9]+""")
    /** Vereinskürzel und Allerweltswörter: Allein sagen sie nichts über den Verein. */
    private val GENERIC = setOf(
        "fc", "sv", "sc", "vfl", "vfb", "tsg", "fsv", "tsv", "spvgg", "ssv", "sg", "rb", "bor", "borussia", "eintracht",
        "fortuna", "union", "hertha", "bsc", "werder", "bayer", "1899", "04", "05", "1860", "1846", "kv", "ac", "as", "ss",
        "real", "atletico", "sporting", "olympique", "afc", "cf", "cd", "ud", "rc", "club", "de", "der", "die", "1",
    )
    private val YOUTH = Regex("""(?i)\b(u\s?1[0-9]|u\s?2[0-3]|frauen|women|damen|junioren|ii)\b""")

    /** Die beiden Mannschaften einer Paarung, ohne Spieltag und Zusätze. */
    fun ofPairing(pairing: String): List<String> {
        val parts = pairing.substringBefore(',').substringAfter(": ").split(SPLIT)
        if (parts.size != 2) return emptyList()
        return parts.map { it.trim().trimEnd('.') }.filter { it.length >= 2 }
    }

    /**
     * Folgt man [team], ist dieses Spiel eins seiner Spiele? [pairing]: „A – B“ (evtl. mit Zusätzen),
     * [context]: Titel/Wettbewerb, um Frauen- und Jugendspiele zu erkennen.
     */
    fun plays(team: String, pairing: String, context: String = ""): Boolean {
        val wanted = words(team)
        if (wanted.isEmpty()) return false
        // Frauen/Jugend nur, wenn genau danach gefolgt wird
        if ((YOUTH.containsMatchIn(pairing) || YOUTH.containsMatchIn(context)) && !YOUTH.containsMatchIn(team)) return false
        val sides = ofPairing(pairing).ifEmpty { pairing.split(SPLIT) }
        return sides.any { side ->
            val have = words(side)
            val core = wanted.filter { it !in GENERIC && it.length >= 3 }
            if (core.isEmpty()) have.containsAll(wanted) else core.all { it in have || have.any { h -> h.length >= 4 && h.startsWith(it.take(4)) && it.startsWith(h.take(4)) } }
        }
    }

    /** Aussagekräftige Wörter eines Vereinsnamens (ohne Kürzel und Allerweltswörter), klein und ohne Akzente. */
    fun keywords(text: String): Set<String> = words(text).filter { it !in GENERIC && it.length >= 4 }.toSet()

    /** Text als Wortmenge, klein, ohne Akzente; „Schüco-Arena“ ergibt zusätzlich „schucoarena“ (Wörter verbunden). */
    fun textWords(text: String): Set<String> {
        val plain = words(text)
        val joined = Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFKD).replace(MARKS, "")
            .split(Regex("""\s+""")).map { it.replace(NON_WORD, "") }.filter { it.isNotEmpty() }
        return plain + joined
    }

    private fun words(text: String): Set<String> =
        Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFKD).replace(MARKS, "")
            .split(NON_WORD).filter { it.isNotEmpty() }.toSet()
}
