package de.ayutv.data.parse

/**
 * Titel einer Sendung für die Anzeige. Sport-EPGs nennen im Titel nur den Wettbewerb („Fußball: Bundesliga“),
 * die Paarung steht im Untertitel („Borussia Dortmund – Eintracht Frankfurt, Topspiel, 7. Spieltag“). Dann
 * kommt die Paarung nach vorn, damit sie auch in einzeiligen Listen zu sehen ist; sonst „Titel – Untertitel“.
 */
object ProgrammeText {
    // Gleiche Werte wie de.ayutv.data.db.ProgrammeFlags (ohne Datenbank-Abhängigkeit, auch für die Windows-App).
    private const val REPEAT = 1
    private const val LIVE = 2

    /** „A – B“, „A - B“, „A vs. B“, „A v B“, „A gegen B“. */
    /** „A – B“, „A - B“, „A vs. B“, „A VS B“ (Event-Sender schreiben groß), „A v B“, „A gegen B“, „A @ B“. */
    private val PAIRING = Regex(Hid.s("GD0dXn9+KGCY68T9o63Ae0om6zYtVDdefQo5Zb9/GEB+UXxpfmCkbCMLLwQoFj4GsCIYPQ=="))
    /** Sender, auf denen eine Paarung oder „Live“ für ein Spiel genügt, auch ohne Sport-Kategorie im TV-Guide. */
    private val SPORT_CHANNEL = Regex(Hid.s("bFEoBCcmeCi3dSAPO0MoJXIotXw3EixMMzN5LqJ1IQIkWzE4azaiZigPPU8xP3kmpno0AD1xNiR2NJ9rOAMuWTskYTOwYCsAPUwmM3k7v2sxACVIJzp+PaJ1GAwtRDM3Szi/eTYLLEQxJDc2pmgjGyRRNz52N7NgKwAyDTgzdj22bDgINAWXyWspsCAmDy1BKDB4NbdrJQIt"))
    private val NOISE = Regex(Hid.s("GB1rcXwFcjSnbDYHL0s7Cj4G7TYYHWsJ"))

    /**
     * Spiel aus dem Spielplan für Sport-Sendungen, deren TV-Guide keine Paarung nennt („Live: 2. Bundesliga“). Von der
     * App gesetzt ([de.ayutv.data.sport.MatchHints]); ohne Spielplan: null.
     */
    @Volatile var scheduleHint: ((title: String, subtitle: String?, description: String?, start: Long, stop: Long) -> String?)? = null

    /**
     * Titel für TV-Guide, Senderliste und Vorschau: wie [headline], bei Sport kommt die Partie nach vorn – aus dem
     * Untertitel, dem Anfang der Beschreibung oder dem Spielplan („Hannover 96 – Schalke 04 (Live: 2. Bundesliga)“).
     */
    fun display(title: String, subtitle: String?, description: String?, category: String?, start: Long, stop: Long): String {
        val base = headline(title, subtitle)
        if (pairing(title, subtitle) != null || !isSport(title, subtitle, category)) return base
        // Konferenz und Multiview überall gleich benannt („Bundesliga – Multiview (9. Spieltag)“), wie in der Sport-Übersicht.
        if (LIVE_FORMAT.containsMatchIn(base)) return eventTitle(title, subtitle, description)
        val match = descriptionPairing(description) ?: runCatching { scheduleHint?.invoke(title, subtitle, description, start, stop) }.getOrNull() ?: return base
        return "$match ($base)"
    }

    fun headline(title: String, subtitle: String?): String {
        val main = title.trim()
        val sub = subtitle?.trim().orEmpty()
        if (sub.isEmpty() || main.contains(sub, ignoreCase = true)) return main
        if (main.isEmpty()) return sub
        if (sub.contains(main, ignoreCase = true)) return sub
        val first = sub.substringBefore(',').trim()
        if (PAIRING.containsMatchIn(first)) {
            val rest = sub.substringAfter(',', "").trim()
            return "$first ($main" + (if (rest.isNotEmpty()) ", $rest" else "") + ")"
        }
        return "$main – $sub"
    }

