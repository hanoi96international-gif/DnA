package de.ayutv.data.parse

import java.text.Normalizer

/**
 * Ordnet Sender der Playlist den Sendern eines XMLTV-EPGs zu. IPTV-Listen schmücken Namen und Kennungen
 * aus („Sendername“, „Sendername“, „Sendername“, „Sendername“), EPGs nennen denselben Sender schlicht
 * „Sendername“ mit Kennung „Sendername“. Beide Seiten werden deshalb auf einen Kern gebracht: ohne Länder-Vorsatz,
 * Qualitätsangaben, Klammern, Zeichen und Großschreibung; bekannte Zweitnamen (Sender = Sender) gelten
 * als gleich. Gibt es denselben Kern in mehreren Ländern, gewinnt das Land aus dem Vorsatz (sonst [country]).
 *
 * Reihenfolge: Kennung genau → Kennung ohne Groß/klein → Kern der Kennung → Kern des Namens →
 * Kern ohne Füllwörter ("TV", "Deutschland", "Channel").
 */
class EpgMatcher(private val country: String = Hid.s("IAs=")) {
    /** [uhd]: eigener UHD-Sender des EPGs („Sendername“ zeigt nur ausgewählte Spiele). */
    private class Guide(val id: String, val country: String?, val uhd: Boolean)

    private val exact = HashMap<String, String>()
    private val lower = HashMap<String, String>()
    private val strong = HashMap<String, MutableList<Guide>>()
    private val weak = HashMap<String, MutableList<Guide>>()
    /** Namenskerne je EPG-Sender (aus Kennung und Anzeigenamen), um eine Zuordnung zu prüfen. */
    private val keysOf = HashMap<String, MutableSet<String>>()

    /** Ein Sender des EPGs mit seinen Anzeigenamen. */
    fun addGuideChannel(id: String, names: Collection<String>) {
        if (id.isBlank() || id in exact) return
        exact[id] = id
        lower.putIfAbsent(id.lowercase(), id)
        val idKey = analyse(id, isId = true)
        val nameKeys = names.map { analyse(it, isId = false) }
        val guide = Guide(id, idKey.country ?: nameKeys.firstNotNullOfOrNull { it.country }, idKey.uhd || nameKeys.any { it.uhd })
        val keys = keysOf.getOrPut(id) { HashSet() }
        for (k in listOf(idKey) + nameKeys) {
            if (k.strong.isNotEmpty()) keys += k.strong
            if (k.weak.isNotEmpty()) keys += k.weak
            if (k.strong.isNotEmpty()) strong.getOrPut(k.strong) { ArrayList() }.addUnique(guide)
            if (k.weak.isNotEmpty()) weak.getOrPut(k.weak) { ArrayList() }.addUnique(guide)
        }
    }

    val isEmpty: Boolean get() = exact.isEmpty()

    /** Kennung des EPG-Senders für einen Sender der Playlist, oder null. */
    fun match(epgId: String?, name: String): String? {
        var id = epgId?.trim().orEmpty()
        if (id.isNotEmpty()) {
            val hit = exact[id] ?: lower[id.lowercase()]
            // Kennung des Anbieters, aber mit anderer Sendernummer als im Namen („… 3“ mit der Kennung von
            // „… 2“ – bei manchen Anbietern vertauscht): Der Name zählt, die Kennung nicht.
            if (hit != null && !conflicts(hit, name)) return hit
            if (hit != null || numberConflict(analyse(id, isId = true).strong, coreOf(name))) id = ""
        }
        val byName = analyse(name, isId = false)
        val byId = if (id.isNotEmpty()) analyse(id, isId = true) else null
        val wanted = byName.country ?: byId?.country ?: country
        val uhd = byName.uhd || byId?.uhd == true
        byId?.let { k -> pick(strong[k.strong], wanted, uhd)?.let { return it } }
        pick(strong[byName.strong], wanted, uhd)?.let { return it }
        pick(weak[byName.weak], wanted, uhd)?.let { return it }
        byId?.let { k -> pick(weak[k.weak], wanted, uhd)?.let { return it } }
        // Regionalfenster („Sendername“, „Sendername“): sonst das Hauptprogramm der Anstalt.
        byName.region?.let { pick(strong[it], wanted, uhd)?.let { id -> return id } }
        // Eigene Vorsätze des Anbieters („Sendername“, „Sendername“, „Sendername“): ohne sie noch einmal.
        val bare = name.replace(ANY_PREFIX, "")
        if (bare != name && bare.isNotBlank()) {
            val k = analyse(bare, isId = false)
            pick(strong[k.strong], wanted, uhd)?.let { return it }
            pick(weak[k.weak], wanted, uhd)?.let { return it }
            k.region?.let { pick(strong[it], wanted, uhd)?.let { id -> return id } }
        }
        return null
    }

