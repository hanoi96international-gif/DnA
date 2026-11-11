package de.ayutv.data.parse

/**
 * Land einer Gruppe aus ihrem Namen: „DE | Sport“, „|UK| News“, „[TR] Ulusal“, „GERMANY HD“, „FR: Cinéma“.
 * Für die Länderauswahl: Wer nur deutschsprachig schaut, blendet den Rest mit einem Klick aus.
 */
object Countries {
    data class Country(val code: String, val label: String)

    private val NAMES = linkedMapOf(
        "DE" to "Deutschland", "AT" to "Österreich", "CH" to "Schweiz", "UK" to "Großbritannien", "US" to "USA",
        "CA" to "Kanada", "FR" to "Frankreich", "IT" to "Italien", "ES" to "Spanien", "PT" to "Portugal", "NL" to "Niederlande",
        "BE" to "Belgien", "PL" to "Polen", "TR" to "Türkei", "GR" to "Griechenland", "RU" to "Russland", "UA" to "Ukraine",
        "RO" to "Rumänien", "BG" to "Bulgarien", "HU" to "Ungarn", "CZ" to "Tschechien", "SK" to "Slowakei", "EXYU" to "Ex-Jugoslawien",
        "AL" to "Albanien", "SE" to "Schweden", "DK" to "Dänemark", "NO" to "Norwegen", "FI" to "Finnland", "AR" to "Arabisch",
        "IR" to "Iran", "KU" to "Kurdisch", "IN" to "Indien", "PK" to "Pakistan", "AF" to "Afghanistan", "LAT" to "Lateinamerika",
        Hid.s("Bjw=") to "Brasilien", "MX" to "Mexiko", "AU" to "Australien", "AFR" to "Afrika", "ASIA" to "Asien", "CN" to "China",
        "IL" to "Israel", "AM" to "Armenien", "AZ" to "Aserbaidschan", "GE" to "Georgien", "LT" to "Litauen", "LV" to "Lettland",
        "EE" to "Estland", "IE" to "Irland",
    )
    private val ALIASES = mapOf(
        "GER" to "DE", "DEU" to "DE", "GERMANY" to "DE", "DEUTSCHLAND" to "DE", "GERMAN" to "DE", "DEUTSCH" to "DE",
        "AUT" to "AT", "AUSTRIA" to "AT", "ÖSTERREICH" to "AT", "SUI" to "CH", "SWISS" to "CH", "SCHWEIZ" to "CH",
        "GB" to "UK", "ENGLAND" to "UK", "USA" to "US", "FRANCE" to "FR", "ITALY" to "IT", "ITALIA" to "IT", "SPAIN" to "ES",
        "ESPANA" to "ES", "NETHERLANDS" to "NL", "HOLLAND" to "NL", "POLAND" to "PL", "POLSKA" to "PL", "TURKEY" to "TR",
        "TURKIYE" to "TR", "TÜRKIYE" to "TR", "GREECE" to "GR", "RUSSIA" to "RU", "ROMANIA" to "RO", "BULGARIA" to "BG",
        "HUNGARY" to "HU", "EX-YU" to "EXYU", "YU" to "EXYU", "BALKAN" to "EXYU", "BALKANS" to "EXYU", "ALB" to "AL",
        "ALBANIA" to "AL", "SWEDEN" to "SE", "DENMARK" to "DK", "NORWAY" to "NO", "FINLAND" to "FI", "ARAB" to "AR",
        "ARABIC" to "AR", "KURD" to "KU", "KURDISH" to "KU", "INDIA" to "IN", "LATINO" to "LAT", "LATAM" to "LAT",
        "BRAZIL" to Hid.s("Bjw="), "MEXICO" to "MX", "AFRICA" to "AFR", "AFRIKA" to "AFR", "PERSIAN" to "IR", "UKRAINE" to "UA",
        "PORTUGAL" to "PT", "BELGIUM" to "BE", "CANADA" to "CA", "IRELAND" to "IE",
    )
    // Erstes Wort: Buchstaben (auch EX-YU), nach Zierzeichen wie „|“, „[“, „★“ am Anfang
    private val FIRST = Regex("""^[^\p{L}]*([\p{L}]+(?:-[\p{L}]+)?)""")
    /** Zweibuchstabige Kürzel zählen nur mit Trennzeichen dahinter („DE |“, „DE:“), damit „TV Spielfilm“ kein Land ist. */
    private val SEPARATED = Regex("""^[^\p{L}]*[\p{L}-]+\s*([|:\]▎│»\-–]|$)""")

    fun of(group: String): Country? {
        val text = group.trim()
        val word = FIRST.find(text)?.groupValues?.get(1)?.uppercase() ?: return null
        val code = ALIASES[word] ?: word.takeIf { it in NAMES } ?: return null
        // Kurze Kürzel ohne Trennzeichen nur am Anfang eines Namens, der sonst nichts sagt (z. B. „DE HD“)
        if (word.length <= 3 && !SEPARATED.containsMatchIn(text) && text.length > word.length && !text.substring(word.length).trim().uppercase().let { it in NOISE }) {
            // „DE Sport“ (Leerzeichen) ist bei vielen Anbietern üblich; zulassen, wenn es ein bekanntes Kürzel ist
            if (word !in COMMON_WITH_SPACE) return null
        }
        return Country(code, NAMES[code] ?: code)
    }

    private val NOISE = setOf("HD", "FHD", "UHD", "4K", "SD", "VIP", "")
    private val COMMON_WITH_SPACE = setOf("DE", "GER", "AT", "CH", "UK", "US", "USA", "FR", "IT", "ES", "NL", "PL", "TR", "GR", "RU", "RO", "AL", "AR")
}
