package de.ayutv.data.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import de.ayutv.data.Languages
import de.ayutv.data.repo.Translators
import de.ayutv.player.EmbeddedSubtitles

/**
 * Prüft Texterkennung und Übersetzung auf diesem Gerät, ohne dass ein Film läuft: zeigt, ob beide
 * Modelle hier überhaupt rechnen können und wie schnell. Stürzt eines ab, steht der Schritt dabei.
 */
object TranslatorSelfTest {
    suspend fun run(language: String): String {
        val remote = Translators.remote
        val parts = mutableListOf<String>()
        var text = SAMPLE
        try {
            val started = SystemClock.elapsedRealtime()
            text = remote.recognize(EmbeddedSubtitles.GrayImage.of(render(SAMPLE))).ifBlank { SAMPLE }
            parts += "Texterkennung ok: „$text“ (${SystemClock.elapsedRealtime() - started} ms)"
        } catch (e: Exception) {
            parts += "Texterkennung: ${e.message ?: e.javaClass.simpleName}"
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val translated = remote.translate(listOf(text), language, "en").first()
            parts += "Übersetzung ${Languages.label(language)} ok: „$translated“ (${SystemClock.elapsedRealtime() - started} ms, inkl. Laden)"
        } catch (e: Exception) {
            parts += "Übersetzung: ${e.message ?: e.javaClass.simpleName}"
        }
        parts += "Rechenkern: ${remote.engine.label}"
        remote.lastNativeCrash()?.let { parts += "Letzter Absturz: ${it.lines().take(4).joinToString(" | ")}" }
        remote.lastPeakMb?.let { peak -> parts += "Speicher Übersetzer: höchstens $peak MB, belegt ${remote.lastAnonMb ?: "?"} MB" }
        cpu()?.let { parts += "Prozessor: $it" }
        return parts.joinToString("\n")
    }

    /** Prozessor und Befehlssätze (für Fehler, die nur auf bestimmten Chips auftreten). */
    private fun cpu(): String? = runCatching {
        val info = java.io.File("/proc/cpuinfo").readLines()
        fun field(name: String) = info.firstOrNull { it.startsWith(name) }?.substringAfter(':')?.trim()
        listOfNotNull(field("Hardware"), field("model name") ?: field("Processor"), field("CPU part")?.let { "Teil $it" },
            field("Features")?.let { "Features: $it" }).joinToString(" · ")
    }.getOrNull()

    /** Wie ein Blu-ray-Untertitel: weiße Schrift mit schwarzem Rand auf durchsichtigem Grund. */
    private fun render(text: String): Bitmap {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 46f
            color = Color.WHITE
        }
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.BLACK
        }
        val bitmap = Bitmap.createBitmap(fill.measureText(text).toInt() + 30, 70, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawText(text, 15f, 52f, stroke)
            drawText(text, 15f, 52f, fill)
        }
        return bitmap
    }

    private const val SAMPLE = "Where is my brother?"
}
