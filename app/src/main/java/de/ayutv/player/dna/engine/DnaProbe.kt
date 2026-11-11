package de.ayutv.player.dna.engine

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Sender prüfen, ohne abzuspielen: Strom (TS oder HLS) lesen, bis das erste Schlüsselbild mit Parametersätzen da ist,
 * und dessen Bildhöhe liefern (0 = kein Bild). Liest höchstens ein paar MB und bricht sofort ab, wenn [cancelled].
 */
object DnaProbe {
    fun videoHeight(http: OkHttpClient, url: String, userAgent: String?, cancelled: () -> Boolean): Int {
        var height = 0
        val demuxer = TsDemuxer(object : TsDemuxer.Output {
            override fun onTracks(tracks: List<Track>) = Unit
            override fun onSample(sample: Sample) {
                if (height == 0 && sample.track.codec.kind == Kind.VIDEO && sample.keyframe) {
                    height = VideoConfig.forTrack(sample.track, sample.data)?.height ?: 0
                }
            }
        })
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) {
            val loader = HlsLoader(http, userAgent, { !cancelled() }, maxHeight = 2160, maxBandwidth = 0)
            loader.open(url)
            var segment = loader.firstSegment(0)
            var segments = 0
            while (segment != null && height == 0 && !cancelled() && segments++ < 3) {
                val data = loader.load(segment)
                val init = loader.init(segment)
                if (init != null || Mp4Demuxer.sniff(data)) {
                    val mp4 = Mp4Demuxer(SourceReader(ArrayByteSource((init ?: ByteArray(0)) + data)))
                    val info = mp4.init()
                    info.tracks.firstOrNull { it.codec.kind == Kind.VIDEO }?.let { if (it.height > 0) height = it.height }
                    while (height == 0) {
                        val s = mp4.read() ?: break
                        if (s.track.codec.kind == Kind.VIDEO && s.keyframe) height = VideoConfig.forTrack(s.track, s.data)?.height ?: 0
                    }
                } else demuxer.feed(data, 0, data.size)
                segment = if (height == 0) loader.nextSegment() else null
            }
            return height
        }
        val request = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
        val call = http.newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) return 0
            val input = response.body.byteStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (height == 0 && total < MAX_BYTES) {
                if (cancelled()) {
                    call.cancel()
                    return -1
                }
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                demuxer.feed(buffer, 0, n)
            }
        }
        return height
    }

    private const val MAX_BYTES = 8L * 1024 * 1024
}