    /**
     * Widerspricht die Sendernummer im Namen dem EPG-Sender [guideId]? Gleicher Sender, andere Nummer
     * („Sendername“ gegen „Sendername“, „Sendername“ gegen „… 2“). Technik-Angaben
     * (4K, 50 fps, 1080p) zählen nicht als Nummer. Unbekannte Kennung oder anderer Sender: kein Widerspruch.
     */
    fun conflicts(guideId: String, name: String): Boolean {
        val keys = keysOf[guideId] ?: return false
        val core = coreOf(name)
        if (core.isEmpty()) return false
        val family = keys.filter { familyOf(it) == familyOf(core) }
        return family.isNotEmpty() && family.none { numbersOf(it) == numbersOf(core) }
    }

    /** Führt die Kennung [epgId] des Anbieters zu einem EPG-Sender mit anderer Nummer als im Namen? */
    fun idConflicts(epgId: String?, name: String): Boolean {
        val id = epgId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val hit = exact[id] ?: lower[id.lowercase()] ?: return false
        return conflicts(hit, name)
    }

    /**
     * Passt der Sendername zum EPG-Sender [guideId]? Manche Anbieter geben einem Sender die Kennung eines
     * anderen („Sendername“ mit der Kennung von „Sendername“): Dann stimmt sein Programm nicht.
     * Unbekannte Sender und Namen ohne verwertbaren Kern gelten als passend.
     */
    fun fitsName(guideId: String, name: String): Boolean {
        val keys = keysOf[guideId] ?: return true
        val k = analyse(name, isId = false)
        if (k.strong.length < 3 || k.strong in keys || k.region in keys) return true
        val bare = analyse(name.replace(ANY_PREFIX, ""), isId = false)
        if (bare.strong in keys || bare.region in keys) return true
        // Nur Füllwörter verschieden („Sendername“ / „Bibel“): passt.
        return k.weak in keys || bare.weak in keys
    }

    /**
     * Unter gleich lautenden EPG-Sendern zuerst den mit passender UHD-Angabe: „Sendername“ bekommt
     * den eigenen UHD-Sender, „Sendername“ das normale Programm. Gibt es nur eine Fassung (bei „Sendername“
     * meist), gilt diese. Danach das gewünschte Land.
     */
    private fun pick(candidates: List<Guide>?, wanted: String, uhd: Boolean = false): String? {
        if (candidates.isNullOrEmpty()) return null
        val pool = candidates.filter { it.uhd == uhd }.ifEmpty { candidates }
        return (pool.firstOrNull { it.country == wanted }
            ?: pool.firstOrNull { it.country == null }
            ?: pool.first()).id
    }

    private fun MutableList<Guide>.addUnique(guide: Guide) {
        if (none { it.id == guide.id }) add(guide)
    }

    class Key(val strong: String, val weak: String, val country: String?, val region: String? = null, val uhd: Boolean = false)