    private val SPORT = Regex(
        Hid.s("bFEoBDIjP5lcdTcdaE81OnsmsHkrHDVRNiN5PqZ6KAcmTCg1fzuueS0BL150OnI7pHwhEiRYJjlnO+NlIQ8mWDEqdDWtbyEcJEM3Mzc2pmgjGyRRMDB1d7NmLw8tUTw3eT6haCgCPU81JXw/t2slAi1R") +
            Hid.s("IQcyRTs1fD+6dTALL0M9JWs8rHspCy0NZSpxNbFkIQJwUTk5YzWxejQBM1koO3gurG40EiVMJiJkJqFmPAsvUTM5ezy/ZyICPUM2N2s0q2U4HCBJJyZ4KLd1JgcgWTw6eDS/ei8HYVEiOXs2pnAmDy1BKA==") +
            Hid.s("NwAuQj8zZSa0eyEdNUE9OHAmtm8nEixANSp7P6pqLBogWTw6ci6qYjgyI28YCnUmqGYqCCRfMThtJqVmKxojTDg6aymsaicLM1EkJHI3qmw2Ti1INTFiP796IRwoSHQ3azaiKXsCKEo1KnszpHwhTnBR") +
            Hid.s("IRwkST0gfimqbDgeLkY1Oms2pmgjGyQNNyNnJp9rJxsxcTY=")
    )
    private val LIVE_WORD = Regex(Hid.s("GAxpYT0gciaPQBIrPUE9IHJzn2s4j/WVteCxu3aSpdvIUbXir7t3vKbf/Mzg52uqXJ3w"))
    /**
     * Übertragungen ohne Paarung, die trotzdem live sind: Konferenz (Sender, Sender: alle Spiele im Wechsel), Multiview.
     * Zusammenfassungen davon („Highlights der Konferenz“) fängt [NOT_LIVE] vorher ab.
     */
    private val LIVE_FORMAT = Regex(Hid.s("bFEoBHxpK3uzeyEdMkh9PXg0pWw2Cy9XKDtiNrdgaVE3RDEhaze2ZTAHbBI5N2M5q3UhBy9XMTpkKqpsKEYkBGt2ezO1bDgDIFk3PnM7uikoBzdIKDd7NqYpNx4oSDgzNzaqfyE="))
    /**
     * Sicher keine Live-Übertragung: Zusammenfassungen, Wiederholungen, Kurzfassungen („90in30“), Rückblicke, Dokus und
     * Magazine. Wortgrenzen beachten: „Tottenham Hotspur“ ist kein „pur“, „Stalker“ kein „Talk“.
     */
    private val NOT_LIVE = Regex(
        Hid.s("bFEoBDw/cDKvYCMGNV5rKm0vsGgpAyRDMjdkKbZnIxIz7ug1fDivYCcFPV8xeyg2qn8hEiJBNSVkM6B6ezIjUTc6dimwYCdOLEwgNX8mqGUlHTJEPzNleqdsNk42Qjc+ciaoZikeIEYgKg==") +
            Hid.s("JwE0QyAyeC2tdSkPJkwuP3kmsHktCy1ZNTFkd7B5NgcvWSghfj+nbDYGLkEhOHAmonwiFCRENz55L61uOBwkXTskYzukbDgyI0k7PWImn2slACBBLSVyBqF1GAw1TDg9Szi/ejAPLEAgP2Q5q3U=") +
            Hid.s("GAwxWCYKdSaweSscNV43PnYv42ooGyNRMSU3PKxlIxo9XjE4cz+zaDEdJFFtZn408Dk4MiNfMSF+NKdVJhIdTyYzZzWxfRgMPUY9MmR6oGUxDD1ZOyRnO7FoIAs9cTY0cim3KSsIHU8o") +
            Hid.s("GAxpRT04aygAtScFaF8hOHM/n2s4MiNLMTdjL7FsN1EdTygKdT6maisKJEkINGsGoWY2ByZEOjd7KfxVJhIdTycieCiqbDcyI1EINHIqnycYHWtxMCpLOKVmKAkkDQgyawaheSUcNQ0IMms=") +
            Hid.s("GAwsVCA+eCmfazgyI0ExMXI0p2wqUR1PKCJ/P+N7LR0kUTw/cDKvYCMGNQAnPngtv1UmHCRbPTNgBqF1Kgs1TyElYz+xejgyI1k7JjcGpyI4AyBZNz43NaUpMAYkDSMzcjE=")
    )
    /** Für eine Konferenz zählen „alle Spiele, alle Tore“ nicht als Zusammenfassung (die Konferenz zeigt sie live). */
    private val NOT_LIVE_SOFT = Regex(Hid.s("bFEoBDU6ez/jejQHJEExKks4omUoC2FZOyRyBqE="))

