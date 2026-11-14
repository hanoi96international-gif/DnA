package de.ayutv.player

/**
 * Doppelte Zeilen in einer Einblendung entfernen. Untertitel mit eigener Gestaltung (ASS/SSA, oft für fremdsprachige
 * Passagen) legen dieselbe Zeile mehrfach übereinander (Schatten, Rand, Leuchten); Dateien mit erzwungenen und vollen
 * Zeilen haben an solchen Stellen zwei gleichzeitige Einblendungen mit demselben Text. Übersetzt erschien jede Kopie –
 * dieselbe Übersetzung stand doppelt im Bild. Wechselrede („- Nein.“ / „- Nein.“) bleibt, wie sie ist.
 */
object SubtitleLines {
    /** Zeilen (auch mehrzeilige Einträge) ohne doppelte, in ursprünglicher Reihenfolge, mit Zeilenumbruch verbunden. */
    fun distinctLines(parts: List<String>): String {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (part in parts) for (raw in part.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!isDialogue(line) && !seen.add(normalize(line))) continue
            out += line
        }
        return out.joinToString("\n")
    }

    /** Fertiger Text (etwa eine Übersetzung, die die doppelte Vorlage doppelt wiedergibt). */
    fun dedupe(text: String): String = distinctLines(listOf(text)).ifEmpty { text }

    private fun isDialogue(line: String) = line.startsWith("-") || line.startsWith("–") || line.startsWith("—")

    private fun normalize(line: String) = line.lowercase().replace(Regex("<[^>]*>|\\{[^}]*\\}"), "").replace(Regex("\\s+"), " ").trim()
}
