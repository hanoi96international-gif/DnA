package de.ayutv.data.translate

/**
 * Kurze Zeilen („Ja, ihn.“, „Zum Auto!“) übersetzt das Modell ohne Zusammenhang oft falsch (du/Sie, er/sie/es).
 * Dann wird der vorherige Satz mitübersetzt und dessen Übersetzung vorn wieder abgeschnitten. Passt der Anfang
 * nicht eindeutig zur schon vorhandenen Übersetzung des vorherigen Satzes, bleibt es bei der Übersetzung ohne Zusammenhang.
 */
object SubtitleContext {
    /** Höchstens so viele Wörter: Nur hier lohnt der Zusammenhang (und kostet wenig Rechenzeit). */
    const val SHORT_WORDS = 4

    fun isShort(text: String): Boolean {
        val words = SubtitleSentences.plain(text).split(' ').count { w -> w.any(Char::isLetter) }
        return words in 1..SHORT_WORDS && !SubtitleSentences.isDialog(text)
    }

    /** Mit Zusammenhang: vorheriger Satz und Zeile als ein Text für das Modell. */
    fun joined(previous: String, text: String): String =
        SubtitleSentences.plain(previous).trimEnd() + " " + SubtitleSentences.plain(text).trimStart()

    /**
     * Schneidet die Übersetzung des vorherigen Satzes ([prefix]) vorn von [combined] ab. Verglichen werden nur
     * Buchstaben und Ziffern (das Modell setzt Satzzeichen und Leerzeichen manchmal anders); null, wenn der
     * Anfang nicht passt oder nichts übrig bleibt.
     */
    fun stripPrefix(combined: String, prefix: String): String? {
        val want = prefix.filter(Char::isLetterOrDigit).lowercase()
        if (want.isEmpty()) return null
        var matched = 0
        var cut = -1
        for ((i, c) in combined.withIndex()) {
            if (!c.isLetterOrDigit()) continue
            if (matched == want.length) break
            if (c.lowercaseChar() != want[matched]) return null
            matched++
            if (matched == want.length) cut = i + 1
        }
        if (cut < 0) return null
        // Satzzeichen, die noch zum vorherigen Satz gehören, mit abschneiden.
        val rest = combined.substring(cut).trimStart { it.isWhitespace() || it in ".,;:!?…»”\"'" }.trim()
        return rest.takeIf { r -> r.any(Char::isLetter) }
    }
}