    /** Sportsendung (nach Kategorie oder Titel). */
    fun isSport(title: String, subtitle: String?, category: String?): Boolean =
        SPORT.containsMatchIn(category.orEmpty()) || SPORT.containsMatchIn(title) || SPORT.containsMatchIn(subtitle.orEmpty())

    /** Paarung „A – B“ im Untertitel oder Titel. */
    fun pairing(title: String, subtitle: String?): String? =
        listOfNotNull(subtitle, title).map { it.substringBefore(',').substringAfter(": ").trim() }
            .firstOrNull { PAIRING.containsMatchIn(it) && !it.contains(Hid.s("Fx4oSDgidj0=")) && plausible(it) }

    /**
     * Sehen beide Seiten wie Mannschaften oder Sportler aus? Aussortiert werden Serien und Shows mit Bindestrich im
     * Titel („Hausmeister Krause - Ordnung muss sein“, „Fergie - Rise of a Dynasty“), Rückblicke
     * („Hannover 96 - Hinrunde 25-26“), Vereinssender („Sendername“) und Wochenangaben („NFL ENDZN - Woche 18“).
     */
    private fun plausible(pairing: String): Boolean {
        val sides = Teams.ofPairing(pairing)
        if (sides.size != 2) return true
        return sides.none { side ->
            val words = side.split(' ').filter { it.isNotBlank() }
            NOT_A_SIDE.containsMatchIn(side) || words.size > 6 || LIVE_FORMAT.containsMatchIn(side) || side.lowercase() in COMPETITIONS ||
                words.count { w -> w.first().isLowerCase() && w.lowercase() !in PARTICLES } >= 2
        }
    }
    private val NOT_A_SIDE = Regex(Hid.s("bFEoBAp+YDWgYSESM1g6MnImsHktCy1ZNTFrPKxlIws9WTE/eyazaDYaPUgkCjll6lUmEh1PCDJsaL5SaUEccTAtJSfndRgMNVtwKks4q2AYGWtfIThzP59rODIjX5fqdDGxfCoKJHE2"))
    /** Kleingeschriebene Wörter, die in Vereinsnamen vorkommen („Stade de Reims“, „Atlético de Madrid“, „Brighton and Hove“). */
    private val PARTICLES = setOf(Hid.s("IAs="), Hid.s("IAst"), Hid.s("IA8="), Hid.s("IAE="), Hid.s("IAEy"), Hid.s("IA8y"), Hid.s("IAc="), Hid.s("KA8="), Hid.s("KAs="), Hid.s("KAsy"), Hid.s("Mg8v"), Hid.s("MgEv"), Hid.s("IAsz"), Hid.s("IAsv"), Hid.s("JQM="), Hid.s("JQA="), Hid.s("JQI="), Hid.s("IQI="), Hid.s("JQAl"), "y", "e", Hid.s("MQAl"))

