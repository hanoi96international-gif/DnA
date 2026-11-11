package de.ayutv.data.sport

import de.ayutv.data.parse.Hid

import java.text.Normalizer

/**
 * Länderspiele: Flagge statt Vereinswappen („Deutschland – Frankreich“, „Germany U21“, „England Frauen“). Deutsche und
 * englische Namen (TV-Guide schreibt deutsch, Dienst englisch); England, Schottland, Wales und Nordirland mit eigener Flagge.
 * Bilder von flagcdn.com (frei, PNG).
 */
object NationalTeams {
    /** Zusätze, die nichts am Land ändern („U21“, „Frauen“, „Women“, „(F)“, „Olympia“). */
    private val SUFFIX = Regex("""(?i)\s*(\(.*\)|\bu ?\d{2}\b|\bfrauen\b|\bdamen\b|\bwomen\b|\bwomen's\b|\bladies\b|\bolympia\w*\b|\bnationalmannschaft\b|\bnational team\b|\bteam\b)\s*""")

    private val CODES: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) = names.forEach { put(key(it), code) }
        add("de", "Deutschland", "Germany", "DFB")
        add("at", "Österreich", "Oesterreich", "Austria")
        add("ch", "Schweiz", "Switzerland")
        add("gb-eng", "England")
        add("gb-sct", "Schottland", "Scotland")
        add("gb-wls", "Wales")
        add("gb-nir", "Nordirland", "Northern Ireland")
        add("ie", "Irland", "Republic of Ireland", "Ireland")
        add("fr", "Frankreich", "France")
        add("it", "Italien", "Italy")
        add("es", "Spanien", "Spain")
        add("pt", "Portugal")
        add("nl", "Niederlande", "Holland", "Netherlands")
        add("be", "Belgien", "Belgium")
        add("lu", "Luxemburg", "Luxembourg")
        add("dk", "Dänemark", "Daenemark", "Denmark")
        add("se", "Schweden", "Sweden")
        add("no", "Norwegen", "Norway")
        add("fi", "Finnland", "Finland")
        add("is", "Island", "Iceland")
        add("pl", "Polen", "Poland")
        add("cz", "Tschechien", "Czechia", "Czech Republic")
        add("sk", "Slowakei", "Slovakia")
        add("hu", "Ungarn", "Hungary")
        add("si", "Slowenien", "Slovenia")
        add(Hid.s("LBw="), "Kroatien", "Croatia")
        add("rs", "Serbien", "Serbia")
        add("ba", "Bosnien-Herzegowina", "Bosnien und Herzegowina", "Bosnia and Herzegovina", "Bosnia-Herzegovina")
        add("me", "Montenegro")
        add("mk", "Nordmazedonien", "North Macedonia")
        add("al", "Albanien", "Albania")
        add("xk", "Kosovo")
        add("gr", "Griechenland", "Greece")
        add("tr", "Türkei", "Tuerkei", "Turkey", "Türkiye", "Turkiye")
        add("cy", "Zypern", "Cyprus")
        add("ro", "Rumänien", "Rumaenien", "Romania")
        add("bg", "Bulgarien", "Bulgaria")
        add("md", "Moldau", "Moldawien", "Moldova")
        add("ua", "Ukraine")
        add("by", "Belarus", "Weißrussland")
        add("ru", "Russland", "Russia")
        add("ge", "Georgien", "Georgia")
        add("am", "Armenien", "Armenia")
        add("az", "Aserbaidschan", "Azerbaijan")
        add("kz", "Kasachstan", "Kazakhstan")
        add("lt", "Litauen", "Lithuania")
        add("lv", "Lettland", "Latvia")
        add("ee", "Estland", "Estonia")
        add("mt", "Malta")
        add("ad", "Andorra")
        add("sm", "San Marino")
        add("li", "Liechtenstein")
        add("gi", "Gibraltar")
        add("fo", "Färöer", "Faeroeer", "Faroe Islands")
        add("il", "Israel")
        add("us", "USA", "Vereinigte Staaten", "United States")
        add("ca", "Kanada", "Canada")
        add("mx", "Mexiko", "Mexico")
        add(Hid.s("Jhw="), "Brasilien", "Brazil")
        add("ar", "Argentinien", "Argentina")
        add("uy", "Uruguay")
        add("co", "Kolumbien", "Colombia")
        add("cl", "Chile")
        add("pe", "Peru")
        add("ec", "Ecuador")
        add("py", "Paraguay")
        add("ve", "Venezuela")
        add("bo", "Bolivien", "Bolivia")
        add("cr", "Costa Rica")
        add("pa", "Panama")
        add("jm", "Jamaika", "Jamaica")
        add("jp", "Japan")
        add("kr", "Südkorea", "Suedkorea", "South Korea", "Korea Republic")
        add("kp", "Nordkorea", "North Korea")
        add("cn", "China", "China PR")
        add("au", "Australien", "Australia")
        add("nz", "Neuseeland", "New Zealand")
        add("sa", "Saudi-Arabien", "Saudi Arabia")
        add("qa", "Katar", "Qatar")
        add("ae", "Vereinigte Arabische Emirate", "United Arab Emirates", "UAE")
        add("ir", "Iran", "IR Iran")
        add("iq", "Irak", "Iraq")
        add("jo", "Jordanien", "Jordan")
        add("uz", "Usbekistan", "Uzbekistan")
        add("ma", "Marokko", "Morocco")
        add("dz", "Algerien", "Algeria")
        add("tn", "Tunesien", "Tunisia")
        add("eg", "Ägypten", "Aegypten", "Egypt")
        add("sn", "Senegal")
        add("ng", "Nigeria")
        add("gh", "Ghana")
        add("ci", "Elfenbeinküste", "Elfenbeinkueste", "Ivory Coast", "Côte d'Ivoire", "Cote d'Ivoire")
        add("cm", "Kamerun", "Cameroon")
        add("ml", "Mali")
        add("za", "Südafrika", "Suedafrika", "South Africa")
        add("cd", "DR Kongo", "Kongo DR", "DR Congo")
        add("cv", "Kap Verde", "Cape Verde", "Cabo Verde")
    }

    private fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKD).replace(Regex("""\p{M}+"""), "").lowercase()
            .replace("ß", "ss").replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /** Länderkürzel (flagcdn) für einen Mannschaftsnamen, wenn es eine Nationalmannschaft ist, sonst null. */
    fun code(team: String): String? {
        val bare = team.replace(SUFFIX, " ").trim()
        if (bare.isEmpty()) return null
        return CODES[key(bare)]
    }

    /** Flagge als Bild (PNG, 160 Pixel breit), wenn [team] eine Nationalmannschaft ist. */
    fun flag(team: String): String? = code(team)?.let { "https://flagcdn.com/w160/$it.png" }
}
