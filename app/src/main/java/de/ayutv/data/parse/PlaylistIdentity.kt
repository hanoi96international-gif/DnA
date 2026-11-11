package de.ayutv.data.parse

/**
 * Ist eine neu eingegebene Playlist dieselbe wie eine vorhandene? Dann wird die vorhandene aktualisiert statt eine
 * zweite angelegt. Eine zweite hätte eine neue Nummer; eigene Gruppen, Favoriten und Ausblendungen hängen aber an
 * der alten und sähen in der neuen leer aus (und alles erschiene doppelt).
 *
 * Xtream: gleicher Server (ohne Unterschied http/https, Groß-/Kleinschreibung, Schrägstrich am Ende, „/player_api.php“)
 * und gleicher Benutzer. M3U: gleiche Adresse (ebenso verglichen).
 */
object PlaylistIdentity {
    /** [type] als Text („XTREAM“, „M3U“), damit Fire TV und Windows dieselbe Prüfung nutzen. */
    fun same(typeA: String, urlA: String, userA: String?, typeB: String, urlB: String, userB: String?): Boolean {
        if (!typeA.equals(typeB, ignoreCase = true)) return false
        if (address(urlA) != address(urlB)) return false
        val xtream = typeA.equals("XTREAM", ignoreCase = true)
        return !xtream || userA.orEmpty().trim() == userB.orEmpty().trim()
    }

    /** Adresse ohne Schema, Host klein geschrieben, ohne Schrägstrich am Ende und ohne Xtream-Endpunkt. */
    fun address(url: String): String {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme in 1..10) s = s.substring(scheme + 3)
        s = s.trimEnd('/').removeSuffix("/player_api.php").removeSuffix("/get.php").trimEnd('/')
        val slash = s.indexOf('/').let { if (it < 0) s.length else it }
        val host = s.substring(0, slash).lowercase().removeSuffix(":80")
        return host + s.substring(slash)
    }
}
