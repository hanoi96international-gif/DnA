package de.ayutv.data.repo

import android.content.Context

/**
 * Wie viele Untertitel-Downloads OpenSubtitles am Tag erlaubt (laut letzter Anmeldung): 5 ohne Konto, 20 mit Konto,
 * 1000 mit VIP. Danach richtet sich, wie großzügig die App Dateien vergleicht und nachts vorbereitet.
 */
object OpenSubtitlesQuota {
    private const val PREFS = "opensubtitles_quota"
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Downloads pro Tag (0 = unbekannt, z. B. ohne Konto). */
    var daily: Int
        get() = prefs?.getInt("daily", 0) ?: 0
        set(value) {
            prefs?.edit()?.putInt("daily", value)?.apply()
        }

    /** VIP oder ähnlich großes Kontingent: viele Dateien vergleichen, nachts viel vorbereiten. */
    val generous: Boolean get() = daily >= 200

    /** So viele Dateien je Film vergleichen (die erste mitgezählt). */
    val candidatesPerFilm: Int get() = when {
        generous -> 5
        // Ohne Konto (5 am Tag, oder noch unbekannt): nur eine weitere Datei vergleichen.
        daily in 0..10 -> 2
        else -> 3
    }

    /** Unter diesem Anteil (Prozent) werden weitere Dateien verglichen. */
    val compareBelowPercent: Int get() = if (generous) 95 else 85

    /** So viele Downloads darf das nächtliche Vorbereiten je Tag verbrauchen. */
    fun nightlyBudget(account: Boolean): Int = when {
        generous -> daily / 5
        account -> 8
        else -> 2
    }
}
