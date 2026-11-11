package de.ayutv.data

import android.content.Context

/**
 * Von Hand gewählte TV-Guide-Zuordnung je Sender (Schlüssel des Senders → Kennung im EPG). Gilt über
 * jedes Neuladen hinweg, auch wenn der Anbieter dem Sender eine falsche Kennung gibt.
 */
class EpgOverrides(context: Context) {
    private val prefs = context.getSharedPreferences("epg_overrides", Context.MODE_PRIVATE)

    fun get(channelKey: String): String? = prefs.getString(channelKey, null)

    fun set(channelKey: String, guideId: String?) {
        prefs.edit().apply { if (guideId == null) remove(channelKey) else putString(channelKey, guideId) }.apply()
    }

    fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}
