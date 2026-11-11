package de.ayutv.data.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import de.ayutv.AyuApp
import de.ayutv.R
import de.ayutv.player.TsReplayFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Nimmt auf und lädt herunter, auch wenn DnA-TV nicht offen ist (Dienst im Vordergrund mit Hinweis).
 * Live: der Stream des Senders wird von Beginn bis Ende in eine Datei geschrieben, abgerissene
 * Verbindungen werden neu aufgebaut. Download: die Datei des Films, nach Abbruch ab der letzten Stelle.
 * Zählt beim Anbieter als eigene Verbindung (wie ein zweites Gerät).
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val recordings get() = (application as AyuApp).container.recordings
    private val http by lazy {
        (application as AyuApp).container.http.newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground("Aufnahmen werden vorbereitet")
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dnatv:recording")?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60_000L)
            }
        }
        if (watcher == null) watcher = scope.launch { recordings.all.collectLatest { refresh(it) } }
        return START_STICKY
    }

    /** Fällige Aufträge starten, entfernte beenden; ohne Arbeit den Dienst beenden. */
    private suspend fun refresh(list: List<Recording>) {
        val now = System.currentTimeMillis()
        val ids = list.mapTo(HashSet()) { it.id }
        synchronized(jobs) {
            jobs.keys.filter { it !in ids }.forEach { jobs.remove(it)?.cancel() }
        }
        val due = list.filter { (it.state == Recording.State.SCHEDULED || it.state == Recording.State.RUNNING) && it.from <= now + 1_000 }
        for (r in due) synchronized(jobs) {
            if (jobs[r.id]?.isActive != true) jobs[r.id] = scope.launch { run(r) }
        }
        val running = synchronized(jobs) { jobs.values.count { it.isActive } }
        if (running == 0) {
            // Spätere Aufträge weckt der Wecker; bis dahin kein Dienst im Vordergrund.
            stopSelf()
            return
        }
        goForeground(
            list.filter { it.state == Recording.State.RUNNING }.joinToString(" · ") { it.title }.ifEmpty { "Aufnahme läuft" }
        )
        // Später fällige Aufträge, solange der Dienst ohnehin läuft.
        list.filter { it.state == Recording.State.SCHEDULED && it.from > now }.minOfOrNull { it.from }?.let { next ->
            delay((next - now).coerceAtMost(60_000))
            refresh(recordings.all.value)
        }
    }

    private suspend fun run(r: Recording) {
        recordings.update(r.id) { it.copy(state = Recording.State.RUNNING, error = null) }
        val result = runCatching { if (r.kind == Recording.Kind.RECORDING) record(r) else download(r) }
        val error = result.exceptionOrNull()?.takeIf { it !is kotlinx.coroutines.CancellationException }
        if (recordings.all.value.none { it.id == r.id }) return
        val bytes = recordings.file(r).length()
        recordings.update(r.id) {
            when {
                error != null && !(r.kind == Recording.Kind.RECORDING && bytes > MIN_USEFUL) ->
                    it.copy(state = Recording.State.FAILED, error = error.message ?: error.javaClass.simpleName, bytes = bytes)
                bytes == 0L -> it.copy(state = Recording.State.FAILED, error = "Nichts aufgenommen (Gerät aus oder Sender gestört?)")
                else -> it.copy(state = Recording.State.DONE, bytes = bytes, error = error?.message)
            }
        }
        synchronized(jobs) { jobs.remove(r.id) }
        refresh(recordings.all.value)
    }

    private suspend fun record(r: Recording) {
        if (r.url.contains(".m3u8", ignoreCase = true)) throw IOException("Sender mit HLS-Stream lassen sich nicht aufnehmen")
        val file = recordings.file(r)
        var failures = 0
        // Nach dem Neuverbinden schicken viele Server die letzten Sekunden noch einmal: nicht doppelt aufnehmen.
        val replay = TsReplayFilter()
        while (scope.isActive && System.currentTimeMillis() < r.until && recordings.all.value.any { it.id == r.id }) {
            if (recordings.freeBytes() < MIN_FREE) throw IOException("Speicher voll")
            replay.reconnected()
            try {
                val request = Request.Builder().url(r.url).header("User-Agent", r.userAgent).build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val input = response.body.byteStream()
                    FileOutputStream(file, true).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var written = file.length()
                        var reported = 0L
                        val write: (ByteArray, Int, Int) -> Unit = { b, o, l ->
                            out.write(b, o, l)
                            written += l
                        }
                        while (System.currentTimeMillis() < r.until) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            replay.process(buffer, 0, n, write)
                            failures = 0
                            if (written - reported > REPORT_EVERY) {
                                reported = written
                                recordings.update(r.id) { it.copy(bytes = written) }
                                if (recordings.all.value.none { it.id == r.id }) return
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (++failures > MAX_FAILURES) throw e
                Log.w(TAG, "Aufnahme ${r.title}: ${e.message}, neuer Versuch")
                delay(minOf(1_000L * failures, 10_000L))
            }
        }
    }

    private suspend fun download(r: Recording) {
        val file = recordings.file(r)
        var failures = 0
        while (scope.isActive) {
            val have = file.length()
            try {
                val request = Request.Builder().url(r.url).header("User-Agent", r.userAgent)
                    .apply { if (have > 0) header("Range", "bytes=$have-") }
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.code == 416) return
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val append = have > 0 && response.code == 206
                    val total = (response.body.contentLength().takeIf { it > 0 } ?: 0L) + if (append) have else 0L
                    if (total > 0 && total - (if (append) have else 0) > recordings.freeBytes() - MIN_FREE) throw IOException("Nicht genug Speicher frei")
                    recordings.update(r.id) { it.copy(totalBytes = total) }
                    val input = response.body.byteStream()
                    FileOutputStream(file, append).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var written = if (append) have else 0L
                        var reported = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            failures = 0
                            if (written - reported > REPORT_EVERY) {
                                reported = written
                                recordings.update(r.id) { it.copy(bytes = written) }
                                if (recordings.all.value.none { it.id == r.id }) return
                            }
                        }
                        if (total > 0 && written < total) throw IOException("Verbindung abgebrochen")
                    }
                }
                return
            } catch (e: IOException) {
                if (++failures > MAX_FAILURES) throw e
                delay(minOf(2_000L * failures, 30_000L))
            }
        }
    }

    private fun goForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Aufnahmen und Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DnA-TV nimmt auf")
            .setContentText(text)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "Vordergrund nicht möglich", it) }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Recording"
        const val ACTION_CHECK = "de.ayutv.RECORDING_CHECK"
        private const val CHANNEL_ID = "recordings"
        private const val NOTIFICATION_ID = 4711
        private const val MIN_FREE = 500L * 1024 * 1024
        private const val MIN_USEFUL = 5L * 1024 * 1024
        private const val REPORT_EVERY = 4L * 1024 * 1024
        private const val MAX_FAILURES = 30

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_CHECK)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w(TAG, "Dienst nicht gestartet", it) }
        }
    }
}