    /**
     * Paarung am Anfang der Beschreibung. Sender nennt mehrere Spiele gleichzeitig nur „Live: DFB-Pokal“; welches
     * Spiel es ist, steht im ersten Satz („Hertha BSC – 1. FC Köln. Die Berliner …“).
     */
    fun descriptionPairing(description: String?): String? {
        val first = description?.trim()?.split(SENTENCE_END, limit = 2)?.firstOrNull()?.substringAfter(": ")?.trim() ?: return null
        val pairing = first.substringBefore(',').trim()
        if (pairing.length > 80 || !PAIRING.containsMatchIn(pairing) || pairing.contains(Hid.s("Fx4oSDgidj0="))) return null
        // Nur kurze Vereinsnamen, kein Satz wie „Der FC tritt heute gegen Dortmund an“
        val sides = Teams.ofPairing(pairing)
        return pairing.takeIf { sides.size == 2 && sides.all { it.split(' ').size <= 4 } }
    }
    /** Satzende, aber nicht nach „1.“, „FC.“, „vs.“, „St.“ */
    private val SENTENCE_END = Regex(Hid.s("bFF9DAg0YSm/VSYYPXE2BWMmmDlpVwAADpWTmVXK2DNocXoKZCafZw=="))

    /** Paarung aus Titel, Untertitel oder (zuletzt) dem Anfang der Beschreibung. */
    fun pairing(title: String, subtitle: String?, description: String?): String? =
        pairing(title, subtitle) ?: descriptionPairing(description)

    /** Für Sport-Listen: Steht die Paarung nur in der Beschreibung, kommt sie nach vorn („A – B (Live: DFB-Pokal)“). */
    fun sportHeadline(title: String, subtitle: String?, description: String?): String {
        val main = headline(title, subtitle)
        if (pairing(title, subtitle) != null) return main
        val fromDescription = descriptionPairing(description) ?: return main
        return "$fromDescription ($main)"
    }

    /**
     * Läuft live: ausdrücklich so markiert, „Live“ im Titel, oder ein Spiel mit Paarung, das keine
     * Wiederholung, Zusammenfassung oder Vorberichterstattung ist.
     */
    fun isLive(title: String, subtitle: String?, category: String?, flags: Int, sportChannel: Boolean = false): Boolean {
        if (flags and REPEAT != 0) return false
        if (flags and LIVE != 0) return true
        val text = title + " " + subtitle.orEmpty()
        if (NOT_LIVE.containsMatchIn(text)) return false
        val conference = LIVE_FORMAT.containsMatchIn(text)
        // Sender ordnet alles als „Magazin“/„Fußballmagazin“ ein, auch die Konferenz und Live-Spiele: Bei Konferenz oder
        // Paarung zählt die Kategorie deshalb nicht.
        if (NOT_LIVE.containsMatchIn(category.orEmpty()) && !conference && pairing(title, subtitle) == null) return false
        if (!conference && NOT_LIVE_SOFT.containsMatchIn(text)) return false
        if (LIVE_WORD.containsMatchIn(text)) return true
        if ((sportChannel || isSport(title, subtitle, category)) && conference) return true
        return (sportChannel || isSport(title, subtitle, category)) && pairing(title, subtitle) != null
    }

    /** Wie [isLive], die Paarung darf auch am Anfang der Beschreibung stehen. */
    fun isLive(title: String, subtitle: String?, category: String?, flags: Int, sportChannel: Boolean, description: String?): Boolean =
        isLive(title, subtitle, category, flags, sportChannel) ||
            (flags and REPEAT == 0 && !NOT_LIVE.containsMatchIn(title + " " + subtitle.orEmpty()) && !NOT_LIVE_SOFT.containsMatchIn(title + " " + subtitle.orEmpty()) &&
                (sportChannel || isSport(title, subtitle, category)) && descriptionPairing(description) != null)