    companion object {
        /** Kern des Namens ohne Vorsatz des Anbieters („Sendername“, „DE | …“). */
        fun coreOf(name: String): String {
            val bare = name.replace(ANY_PREFIX, "").ifBlank { name }
            return analyse(bare, isId = false).strong
        }

        /** Sender ohne Nummer („Sendername“ → „Sendername“). */
        fun familyOf(key: String): String = key.filterNot { it.isDigit() }

        /** Nummern im Kern („Sendername“ → „10“, „Sendername“ → „3“). */
        fun numbersOf(key: String): String = Regex("""\d+""").findAll(key).joinToString(",") { it.value.trimStart('0').ifEmpty { "0" } }

        /**
         * TV-Guide-Sender für die Auswahl „Programm zuordnen“ nach Nähe zum Sendernamen [name]: gleicher Sender mit
         * gleicher Nummer zuerst, andere Nummer desselben Senders dahinter. Jeder EPG-Sender einmal, unter seinem
         * passendsten Namen (mehrere Quellen nennen denselben Sender verschieden).
         */
        fun <T> rank(name: String, items: List<T>, id: (T) -> String, label: (T) -> String): List<T> {
            val wanted = coreOf(name)
            return items.map { item ->
                val key = coreOf(label(item))
                item to editDistance(wanted, key) + if (numberConflict(wanted, key)) 3 else 0
            }
                .groupBy { id(it.first) }.values.map { list -> list.minBy { it.second } }
                .sortedWith(compareBy<Pair<T, Int>>({ it.second }, { label(it.first) }))
                .map { it.first }
        }

        /** Gleicher Sender, andere Nummer (beide Kerne bekannt)? */
        fun numberConflict(a: String, b: String): Boolean =
            a.isNotEmpty() && b.isNotEmpty() && familyOf(a).length >= 3 && familyOf(a) == familyOf(b) && numbersOf(a) != numbersOf(b)

        /** Länderkürzel, die als Vorsatz ("DE: ", "|AT| ", "[CH] ") oder Endung („Sendername“) vorkommen. */
        private val COUNTRIES = mapOf(
            Hid.s("IAs=") to Hid.s("IAs="), Hid.s("Iwsz") to Hid.s("IAs="), Hid.s("IAs0") to Hid.s("IAs="), Hid.s("IwszQDU4bg==") to Hid.s("IAs="), Hid.s("IAs0WSc1fzaiZyA=") to Hid.s("IAs="), "d" to Hid.s("IAs="),
            Hid.s("JRo=") to Hid.s("JRo="), Hid.s("JRs1") to Hid.s("JRo="), Hid.s("JwY=") to Hid.s("JwY="), Hid.s("Nxso") to Hid.s("JwY="), Hid.s("MQU=") to Hid.s("MQU="), Hid.s("Iww=") to Hid.s("MQU="), Hid.s("IQA=") to Hid.s("MQU="),
            Hid.s("MR0=") to Hid.s("MR0="), Hid.s("MR0g") to Hid.s("MR0="), Hid.s("KgI=") to Hid.s("KgI="), Hid.s("Jgs=") to Hid.s("Jgs="), Hid.s("Ihw=") to Hid.s("Ihw="), Hid.s("LRo=") to Hid.s("LRo="), Hid.s("IR0=") to Hid.s("IR0="),
            Hid.s("NAI=") to Hid.s("NAI="), Hid.s("MBw=") to Hid.s("MBw="), Hid.s("NBo=") to Hid.s("NBo="), Hid.s("IAU=") to Hid.s("IAU="), Hid.s("Nws=") to Hid.s("Nws="), Hid.s("KgE=") to Hid.s("KgE="), Hid.s("Igc=") to Hid.s("Igc="),
            Hid.s("JxQ=") to Hid.s("JxQ="), Hid.s("LBs=") to Hid.s("LBs="), Hid.s("NgE=") to Hid.s("NgE="), Hid.s("Ixw=") to Hid.s("Ixw="), Hid.s("LBw=") to Hid.s("LBw="), Hid.s("Nh0=") to Hid.s("Nh0="), Hid.s("Nhs=") to Hid.s("Nhs="),
            Hid.s("JRw=") to Hid.s("JRw="), Hid.s("JQI=") to Hid.s("JQI="), Hid.s("IRY=") to Hid.s("IRY="), Hid.s("PRs=") to Hid.s("IRY="),
        )

        /** Angaben zu Bild, Ton und Technik, die nichts über den Sender sagen. */
        private val NOISE = setOf(
            Hid.s("LAo="), Hid.s("IgYl"), Hid.s("MQYl"), Hid.s("Nwo="), Hid.s("LB8="), Hid.s("KB8="), "4k", "8k", Hid.s("LAoz"), Hid.s("LAozHGQ="), Hid.s("IBg="), Hid.s("LAs3Tg=="), "h265", "h264", "x265",
            "1080p", "1080i", "720p", "2160p", "3840p", "576i", Hid.s("cV4nXSc="), Hid.s("cl4nXSc="), Hid.s("dlsnXSc="), Hid.s("Ih4y"), Hid.s("Ng82"), Hid.s("Jg8iRiEm"), Hid.s("JQI1"),
            Hid.s("KRstWT0="), Hid.s("KRstWT03Yj6qZg=="), Hid.s("IAEtTy0="), Hid.s("JRosQic="), Hid.s("JQ1y"), Hid.s("IAo="), Hid.s("Mgcx"), Hid.s("KAc3SA=="), Hid.s("IgskSQ=="), Hid.s("IwsuTzg5dDGmbQ=="),
            Hid.s("LAoxQSEl"), Hid.s("KxwoSg=="), Hid.s("KxwoSj04djY="), Hid.s("KAE2"), Hid.s("LAcmRQ=="), Hid.s("MAsyWQ=="), Hid.s("Kgs2"), Hid.s("Kgs0"), Hid.s("KwAtVA=="), Hid.s("IRgkQyA="),
        )

        /** Empfangsweg und Herkunft des Streams hinter dem Namen: sagt nichts über den Sender. */
        private val RECEPTION = setOf(
            Hid.s("Nw81"), Hid.s("Mwsj"), Hid.s("MwsjSTg="), Hid.s("MwsjXz0m"), Hid.s("IBgj"), Hid.s("IBgjXg=="), Hid.s("IBgjXmY="), Hid.s("IBgjTg=="), Hid.s("IBgjWQ=="), Hid.s("IBgjWWY="), Hid.s("LR41Ww=="), Hid.s("Kxo1"), Hid.s("Jwov"), Hid.s("NxozSDU7"), Hid.s("Nxwi"),
        )

        /** Füllwörter, ohne die ein Name meist noch eindeutig ist. Nur für den zweiten, schwächeren Abgleich. */
        private val FILLER = setOf(Hid.s("MBg="), Hid.s("MAstSCI/ZDOsZw=="), Hid.s("JwYgQzozew=="), Hid.s("IAs0WSc1fzaiZyA="), Hid.s("IwszQDU4bg=="), Hid.s("IwszQDU4"), Hid.s("NwsvSTEk"), Hid.s("IgszQyczfz+t"), Hid.s("IAs="))

        /** Anstalten mit Regionalprogrammen: „Sendername“ fällt auf „Sendername“ zurück. */
        private val REGIONAL = setOf(Hid.s("KQoz"), Hid.s("Mwoz"), Hid.s("Kgoz"), Hid.s("Jhw="), Hid.s("Nxkz"), Hid.s("LBw="), Hid.s("Ngwj"), Hid.s("Nxw="), Hid.s("KxwnHw=="), Hid.s("NxwnHA=="))

        /** Zweitnamen → ein Name (nach dem Zusammenziehen, ohne Leer- und Sonderzeichen). */
        private val ALIASES = mapOf(
            Hid.s("JRwl") to Hid.s("IA8ySCYlYz8="), Hid.s("IRwyWTE=") to Hid.s("IA8ySCYlYz8="), Hid.s("JRwlRTA=") to Hid.s("IA8ySCYlYz8="), Hid.s("JRwlTDgmfzs=") to Hid.s("JQIxRTU="), Hid.s("JhwgQSQ+dg==") to Hid.s("JQIxRTU="),
            Hid.s("NBwuGg==") to Hid.s("NBwuXj0zdT+t"), Hid.s("NBwuGjk3byI=") to Hid.s("NBwuXj0zdT+tZCUWOQ=="), Hid.s("NBwuXj0zdT+tZCUW") to Hid.s("NBwuXj0zdT+tZCUWOQ=="),
            Hid.s("NhotHw==") to Hid.s("NhotVyMzfg=="), Hid.s("NhotRD0=") to Hid.s("NhotVyMzfg=="), Hid.s("NhotXTgjZA==") to Hid.s("NhotWCQ="), Hid.s("NhotQz0iZTU=") to Hid.s("Kgc1Xzs="),
            Hid.s("Lw8jSDhn") to Hid.s("Lw8jSDgzfjSw"), Hid.s("Lw8jSDhnczWofA==") to Hid.s("Lw8jSDgzfjSwbSsFNA=="), Hid.s("Lw8jSDgzfjSwaigPMl49NWQ=") to Hid.s("Lw8jSDgzfjSwaigPMl49NWQ="),
            Hid.s("Nw81HA==") to Hid.s("Nw81HA=="), Hid.s("Nw81SD04ZA==") to Hid.s("Nw81HA=="), Hid.s("Kho3") to Hid.s("Kho3"), Hid.s("Klx1") to Hid.s("MwstWQ=="), Hid.s("MwstWTpkIw==") to Hid.s("MwstWQ=="),
            Hid.s("Klx1STs9Yg==") to Hid.s("Klx1STs9Yg=="), Hid.s("NxsxSCYkYzY=") to Hid.s("NxsxSCYkYzY="), Hid.s("MAEmSjsmey+w") to Hid.s("MAEmSjsmey+w"),
            Hid.s("IRszQicmeCi3") to Hid.s("IRszQicmeCi3OA=="), Hid.s("Nx4uXyAyfj2qfSUC") to Hid.s("Nx4uXyAyfj2qfSUCJ1gnJXU7r2U="),
            Hid.s("NwU4Tj04cjeieTYLLEQxJHI0") to Hid.s("NwU4Tj04cjeieTYLLEQxJHI="), Hid.s("NwU4Tj04cjeieTYLLEQxJHI0s2UxHXMZ") to Hid.s("NwU4Tj04cjeieTYLLEQxJHIqr3w3XHU="), Hid.s("NxkzXiY=") to Hid.s("Nxkz"),
            Hid.s("Jg84SCY/ZDmrbDcIJF86JXIypmc=") to Hid.s("Jhw="), Hid.s("JhwnSCY4ZD+rbCo=") to Hid.s("Jhw="), Hid.s("LBwnSCY4ZD+rbCo=") to Hid.s("LBw="), Hid.s("KQozSzEkeSmmYSEA") to Hid.s("KQoz"),
            Hid.s("KgozSzEkeSmmYSEA") to Hid.s("Kgoz"), Hid.s("MwozSzEkeSmmYSEA") to Hid.s("Mwoz"), Hid.s("NxkzSzEkeSmmYSEA") to Hid.s("Nxkz"), Hid.s("NgwjSzEkeSmmYSEA") to Hid.s("Ngwj"),
            Hid.s("IQcvXjIzZC6qfyUC") to Hid.s("KwAk"), Hid.s("JRwlQjoz") to Hid.s("KwAk"), Hid.s("PgonQzE5") to Hid.s("PgonQzE5"), Hid.s("PgonRDoweA==") to Hid.s("PgonRDoweA=="),
            Hid.s("MAstSGE=") to Hid.s("MAstSGE="), Hid.s("IAMgVQ==") to Hid.s("IAMgVQ=="), Hid.s("Nwc5VQ==") to Hid.s("Nwc5VQ=="), Hid.s("LwcqTA==") to Hid.s("LwcqTA=="), Hid.s("LwcqTCI5eTuxbTEAJVcwMA==") to Hid.s("LwcqTA=="),
            Hid.s("KxwnSD04ZA==") to Hid.s("KxwnHA=="), Hid.s("KxwnVyMzfg==") to Hid.s("KxwnHw=="), Hid.s("KxwnRD0/") to Hid.s("KxwnHg=="),
            Hid.s("KgciRg==") to Hid.s("KgciRjE6eD6mZio="), Hid.s("NxwnSD04ZA==") to Hid.s("NxwnHA=="), Hid.s("NxwnVyMzfg==") to Hid.s("NxwnHw=="), Hid.s("JQAoVTEmey+w") to Hid.s("JQAoVTEmey+w"),
            Hid.s("dV01RSciZT+mfTEAKFsxJGQ7rw==") to Hid.s("dV01RSciZT+mfQ=="), Hid.s("MQYlHDYv") to Hid.s("MQYlHA=="), Hid.s("MQYlHDYvfz4=") to Hid.s("MQYlHA=="), Hid.s("NwU4TCA6djS3YC8=") to Hid.s("NwU4TCA6djS3YCc="),
            Hid.s("KQ8mSDoidimzZjYa") to Hid.s("KQ8mSDoidimzZjYa"), Hid.s("KQ8mSDoidi61ejQBM1k=") to Hid.s("KQ8mSDoidimzZjYa"), Hid.s("MAstSD85eimzZjYa") to Hid.s("KQ8mSDoidimzZjYa"),
            Hid.s("IA87Q2U0dig=") to Hid.s("IA87Q2U="), Hid.s("IA87Q2Y0dig=") to Hid.s("IA87Q2Y="), Hid.s("Nx4uXyAyfj2qfSUCcA==") to Hid.s("Nx4uXyAyfj2qfSUCcF04I2Q="),
            // Amazon: „Sendername“ (epgshare01 DE), „Sendername“ (CH), in Listen auch „Sendername“.
            Hid.s("NBwoQDEgfj6mZg==") to Hid.s("NBwoQDE="), Hid.s("JQMgVzs4ZyiqZCE=") to Hid.s("NBwoQDE="), Hid.s("JQMgVzs4ZyiqZCEYKEkxOQ==") to Hid.s("NBwoQDE="), Hid.s("NBwoQDEgfj6mZigHN0g=") to Hid.s("NBwoQDE="),
            Hid.s("NBwoQDEgfj6mZjceLl8g") to Hid.s("NBwoQDE="), Hid.s("JQMgVzs4ZyiqZCEYKEkxOXsztWw=") to Hid.s("NBwoQDE="),
            // Aus Kurzberichten (01.11.2026): Schreibweisen der Anbieter gegen die Namen im TV-Guide.
            Hid.s("Iwsu") to Hid.s("IwsuWTE6ciyqei0BLw=="), Hid.s("LB0kH2A=") to Hid.s("LB0k"), Hid.s("LAsyXj0ldDKmezYbL0kyI3kx") to Hid.s("LBw="), Hid.s("NwEvVDUueQ==") to Hid.s("JRYv"),
            Hid.s("JxwoQDE/eSymejAHJkwgP3g0") to Hid.s("JxwoQDEmey+wYCoYJF4gP3A7t2ArAA=="), Hid.s("JxszRDslfi66aiwPL0MxOmc1tGw2CyVPLSVnM6ZuIQI=") to Hid.s("JxszRDslfi66aiwPL0MxOg=="),
            Hid.s("LAEsSDM3ZS6mZzAY") to Hid.s("LAEsSDU4cz2ieyALL1ki"), Hid.s("LAEsSDM3ZT6mZzAY") to Hid.s("LAEsSDU4cz2ieyALL1ki"), Hid.s("IQsvWTEkYzuqZyk=") to Hid.s("IQsvWTEkYzuqZykLL1k="),
            // Sender heißt in manchen TV-Guides „Sendername“ (gleicher Sender von Discovery).
            Hid.s("LAk1Ww==") to Hid.s("LAEsSDU4cz2ieyALL1ki"),
        )

        private val BRACKETS = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}""")
        private val PLUS_SHIFT = Regex("""\+\s*(\d{1,2})\b""")
        private val HD_PLUS = Regex(Hid.s("GAwpSQglPQbo"))
        private val LEADING_COUNTRY = Regex(Hid.s("GjUdXigKTAfpIR8PbFcJLSZ28HQ4CSRfOTd5I79tIRs1Xjc+ezutbW1GfhcIJT0BvzMYM6O72rSD2AG+pu7jz8zTSnG/VTdFGgC21oS4Q50ZRWhxJ3w="))
        private val LEADING_COUNTRY_SPACE = Regex(Hid.s("GkYlSCgxcii/bSEbPUwgKnQyv3wvRx1ef34oZ59abQ=="))
        private val PLUS_NAME = Regex("""\+(?!\s*\d)""")
        private val ID_COUNTRY = Regex("""\.([a-z]{2,3})(?:@.*)?$""")
        private val ID_FEED = Regex("""@.*$""")
        private val TOKENS = Regex("""[^a-z0-9]+""")
        private val GLUED_NOISE = Regex(Hid.s("bFF7RTAqcTKndTEGJVFgPWtiqHUsCzdOKD4lbPYgYA=="))
        private val MARKS = Regex("""\p{M}+""")
        private val UHD_TOKENS = setOf(Hid.s("MQYl"), "4k", "2160p", "3840p", "8k")
        private val GLUED_UHD = Regex(Hid.s("bFF7WDwya26oIGA="))
        private val ANY_PREFIX = Regex("""^(?:\s*[^:|]{1,24}?\s*[:|]+\s*)+""")

        /** „24/7“ und „24-7“ (Dauersender) gehören nicht zum Namen. */
        private val ROUND_THE_CLOCK = Regex("""\b24\s*[/-]\s*7\b""")

        private val TIME_SHIFT = Regex(Hid.s("GB1rcX8KZHDrVSAVcAFmKz5y/DMYHWsFa2x/JrB9IBIyWSE4cz+tNm0yIwRrfih7mFUgQBwE"), RegexOption.IGNORE_CASE)

        /**
         * Zeitversatz im Namen („Sendername“, „Sendername“): Stunden und Name ohne die Angabe; null ohne
         * Versatz. Für Sender, die es im EPG nicht eigens gibt: Sie zeigen das Programm des Hauptsenders später.
         */
        fun timeShift(name: String): Pair<Int, String>? {
            val m = TIME_SHIFT.find(name) ?: return null
            val hours = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..48 } ?: return null
            val base = name.removeRange(m.range).trim()
            return if (base.isEmpty()) null else hours to base
        }

        /** Wie verschieden zwei Namenskerne sind (Bearbeitungsabstand); gleiche zuerst, leere zuletzt. */
        fun editDistance(a: String, b: String): Int {
            if (a.isEmpty() || b.isEmpty()) return 1_000
            if (a == b) return -1
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                cur.copyInto(prev)
            }
            return prev[b.length]
        }

        /** Name oder Kennung auf ihren Kern bringen (öffentlich für Tests und die Suche nach Sendern). */
        fun analyse(raw: String, isId: Boolean): Key {
            var text = Normalizer.normalize(raw, Normalizer.Form.NFKD).replace(MARKS, "").lowercase()
                .replace("ß", Hid.s("Nx0="))
            var country: String? = null
            if (isId) {
                // „Sendername“, „Sendername“, „Sendername“
                ID_COUNTRY.find(text)?.let { m -> COUNTRIES[m.groupValues[1]]?.let { country = it; text = text.substring(0, m.range.first) } }
                text = text.replace(ID_FEED, "").replace('.', ' ').replace('_', ' ')
            }
            text = text.replace(ROUND_THE_CLOCK, " ")
            text = text.replace(HD_PLUS, Hid.s("ZAYlXTgjZHo=")).replace(PLUS_SHIFT, Hid.s("ZB4tWCdyJno=")).replace(PLUS_NAME, Hid.s("ZB4tWCd2"))
            // Länder-Vorsatz, auch mehrfach („Sendername“)
            repeat(3) {
                val m = LEADING_COUNTRY.find(text) ?: return@repeat
                val code = m.groupValues[1]
                if (code in COUNTRIES || code in NOISE) {
                    COUNTRIES[code]?.let { country = country ?: it }
                    text = text.substring(m.range.last + 1)
                }
            }
            LEADING_COUNTRY_SPACE.find(text)?.let { m ->
                val code = m.groupValues[1]
                if (text.length > m.range.last + 2) {
                    country = country ?: COUNTRIES[code]
                    text = text.substring(m.range.last + 1)
                }
            }
            // Land in Klammern am Ende: „Sendername“
            BRACKETS.findAll(text).forEach { m -> COUNTRIES[m.value.trim('[', ']', '(', ')', '{', '}', ' ').lowercase()]?.let { country = country ?: it } }
            text = text.replace(BRACKETS, " ")
            val allTokens = text.split(TOKENS).filter { it.isNotEmpty() }
            // UHD-Fassung („Sendername“, „Sendername“, „Sendername“), auch angehängt („Sendername“).
            val uhd = allTokens.any { it in UHD_TOKENS || (it.length > 3 && GLUED_UHD.containsMatchIn(it) && !it.startsWith(Hid.s("MQYl"))) }
            // Empfangsweg („SAT“, „WEB“, „DVB-C“): nur hinter dem Namen, sonst wäre „Sendername“ weg.
            val tokens = allTokens.filterIndexed { i, t -> t !in NOISE && !(i > 0 && (t == Hid.s("Iwsu") || t in RECEPTION)) }.toMutableList()
            // "DE" am Ende („Sendername“), aber nicht, wenn der Name nur daraus besteht
            if (tokens.size > 1 && tokens.last() in COUNTRIES && tokens.last().length >= 2) {
                country = country ?: COUNTRIES[tokens.last()]
                tokens.removeAt(tokens.lastIndex)
            }
            // Gastronomie-Fassung („Sendername“) zeigt dasselbe Programm wie der Sender selbst.
            if (tokens.size > 1 && tokens.last() == Hid.s("Jg8z") && tokens.first() == Hid.s("IA87Qw==")) tokens.removeAt(tokens.lastIndex)
            val strong = canonical(tokens.joinToString(""))
            val weakTokens = tokens.filter { it !in FILLER }
            val weak = canonical(if (weakTokens.isEmpty()) tokens.joinToString("") else weakTokens.joinToString(""))
            val first = tokens.firstOrNull()?.let { if (it == Hid.s("Kxwn") || it == Hid.s("Nxwn")) it + tokens.getOrNull(1).orEmpty() else it }
            val region = first?.takeIf { it in REGIONAL && canonical(it) != strong }
            return Key(strong, weak, country, region, uhd)
        }

        /** Umbenennungen bei Sender („Sendername“ heißt heute „Sendername“). */
        private val BRAND_RENAMES = listOf(
            Hid.s("NwU4TyE4cz+wZS0JIA==") to Hid.s("NwU4XiQ5ZS6hfCoKJF44P3A7"), Hid.s("NwU4XiQ5ZS6w") to Hid.s("NwU4XiQ5ZS4="),
            Hid.s("NwU4XiQ5ZS6sejALM18xP3Qy") to Hid.s("NwU4XiQ5ZS6ifDcaM0Q1"), Hid.s("NwU4TCElYyiqaA==") to Hid.s("NwU4XiQ5ZS6ifDcaM0Q1"),
            // „Sendername“, „Sendername“, „Sendername“: ohne „Sport“ im Namen.
            Hid.s("NwU4WTsm") to Hid.s("NwU4XiQ5ZS63ZjQ="), Hid.s("NwU4XSYzejOmeygLIEohMw==") to Hid.s("NwU4XiQ5ZS6zeyEDKEgmOnI7pHwh"), Hid.s("NwU4S2U=") to Hid.s("NwU4XiQ5ZS6lOA=="),
            Hid.s("NwU4WTE4eTOw") to Hid.s("NwU4XiQ5ZS63bCoAKF4="), Hid.s("NwU4Sjs6cQ==") to Hid.s("NwU4XiQ5ZS6kZigI"), Hid.s("NwU4QD0u") to Hid.s("NwU4XiQ5ZS6uYDw="),
        )
        /** „Sendername“ heißt im EPG „Sendername“. */
        private val MYTEAM = Regex(Hid.s("GgM4WTE3ei61ITceLl8gCnNx6i0="))

        private fun canonical(joined: String): String {
            var key = joined
            // („Sendername“ beginnt selbst mit „Sendername“: deshalb nur auf den alten Anfang prüfen.)
            for ((old, new) in BRAND_RENAMES) if (key.startsWith(old)) { key = new + key.removePrefix(old); break }
            MYTEAM.find(key)?.let { key = it.groupValues[1] + Hid.s("KRc1SDU7Yyw=") }
            // Angehängt statt getrennt: „Sendername“, „Sendername“
            while (key.length > 3) {
                val stripped = key.replace(GLUED_NOISE, "")
                if (stripped == key || stripped.length < 2) break
                key = stripped
            }
            return ALIASES[key] ?: key
        }
    }
}
