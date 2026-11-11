package de.ayutv.data.translate

/**
 * Namen, die zugleich gewöhnliche Wörter sind („Will“, „Hope“, „Grace“, „Mark“), übersetzt das Modell gern mit
 * („Wille“, „Hoffnung“). Solche Namen werden vor dem Übersetzen durch erfundene Platzhalter ersetzt, die das
 * Modell unverändert durchreicht, und danach wieder eingesetzt. Welche Wörter in einer Serie Namen sind, lernt
 * die App aus den Untertiteln selbst: großgeschrieben mitten im Satz, als Anrede („Will, komm!“) oder mit „'s“.
 */
object TranslationNames {
    /** Englische Wörter, die auch Vornamen oder Namen sind (nur bei ihnen verrutscht die Übersetzung). */
    private val AMBIGUOUS = setOf(
        "will", "hope", "grace", "faith", "joy", "mark", "bill", "rose", "frank", "hunter", "summer", "autumn", "april",
        "may", "june", "august", "angel", "max", "nick", "pat", "ray", "sky", "river", "stone", "king", "page", "rich",
        "sunny", "dawn", "daisy", "lily", "ivy", "ruby", "amber", "crystal", "pearl", "jade", "holly", "heather", "iris",
        "violet", "olive", "rob", "sue", "art", "chase", "cliff", "dean", "drew", "gene", "glen", "guy", "jay", "lance",
        "miles", "norm", "penny", "sandy", "skip", "wade", "warren", "woody", "buck", "chuck", "duke", "earl", "ford",
        "gray", "grey", "grant", "hardy", "hill", "hawk", "price", "fox", "wolf", "bishop", "mason", "cook", "baker",
        "smith", "brown", "green", "white", "black", "young", "hall", "wood", "bell", "lane", "forest", "storm", "rain",
        "star", "ace", "harmony", "destiny", "liberty", "patience", "prudence", "mercy", "honor", "trinity", "chance",
        "noble", "major", "sterling", "rusty", "buddy", "bear", "bunny", "kitty", "candy", "brandy", "ginger", "pepper",
        "cash", "jewel", "goldie", "dusty", "rocky", "sonny", "earnest", "constance", "felicity", "hazel", "willow",
        "sage", "rowan", "reed", "heath", "moss", "flint", "carter", "porter", "tanner", "taylor", "archer", "fisher",
        "booker", "parker", "walker", "turner", "miller", "butler", "shepherd", "rider", "banks", "rivers", "hart",
    )

    /** Erfundene Namen ohne Bedeutung: Das Modell lässt sie stehen (höchstens mit Endung, z. B. Genitiv-s). */
    private val PLACEHOLDERS = listOf("Zorvan", "Kelmor", "Durvik", "Talnek", "Morvak", "Selvor", "Quarnel", "Belvik")

    private val WORD = Regex("""[A-Za-zÀ-ÖØ-öø-ÿ']+""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    /** Namen aus Untertitel-Zeilen lernen (je Wort mindestens [minHits] eindeutige Stellen). */
    fun learn(lines: Iterable<String>, minHits: Int = 2): Set<String> {
        val hits = HashMap<String, Int>()
        for (line in lines) {
            for ((word, sure) in occurrences(line)) {
                if (sure && word.lowercase() in AMBIGUOUS) hits[word] = (hits[word] ?: 0) + 1
            }
        }
        return hits.filterValues { it >= minHits }.keys
    }

    /**
     * Großgeschriebene Wörter einer Zeile; „sicher“ ist ein Name mitten im Satz, als Anrede am Satzanfang
     * („Will, …“, „Hope!“) oder mit „'s“. Am Satzanfang sonst nicht („Will you …“ ist kein Name).
     */
    private fun occurrences(line: String): List<Pair<String, Boolean>> {
        val text = line.replace(TAGS, " ")
        val out = ArrayList<Pair<String, Boolean>>()
        for (m in WORD.findAll(text)) {
            val raw = m.value
            val word = raw.removeSuffix("'s").trim('\'')
            if (word.length < 2 || !word[0].isUpperCase() || word.drop(1).any { it.isUpperCase() }) continue
            val before = text.substring(0, m.range.first).trimEnd()
            val sentenceStart = before.isEmpty() || before.last() in ".!?…:-–\"“„♪" || before.endsWith("...")
            val after = text.substring(m.range.last + 1).trimStart()
            val sure = !sentenceStart || raw.endsWith("'s") || (after.isNotEmpty() && after[0] in ",!?.") || after.isEmpty()
            out += word to sure
        }
        return out
    }

    data class Protected(val text: String, val names: Map<String, String>)

    /** Bekannte Namen in [text] durch Platzhalter ersetzen (nur dort, wo sie sicher Namen sind). */
    fun protect(text: String, names: Set<String>): Protected {
        if (names.isEmpty()) return Protected(text, emptyMap())
        val used = LinkedHashMap<String, String>() // Platzhalter → Name
        val byName = HashMap<String, String>()
        val sb = StringBuilder()
        var last = 0
        val plain = text
        for (m in WORD.findAll(plain)) {
            val raw = m.value
            val word = raw.removeSuffix("'s").trim('\'')
            if (word !in names) continue
            val before = plain.substring(0, m.range.first).trimEnd()
            val sentenceStart = before.isEmpty() || before.last() in ".!?…:-–\"“„♪\n" || before.endsWith("...")
            val after = plain.substring(m.range.last + 1).trimStart()
            val sure = !sentenceStart || raw.endsWith("'s") || (after.isNotEmpty() && after[0] in ",!?.") || after.isEmpty()
            if (!sure) continue
            val placeholder = byName.getOrPut(word) {
                PLACEHOLDERS.getOrNull(used.size)?.also { used[it] = word } ?: return@getOrPut ""
            }
            if (placeholder.isEmpty()) continue
            val start = m.range.first + raw.indexOf(word)
            sb.append(plain, last, start).append(placeholder)
            last = start + word.length
        }
        if (used.isEmpty()) return Protected(text, emptyMap())
        sb.append(plain, last, plain.length)
        return Protected(sb.toString(), used)
    }

    /** Platzhalter in der Übersetzung wieder durch die Namen ersetzen; null, wenn einer fehlt (dann ohne Schutz übersetzen). */
    fun restore(translated: String, protected: Protected): String? {
        if (protected.names.isEmpty()) return translated
        var out = translated
        for ((placeholder, name) in protected.names) {
            val expected = Regex("""\b$placeholder""").findAll(protected.text).count()
            val found = Regex("""\b$placeholder""").findAll(out).count()
            if (found < expected || found == 0) return null
            out = out.replace(Regex("""\b$placeholder"""), name)
        }
        // Ein Platzhalter, den das Modell verändert hat (z. B. „Zorwan“), darf nicht im Bild stehen.
        if (protected.names.keys.any { out.contains(it.take(4)) }) return null
        return out
    }

    /** Namen als Textzeile speichern / lesen (je Serie). */
    fun encode(names: Set<String>): String = names.sorted().joinToString("\n")
    fun decode(text: String): Set<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() in AMBIGUOUS }.toSet()
}
