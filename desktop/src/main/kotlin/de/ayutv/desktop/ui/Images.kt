package de.ayutv.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Senderlogos und Poster: laden, im Hintergrund entpacken und verkleinern, im Speicher halten (die zuletzt
 * gebrauchten, höchstens [MAX_CACHE_BYTES]). Entpackt wird nie beim Zeichnen: Sonst ruckelt das Blättern, sobald
 * neue Bilder ins Bild kommen.
 */
object Images {
    private const val MAX_BYTES = 4_000_000
    /** Längste Seite im Speicher (Poster und Logos werden nie größer gezeigt). */
    private const val MAX_SIDE = 640
    private const val MAX_CACHE_BYTES = 192L * 1024 * 1024
    private var cacheBytes = 0L
    private val http = Network.http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val parallel = Semaphore(6)
    private val failed = HashSet<String>()
    private val cache = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)

    private fun put(url: String, image: ImageBitmap) {
        cache.put(url, image)?.let { cacheBytes -= it.width.toLong() * it.height * 4 }
        cacheBytes += image.width.toLong() * image.height * 4
        val iterator = cache.entries.iterator()
        while (cacheBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == url) continue
            cacheBytes -= eldest.value.width.toLong() * eldest.value.height * 4
            iterator.remove()
        }
    }

    /** Fertig entpacktes, höchstens [MAX_SIDE] großes Bild (im Hintergrund-Thread). */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val encoded = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        try {
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(encoded.width, encoded.height).coerceAtLeast(1))
            val width = (encoded.width * scale).toInt().coerceAtLeast(1)
            val height = (encoded.height * scale).toInt().coerceAtLeast(1)
            val bitmap = org.jetbrains.skia.Bitmap()
            if (!bitmap.allocN32Pixels(width, height)) return null
            val pixels = bitmap.peekPixels() ?: return null
            if (!encoded.scalePixels(pixels, org.jetbrains.skia.SamplingMode.LINEAR, false)) return null
            bitmap.setImmutable()
            return bitmap.asComposeImageBitmap()
        } finally {
            encoded.close()
        }
    }

    @Synchronized fun cached(url: String): ImageBitmap? = cache[url]

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        synchronized(this) { if (url in failed) return null }
        return parallel.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = http.newCall(Request.Builder().url(url).header("User-Agent", Sync.USER_AGENT).build()).execute().use { r ->
                        if (!r.isSuccessful) return@runCatching null
                        r.body.bytes().takeIf { it.size <= MAX_BYTES }
                    } ?: return@runCatching null
                    decode(bytes)
                }.getOrNull()
            }
        }.also { image ->
            synchronized(this) { if (image != null) put(url, image) else failed += url }
        }
    }
}

@Composable
fun rememberImage(url: String?): ImageBitmap? {
    if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return null
    var image by remember(url) { mutableStateOf(Images.cached(url)) }
    LaunchedEffect(url) { if (image == null) image = Images.load(url) }
    return image
}
