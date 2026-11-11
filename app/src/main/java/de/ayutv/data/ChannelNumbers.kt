package de.ayutv.data

import android.content.Context
import de.ayutv.data.db.AyuDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Eigene Sendernummern: „Sendername“ auf die 7, „Sendername“ auf die 1. Gespeichert je Sender (Schlüssel bleibt
 * über Aktualisierungen gleich) und in die Senderliste geschrieben, damit Anzeige, Sortierung und Zifferntasten sie
 * ohne Sonderwege nutzen. Nach jedem Laden einer Playlist werden sie neu eingetragen. Jede Nummer gehört genau
 * einem Sender: Wer eine schon vergebene Nummer wählt, nimmt sie dem anderen weg.
 */
class ChannelNumbers(context: Context) {
    data class Entry(val number: Int, val name: String, val original: Int)

    private val prefs = context.getSharedPreferences("channel_numbers", Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(load())
    /** Schlüssel des Senders → eigene Nummer. */
    val all: StateFlow<Map<String, Entry>> = _all.asStateFlow()

    fun numberOf(key: String): Int? = _all.value[key]?.number

    /** Sender mit dieser eigenen Nummer (Schlüssel), sonst null. */
    fun keyFor(number: Int): String? = _all.value.entries.firstOrNull { it.value.number == number }?.key

    /**
     * Nummer setzen ([number] null oder ≤ 0: entfernen, die Nummer der Playlist gilt wieder). [original] ist die
     * Nummer, die der Sender gerade hat (zum Wiederherstellen).
     */
    suspend fun set(db: AyuDatabase, key: String, name: String, original: Int, number: Int?) {
        val current = _all.value.toMutableMap()
        val previous = current.remove(key)
        val restore = previous?.original ?: original
        if (number == null || number <= 0) {
            db.channels().setNumber(key, restore)
        } else {
            // Nummer schon vergeben: dem anderen Sender seine alte Nummer zurückgeben.
            current.entries.firstOrNull { it.value.number == number }?.let { (otherKey, other) ->
                current.remove(otherKey)
                db.channels().setNumber(otherKey, other.original)
            }
            current[key] = Entry(number, name, restore)
            db.channels().setNumber(key, number)
        }
        save(current)
    }

    /** Nach dem Laden einer Playlist: die eigenen Nummern wieder eintragen (die Playlist hat sie überschrieben). */
    suspend fun apply(db: AyuDatabase) {
        val current = _all.value
        if (current.isEmpty()) return
        var changed = false
        val next = current.toMutableMap()
        for ((key, entry) in current) {
            val channel = db.channels().byKey(key) ?: continue
            // Die Nummer der Playlist kann sich geändert haben: merken, damit „Entfernen“ die richtige zurückgibt.
            if (channel.number != entry.number && channel.number != entry.original) {
                next[key] = entry.copy(original = channel.number)
                changed = true
            }
            if (channel.number != entry.number) db.channels().setNumber(key, entry.number)
        }
        if (changed) save(next)
    }

    private fun save(map: Map<String, Entry>) {
        _all.value = map
        val editor = prefs.edit().clear()
        for ((key, e) in map) editor.putString(key, "${e.number}\t${e.original}\t${e.name}")
        editor.apply()
    }

    private fun load(): Map<String, Entry> = prefs.all.mapNotNull { (key, value) ->
        val parts = (value as? String)?.split('\t', limit = 3) ?: return@mapNotNull null
        val number = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        key to Entry(number, parts.getOrNull(2).orEmpty(), parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }.toMap()
}
