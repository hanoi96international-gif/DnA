package de.ayutv.desktop.player

import de.ayutv.desktop.Log
import de.ayutv.desktop.data.Network
import de.ayutv.player.dna.engine.HlsLoader
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Aufnahmen: Der Stream wird im Hintergrund unverändert in eine Datei geschrieben (ohne Neukodieren), während man
 * weiter schaut – direkte Streams (TS) als Byte-Kopie, HLS Abschnitt für Abschnitt (entschlüsselt, bei fMP4 mit
 * Kopfteil). Bricht die Leitung ab, verbindet die Aufnahme neu und schreibt weiter. Jede laufende Aufnahme ist beim
 * Anbieter eine zweite Verbindung.
 */
class Recorder(val folder: File = defaultFolder()) {
    private class Job(val thread: Thread) {
        @Volatile var running = true
        @Volatile var call: okhttp3.Call? = null
    }

    private val running = HashMap<Long, Job>()

    val available: Boolean get() = true

    /** Datei für eine Aufnahme: „2026-10-31 20.15 Sender – Titel.ts“. */
    fun fileFor(channelName: String, title: String, start: Long): File {
        folder.mkdirs()
        val name = "${NAME_TIME.format(Instant.ofEpochMilli(start))} $channelName – $title".replace(Regex("""[\\/:*?"<>|]"""), "_").take(150)
        var file = File(folder, "$name.ts")
        var n = 2
        while (file.exists()) file = File(folder, "$name ($n).ts").also { n++ }
        return file
    }

    @Synchronized
    fun start(recording: de.ayutv.desktop.data.Recording, url: String, userAgent: String): Boolean {
        if (recording.id in running) return true
        val file = File(recording.file)
        file.parentFile?.mkdirs()
        lateinit var job: Job
        val thread = Thread({ record(job, url, userAgent, file) }, "Aufnahme ${recording.id}").apply { isDaemon = true }
        job = Job(thread)
        running[recording.id] = job
        thread.start()
        Log.info("Aufnahme gestartet: ${recording.title}")
        return true
    }

    private fun record(job: Job, url: String, userAgent: String, file: File) {
        FileOutputStream(file, true).use { out ->
            var failures = 0
            while (job.running) {
                val ok = runCatching {
                    if (isHls(url)) copyHls(job, url, userAgent, out) else copyStream(job, url, userAgent, out)
                }.onFailure { if (job.running) Log.warn("Aufnahme: ${it.message}") }.getOrDefault(false)
                if (!job.running) break
                failures = if (ok) 0 else failures + 1
                // Leitung weg oder Ende: kurz warten und neu verbinden (Live läuft weiter).
                runCatching { Thread.sleep(minOf(1_000L * (failures + 1), 10_000L)) }
            }
        }
    }

    /** Direkter Stream: Bytes unverändert anhängen. True = es kam etwas an. */
    private fun copyStream(job: Job, url: String, userAgent: String, out: FileOutputStream): Boolean {
        val call = Network.http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        job.call = call
        var got = false
        call.execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val input = r.body.byteStream()
            val buffer = ByteArray(256 * 1024)
            while (job.running) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                got = true
            }
        }
        return got
    }

    /** HLS: jeden neuen Abschnitt laden und anhängen (bei fMP4 einmal den Kopfteil davor). */
    private fun copyHls(job: Job, url: String, userAgent: String, out: FileOutputStream): Boolean {
        val loader = HlsLoader(Network.http, userAgent, { job.running }, maxHeight = 2160, maxBandwidth = 0)
        loader.open(url)
        var segment = loader.firstSegment(0)
        var lastInit: ByteArray? = null
        var got = false
        while (segment != null && job.running) {
            val init = loader.init(segment)
            if (init != null && init !== lastInit) {
                if (lastInit == null && out.channel.size() == 0L) out.write(init)
                lastInit = init
            }
            out.write(loader.load(segment))
            got = true
            segment = loader.nextSegment()
        }
        return got
    }

    private fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    @Synchronized
    fun stop(id: Long) {
        val job = running.remove(id) ?: return
        job.running = false
        runCatching { job.call?.cancel() }
        job.thread.interrupt()
        Log.info("Aufnahme beendet: $id")
    }

    @Synchronized
    fun isRunning(id: Long) = id in running

    @Synchronized
    fun release() {
        running.keys.toList().forEach(::stop)
    }
    companion object {
        private val NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm").withZone(ZoneId.systemDefault())

        /** Videos\DnA-TV im Benutzerordner. */
        fun defaultFolder(): File = File(System.getProperty("user.home"), "Videos" + File.separator + "DnA-TV")
    }
}
