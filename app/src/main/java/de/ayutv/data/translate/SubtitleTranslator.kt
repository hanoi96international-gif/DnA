package de.ayutv.data.translate

import android.content.Context
import de.ayutv.data.repo.Translator

/**
 * Übersetzt Untertitel-Sätze mit zwei Hilfen um den eigentlichen Übersetzer herum:
 * Namen, die zugleich Wörter sind, bleiben Namen ([TranslationNames]), und kurze Zeilen bekommen den
 * vorherigen Satz als Zusammenhang mit ([SubtitleContext]). Geht eine Hilfe schief, gilt die einfache Übersetzung.
 */
class SubtitleTranslator(private val base: Translator, private val names: Set<String>) {
    val name: String get() = base.name

    /**
     * Übersetzt [texts] (Sätze in ihrer Reihenfolge). [before]: der Satz direkt vor dem ersten mit seiner schon
     * fertigen Übersetzung, als Zusammenhang für eine kurze erste Zeile.
     */
    suspend fun translate(texts: List<String>, target: String, source: String?, before: Pair<String, String>? = null): List<String> {
        if (texts.isEmpty()) return emptyList()
        val out = translateProtected(texts, target, source).toMutableList()
        // Zusammenhang: kurze Zeilen mit dem vorherigen Satz zusammen übersetzen, dessen Übersetzung vorn abschneiden.
        val previous = texts.indices.map { i -> if (i == 0) before else texts[i - 1] to out[i - 1] }
        val short = texts.indices.filter { i ->
            val p = previous[i]
            p != null && p.first.isNotBlank() && p.second.isNotBlank() && SubtitleContext.isShort(texts[i]) && !SubtitleContext.isShort(p.first)
        }
        if (short.isEmpty()) return out
        val combined = runCatching {
            translateProtected(short.map { SubtitleContext.joined(previous[it]!!.first, texts[it]) }, target, source, fallback = false)
        }.getOrNull() ?: return out
        short.forEachIndexed { k, i ->
            val whole = combined.getOrNull(k)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val rest = SubtitleContext.stripPrefix(whole.replace('\n', ' '), previous[i]!!.second.replace('\n', ' ')) ?: return@forEachIndexed
            out[i] = SubtitleSentences.wrap(rest)
        }
        return out
    }

    /** Mit geschützten Namen; Zeilen, bei denen das schiefgeht, ohne Schutz (oder mit [fallback] = false: leer). */
    private suspend fun translateProtected(texts: List<String>, target: String, source: String?, fallback: Boolean = true): List<String> {
        val protected = texts.map { TranslationNames.protect(it, names) }
        if (protected.all { it.names.isEmpty() }) return base.translate(texts, target, source)
        val raw = base.translate(protected.map { it.text }, target, source)
        val out = texts.indices.map { i -> raw.getOrNull(i)?.let { TranslationNames.restore(it, protected[i]) } }.toMutableList()
        val failed = out.indices.filter { out[it] == null }
        if (failed.isNotEmpty()) {
            if (!fallback) failed.forEach { out[it] = "" } else {
                val plain = base.translate(failed.map { texts[it] }, target, source)
                failed.forEachIndexed { k, i -> out[i] = plain.getOrNull(k) ?: texts[i] }
            }
        }
        return out.map { it.orEmpty() }
    }
}

/** Gelernte Namen je Serie (oder Film), damit auch die nächste Folge sie von der ersten Zeile an kennt. */
class SeriesNames(context: Context) {
    private val prefs = context.getSharedPreferences("translation_names", Context.MODE_PRIVATE)

    fun get(key: String): Set<String> = TranslationNames.decode(prefs.getString(key, null).orEmpty())

    /** Neue Namen ergänzen; gibt alle bekannten zurück. */
    fun add(key: String, names: Set<String>): Set<String> {
        val known = get(key)
        val all = known + names
        if (all.size != known.size) prefs.edit().putString(key, TranslationNames.encode(all)).apply()
        return all
    }
}
