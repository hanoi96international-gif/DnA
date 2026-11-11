package de.ayutv.data.parse

/**
 * Gesprochene Suche: Zahlwörter werden zu Ziffern („Sendername“ → „Sendername“). Passt ein Sender oder
 * Titel genau, startet er direkt, statt nur in der Trefferliste zu stehen.
 */
object SpokenQuery {
    private val NUMBERS = mapOf(
        Hid.s("IQcvXg==") to "1", Hid.s("PhkkRA==") to "2", Hid.s("IBwkRA==") to "3", Hid.s("MgckXw==") to "4", Hid.s("Iq39QzI=") to "5", Hid.s("IhsvSw==") to "5",
        Hid.s("NwsiRSc=") to "6", Hid.s("NwckTzE4") to "7", Hid.s("JQ0pWQ==") to "8", Hid.s("Kgs0Qw==") to "9", Hid.s("PgspQw==") to "10",
        Hid.s("KwAk") to "1", Hid.s("MBku") to "2", Hid.s("MAYzSDE=") to "3", Hid.s("IgE0Xw==") to "4", Hid.s("Igc3SA==") to "5",
    )
    private val SPACES = Regex("""\s+""")
    private val NOT_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val YEAR = Regex("""\((?:19|20)\d\d\)|\b(?:19|20)\d\d\b""")
    private val PREFIX = Regex("""^(?:\s*[^:|]{1,12}?\s*[:|]+\s*)+""")

    fun normalize(spoken: String): String =
        spoken.trim().split(SPACES).joinToString(" ") { word -> NUMBERS[word.lowercase()] ?: word }

    /** Sender, dessen Namenskern genau dem Gesprochenen entspricht (in der besten Fassung), sonst null. */
    fun <T> channel(spoken: String, candidates: List<T>, name: (T) -> String): T? {
        val key = ChannelVariants.key(normalize(spoken)) ?: return null
        return candidates.filter { ChannelVariants.key(name(it)) == key }.minByOrNull { ChannelVariants.rank(name(it)) }
    }

    /** Film oder Serie mit genau diesem Titel (ohne Jahr, Länder-Vorsatz, Satzzeichen), sonst null. */
    fun <T> title(spoken: String, candidates: List<T>, name: (T) -> String): T? {
        val wanted = simplify(normalize(spoken)).takeIf { it.length >= 2 } ?: return null
        return candidates.firstOrNull { simplify(name(it)) == wanted }
    }

    private fun simplify(title: String): String =
        title.replace(PREFIX, "").replace(YEAR, " ").lowercase().replace(NOT_ALNUM, "")
}
