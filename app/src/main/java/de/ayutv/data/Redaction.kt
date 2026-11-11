package de.ayutv.data

/** Für Fehlerberichte: Zugangsdaten unkenntlich machen, Server und Fehlermeldungen bleiben lesbar. */
object Redaction {
    private val URL = Regex("""(?i)\b(https?|rtmp|rtsp|udp)://(?:[^\s/@"'<>]+@)?([^\s/:?"'<>]+)(:\d+)?[^\s"'<>]*""")
    private val SECRET = Regex("""(?i)\b(password|passwort|pass|pwd|username|user|token|api[-_]?key|apikey|key|auth|authorization)(["']?\s*[=:]\s*["']?)[^\s&"',;]+""")
    private val LONG_ID = Regex("""\b[A-Za-z0-9_\-]{28,}\b""")
    private const val MIN_SECRET = 4
    private val MAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")

    /**
     * Zugangsdaten unkenntlich machen: Adressen nur mit Server, keine Passwörter, Schlüssel oder E-Mail-Adressen.
     * [secrets] (die eigenen Passwörter, Benutzernamen, Schlüssel) verschwinden zusätzlich überall wörtlich, auch
     * wo sie ohne erkennbares Muster stehen (z. B. als Pfad „/live/name/passwort/1.ts“ ohne Adresse davor).
     */
    fun apply(text: String, secrets: Collection<String> = emptyList()): String = secrets
        .map { it.trim() }.filter { it.length >= MIN_SECRET }.distinct().sortedByDescending { it.length }
        .fold(text) { acc, secret -> acc.replace(secret, "***") }
        .replace(URL) { m -> "${m.groupValues[1]}://${m.groupValues[2]}${m.groupValues[3]}/…" }
        .replace(SECRET) { m -> "${m.groupValues[1]}${m.groupValues[2]}***" }
        .replace(MAIL, "***@***")
        .replace(LONG_ID, "***")
}
