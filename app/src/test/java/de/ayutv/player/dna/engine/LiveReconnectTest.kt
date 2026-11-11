package de.ayutv.player.dna.engine

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live-TS neu verbinden (DnaReader): Ein IPTV-Server trennt, beim Neuverbinden kommen die letzten Sekunden noch einmal
 * (Zeitstempel laufen weiter) → nahtlos, nichts doppelt. Beginnt der Strom von vorn (Zeitsprung) → lückenlos weiter
 * ab einem Schlüsselbild.
 */
class LiveReconnectTest {
    private val file = javaClass.getResourceAsStream("/dna/h264_aac.ts")!!.readBytes()

    private class Collect(private val stopAfter: Int, val connections: AtomicInteger) : DnaReader.Sink {
        val samples = ArrayList<Sample>()
        val done = CountDownLatch(1)
        @Volatile var last = 0L
        override fun alive(gen: Int) = connections.get() <= stopAfter
        override fun onPrepared(durationUs: Long, seekable: Boolean, live: Boolean) = Unit
        override fun onTracks(tracks: List<Track>) = Unit
        @Synchronized override fun onSample(sample: Sample, gen: Int) {
            if (sample.track.codec.kind == Kind.TEXT) return
            samples += sample
            last = maxOf(last, sample.timeUs + sample.durationUs)
        }
        override fun onEnded() = Unit
        override fun onError(message: String, fatal: Boolean) { done.countDown() }
        override fun onInfo(text: String) = Unit
        override fun onReconnect() = Unit
        override fun awaitRoom(gen: Int, limitUs: Long) = Unit
        override val lastMediaUs: Long get() = last
    }

    /** Erste Verbindung: ganze Datei; zweite: ab [secondFrom] (Bytes); dann ist Schluss. */
    private fun run(secondFrom: Int): List<Sample> {
        val connections = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sink = Collect(stopAfter = 2, connections)
        server.createContext("/live.ts") { ex ->
            val n = connections.incrementAndGet()
            val body = when (n) {
                1 -> file
                2 -> file.copyOfRange(secondFrom - secondFrom % 188, file.size)
                else -> ByteArray(0)
            }
            ex.sendResponseHeaders(200, body.size.toLong().coerceAtLeast(1))
            ex.responseBody.use { if (body.isNotEmpty()) it.write(body) else it.write(0) }
            if (n >= 3) sink.done.countDown()
        }
        server.start()
        try {
            val reader = DnaReader(OkHttpClient(), DnaReader.Config(), sink)
            reader.open("http://127.0.0.1:${server.address.port}/live.ts", null, null, live = true)
            reader.start(1, 0, true) { _, block -> block() }
            sink.done.await(20, TimeUnit.SECONDS)
            reader.stop()
        } finally {
            server.stop(0)
        }
        return synchronized(sink) { sink.samples.toList() }
    }

    @Test
    fun overlapAfterReconnectIsDroppedAndTimeContinues() {
        val single = run(secondFrom = file.size).filter { it.track.codec.kind == Kind.VIDEO }.size
        // Zweite Verbindung wiederholt die letzten ~40 % (wie der Puffer eines IPTV-Servers).
        val samples = run(secondFrom = file.size * 6 / 10)
        val video = samples.filter { it.track.codec.kind == Kind.VIDEO }
        val audio = samples.filter { it.track.codec.kind == Kind.AUDIO }
        assertEquals("Bild doppelt", video.map { it.timeUs }.toSet().size, video.size)
        assertEquals("alle Bilder genau einmal", single, video.size)
        val audioTimes = audio.map { it.timeUs }
        assertTrue("Ton läuft zurück", audioTimes.zipWithNext().all { (a, b) -> b > a })
        // Zeit läuft weiter, nicht doppelt so lang.
        assertTrue("Medienzeit ${video.maxOf { it.timeUs }}", video.maxOf { it.timeUs } < 2_200_000)
    }

    @Test
    fun restartFromBeginningContinuesAfterLastFromKeyframe() {
        val samples = run(secondFrom = 0)
        val video = samples.filter { it.track.codec.kind == Kind.VIDEO }
        val firstPassEnd = video.take(video.size / 2).maxOf { it.timeUs }
        val second = video.drop(video.size / 2)
        assertTrue("zweiter Durchgang beginnt mit Schlüsselbild", second.first().keyframe)
        assertTrue("Zeit springt zurück: ${second.minOf { it.timeUs }} ≤ $firstPassEnd", second.minOf { it.timeUs } > firstPassEnd)
        assertEquals(video.map { it.timeUs }.toSet().size, video.size)
    }
}