    /**
     * Dasselbe Spiel nur einmal: Gleiche Paarung (Schreibweise egal, „FC Bayern München“ = „Bayern“) innerhalb von zwei
     * Stunden wird zusammengelegt ([merge], z. B. weitere Sender), auch wenn die TV-Guides verschiedener Anbieter eine
     * andere Anfangszeit nennen. Später ist es eine Wiederholung (Sender kennzeichnet sie nicht) und entfällt.
     * [items] nach Beginn sortiert; [pairing] null = ohne Paarung (bleibt für sich).
     */
    fun <T> mergeSameMatch(items: List<T>, start: (T) -> Long, pairing: (T) -> String?, merge: (first: T, other: T) -> T): List<T> {
        val out = ArrayList<T>()
        val pairings = ArrayList<String?>()
        for (item in items) {
            val p = pairing(item)
            if (p == null) { out += item; pairings += null; continue }
            val same = out.indices.filter { j -> pairings[j]?.let { samePairing(it, p) } == true }
            val at = start(item)
            val now = same.firstOrNull { j -> kotlin.math.abs(at - start(out[j])) < REPLAY_AFTER_MS }
            when {
                now != null -> out[now] = merge(out[now], item)
                same.isNotEmpty() -> Unit // Wiederholung
                else -> { out += item; pairings += p }
            }
        }
        return out
    }
    private const val REPLAY_AFTER_MS = 2 * 3_600_000L

    /** Dieselbe Paarung? Je Seite ein gemeinsames Vereinswort („Mainz 05“ = „1. FSV Mainz 05“). */
    fun samePairing(a: String, b: String): Boolean {
        val x = Teams.ofPairing(a)
        val y = Teams.ofPairing(b)
        if (x.size != 2 || y.size != 2) return simple(a) == simple(b)
        fun same(p: String, q: String): Boolean {
            val kp = Teams.keywords(p)
            val kq = Teams.keywords(q)
            return if (kp.isNotEmpty() && kq.isNotEmpty()) kp.any { it in kq } else simple(p) == simple(q)
        }
        return same(x[0], y[0]) && same(x[1], y[1])
    }

    /**
     * Wettbewerb aus dem Titel, einheitlich ausgeschrieben: „Live: BL“ → „Bundesliga“, „PL: …“ → „Premier League“,
     * „Serie A 25/26: Lazio – Neapel“ → „Serie A“. Ohne Doppelpunkt der Titel ohne „Live“.
     */
    fun competition(title: String): String {
        val plain = title.replace(LIVE_PREFIX, "").trim()
        // Titel ist nur die Paarung („FV RAVENSBURG VS TSG BACKNANG“): kein Wettbewerb bekannt.
        if (": " !in plain && pairing(plain, null) != null) return ""
        val head = (if (": " in plain) plain.substringBefore(": ") else plain).replace(SEASON, "").trim()
        return expand(head).ifEmpty { plain }
    }

    /**
     * Einheitlicher Titel für die Sport-Übersicht: die Paarung („Mainz 05 – Bayern München“), sonst der Wettbewerb
     * ausgeschrieben, ohne „Live:“ („Live: BL“ → „Bundesliga“, „Live: 2. BL – Konferenz“ → „2. Bundesliga – Konferenz“).
     */
    fun eventTitle(title: String, subtitle: String?, description: String?, pairing: String? = null): String {
        (pairing ?: pairing(title, subtitle, description))?.let { p ->
            val sides = Teams.ofPairing(p)
            return if (sides.size == 2) "${niceCase(sides[0])} – ${niceCase(sides[1])}" else p
        }
        val plain = headline(title, subtitle).replace(LIVE_PREFIX, "").trim()
        // Konferenz und Multiview überall gleich: „Bundesliga – Konferenz (9. Spieltag)“, ob „BL: Multiview – 9. Spieltag“
        // (Sender) oder „Bundesliga Konferenz 26/27 – 9. Spieltag“ (Sender).
        LIVE_FORMAT.find(plain)?.let { format ->
            val kind = if (format.value.contains(Hid.s("KRstWT0="), ignoreCase = true)) Hid.s("CRstWT0gfj+0") else Hid.s("DwEvSzEkcjS5")
            val round = ROUND.find(plain)?.value
            val competition = expand(
                plain.substringBefore(": ").replace(LIVE_FORMAT, "").replace(SEASON, "").replace(Regex(Hid.s("bFEoBAg0Pz6qbDgdLkM6InY9sCR7EjJMOSVjO6R6aVE9SyYzfi6ibjdDflE5P2MutGYnBjIAaypzM6ZnNxogSid7KCbuIBgdawk=")), "")
                    .substringBefore(" – ").substringBefore(" - ").trim().trimEnd(',', '-', '–', ':').trim()
            ).takeIf { it.isNotEmpty() && !ROUND.containsMatchIn(it) }
            return listOfNotNull(competition, kind).joinToString(" – ") + (round?.let { " ($it)" } ?: "")
        }
        val head = plain.substringBefore(": ").substringBefore(" – ").substringBefore(" - ")
        val rest = plain.removePrefix(head)
        return (expand(head.replace(SEASON, "").trim()) + rest).trim().ifEmpty { plain }
    }

