package de.ayutv.player

import android.content.Context
import de.ayutv.data.db.HistoryKind
import java.io.File

/**
 * 4K-Test: ein mitgelieferter Clip wie bei echten UHD-Sendern (3840×2160, HEVC Main 10, HDR10, 50 Bilder/s) läuft im
 * normalen Player. Am Ende steht, welcher Decoder lief und wie viele Bilder gezeigt bzw. verworfen wurden – so sieht man
 * auf Handy, Fire TV und Co. in Sekunden, ob 4K hier flüssig läuft.
 */
object UhdSelfTest {
    const val KEY = "selftest:4k"
    private const val ASSET = "selftest/uhd50.ts"
    private const val PREFS = "selftest"
    private const val RESULT = "uhd_result"
    /** So viele Bilder hat der Clip (8 s × 50). */
    const val FRAMES = 400

    fun isTest(key: String?) = key == KEY

    fun start(context: Context) {
        val file = File(context.cacheDir, "uhd50.ts")
        if (file.length() == 0L) context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        val request = VodRequest(KEY, HistoryKind.VOD, "4K-Test · HEVC 10 Bit · HDR10 · 50 Bilder/s", null, null, file.absolutePath)
        context.startActivity(PlayerActivity.vodIntent(context, request).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Ergebnis als Satz; [ok] = 4K lief flüssig (höchstens 5 % der Bilder verworfen). */
    fun result(height: Int, decoder: String, rendered: Int, dropped: Int): Pair<Boolean, String> {
        val total = rendered + dropped
        val ok = height >= 2000 && rendered > 0 && dropped <= total / 20
        val text = when {
            height < 2000 || rendered == 0 -> "✗ 4K läuft hier nicht (kein Bild${decoder.takeIf { it.isNotBlank() }?.let { ", Decoder $it" }.orEmpty()})"
            ok -> "✓ 4K läuft flüssig: $rendered von $total Bildern gezeigt · Decoder $decoder"
            else -> "⚠ 4K läuft, aber nicht flüssig: $rendered gezeigt, $dropped verworfen · Decoder $decoder"
        }
        return ok to text
    }

    fun save(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RESULT, text).apply()
    }

    fun lastResult(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RESULT, null)
}
