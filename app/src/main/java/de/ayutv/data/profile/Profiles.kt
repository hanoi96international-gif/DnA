package de.ayutv.data.profile

import android.content.Context
import de.ayutv.AppContainer
import de.ayutv.data.remote.DeviceSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Ein Profil: eigene Favoriten, eigener Verlauf („Weiter schauen“, gesehene Folgen). */
@Serializable
data class Profile(val id: String, val name: String, val emoji: String = "🙂", val kids: Boolean = false)

/**
 * Profile für mehrere Personen an einem Gerät. Aktiv ist immer genau eins; seine Favoriten und sein Verlauf
 * stehen in der Datenbank, die der anderen liegen als Datei daneben und werden beim Wechsel getauscht.
 * Ein Kinderprofil hält die Kindersicherung gesperrt; heraus kommt man nur mit der PIN.
 */
class Profiles(private val context: Context, private val container: AppContainer) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Profile>> = _all.asStateFlow()
    private val _active = MutableStateFlow(prefs.getString("active", null)?.let { id -> _all.value.firstOrNull { it.id == id } } ?: _all.value.first())
    val active: StateFlow<Profile> = _active.asStateFlow()

    private fun load(): List<Profile> = runCatching { json.decodeFromString(LIST, prefs.getString("list", null)!!) }.getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: listOf(Profile(MAIN, "Ich", "😀"))

    private fun save(list: List<Profile>) {
        _all.value = list
        prefs.edit().putString("list", json.encodeToString(LIST, list)).apply()
    }

    fun add(name: String, emoji: String, kids: Boolean): Profile {
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim().ifEmpty { "Profil" }.take(20), emoji, kids)
        save(_all.value + p)
        return p
    }

    /** Profil löschen (nicht das aktive und nicht das letzte). */
    fun remove(profile: Profile): Boolean {
        if (profile.id == _active.value.id || _all.value.size <= 1) return false
        save(_all.value.filterNot { it.id == profile.id })
        File(dir, "${profile.id}.json").delete()
        return true
    }

    /** Muss vor dem Wechsel die PIN abgefragt werden? (aus einem Kinderprofil heraus, wenn eine PIN gesetzt ist) */
    suspend fun needsPin(): Boolean = _active.value.kids && container.parental.hasPinNow()

    /** Zum Profil [target] wechseln: Favoriten und Verlauf tauschen. [pin] nur aus einem Kinderprofil heraus. */
    suspend fun switchTo(target: Profile, pin: String? = null): Boolean = lock.withLock {
        val current = _active.value
        if (target.id == current.id) return@withLock true
        if (needsPin() && (pin == null || !container.parental.check(pin))) return@withLock false
        val sync = DeviceSync(container)
        val db = container.db
        withContext(Dispatchers.IO) {
            File(dir, "${current.id}.json").writeText(sync.export())
            db.favorites().clear()
            db.history().clear()
        }
        File(dir, "${target.id}.json").takeIf { it.isFile }?.let { file -> runCatching { sync.import(file.readText()) } }
        _active.value = target
        prefs.edit().putString("active", target.id).apply()
        if (target.kids) container.parental.lock()
        true
    }

    companion object {
        const val MAIN = "main"
        private val LIST = ListSerializer(Profile.serializer())
        val EMOJIS = listOf("😀", "😎", "👩", "👨", "👧", "👦", "👵", "👴", "🐱", "🐶", "⚽", "🎬")
    }
}