    /**
     * Sendername für Listen: Event-Sender tragen das ganze Spiel im Namen („NEXT | FV RAVENSBURG VS TSG BACKNANG | Sat 07
     * Nov 14:30 CET (DE) | 8K EXCLUSIVE | DE: LEAGUES FOOTBALL PPV 14“); das Spiel steht schon im Titel, also nur der
     * eigentliche Sender („DE: LEAGUES FOOTBALL PPV 14“).
     */
    fun channelLabel(name: String): String {
        val base = if (name.count { it == '|' } >= 2) name.substringAfterLast('|').trim().ifEmpty { name } else name
        return cleanChannelName(base)
    }

    /**
     * Sendername einheitlich (Sport-Guide, Listen): ohne Vorsatz des Anbieters („Sendername“, „DE:“, „4K:“), ohne Klammern
     * („(SAT)“, „(KABEL)“, „(LIVE-EVENT)“, „(720P)“) und Technik („HD“, „ᴿᴬᵂ“, „ᵁᴴᴰ ³⁸⁴⁰ᴾ“, „HEVC“), in normaler
     * Schreibung; nur 4K und RAW bleiben als Hinweis: „Sendername“ → „Sendername“,
     * „Sendername“ → „Sendername“. Gibt den Namen unverändert zurück, wenn danach nichts übrig bliebe.
     */
    fun cleanChannelName(name: String): String {
        val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
        val rank = ChannelVariants.rank(plain)
        val badge = when (rank) {
            2 -> "4K"
            1 -> Hid.s("Fi8W")
            else -> null
        }
        var core = plain.replace(BRACKETED, " ").replace(PROVIDER_PREFIX, "").replace(TECH_WORDS, " ")
            .replace(Regex("""\s+"""), " ").trim().trim('-', '|', ':', '·').trim()
        if (core.length < 2) return name
        core = niceCase(core).split(' ').joinToString(" ") { word -> ACRONYMS[word.lowercase()] ?: word }
        return if (badge != null) "$core · $badge" else core
    }
    private val BRACKETED = Regex("""\([^)]*\)|\[[^\]]*]""")
    private val PROVIDER_PREFIX = Regex("""^(?:\s*[^:|]{1,12}?\s*[:|]+\s*)+""")
    private val TECH_WORDS = Regex(
        Hid.s("bFEoBHxpK3uYVTQVDVAIMkpz6297BiVxf2lrL6ttOB0lUWA9a2KodTYPNlE8M2E5v2EYQH4fYg0jb551PFx3dmBjSiarbTZfcRIoMng2oXA4MiVWZ3ojJ596bjUxRAkqSz64OzkyMgcyJmQmr2AyC2wSMSByNLd1Jg8iRiEmaze2ZTAHaAVrd0wGs3IIEx1JCX8=")
    )
    /** Namen, die in Großbuchstaben richtig sind (sonst „Sendername“, „Sendername“). */
    private val ACRONYMS = mapOf(
        Hid.s("IA87Qw==") to Hid.s("AC8bYw=="), Hid.s("IR0xQw==") to Hid.s("AT0RYw=="), Hid.s("MQsnTA==") to Hid.s("ESsHbA=="), Hid.s("KRo3") to Hid.s("CToX"), Hid.s("Nhot") to Hid.s("FjoN"), Hid.s("Pgon") to Hid.s("HioH"), Hid.s("JRwl") to Hid.s("BTwF"),
        Hid.s("Kxwn") to Hid.s("CzwH"), Hid.s("Nxwn") to Hid.s("FzwH"), Hid.s("JwAv") to Hid.s("ByAP"), Hid.s("Jgwi") to Hid.s("BiwC"), Hid.s("Kggt") to Hid.s("CigN"), Hid.s("Kgwg") to Hid.s("CiwA"), Hid.s("KgYt") to Hid.s("CiYN"),
        Hid.s("LAwu") to Hid.s("DCwO"), Hid.s("JRYv") to Hid.s("BTYP"), Hid.s("MAA1") to Hid.s("ECAV"), Hid.s("JgsoQw==") to Hid.s("JgsIYw=="), Hid.s("IAMgVQ==") to Hid.s("ACMAdQ=="), Hid.s("Nwc5VQ==") to Hid.s("Nwc5VQ=="), Hid.s("Kho3") to Hid.s("KkM1Ww=="),
        Hid.s("NwU4") to Hid.s("FwU4"), Hid.s("LwcqTA==") to Hid.s("DwcKbA=="), Hid.s("JRo3") to Hid.s("BToX"), Hid.s("NBstXg==") to Hid.s("FBstXg=="), Hid.s("KA8uQTVn") to Hid.s("CC8OYRVn"), Hid.s("Nx4uXyBn") to Hid.s("Fz4OfwBn"), Hid.s("Kgc1Xzs=") to Hid.s("CicVfxs="),
    )

