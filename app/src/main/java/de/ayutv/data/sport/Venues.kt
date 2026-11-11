package de.ayutv.data.sport

import de.ayutv.data.parse.Hid

import de.ayutv.data.parse.Teams

/**
 * Stadion und Stadt der Vereine aus 1. und 2. Bundesliga und 3. Liga. Sender nennt Einzelspiele oft nur „Live: BL“ und in
 * der Beschreibung „Aus der MEWA Arena in Mainz“; OpenLigaDB gibt den Spielort aber bei vielen Spielen nicht an. Dann
 * findet [LiveScores.byVenue] die Partie über diese Liste (Heimverein). Wörter klein, ohne Umlaute und Akzente.
 */
object Venues {
    private class Club(val keys: List<Set<String>>, val words: Set<String>)

    private fun club(keys: String, words: String) =
        Club(keys.split('|').map { it.split(' ').toSet() }, words.split(' ').toSet())

    // Reihenfolge zählt: Der erste passende Eintrag gilt („Viktoria Köln“ vor „Köln“, „1860“ vor „München“).
    private val CLUBS = listOf(
        club("1860", "grunwalder giesing munchen"),
        club("bayern", "allianz allianzarena munchen"),
        club("dortmund", "signal iduna westfalenstadion dortmund"),
        club("leverkusen", "bayarena leverkusen"),
        club("leipzig", Hid.s("JhstQXQkcj6hfCgCIF8xOHZ6r2wtHjtEMw==")),
        club("frankfurt", "waldstadion frankfurt"),
        club("stuttgart", "mhp mhparena stuttgart cannstatt"),
        club("freiburg", "europapark freiburg"),
        club("wolfsburg", "volkswagen wolfsburg"),
        club("gladbach|monchengladbach", "borussiapark monchengladbach gladbach"),
        club("werder|bremen", "weserstadion bremen"),
        club("hoffenheim", "prezero sinsheim"),
        club("mainz", "mewa mainz"),
        club("augsburg", "wwk augsburg"),
        club("union berlin", "forsterei kopenick berlin"),
        club("hertha", "olympiastadion berlin"),
        club("viktoria koln", "hohenberg koln"),
        club("koln", "rheinenergiestadion mungersdorf koln"),
        club("hamburger|hsv", "volksparkstadion hamburg"),
        club("pauli", "millerntor hamburg"),
        club("heidenheim", "voith heidenheim"),
        club("bochum", "ruhrstadion vonovia bochum"),
        club("kiel", "holsteinstadion kiel"),
        club("darmstadt", "bollenfalltor darmstadt"),
        club("schalke", "veltins gelsenkirchen"),
        club("hannover", "heiden hannover"),
        club("dusseldorf", "merkur dusseldorf"),
        club("nurnberg", "morlock nurnberg"),
        club("furth", "ronhof furth"),
        club("karlsruhe|karlsruher", "wildpark karlsruhe"),
        club("paderborn", "deluxe paderborn"),
        club("elversberg", "ursapharm kaiserlinde elversberg"),
        club("kaiserslautern", "betzenberg fritzwalterstadion kaiserslautern"),
        club("magdeburg", "avnet mdcc magdeburg"),
        club("braunschweig", "braunschweig"),
        club("bielefeld", "schuco bielefeld"),
        club("munster|preussen", "preussenstadion munster"),
        club("dresden|dynamo", "harbig dresden"),
        club("ulm", "donaustadion ulm"),
        club("regensburg", "jahnstadion regensburg"),
        club("rostock|hansa", "ostseestadion rostock"),
        club("saarbrucken", "ludwigspark saarbrucken"),
        club("cottbus", "cottbus"),
        club("aue|erzgebirge", "erzgebirgsstadion aue"),
        club("osnabruck", "osnabruck"),
        club("wiesbaden|wehen", "brita wiesbaden"),
        club("essen", "hafenstrasse essen"),
        club("mannheim|waldhof", "carlbenzstadion mannheim"),
        club("ingolstadt", "ingolstadt"),
        club("verl", "verl"),
        club("duisburg", "schauinsland duisburg"),
        club("aachen", "tivoli aachen"),
        club("unterhaching|haching", "unterhaching"),
        club("schweinfurt", "schweinfurt"),
        club("sandhausen", "hardtwald sandhausen"),
        club("havelse", "havelse"),
        club("lubeck", "lohmuhle lubeck"),
    )

    /** Stadion- und Ortswörter des Vereins (Heimspiel), leer wenn unbekannt. */
    fun of(team: String): Set<String> {
        val have = Teams.textWords(team)
        return CLUBS.firstOrNull { c -> c.keys.any { have.containsAll(it) } }?.words.orEmpty()
    }
}
