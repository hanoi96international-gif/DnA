package de.ayutv.data.parental

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import android.content.Context
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.LockedGroupEntity
import de.ayutv.data.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Kindersicherung: Gesperrte Gruppen verschwinden überall (Listen, TV-Guide, Suche, Zappen), bis sie mit
 * der PIN entsperrt werden. Nach jedem App-Start ist wieder alles gesperrt. Die PIN wird nur als
 * Prüfsumme gespeichert.
 */
class ParentalControl(private val context: Context, private val db: AyuDatabase) {
    data class LockedGroup(val playlistId: Long, val kind: GroupKind, val name: String)

    private val pinKey = stringPreferencesKey("parental_pin")
    private val groupsKey = stringPreferencesKey("parental_groups")
    private val mutex = Mutex()
    private val _unlocked = MutableStateFlow(false)

    /** true = bis zum nächsten App-Start entsperrt. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    val hasPin: Flow<Boolean> = context.settingsDataStore.data.map { !it[pinKey].isNullOrEmpty() }

    val lockedGroups: Flow<Set<LockedGroup>> = context.settingsDataStore.data.map { decode(it[groupsKey]) }

    suspend fun setPin(pin: String) {
        require(pin.length >= 4 && pin.all(Char::isDigit)) { "PIN: mindestens 4 Ziffern" }
        context.settingsDataStore.edit { it[pinKey] = hash(pin) }
    }

    /** PIN entfernen (nur mit der richtigen PIN): Danach ist nichts mehr gesperrt. */
    suspend fun removePin(pin: String): Boolean {
        if (!check(pin)) return false
        context.settingsDataStore.edit { it.remove(pinKey) }
        _unlocked.value = false
        applyLocks()
        return true
    }

    suspend fun hasPinNow(): Boolean = !context.settingsDataStore.data.first()[pinKey].isNullOrEmpty()

    suspend fun check(pin: String): Boolean {
        val stored = context.settingsDataStore.data.first()[pinKey] ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), hash(pin).toByteArray())
    }

    suspend fun unlock(pin: String): Boolean {
        if (!check(pin)) return false
        _unlocked.value = true
        applyLocks()
        return true
    }

    suspend fun lock() {
        _unlocked.value = false
        applyLocks()
    }

    suspend fun setLocked(group: LockedGroup, locked: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val set = decode(prefs[groupsKey]).toMutableSet()
            if (locked) set += group else set -= group
            prefs[groupsKey] = encode(set)
        }
        applyLocks()
    }

    /** Sperren in der Datenbank anwenden (beim Start, nach Ändern, Sperren und Entsperren). */
    suspend fun applyLocks() = mutex.withLock {
        val prefs = context.settingsDataStore.data.first()
        val active = !_unlocked.value && !prefs[pinKey].isNullOrEmpty()
        db.lockedGroups().clear()
        if (active) {
            db.lockedGroups().insertAll(decode(prefs[groupsKey]).map { LockedGroupEntity(it.playlistId, it.kind, it.name) })
        }
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("dna-tv:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun decode(raw: String?): Set<LockedGroup> = raw.orEmpty().split('\n').mapNotNullTo(HashSet()) { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return@mapNotNullTo null
        val kind = GroupKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNullTo null
        LockedGroup(parts[0].toLongOrNull() ?: return@mapNotNullTo null, kind, parts[2])
    }

    private fun encode(groups: Set<LockedGroup>) = groups.joinToString("\n") { "${it.playlistId}\t${it.kind.name}\t${it.name}" }
}