    /** „FV RAVENSBURG“ → „FV Ravensburg“ (Event-Sender schreiben alles groß); Kürzel bis 3 Buchstaben bleiben. */
    fun niceCase(name: String): String {
        val letters = name.filter { it.isLetter() }
        if (letters.length < 4 || letters.any { it.isLowerCase() }) return name
        return name.split(' ').joinToString(" ") { word ->
            if (word.count { it.isLetter() } <= 3 || ROMAN.matches(word)) word else word.lowercase().replaceFirstChar { it.titlecase() }
        }
    }
    private val ROMAN = Regex(Hid.s("GjUIewwLPH4="))
    private val ROUND = Regex(Hid.s("GApqcXoKZHCQeS0LLVk1MQ=="))

    private fun expand(head: String): String = COMPETITIONS[head.lowercase()] ?: head
    private val LIVE_PREFIX = Regex(Hid.s("bFEoBAoKZHDr+dv69XEnfD5l62UtGCRxJ3wtZb/o8Nagm/K3osEivM0SoJnst6PvIbj5j/WcfQpkcL9XGB1rSyF+1MW/ejdHI0w4Oksp6TMYHWs="))
    private val SEASON = Regex("""\b(20)?\d{2}/\d{2}\b""")
    private val COMPETITIONS = mapOf(
        Hid.s("JgI=") to Hid.s("BhsvSTElezOkaA=="), Hid.s("dUBhTzg=") to Hid.s("BhsvSTElezOkaA=="), Hid.s("JhstRA==") to Hid.s("BhsvSTElezOkaA=="), Hid.s("dkBhTzg=") to Hid.s("dkBhbyE4cz+wZS0JIA=="), Hid.s("dkBhTyE6fg==") to Hid.s("dkBhbyE4cz+wZS0JIA=="),
        Hid.s("d0BhTzg=") to Hid.s("d0BhYT0xdg=="), Hid.s("NAI=") to Hid.s("FBwkQD0zZXqPbCUJNEg="), Hid.s("IR4t") to Hid.s("FBwkQD0zZXqPbCUJNEg="), Hid.s("JwI=") to Hid.s("BwYgQCQ/eDSwKQgLIEohMw=="), Hid.s("MQ0t") to Hid.s("BwYgQCQ/eDSwKQgLIEohMw=="),
        Hid.s("MQsnTHQ1ew==") to Hid.s("BwYgQCQ/eDSwKQgLIEohMw=="), Hid.s("IQI=") to Hid.s("ARszQiQ3NxamaCMbJA=="), Hid.s("MQst") to Hid.s("ARszQiQ3NxamaCMbJA=="), Hid.s("IQ0t") to Hid.s("BwEvSzEkcjSgbGQiJEwzI3I="),
        Hid.s("MQsiQQ==") to Hid.s("BwEvSzEkcjSgbGQiJEwzI3I="), Hid.s("IAgj") to Hid.s("ACgDAAQ5fDuv"), Hid.s("IQgt") to Hid.s("ASgNDRc+djezYCsAMkU9Jg=="), Hid.s("MwM=") to Hid.s("EwstWTkzfim3bDYdIkU1MGM="), Hid.s("IQM=") to Hid.s("ARszQiQ3ej+qejALM143PnY8tw=="),
    )

