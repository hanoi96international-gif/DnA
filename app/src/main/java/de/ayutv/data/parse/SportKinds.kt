package de.ayutv.data.parse

/**
 * Welche Sportart eine Sendung ist, für die Filter im Bereich Sport. Andere Sportarten werden zuerst geprüft: Viele
 * haben selbst eine „Bundesliga“ oder einen „Pokal“ (Handball-Bundesliga, Volleyball Bundesliga, Basketball BBL),
 * die früher als Fußball zählten. Fußball nur mit eindeutigem Hinweis (Fußball, Bundesliga ohne andere Sportart,
 * bekannte Ligen und Wettbewerbe) oder wenn der Spielplan das Spiel als Fußballspiel kennt.
 */
object SportKinds {
    enum class Kind(val label: String) {
        FOOTBALL(Hid.s("AhuCsjY3ezY=")), HANDBALL(Hid.s("DA8vSTY3ezY=")), BASKETBALL(Hid.s("Bg8yRjEidTuvZQ==")), ICE_HOCKEY(Hid.s("AQcyRTs1fD+6")),
        VOLLEYBALL(Hid.s("EgEtQTEvdTuvZQ==")), TENNIS(Hid.s("EAsvQz0l")), MOTORSPORT(Hid.s("CQE1QiYlZzWxfQ==")), DARTS(Hid.s("AA8zWSc=")), US_SPORTS(Hid.s("ET1sfiQ5ZS4=")),
        WINTER(Hid.s("EwcvWTEkZCqsezA=")), COMBAT(Hid.s("Dw8sXTIlZzWxfQ==")), CYCLING(Hid.s("Fg8lXiQ5ZS4=")), GOLF(Hid.s("AwEtSw==")), SNOOKER(Hid.s("FwAuQj8zZQ==")),
        HOCKEY(Hid.s("DAEiRjEv")), OTHER(Hid.s("EwsoWTEkcg==")),
    }

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Reihenfolge zählt: Spezielleres zuerst (Eishockey vor Hockey, US-Sport vor Fußball wegen „Football“). */
    private val RULES: List<Pair<Kind, Regex>> = listOf(
        Kind.ICE_HOCKEY to rx(Hid.s("IQcyRTs1fD+6dS0NJA08OXQxpnA4MiNJMTpLOL9VJgApQQg0awahYiwCHU8oM34pocrgHCRDKDdzNqZ7ZAMgQzo+cjOudS+t90E6M2V6q2gtCw==")),
        Kind.HANDBALL to rx(Hid.s("LA8vSTY3eza/VSYGI0EINGsGoWwsCB1PKDDU5qBhNwthTzEkezOtdTAGNg0/P3I2v3ojTidBMThkOLZ7IxIsWXQ7cjawfCoJJEMoJH8/qmdpACROPzdleq/K8hkkQyg+ZD3jdRgMNUUnIEs4")),
        Kind.BASKETBALL to rx(Hid.s("Jg8yRjEidTuvZTgyI082Oks4v1UmACNMCDRrP7Z7KwIkTDMjciamfDYBIlgkKnU7sGIhGjJRJzN2LaxlMgsyUSA5YD+xemQGIEA2I2U9v2goDCANNjNlNqpnOBokQTE9eDfjayUdKkggJWsGoWQmDR1P")),
        Kind.VOLLEYBALL to rx(Hid.s("MgEtQTEvdTuvZTgyI1s2Oks4v38rAi1ILSVrOKZoJwZsEiI5ezamcA==")),
        Kind.US_SPORTS to rx(Hid.s("GAwvSzgKdSaiZCEcKE41ODc8rGYwDCBBOCpkL7NsNk4jQiM6awahZCgMHU8oNHYppmslAi1RCDR5OaJoGAw9Tjs6ez+kbGQILkIgNHY2r3UwBjRfJzJ2I+NnLQkpWSglYjSnaD1OL0QzPmN6pWYrGiNMODprBqFoME5pTz06eym/ayEPM14oNGU1rWorHT1eIDNyNqZ7NxIiRT0zcSm/eSUaM0Q7ImQmsWgyCy9eKCZ2OahsNh09TjshdTW6ejgLIEo4M2Rzn2s=")),
        Kind.TENNIS to rx(Hid.s("MAsvQz0lawahaDAeHU8oCnUtt2gYDD1aPTt1NqZtKwA9Xzs6djSnKSMPM187JWsvsCkrHiRDKDdiKbd7JQIoTDp2eCqmZzgKIFs9JTc5tnk4DChBOD9yeqlsJQBhRj04cA==")),
        Kind.MOTORSPORT to rx(Hid.s("IgEzQDE6N2XydSIBM0AhOnZ6/Dg4MiNLZQp1Jq5mMAEmXSg7eC6sKXsJMVEINHMurlUmEi9MJzV2KL9gKgo4TjUkayiiZSgXJFEmN3s2unUpATVCJiVnNbF9OAkzTDoyNyqxYDwSMlgkM2U4qmIhEh1PIzN0BqF1KAthQDU4ZCalZjYDJEF0M2s8rHspGy1MdDM=")),
        Kind.DARTS to rx(Hid.s("IA8zWScqSzizbScyIw==")),
        Kind.SNOOKER to rx(Hid.s("NwAuQj8zZQ==")),
        Kind.WINTER to rx(Hid.s("JgcgWTw6eDS/ei8HYUw4Jn40v3ovBzJdJj95PaZnOAIgQzM6di+ldTYBJUg4OGs4rGsYDD1IPSV8L616MAIgWDIqcjOweicGL0g4Ons7tm84HS9CIzR4O7FtOB0qSDgzYzWtdSoBM0k9JXQypikvASxPPTh2LqpmKhIyRjUicnqgaCoPJUw=")),
        Kind.COMBAT to rx(Hid.s("JgE5SDoqdTW7YCoJPXE2I3E5n2s4MiNAOTdLOL9+NgsyWTg/eT2/VSYZNkgINGsxqmovDC5VMThrMLZtKxIzRDoxcjQ=")),
        Kind.CYCLING to rx(Hid.s("Ng8lXiQ5ZS6/fSsbMw0wMzc8sWgqDSRRMz9lNeNtOBg0SDgidiagcCcCLk4mOWQpv3slCjNIOjhyNL9rJQYvXzUy")),
        Kind.GOLF to rx(Hid.s("GAwmQjgwSzi/eSMPYVk7I2UmsXAgCzMNNyNnJqd5ZBkuXzgyNy6sfDY=")),
        Kind.HOCKEY to rx(Hid.s("GAwpQjc9ciOfazgIJEEwPng5qGw9EilMODpyNKtmJwUkVCg+eDmobD1DI1g6MnIpr2AjDz1xNj50BqEpGEY9cXwyS3PjJDgyaUUIfzd3")),
    )
    private val FOOTBALL = rx(
        Hid.s("Ihtp7ssqZCnqayUCLVEnOXQ5pns4MiNLOzljOKJlKDIjUTYjeT6meigHJkwoZEt04zYmAh1PKGVLdOM2KAcmTCgKdTivVSYSJUs2eygqrGIlAj1OPDd6KqpmKh1hQTE3cC+mdSEbM0IkNzc2pmgjGyRR") +
            Hid.s("JwEvSzEkcjSgbGQCJEwzI3Ims3shAyhIJnZ7P6JuMQs9QTV2KDaqbiUSMkgmP3J6onUoByZYMXYmJqZ7IQooWz0lfj+/eofSMUgmdnszpHU3GzFIJnZ7M6R1NgsmRDs4djavYCMPPQ==") +
            Hid.s("KwwkXzg/cDu/bzYPNEg6e3UvrW0hHS1EMzdrP64kNRsgQT0qYDfueDEPLUQoOtT+rW0hHDJdPTN7Jq1oMAcuQyd2ez+ibjELPU44I3V6tGY2AiUNNyNnJp9rIgcnTAg0awahfCEIIHE2")
    )
    /** Vereinskürzel, die meist Fußball heißen – aber nicht bei Sender (Handball, Basketball, Volleyball, Hockey). */
    private val CLUBS = rx(Hid.s("GAwnTgg0awahejIyI1EINGE8oVUmEh1PIjB7BqF1JgEzWCclfju/bC0ANV81NX8uv2slFyRfOnZ6mX9nJwYkQygwdHqhaD0LM0MoCnUv8lJzVxxxNipLOLY7H19ycAg0"))
    private val NOT_FOOTBALL_CHANNEL = rx(Hid.s("GAwlVDoKdSawfSUHJkgoPnY0p2slAi1RNjdkMaZ9Jg8tQSggeDavbD0MIEE4"))

    /**
     * Sportart einer Sendung. [footballMatch]: der Spielplan (OpenLigaDB, nur Fußball) kennt das Spiel. Ohne
     * eindeutigen Hinweis [Kind.OTHER].
     */
    fun kind(title: String, subtitle: String?, category: String?, description: String?, channel: String = "", footballMatch: Boolean = false): Kind {
        if (footballMatch) return Kind.FOOTBALL
        val main = listOfNotNull(title, subtitle, category).joinToString(" ")
        // Erst Titel, Untertitel, Kategorie (eindeutig), dann die Beschreibung, zuletzt der Sendername.
        for (text in listOf(main, description.orEmpty(), channel)) {
            if (text.isBlank()) continue
            RULES.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.let { return it.first }
            if (FOOTBALL.containsMatchIn(text)) return Kind.FOOTBALL
            if (text !== channel && CLUBS.containsMatchIn(text) && !NOT_FOOTBALL_CHANNEL.containsMatchIn(channel)) return Kind.FOOTBALL
        }
        return Kind.OTHER
    }
}
