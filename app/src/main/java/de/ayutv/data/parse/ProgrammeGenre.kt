package de.ayutv.data.parse

/**
 * Art einer Sendung für die Farben im TV-Guide: aus der Kategorie des EPG, sonst aus Titel und Sender. Lieber
 * „Sonstiges“ als falsch geraten.
 */
enum class ProgrammeGenre(val label: String, val argb: Long) {
    SPORT(Hid.s("Fx4uXyA="), 0xFF2EA043),
    FILM(Hid.s("AgctQA=="), 0xFF8957E5),
    SERIES(Hid.s("FwszRDE="), 0xFF3B82F6),
    NEWS(Hid.s("Cg8iRSY/dDK3bCo="), 0xFFE3A008),
    KIDS(Hid.s("DwcvSTEk"), 0xFFEC4899),
    DOCU(Hid.s("AAEqWA=="), 0xFF14B8A6),
    SHOW(Hid.s("FwYuWg=="), 0xFFF97316),
    OTHER(Hid.s("FwEvXiA/cD+w"), 0x00000000),
    ;

    companion object {
        private val SPORT_WORDS = Regex(Hid.s("bFEoBAg0ZCqsezASJ1iXyXU7r2U4CDReJzR2Nq91IgEuWTY3eza/azEAJUgnOn49onUnBiBAJD94NLApKAsgSiEzay6mZyoHMlEyOWU3pmVkUXBROTljNbF6NAEzWSg+djSnayUCLVE2N2Qxpn0mDy1BKDN+KatmJwUkVCg0eCKmZzgJLkEyKmU7p3o0ATNZKCV8M59rOAwoTCA+ezWtdSAPM1knKnk8r3UqDCBROj57JrZvJw=="))
        private val FILM_WORDS = Regex(Hid.s("bFEoBCcmfj+vby0CLFEINHEzr2QYDD1AOyB+P79iLQAuUSA+ZTOvZSEcPUY7O9Tsp2AhEiVfNTt2JrRsNxokXzoqfzWxeyscPV43P3I0oGxqUSdENyJ+Na11JQ01RDs4"))
        private val SERIES_WORDS = Regex(Hid.s("bFEoBAg0ZD+xYCESMkgmP3Ipv3otGiJCOSpkNaJ5OAUzRDk/ZD+xYCESMlk1MHE/r3UiAS1KMQp1JqZ5LR0uSTE="))
        private val NEWS_WORDS = Regex(Hid.s("bFEoBDo3dDKxYCcGNUg6Knk/tHo4GiBKMSVkOatoMRIpSCEicnqpZjEcL0w4Kks4q2wxGiRxNipgP7d9IRw9XTs6fi6qYjgPKlkhM3s2v2MrGzNDNTo="))
        private val KIDS_WORDS = Regex(Hid.s("bFEoBD8/eT6mezgFKEknKm0/qmosCy9ZJj90Mb9oKgcsTCA/eDS/aiUcNUI7OGswtm4hACVRPz98O79nLQ0qUTA/ZDSmcDgaLkozOWswtmctATM="))
        private val DOCU_WORDS = Regex(Hid.s("bFEoBDA5fC+/bSsFNEAxOGM7t2ArAD1JOzViN6ZnMA8zVCgkciqsezAPJkgoOHYutns4GSheJzN5JqRsNw0pRDc+Yz+/YS0dNUImLw=="))
        private val NEWS_CHANNELS = Regex(Hid.s("bFEoBAg0eT+0ehgMPUN5ImEmn2sqGjdxNipgP699GAw="))
        private val SHOW_WORDS = Regex(Hid.s("bFEoBAg0ZDKsfjgbL1kxJH87r30xACZRMThjP7F9JQcvQDE4YyayfC0UPVk1Onwmrnw3BypROSNkM6B1JwEsSDAvayimaCgHNVQ="))

        /** Art aus [category] (EPG), sonst aus [title] und [channel] (z. B. Sportsender, Kindersender). */
        fun of(title: String, category: String?, channel: String = ""): ProgrammeGenre {
            val c = category.orEmpty()
            if (c.isNotBlank()) {
                // Reihenfolge: genauere Arten zuerst („Kinderfilm“ ist Kinder, „Sportdoku“ ist Sport).
                when {
                    KIDS_WORDS.containsMatchIn(c) -> return KIDS
                    SPORT_WORDS.containsMatchIn(c) -> return SPORT
                    NEWS_WORDS.containsMatchIn(c) -> return NEWS
                    SERIES_WORDS.containsMatchIn(c) -> return SERIES
                    FILM_WORDS.containsMatchIn(c) -> return FILM
                    DOCU_WORDS.containsMatchIn(c) -> return DOCU
                    SHOW_WORDS.containsMatchIn(c) -> return SHOW
                }
            }
            return when {
                SPORT_WORDS.containsMatchIn(channel) -> SPORT
                KIDS_WORDS.containsMatchIn(channel) -> KIDS
                NEWS_WORDS.containsMatchIn(title) || NEWS_CHANNELS.containsMatchIn(channel) -> NEWS
                else -> OTHER
            }
        }
    }
}
