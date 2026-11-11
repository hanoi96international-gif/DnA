package de.ayutv.data.parse

/**
 * Eigene Gruppen, Favoriten und Reihenfolgen merken sich Sender über ihren Schlüssel. Der hängt am Anbieter:
 * bei M3U an der Stream-Adresse (Token, Server), bei Xtream an der Stream-Nummer. Ändert der Anbieter das beim
 * nächsten Laden, fänden diese Einträge ihren Sender nicht mehr – die eigene Gruppe sähe leer aus.
 *
 * Nach dem Laden ordnet [remap] solche verwaisten Schlüssel dem neuen Sender zu, wenn er eindeutig wiederzuerkennen
 * ist: gleicher Name in gleicher Gruppe, sonst gleicher Name, sonst gleiche EPG-Kennung in gleicher Gruppe, sonst
 * gleicher Namenskern (ohne Länder-Vorsatz und Qualitätsangabe) mit gleicher Qualität in gleicher Gruppe.
 * Mehrdeutiges wird nicht geraten; nicht Gefundenes bleibt stehen (der Sender kann beim nächsten Mal zurückkommen).
 */
object KeyRescue {
    data class Ref(val key: String, val name: String, val epgId: String?, val group: String)

    /** Alter Schlüssel → neuer Schlüssel, für [old]-Einträge, deren Schlüssel es in [fresh] nicht mehr gibt. */
    fun remap(old: List<Ref>, fresh: List<Ref>): Map<String, String> {
        val freshKeys = fresh.mapTo(HashSet()) { it.key }
        val lost = old.filter { it.key !in freshKeys }
        if (lost.isEmpty()) return emptyMap()
        val byNameGroup = fresh.groupBy { name(it.name) to it.group }
        val byName = fresh.groupBy { name(it.name) }
        val byEpgGroup = fresh.filter { !it.epgId.isNullOrBlank() }.groupBy { it.epgId!!.trim().lowercase() to it.group }
        val byCoreGroup = fresh.groupBy { core(it) to it.group }
        val result = HashMap<String, String>()
        for (r in lost) {
            val match = byNameGroup[name(r.name) to r.group].unique()
                ?: byName[name(r.name)].unique()
                ?: r.epgId?.takeIf { it.isNotBlank() }?.let { byEpgGroup[it.trim().lowercase() to r.group].unique() }
                ?: core(r).takeIf { it.isNotEmpty() }?.let { byCoreGroup[it to r.group].unique() }
            if (match != null) result[r.key] = match.key
        }
        return result
    }

    private fun List<Ref>?.unique(): Ref? = this?.singleOrNull()

    private fun name(text: String) = text.trim().lowercase().replace(SPACES, " ")

    /** Namenskern plus Qualitätsstufe: „Sendername“ und „Sendername“ gleich, „Sendername“ nicht. */
    private fun core(r: Ref): String = ChannelVariants.key(r.name)?.let { it + "#" + ChannelVariants.rank(r.name) }.orEmpty()

    private val SPACES = Regex("""\s+""")
}
