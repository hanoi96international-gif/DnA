package de.ayutv.data.parse

/**
 * Reiter „Apps“: Verknüpfungen zu Streaming-Apps. DnA-TV spielt deren Inhalte nicht selbst ab (Kopierschutz und
 * Nutzungsbedingungen der Anbieter), sondern startet die offizielle App: auf dem Fire TV die Fire-TV-Fassung, auf dem
 * Handy die Handy-Fassung, unter Windows die Webseite im Standard-Browser. Mit Zurück kommt man wieder zu DnA-TV.
 */
object StreamingApps {
    /**
     * [tvPackages]: Paketnamen auf Fire TV / Android TV (bevorzugt), [phonePackages]: auf dem Handy; die erste
     * installierte wird gestartet. [web]: Adresse für Windows. [color]: Kachelfarbe (ARGB).
     */
    data class App(
        val id: String,
        val name: String,
        val tvPackages: List<String>,
        val phonePackages: List<String>,
        val web: String,
        val color: Long,
        /**
         * Namen, unter denen die App auf dem Gerät heißt (klein geschrieben). Ist keiner der Paketnamen installiert
         * (Hersteller und Fire OS nutzen teils eigene), wird die App über ihren Namen unter allen Apps gesucht.
         */
        val labels: List<String> = listOf(name.lowercase()),
    )

    val ALL = listOf(
        App(Hid.s("Kgs1Szg/bw=="), Hid.s("Cgs1Szg/bw=="), listOf(Hid.s("JwEsAzozYzyvYDxAL0Q6PHY=")), listOf(Hid.s("JwEsAzozYzyvYDxALEgwP3Y5r2AhADU=")), Hid.s("LBo1XSdsOHW0fjNAL0ggMHszuycnASwCNiR4LbBs"), 0xFFE50914),
        App(
            Hid.s("PQE0WSE0cg=="), Hid.s("HQE0eSE0cg=="),
            listOf(Hid.s("JwEsAzU7diCsZ2oIKF8xImF0umYxGjRPMQ=="), Hid.s("JwEsAzM5eD2vbGoPL0kmOX4+7XArGzVYNjM5LrU="), Hid.s("JwEsAzM5eD2vbGoPL0kmOX4+7XArGzVYNjM5PaxmIwIkWSI=")),
            listOf(Hid.s("JwEsAzM5eD2vbGoPL0kmOX4+7XArGzVYNjM=")),
            Hid.s("LBo1XSdsOHW0fjNAOEIhImI4picnASw="), 0xFFFF0000,
        ),
        App(
            Hid.s("NBwoQDE="), Hid.s("FBwoQDF2QTOnbCs="),
            listOf(Hid.s("JwEsAzU7diCsZ2oPN0Iw"), Hid.s("JwEsAzU7diCsZ2oIKF8xNHYu"), Hid.s("JwEsAzU7diCsZ2oPLEwuOXksqm0hAW9BPSB+NKR7KwEs")),
            listOf(Hid.s("JwEsAzU7diCsZ2oPN0IweGMyqnsgHiBfIC90NqpsKho=")),
            Hid.s("LBo1XSdsOHW0fjNAMV89O3Isqm0hAW9OOzs="), 0xFF1A98FF,
            labels = listOf(Hid.s("NBwoQDF2YTOnbCs="), Hid.s("JQMgVzs4NyqxYCkLYVs9MnI1"), Hid.s("NBwoQDEgfj6mZg==")),
        ),
        App(Hid.s("Nx4uWT0wbg=="), Hid.s("Fx4uWT0wbg=="), listOf(Hid.s("JwEsAycmeC6qbz1ANVt6N3k+sWYtCg==")), listOf(Hid.s("JwEsAycmeC6qbz1ALFgnP3Q=")), Hid.s("LBo1XSdsOHWseSEAb14kOWMzpXBqDS5A"), 0xFF1DB954),
    )

    /**
     * Passt der App-Name [label] (wie auf dem Gerät angezeigt) zu [app]? 0 = genau, 1 = beginnt damit, null = nein.
     * „Sendername“ oder „Sendername“ zählen für Streaming-App nicht, „Sendername“ schon für Streaming-App.
     */
    fun labelMatch(app: App, label: String): Int? {
        val l = label.trim().lowercase()
        if (app.labels.any { it == l }) return 0
        val others = listOf(Hid.s("LwclXg=="), Hid.s("KRsyRDc="), Hid.s("KRsyRD8="), Hid.s("Nxo0ST05"), Hid.s("JxwkTCAz"), Hid.s("IAczSDci"), Hid.s("NAYuWTsl"))
        // „Sendername“ ist ein eigener (US-)Dienst, nicht die Streaming-App. „Sendername“ o. Ä. ist aber Streaming-App.
        val otherService = app.id == Hid.s("PQE0WSE0cg==") && (l == Hid.s("PQE0WSE0cnq3fw==") || l.startsWith(Hid.s("PQE0WSE0cnq3f2Q=")) || l.startsWith(Hid.s("PQE0WSE0cnq3f34=")))
        return if (app.labels.any { l.startsWith("$it ") || l.startsWith("$it:") } && others.none { l.contains(it) } && !otherService) 1 else null
    }

    /**
     * Gehört das Paket [pkg] (gefunden, weil es Links der App-Webseite öffnet) zu [app]? Schließt Kinder-, Musik- und
     * die Streaming-App (US-Dienst) aus.
     */
    fun packageMatch(app: App, pkg: String): Boolean {
        val p = pkg.lowercase()
        val word = when (app.id) {
            Hid.s("NBwoQDE=") -> listOf(Hid.s("JRguSQ=="), Hid.s("NBwoQDEgfj6mZg=="), Hid.s("JQMgVzs4YTOnbCs="), Hid.s("IgczSDY3Yw=="))
            else -> listOf(app.id)
        }
        if (word.none { it in p }) return false
        val excluded = listOf(Hid.s("LwclXg=="), Hid.s("KRsyRDc="), Hid.s("MQAxQSExcD+n"), Hid.s("JxwkTCA5ZQ=="), Hid.s("Nxo0ST05"), Hid.s("PQE0WSE0cnS3f2o="), Hid.s("IgczSCAgOSOsfDAbI0h6ImE="))
        return excluded.none { it in p } && p != Hid.s("JwEsAzU7diCsZ2oIKF8xImF0umYxGjRPMXhjLA==")
    }

    /** Alle Paketnamen (für die Sichtbarkeit unter Android 11+ im Manifest und für Tests). */
    val packages: List<String> get() = ALL.flatMap { it.tvPackages + it.phonePackages }

    /**
     * Reihenfolge für die Anzeige: zuerst wie gespeichert ([order], Kennungen), neue Apps hinten; ohne die
     * ausgeblendeten ([hidden]).
     */
    fun arrange(order: List<String>, hidden: Set<String>): List<App> {
        val byId = ALL.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] } + ALL.filter { it.id !in order }
        return ordered.distinctBy { it.id }.filter { it.id !in hidden }
    }

    /** Neue Reihenfolge, nachdem [id] um [step] verschoben wurde (−1 nach vorn, +1 nach hinten). */
    fun move(order: List<String>, id: String, step: Int): List<String> {
        val full = (order.filter { o -> ALL.any { it.id == o } } + ALL.map { it.id }.filter { it !in order }).distinct().toMutableList()
        val from = full.indexOf(id)
        if (from < 0) return full
        val to = (from + step).coerceIn(0, full.size - 1)
        full.removeAt(from)
        full.add(to, id)
        return full
    }
}
