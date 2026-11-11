package de.ayutv.data

import android.content.Context

/**
 * Früher gelernte Intros pro Serie. „Intro überspringen“ ist entfallen; die Werte bleiben nur für ältere Sicherungen
 * lesbar (Sichern und Wiederherstellen tragen sie unverändert weiter).
 */
class IntroStore(context: Context) {
    private val prefs = context.getSharedPreferences("intros", Context.MODE_PRIVATE)

    fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun restore(values: Map<String, String>) {
        prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
    }
}
