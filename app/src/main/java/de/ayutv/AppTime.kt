package de.ayutv

import java.util.Locale
import java.util.TimeZone

/**
 * Zeitzone für Uhr und TV-Guide. Manche Fire TV stehen auf „GMT/UTC“ statt auf Berlin (z. B. nach
 * dem Zurücksetzen): Dann zeigte DnA-TV alle Zeiten eine Stunde (Sommer: zwei) zu früh. Bei deutscher
 * Sprache wird in dem Fall Mitteleuropa angenommen – nur für DnA-TV, die Geräteeinstellung bleibt.
 */
object AppTime {
    private val UTC_IDS = setOf("GMT", "UTC", "Etc/UTC", "Etc/GMT", "Etc/Universal", "Universal", "Zulu", "Etc/Zulu", "Etc/GMT+0", "Etc/GMT-0", "GMT0")

    /** War die Gerätezeitzone UTC und wurde ersetzt? (für den Hinweis in den Einstellungen) */
    @Volatile var corrected: Boolean = false
        private set

    /** Zeitzone, die das Gerät meldet (vor einer Korrektur). */
    @Volatile var deviceZone: String = TimeZone.getDefault().id
        private set

    /** Fire TV meldet UTC/GMT (bei anderer Sprache als Deutsch bleibt das so: dann ist der Hinweis nötig). */
    val deviceOnUtc: Boolean get() = deviceZone in UTC_IDS

    fun fixZone() {
        val device = TimeZone.getDefault()
        deviceZone = device.id
        val zone = replacement(device.id, Locale.getDefault()) ?: return
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        corrected = true
    }

    /** Ersatzzone für [deviceZone], oder null, wenn die Gerätezeitzone bleiben soll. */
    fun replacement(deviceZone: String, locale: Locale): String? {
        if (deviceZone !in UTC_IDS) return null
        return when {
            locale.country == "AT" -> "Europe/Vienna"
            locale.country == "CH" -> "Europe/Zurich"
            locale.language == "de" -> "Europe/Berlin"
            else -> null
        }
    }
}
