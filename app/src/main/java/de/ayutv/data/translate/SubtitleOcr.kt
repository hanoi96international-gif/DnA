package de.ayutv.data.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Process
import android.util.Log
import de.ayutv.data.translate.kern.Session
import de.ayutv.data.translate.kern.Tensor
import de.ayutv.player.EmbeddedSubtitles
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Texterkennung für Bild-Untertitel (Blu-ray/PGS, DVB, DVD) auf dem Gerät: PaddleOCR PP-OCRv5
 * für lateinische Schrift (Deutsch, Englisch, Französisch, … inkl. Umlaute), als ONNX-Modell
 * (Apache 2.0, einmalig ca. 8 MB). Untertitel sind saubere Schrift vor leerem Hintergrund:
 * Zeilen werden per Helligkeitsprofil getrennt, eine eigene Texterkennung für Bereiche ist unnötig.
 */
class SubtitleOcr(baseDir: File, private val http: OkHttpClient) {
    @Volatile
    var onProgress: (String) -> Unit = {}

    /** Meldet jeden Schritt in nativem Code vorher (und null danach): Bei einem Absturz ist klar, wo. */
    @Volatile
    var trace: (String?) -> Unit = {}

    private val baseDir = baseDir
    private val dir = File(baseDir, "ocr")
    private val env by lazy { OrtEnvironment.getEnvironment().also { runCatching { it.setTelemetry(false) } } }
    private var session: Recognizer? = null
    private var alphabet: List<String> = emptyList()
    private val lock = Mutex()
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "dna-ocr", 16L * 1024 * 1024)
    }.asCoroutineDispatcher()

    val installed: Boolean get() = FILES.all { (name, size) -> File(dir, name).length() == size }

    /** Text einer Einblendung, Zeilen durch "\n" getrennt ("" wenn nichts erkennbar). */
    suspend fun recognize(image: EmbeddedSubtitles.GrayImage): String = withContext(dispatcher) {
        val model = model()
        lines(image).mapNotNull { (top, bottom) -> line(model, image, top, bottom).trim().takeIf { it.isNotEmpty() } }
            .joinToString("\n")
    }

    suspend fun release() = withContext(dispatcher) {
        lock.withLock {
            session?.close()
            session = null
        }
    }

    /** Rechnet das Modell: Eingabe [1, 3, 48, Breite], Ausgabe je Zeitschritt die Zeichen-Wahrscheinlichkeiten. */
    private interface Recognizer : java.io.Closeable {
        fun scores(input: FloatArray, width: Int): Array<FloatArray>
    }

    private inner class OrtRecognizer(private val model: OrtSession) : Recognizer {
        override fun scores(input: FloatArray, width: Int): Array<FloatArray> =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, HEIGHT.toLong(), width.toLong())).use { tensor ->
                model.run(mapOf(model.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    (result[0].value as Array<Array<FloatArray>>)[0]
                }
            }

        override fun close() = model.close()
    }

    private class KernRecognizer(file: File) : Recognizer {
        private val model = Session(file, KERN_THREADS)

        override fun scores(input: FloatArray, width: Int): Array<FloatArray> {
            val out = model.run(mapOf(model.inputNames.first() to Tensor.floats(intArrayOf(1, 3, HEIGHT, width), input))).values.first()
            val steps = out.shape[1]
            val classes = out.shape[2]
            val data = out.floats
            return Array(steps) { t -> data.copyOfRange(t * classes, (t + 1) * classes) }
        }

        override fun close() = model.close()
    }

    private suspend fun model(): Recognizer = lock.withLock {
        session?.let { return@withLock it }
        if (!installed) download()
        alphabet = File(dir, DICT).readLines(Charsets.UTF_8).map { it.trimEnd('\r', '\n') }
        kind = Engine.select(baseDir)
        try {
            val recognizer = if (kind == Engine.Kind.KERN) {
                KernRecognizer(File(dir, MODEL))
            } else {
                val options = OrtSession.SessionOptions().apply {
                    // Ein Thread (der eigene, mit Hintergrund-Vorrang): kein Thread-Pool, der dem Video Rechenzeit nimmt.
                    setIntraOpNumThreads(1)
                    setMemoryPatternOptimization(false)
                    setCPUArenaAllocator(false)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addConfigEntry("session.inter_op.allow_spinning", "0")
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                }
                trace("Texterkennung startet")
                OrtRecognizer(env.createSession(File(dir, MODEL).path, options)).also { trace(null) }
            }
            session = recognizer
            recognizer
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw IOException("Texterkennung defekt, wird neu geladen: ${e.message}", e)
        }
    }

    /** Zeilen finden: zusammenhängende Bildzeilen mit heller Schrift, kleine Lücken überbrückt. */
    private fun lines(image: EmbeddedSubtitles.GrayImage): List<Pair<Int, Int>> {
        val ink = BooleanArray(image.height) { y ->
            val offset = y * image.width
            var count = 0
            for (x in 0 until image.width) if (image.pixels[offset + x].toInt() and 0xFF > INK) count++
            count >= 2
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var y = 0
        while (y < image.height) {
            if (!ink[y]) {
                y++
                continue
            }
            var end = y
            var gap = 0
            var k = y
            while (k < image.height && gap <= MAX_GAP) {
                if (ink[k]) {
                    end = k
                    gap = 0
                } else {
                    gap++
                }
                k++
            }
            if (end - y + 1 >= MIN_LINE) result += (y - PAD).coerceAtLeast(0) to (end + PAD).coerceAtMost(image.height - 1)
            y = end + 1
        }
        return result
    }

    private fun line(model: Recognizer, image: EmbeddedSubtitles.GrayImage, top: Int, bottom: Int): String {
        // Waagrecht auf die Schrift zuschneiden.
        var left = image.width
        var right = -1
        for (y in top..bottom) {
            val offset = y * image.width
            for (x in 0 until image.width) {
                if (image.pixels[offset + x].toInt() and 0xFF > INK) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (right < left) return ""
        left = (left - PAD).coerceAtLeast(0)
        right = (right + PAD).coerceAtMost(image.width - 1)
        val srcW = right - left + 1
        val srcH = bottom - top + 1
        val width = (srcW * HEIGHT / srcH).coerceIn(16, MAX_WIDTH)
        // Bilinear auf 48 px Höhe, Werte wie beim Training: (x/255 − 0,5) / 0,5, drei gleiche Kanäle.
        val plane = FloatArray(HEIGHT * width)
        for (ty in 0 until HEIGHT) {
            val sy = ((ty + 0.5f) * srcH / HEIGHT - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - y0
            for (tx in 0 until width) {
                val sx = ((tx + 0.5f) * srcW / width - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - x0
                fun p(x: Int, y: Int) = (image.pixels[(top + y) * image.width + left + x].toInt() and 0xFF).toFloat()
                val v = (p(x0, y0) * (1 - fx) + p(x1, y0) * fx) * (1 - fy) + (p(x0, y1) * (1 - fx) + p(x1, y1) * fx) * fy
                plane[ty * width + tx] = (v / 255f - 0.5f) / 0.5f
            }
        }
        val input = FloatArray(3 * plane.size)
        for (c in 0 until 3) plane.copyInto(input, c * plane.size)
        trace("Texterkennung rechnet (Zeile ${width}×$HEIGHT)")
        return decode(model.scores(input, width).also { trace(null) })
    }

    /** CTC: je Zeitschritt das wahrscheinlichste Zeichen, Wiederholungen und Leerstellen (0) entfernen. */
    private fun decode(scores: Array<FloatArray>): String = buildString {
        var last = -1
        for (step in scores) {
            var best = 0
            for (k in 1 until step.size) if (step[k] > step[best]) best = k
            if (best != last && best != 0) append(if (best - 1 < alphabet.size) alphabet[best - 1] else " ")
            last = best
        }
    }.replace(Regex(" {2,}"), " ")

    private fun download() {
        dir.mkdirs()
        onProgress("Lade Texterkennung für Bild-Untertitel (einmalig ca. 8 MB) …")
        for ((name, size) in FILES) {
            val target = File(dir, name)
            if (target.length() == size) continue
            val part = File(dir, "$name.part")
            http.newCall(Request.Builder().url("$BASE/$name").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Texterkennung nicht erreichbar (HTTP ${response.code})")
                response.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it, 256 * 1024) } }
            }
            if (part.length() != size) {
                part.delete()
                throw IOException("Download der Texterkennung unvollständig, bitte erneut versuchen")
            }
            if (!part.renameTo(target)) throw IOException("Texterkennung konnte nicht gespeichert werden")
        }
        Log.i(TAG, "Texterkennung geladen")
    }

    /** Womit zuletzt gerechnet wurde. */
    @Volatile
    var kind: Engine.Kind? = null
        private set

    companion object {
        /** Threads des Rechenkerns für die Texterkennung (Hintergrund-Vorrang, Bild und Ton gehen vor). */
        private const val KERN_THREADS = 2

        private const val TAG = "DnAOcr"
        private const val BASE = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/latin"
        private const val MODEL = "rec.onnx"
        private const val DICT = "dict.txt"
        private val FILES = listOf(MODEL to 7_862_832L, DICT to 1_634L)
        private const val HEIGHT = 48
        private const val MAX_WIDTH = 2_000
        private const val INK = 140
        private const val MAX_GAP = 5
        private const val MIN_LINE = 8
        private const val PAD = 4
    }
}
