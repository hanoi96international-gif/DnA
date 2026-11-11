package de.ayutv.data.parse

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Viele Anbieter führen denselben Sender mehrfach: „Sendername“, „Sendername“, „Sendername“, „Sendername“,
 * „Sendername“. In Listen erscheint er einmal, in der besten Fassung; fällt sie aus, springt der Player
 * auf die nächste. Gleich sind Sender derselben Playlist mit gleichem Namenskern (ohne Länder-Vorsatz,
 * Anbieter-Vorsatz und Qualitätsangaben). „Sendername“ und „Sendername“ bleiben verschieden.
 */
object ChannelVariants {
    private val ANY_PREFIX = Regex("""^(?:\s*[^:|]{1,24}?\s*[:|]+\s*)+""")
    private val keys = ConcurrentHashMap<String, String>()
    private const val NONE = "\u0000"

    /** Namenskern oder null, wenn der Name zu wenig hergibt, um sicher zusammenzufassen. */
    fun key(name: String): String? {
        val cached = keys[name]
        if (cached != null) return cached.takeIf { it != NONE }
        val bare = name.replace(ANY_PREFIX, "").ifBlank { name }
        val strong = EpgMatcher.analyse(bare, isId = false).strong
        val key = if (strong.length >= 2 && strong.any { it.isLetter() }) strong else null
        if (keys.size < 200_000) keys[name] = key ?: NONE
        return key
    }

    /** Reihenfolge der Fassungen: Full-HD/HD, unbearbeitet (RAW), 4K, ohne Angabe, SD, Ersatz. */
    fun rank(name: String): Int {
        // „ᴴᴰ“, „ᵁᴴᴰ“ & Co. werden so zu „HD“, „UHD“
        val n = " " + Normalizer.normalize(name, Normalizer.Form.NFKD).lowercase() + " "
        return when {
            BACKUP.containsMatchIn(n) -> 5
            HD.containsMatchIn(n) && !UHD.containsMatchIn(n) -> 0
            RAW.containsMatchIn(n) -> 1
            UHD.containsMatchIn(n) -> 2
            SD.containsMatchIn(n) -> 4
            else -> 3
        }
    }
    private val BACKUP = Regex(Hid.s("GAxpTzU1fC+zdSUCNVE2PWcmsWw3CzNbMSpjP7B9OAwgX30KdSafIXYyaA=="))
    private val HD = Regex(Hid.s("GAxpSzwya2vzMXQ1KF0JaWsypyAYDA=="))
    private val UHD = Regex(Hid.s("GAxpWDwya26odXZfdx0kaT4GoQ=="))
    private val RAW = Regex(Hid.s("GAwzTCMKdQ=="))
    private val SD = Regex(Hid.s("GAxpXjAqIm31Ui0eHBIoYi9qmGA0M34ECDQ="))

    /**
     * Eine Liste mit jedem Sender einmal: an der Stelle seines ersten Auftretens, in seiner besten Fassung.
     * [name] und [group] holen Name und Playlist aus den Einträgen (Sender verschiedener Playlists bleiben getrennt).
     */
    fun <T> merge(items: List<T>, name: (T) -> String, group: (T) -> Long, score: (T) -> Int = { rank(name(it)) }): List<T> {
        val best = LinkedHashMap<Any, T>(items.size)
        for (item in items) {
            val key: Any = key(name(item))?.let { group(item) to it } ?: Any()
            val current = best[key]
            if (current == null || score(item) < score(current)) best[key] = item
        }
        return best.values.toList()
    }

    /** Andere Fassungen desselben Senders aus [candidates], beste zuerst. */
    fun <T> alternatives(
        target: T,
        candidates: List<T>,
        name: (T) -> String,
        group: (T) -> Long,
        same: (T, T) -> Boolean,
        score: (T) -> Int = { rank(name(it)) },
    ): List<T> {
        val key = key(name(target)) ?: return emptyList()
        return candidates.filter { !same(it, target) && group(it) == group(target) && key(name(it)) == key }
            .sortedBy(score)
    }

    /** Gruppen von Fassungen desselben Senders (nur Sender mit mehr als einer Fassung). */
    fun <T> groups(items: List<T>, name: (T) -> String, group: (T) -> Long): List<List<T>> =
        items.groupBy { item -> key(name(item))?.let { group(item) to it } }.filterKeys { it != null }.values.filter { it.size > 1 }
}
