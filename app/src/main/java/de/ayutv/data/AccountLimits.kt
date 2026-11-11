package de.ayutv.data

import android.content.Context

/**
 * Wie viele gleichzeitige Verbindungen das Konto beim Anbieter erlaubt (Xtream: max_connections),
 * je Playlist beim Laden gemerkt. Damit warnt DnA-TV, bevor eine Aufnahme das Schauen blockiert.
 */
class AccountLimits(context: Context) {
    private val prefs = context.getSharedPreferences("account_limits", Context.MODE_PRIVATE)

    fun maxConnections(playlistId: Long): Int? = prefs.getInt(playlistId.toString(), 0).takeIf { it > 0 }

    fun set(playlistId: Long, maxConnections: Int?) {
        prefs.edit().apply {
            if (maxConnections == null || maxConnections <= 0) remove(playlistId.toString()) else putInt(playlistId.toString(), maxConnections)
        }.apply()
    }

    companion object {
        /** Hinweis, wenn eine Aufnahme beim Anbieter die einzige erlaubte Verbindung belegt. */
        fun recordingWarning(maxConnections: Int?): String? = when (maxConnections) {
            1 -> "Dein Konto erlaubt nur 1 Verbindung: Solange die Aufnahme läuft, lässt sich bei diesem Anbieter nichts anderes schauen."
            else -> null
        }
    }
}
