package de.ayutv.player.dna.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DVB-Untertitel im Live-TV (ETSI EN 300 743): echter Strom aus ffmpeg (Blu-ray-Bilduntertitel → dvbsub, 1920×1080).
 * Geprüft: TS-Leser erkennt die Spur (Sprache, Seiten), gibt jedes Untertitel-Paket sofort aus, und der eigene Leser macht
 * daraus ein Bild mit Schrift an der richtigen Stelle; das nächste Paket blendet aus.
 */
class DvbSubtitleTest {
    private fun read(): Pair<Track, List<Sample>> {
        val data = javaClass.getResourceAsStream("/dna/h264_dvbsub.ts")!!.readBytes()
        var subtitle: Track? = null
        val samples = ArrayList<Sample>()
        val demuxer = TsDemuxer(object : TsDemuxer.Output {
            override fun onTracks(tracks: List<Track>) {
                subtitle = tracks.firstOrNull { it.codec == Codec.DVBSUB }
            }

            override fun onSample(sample: Sample) {
                if (sample.track.codec == Codec.DVBSUB) samples += sample
            }
        })
        demuxer.feed(data, 0, data.size)
        demuxer.flush()
        return subtitle!! to samples
    }

    @Test
    fun trackAndPacketsFromTs() {
        val (track, samples) = read()
        assertEquals("deu", track.language)
        assertEquals(Kind.TEXT, track.codec.kind)
        assertNotNull(track.extra)
        assertTrue("Untertitel-Pakete: ${samples.size}", samples.size >= 2)
        // Erstes Paket mit Bild (gut 10 KB), danach kleine Pakete zum Ausblenden.
        assertTrue(samples.first().data.size > 5_000)
    }

    @Test
    fun decodesBitmapAtItsPlaceAndClears() {
        val (track, samples) = read()
        val pages = track.extra!!
        val decoder = DvbSubtitleDecoder(
            ((pages[0].toInt() and 0xFF) shl 8) or (pages[1].toInt() and 0xFF),
            ((pages[2].toInt() and 0xFF) shl 8) or (pages[3].toInt() and 0xFF),
        )
        val shown = decoder.decodeImages(samples.first().data)
        assertNotNull("erstes Paket ergibt nichts", shown)
        assertTrue("kein Bild im ersten Paket", shown!!.isNotEmpty())
        val image = shown.first()
        assertEquals(1920, image.displayWidth)
        assertEquals(1080, image.displayHeight)
        // Schrift: genug sichtbare Pixel, im Bild, untere Hälfte (Untertitel stehen unten).
        assertTrue("zu wenig Schrift: ${image.opaquePixels}", image.opaquePixels > 1_000)
        assertTrue(image.x in 0 until 1920 && image.x + image.width <= 1920)
        assertTrue("Lage y=${image.y}", image.y > 540 && image.y + image.height <= 1080)
        // Ein späteres kleines Paket blendet aus (leere Liste).
        val clear = samples.drop(1).firstOrNull { it.data.size < 100 }?.let { decoder.decodeImages(it.data) }
        assertNotNull(clear)
        assertTrue("Ausblenden ergibt ${clear!!.size} Bilder", clear.isEmpty())
    }
}
