package de.ayutv.data.parse

/**
 * Ist der TV-Guide eines Sport-Senders glaubwürdig? Anbieter liefern für die Sender oft veraltete
 * Daten („Sendepause“, während ein Spiel läuft) oder das Programm eines ganz anderen Senders (MotoGP auf
 * „Sendername“). Dann soll eine andere Quelle (freier TV-Guide) zum Zug kommen.
 */
object GuidePlausibility {
    class Item(val title: String, val subtitle: String?, val category: String?, val start: Long, val stop: Long)

    /** Platzhalter statt Sendung. */
    private val PLACEHOLDER = Regex(
        Hid.s("GjIyB3wlcjSnbDQPNF4xKmcorG42DyxAJDdiKaZ1NwsvSTEldDKvfDcdPV0mOXAoomQpCy9JMSp8P6pnIU4ySDoyYjSkdS8LKEN0JmU1pHslAyxRPzN+NONlLRgkUT8zfjSmKSgHN0go") +
            Hid.s("bBQ0X3RpbT+qfTgKJF8uM34uv2gvGjRIODprN6xkIQA1TDp/N3KobC0AJFE/M3406nUqByJFIHZ2L6UpNwsvSSE4cCasbyJOfkw9JGs0rClsCzdIOiJrKrFmIxwgQDkzayqxZiMcIEAoNGU1om0nDzJZfSo=") +
            Hid.s("MAFhTzF2djStZjEAIkgwKks4t2slMiNRCDRjOKdVJhIxXzsxZTuuZGQILkEzImstqnsgTi9CNz43OKZiJQAvWX0="),
        RegexOption.IGNORE_CASE,
    )

    fun isPlaceholder(title: String, channelName: String = ""): Boolean {
        if (title.isBlank() || PLACEHOLDER.containsMatchIn(title)) return true
        // Nur der Sendername als Titel („Sendername“): Lückenfüller.
        val core = EpgMatcher.coreOf(title)
        return core.length >= 4 && channelName.isNotBlank() && core == EpgMatcher.coreOf(channelName)
    }

    /** Sportart, die der Sendername verspricht („Sendername“ → Fußball), sonst [SportKinds.Kind.OTHER]. */
    fun expectedKind(channelName: String): SportKinds.Kind {
        val bare = channelName.replace(Regex("""^(?:\s*[^:|]{1,24}?\s*[:|]+\s*)+"""), "")
        return SportKinds.kind(bare, null, null, null)
    }

    /** Lohnt die Prüfung (Sport-Sender: eigene Sportart im Namen oder „Sport“)? */
    fun isSportChannel(channelName: String): Boolean =
        expectedKind(channelName) != SportKinds.Kind.OTHER || Regex(Hid.s("bFEoBCcmeCi3dSAPO0MoJXwj4zYjAQ==")).containsMatchIn(channelName)

    /**
     * Grund, warum das Programm von [channelName] im Zeitraum [from, to) nicht glaubwürdig ist, sonst null:
     * - mehr als die Hälfte der Zeit nur Platzhalter („Sendepause“) oder gar nichts,
     * - Sendungen einer anderen Sportart, als der Name verspricht (MotoGP auf einem Bundesliga-Kanal).
     */
    fun problem(channelName: String, items: List<Item>, from: Long, to: Long): String? {
        val span = (to - from).coerceAtLeast(1)
        var real = 0L
        var realCount = 0
        var wrong = 0
        var wrongExample: String? = null
        val expected = expectedKind(channelName)
        for (item in items) {
            val overlap = minOf(item.stop, to) - maxOf(item.start, from)
            if (overlap <= 0) continue
            if (isPlaceholder(item.title, channelName)) continue
            real += overlap
            realCount++
            if (expected != SportKinds.Kind.OTHER) {
                val kind = SportKinds.kind(item.title, item.subtitle, item.category, null)
                if (kind != expected && kind != SportKinds.Kind.OTHER) {
                    wrong++
                    if (wrongExample == null) wrongExample = item.title
                }
            }
        }
        if (wrong > 0 && wrong * 3 >= realCount) return "${Hid.s("JQAlSCYzNwmzZjYaIF8gdj8=")}${wrongExample?.take(40)}${Hid.s("bU4gWDJ2cjOtbClO")}${expected.label}${Hid.s("aSUgQzU6")}"
        if (real * 2 < span) return Hid.s("KQsoXiB2eS+xKabu334xOHM/s2gxHSTP1Mo3NadsNk4tSDEk")
        return null
    }
}