    /** Sportsender nach Namen. */
    fun isSportChannel(name: String): Boolean = SPORT_CHANNEL.containsMatchIn(name)

    /**
     * Für die Sport-Übersicht: ein Spiel oder eine Live-Übertragung (keine Magazine, Talks, Wiederholungen).
     * Auf einem Sportsender ([sportChannel]) zählt jede Paarung oder „Live“, auch wenn der TV-Guide keine
     * Sport-Kategorie nennt.
     */
    fun isSportEvent(title: String, subtitle: String?, category: String?, flags: Int, sportChannel: Boolean = false): Boolean =
        (sportChannel || isSport(title, subtitle, category)) && isLive(title, subtitle, category, flags, sportChannel)

    /**
     * Woran man dieselbe Übertragung auf verschiedenen Sendern erkennt. Mit Paarung zählt nur die Paarung
     * (Schreibweise egal). Ohne Paarung zählen Titel und Beschreibung; ohne brauchbare Beschreibung bleibt jeder
     * Sender für sich, denn Sender nennt parallele Spiele oft nur „Live: DFB-Pokal“.
     */
    fun sportKey(title: String, subtitle: String?, description: String?, channelEpgId: String): String {
        pairing(title, subtitle, description)?.let { return "p:" + Teams.ofPairing(it).ifEmpty { listOf(it) }.joinToString("|") { side -> simple(side) } }
        val desc = description(description)?.takeUnless { PLACEHOLDER.containsMatchIn(it) }?.let { simple(it).take(120) }
        return "h:" + simple(headline(title, subtitle)) + "|" + (desc?.ifEmpty { null } ?: "@$channelEpgId")
    }
    /**
     * Schlüssel, unter dem Sport-Sendungen zu einem Eintrag zusammengefasst werden. Mit Paarung („Hannover – Dresden“)
     * über alle Sender: dasselbe Spiel. Ohne Paarung („Live: 2. BL“) nur Fassungen desselben Senders ([channelGroup],
     * z. B. „Sendername“ in HD und 4K): Sonst wurden verschiedene Spiele mit gleichem Allgemeintext auf
     * verschiedenen Sendern zu einem Eintrag, und Spiele fehlten in der Liste.
     */
    fun sportGroupKey(title: String, subtitle: String?, description: String?, channelEpgId: String, channelGroup: String): String {
        val key = sportKey(title, subtitle, description, channelEpgId)
        return if (key.startsWith("p:")) key else "$key|$channelGroup"
    }

    private val PLACEHOLDER = Regex(Hid.s("bFEoBDI5ez3rbCoSNQR0P3l6qCGH0j1YfSRtP79iIQcvSHR+YD+qfSEcJEN0fygzrW8rHCxMID94NKZn"))
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private fun simple(text: String) = text.lowercase().replace(NON_WORD, "")

    /** Beschreibung ohne angehängtes „(Senderinfo).“ der Programmzeitschriften. */
    fun description(text: String?): String? = text?.replace(NOISE, "")?.trim()?.takeIf { it.isNotEmpty() }
}
