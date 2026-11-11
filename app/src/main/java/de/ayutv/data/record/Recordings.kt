package de.ayutv.data.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

/**
 * Aufnahmen (Live-Sendungen, zur geplanten Zeit) und Downloads (Filme/Folgen fürs Handy unterwegs).
 * Beides lädt [RecordingService] im Vordergrund; die Liste liegt als JSON neben den Dateien.
 */
@Serializable
data class Recording(
    val id: String,
    val kind: Kind,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val channelKey: String? = null,
    /** Schlüssel des Films/der Folge (Downloads): Weiterschauen und gesehen-Markierung teilen. */
    val sourceKey: String? = null,
    val url: String,
    val userAgent: String,
    /** Aufnahme: Sendungsbeginn und -ende; Download: Zeitpunkt des Auftrags, 0. */
    val start: Long,
    val stop: Long = 0,
    val state: State = State.SCHEDULED,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    enum class Kind { RECORDING, DOWNLOAD }
    enum class State { SCHEDULED, RUNNING, DONE, FAILED }

    /** Live-Aufnahmen beginnen etwas früher und enden etwas später (Sender halten Zeiten selten genau ein). */
    val from: Long get() = if (kind == Kind.RECORDING) start - PAD_BEFORE else start
    val until: Long get() = if (kind == Kind.RECORDING) stop + PAD_AFTER else Long.MAX_VALUE

    companion object {
        const val PAD_BEFORE = 2 * 60_000L
        const val PAD_AFTER = 5 * 60_000L
    }
}

class Recordings(private val context: Context) {
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Recording>> = _all.asStateFlow()

    fun file(r: Recording): File = File(dir, r.id + if (r.kind == Recording.Kind.DOWNLOAD) extensionOf(r.url) else ".ts")

    fun freeBytes(): Long = dir.usableSpace

    /** Sendung aufnehmen bzw. Aufnahme wieder entfernen. Liefert true, wenn sie jetzt geplant ist. */
    suspend fun toggleRecording(channelKey: String, channelName: String, title: String, url: String, userAgent: String, start: Long, stop: Long, image: String?): Boolean {
        val id = "rec-" + "$channelKey@$start".hashCode().toUInt().toString(16)
        all.value.firstOrNull { it.id == id }?.let {
            delete(it)
            return false
        }
        add(Recording(id, Recording.Kind.RECORDING, title, channelName, image, channelKey = channelKey, url = url, userAgent = userAgent, start = start, stop = stop))
        return true
    }

    suspend fun download(sourceKey: String, title: String, subtitle: String?, image: String?, url: String, userAgent: String) {
        val id = "dl-" + sourceKey.hashCode().toUInt().toString(16)
        if (all.value.any { it.id == id && it.state != Recording.State.FAILED }) return
        add(Recording(id, Recording.Kind.DOWNLOAD, title, subtitle, image, sourceKey = sourceKey, url = url, userAgent = userAgent, start = System.currentTimeMillis()))
    }

    private suspend fun add(r: Recording) {
        update { list -> list.filterNot { it.id == r.id } + r }
        schedule(r)
    }

    /** Aufnahme/Download beenden, Datei löschen, aus der Liste nehmen. */
    suspend fun delete(r: Recording) {
        cancelAlarm(r)
        update { list -> list.filterNot { it.id == r.id } }
        // Läuft sie gerade, bemerkt der Dienst das Fehlen in der Liste und hört auf.
        withContext(Dispatchers.IO) { file(r).delete() }
    }

    suspend fun update(id: String, transform: (Recording) -> Recording) = update { list -> list.map { if (it.id == id) transform(it) else it } }

    private suspend fun update(transform: (List<Recording>) -> List<Recording>) = lock.withLock {
        val next = transform(_all.value).sortedBy { it.start }
        _all.value = next
        withContext(Dispatchers.IO) {
            val tmp = File(dir, "index.json.tmp")
            tmp.writeText(json.encodeToString(LIST, next))
            tmp.renameTo(index)
        }
    }

    private fun load(): List<Recording> =
        runCatching { json.decodeFromString(LIST, index.readText()) }.getOrDefault(emptyList())

    /** Nach Neustart: offene Aufträge wieder einplanen. */
    fun rescheduleAll() = all.value.filter { it.state == Recording.State.SCHEDULED || it.state == Recording.State.RUNNING }.forEach(::schedule)

    private fun schedule(r: Recording) {
        val now = System.currentTimeMillis()
        if (r.from <= now) {
            RecordingService.start(context)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(r)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.from, pending)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.from, pending)
        }
    }

    private fun cancelAlarm(r: Recording) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(r))
    }

    private fun pendingIntent(r: Recording): PendingIntent {
        val intent = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_CHECK).putExtra("id", r.id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(context, r.id.hashCode(), intent, flags)
        else PendingIntent.getService(context, r.id.hashCode(), intent, flags)
    }

    companion object {
        private val LIST = ListSerializer(Recording.serializer())

        fun extensionOf(url: String): String =
            Regex("""\.([A-Za-z0-9]{2,4})(?:\?.*)?$""").find(url)?.groupValues?.get(1)?.let { ".$it" } ?: ".mp4"
    }
}
