package de.ayutv.data

import de.ayutv.data.parse.Hid

/** Untertitelsprachen. [code] ist ISO 639-1 (OpenSubtitles, Media3, Übersetzer). */
data class Language(val code: String, val label: String)

object Languages {
    val all: List<Language> = listOf(
        Language("de", "Deutsch"),
        Language("en", "Englisch"),
        Language("fr", "Französisch"),
        Language("es", "Spanisch"),
        Language("it", "Italienisch"),
        Language("pt", "Portugiesisch"),
        Language("nl", "Niederländisch"),
        Language("pl", "Polnisch"),
        Language("tr", "Türkisch"),
        Language("ru", "Russisch"),
        Language("uk", "Ukrainisch"),
        Language("ar", "Arabisch"),
        Language("el", "Griechisch"),
        Language("cs", "Tschechisch"),
        Language("sk", "Slowakisch"),
        Language("sl", "Slowenisch"),
        Language("hu", "Ungarisch"),
        Language("ro", "Rumänisch"),
        Language("bg", "Bulgarisch"),
        Language(Hid.s("LBw="), "Kroatisch"),
        Language(Hid.s("Nxw="), "Serbisch"),
        Language("bs", "Bosnisch"),
        Language("sq", "Albanisch"),
        Language("sv", "Schwedisch"),
        Language("da", "Dänisch"),
        Language("no", "Norwegisch"),
        Language("fi", "Finnisch"),
        Language("et", "Estnisch"),
        Language("lv", "Lettisch"),
        Language("lt", "Litauisch"),
        Language("he", "Hebräisch"),
        Language("fa", "Persisch"),
        Language("ku", "Kurdisch"),
        Language("hi", "Hindi"),
        Language("ur", "Urdu"),
        Language("th", "Thailändisch"),
        Language("vi", "Vietnamesisch"),
        Language("id", "Indonesisch"),
        Language("ja", "Japanisch"),
        Language("ko", "Koreanisch"),
        Language("zh", "Chinesisch"),
    )

    private val byCode = all.associateBy { it.code }

    /** Dreibuchstabige Kennungen (ISO 639-2, wie in MKV/TS) auf zwei Buchstaben. */
    private val THREE_LETTER: Map<String, String> = all.associate { language ->
        runCatching { java.util.Locale(language.code).isO3Language }.getOrDefault("") to language.code
    } + mapOf(
        // Bibliografische Varianten, die Locale nicht kennt.
        "ger" to "de", "fre" to "fr", "dut" to "nl", "gre" to "el", "cze" to "cs", "slo" to "sk",
        "rum" to "ro", "alb" to "sq", "per" to "fa", "chi" to "zh", "nob" to "no", "nno" to "no",
    )

    /** Veraltete Zwei-Buchstaben-Kennungen (ältere Java-Versionen, manche Dateien). */
    private val LEGACY = mapOf("in" to "id", "iw" to "he", "ji" to "yi")

    /** "ger", "deu", "de-DE", "DE", "ms-ind" → "de" bzw. "id"; null, wenn unbekannt. */
    fun normalize(code: String?): String? {
        val parts = code?.trim()?.lowercase()?.replace('_', '-')?.split('-') ?: return null
        // Media3 schreibt Einzelsprachen als „Makrosprache-Einzelsprache“: „ms-ind“ ist Indonesisch (nicht Malaiisch),
        // „no-nob“ Norwegisch. Die Einzelsprache zählt.
        parts.getOrNull(1)?.takeIf { it.length == 3 }?.let { THREE_LETTER[it] }?.let { return it }
        val raw = parts.first().takeIf { it.isNotEmpty() }?.let { LEGACY[it] ?: it } ?: return null
        return when (raw.length) {
            2 -> raw.takeIf { it in byCode }
            3 -> THREE_LETTER[raw]
            else -> null
        }
    }

    fun of(code: String?): Language? = normalize(code)?.let(byCode::get)

    fun label(code: String?): String = of(code)?.label ?: code.orEmpty()

    private val HINTS = mapOf(
        "de" to setOf("der", "die", "das", "und", "nicht", "ist", "ich", "du", "sie", "wir", "ein", "eine", "zu", "mit", "auf", "was", "ja", "nein", "auch", "noch", "aber", "doch", "mir", "dich", "hast", "bin"),
        "en" to setOf("the", "and", "is", "you", "not", "it", "that", "to", "of", "what", "are", "was", "this", "have", "do", "don't", "i'm", "we", "my", "your", "be", "with", "just", "no", "yes", "can"),
        "fr" to setOf("le", "la", "les", "et", "est", "je", "tu", "vous", "pas", "une", "un", "que", "qui", "c'est", "nous", "avec", "pour", "mais"),
        "es" to setOf("el", "los", "las", "y", "es", "yo", "tú", "usted", "no", "una", "que", "qué", "con", "para", "pero", "está", "por"),
        "it" to setOf("il", "lo", "gli", "e", "è", "io", "tu", "non", "una", "che", "con", "per", "ma", "sono", "questo"),
        "tr" to setOf("ve", "bir", "bu", "ne", "değil", "ben", "sen", "için", "çok", "var", "yok", "mi", "da", "de"),
    )

    /**
     * Sprache eines Untertitels erraten, wenn die Spur keine nennt (häufig bei Live-TV: „Spur 1“).
     * Zählt typische kurze Wörter; null, wenn der Text zu kurz oder nicht eindeutig ist.
     */
    fun guess(texts: List<String>): String? {
        val words = texts.joinToString(" ").lowercase()
            .split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.size < 3) return null
        val scores = HINTS.mapValues { (_, hints) -> words.count { it in hints } }
        val best = scores.maxByOrNull { it.value } ?: return null
        val second = scores.values.sortedDescending().getOrElse(1) { 0 }
        return best.key.takeIf { best.value >= 2 && best.value > second }
    }
}
