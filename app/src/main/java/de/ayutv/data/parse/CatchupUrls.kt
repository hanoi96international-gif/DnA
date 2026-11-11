package de.ayutv.data.parse

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Adressen für das Archiv (Catch-up / zeitversetzt) eines Senders, ohne Android- und Datenbank-Abhängigkeit
 * (auch für die Windows-App). Unterstützt die gängigen Arten: Xtream (`/timeshift/…`),
 * M3U `catchup="default|append|shift|flussonic|xc"` mit `catchup-source` und den üblichen Platzhaltern
 * (`{utc}`, `${start}`, `{lutc}`, `{duration}`, `{Y}-{m}-{d}` …).
 */
object CatchupUrls {
    /**
     * Archiv-Adresse oder null, wenn der Sender kein Archiv hat oder die Sendung zu alt ist.
     * [xtreamPlaylist]: der Sender stammt aus einem Xtream-Zugang; [timezone]: Zeitzone des Servers (Xtream).
     */
    fun url(
        channelUrl: String,
        catchupDays: Int,
        catchupType: String?,
        catchupSource: String?,
        xtreamPlaylist: Boolean,
        timezone: String?,
        start: Long,
        stop: Long,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (catchupDays <= 0 || start >= now) return null
        if (now - start > catchupDays * DAY) return null
        val end = minOf(stop, now)
        val type = catchupType ?: if (xtreamPlaylist || XTREAM_LIVE.containsMatchIn(channelUrl)) "xc" else "default"
        val source = catchupSource
        return when (type) {
            "xc", "xtream" -> xtream(channelUrl, start, end, timezone)
            "append" -> source?.let { channelUrl + expand(it, start, end, now) }
            "shift", "timeshift" -> channelUrl + (if ('?' in channelUrl) "&" else "?") + "utc=${start / 1000}&lutc=${now / 1000}"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(channelUrl, start, end)
            else -> when {
                source == null -> xtream(channelUrl, start, end, timezone)
                source.startsWith("http", ignoreCase = true) -> expand(source, start, end, now)
                else -> channelUrl + expand(source, start, end, now)
            }
        }
    }

    /** `…/live/user/pass/123.ts` → `…/timeshift/user/pass/<Minuten>/<yyyy-MM-dd:HH-mm>/123.ts` (in Serverzeit). */
    fun xtream(liveUrl: String, start: Long, end: Long, timezone: String?): String? {
        val match = XTREAM_LIVE.find(liveUrl) ?: return null
        val (base, user, pass, id, ext) = match.destructured
        val minutes = ((end - start) / 60_000L).coerceAtLeast(1)
        val format = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = timezone?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
        }
        return "$base/timeshift/$user/$pass/$minutes/${format.format(Date(start))}/$id.${ext.ifEmpty { "ts" }}"
    }

    /** Flussonic: `…/index.m3u8` → `…/archive-<start>-<dauer>.m3u8`, `…/mpegts` → `…/timeshift_abs-<start>.ts`. */
    fun flussonic(url: String, start: Long, end: Long): String {
        val startSec = start / 1000
        val duration = (end - start) / 1000
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val dir = base.substringBeforeLast('/')
        return when {
            base.endsWith(".m3u8") -> "$dir/archive-$startSec-$duration.m3u8$query"
            else -> "$dir/timeshift_abs-$startSec.ts$query"
        }
    }

    /** Platzhalter ersetzen; `{duration:60}` teilt (Minuten), `{utc:Y-m-d}` formatiert. */
    fun expand(template: String, start: Long, end: Long, now: Long): String {
        val startSec = start / 1000
        val endSec = end / 1000
        val nowSec = now / 1000
        return PLACEHOLDER.replace(template) { m ->
            val raw = m.groupValues[1]
            val name = raw.lowercase()
            val arg = m.groupValues[2].takeIf { it.isNotEmpty() }
            // Datumsfelder unterscheiden Groß/Klein: {m} = Monat, {M} = Minute.
            if (raw in DATE_FIELDS) return@replace dateFormat(raw, start)
            val value: String? = when (name) {
                "utc", "start", "timestamp" -> if (arg != null && !arg.all(Char::isDigit)) dateFormat(arg, start) else startSec.toString()
                "utcend", "end" -> if (arg != null && !arg.all(Char::isDigit)) dateFormat(arg, end) else endSec.toString()
                "lutc", Hid.s("KgE2") -> nowSec.toString()
                "duration" -> ((endSec - startSec) / (arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()
                "offset" -> ((nowSec - startSec) / (arg?.toLongOrNull()?.takeIf { it > 0 } ?: 1)).toString()
                else -> null
            }
            value ?: m.value
        }
    }

    /** Einzelne Felder ({Y}, {m}, {d}, {H}, {M}, {S}) oder Muster wie "Y-m-d:H-M" in UTC. */
    private fun dateFormat(pattern: String, time: Long): String {
        val javaPattern = buildString {
            for (c in pattern) append(
                when (c) {
                    'Y' -> "yyyy"; 'm' -> "MM"; 'd' -> "dd"; 'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"
                    else -> if (c.isLetter()) "'$c'" else c.toString()
                }
            )
        }
        return SimpleDateFormat(javaPattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
    }

    private const val DAY = 24 * 3_600_000L
    private val DATE_FIELDS = setOf("Y", "m", "d", "H", "M", "S")
    private val XTREAM_LIVE = Regex("""^(https?://[^/]+(?:/[^/]+)*?)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.(\w+))?$""")
    private val PLACEHOLDER = Regex("""\$?\{(\w+)(?::([^}]*))?\}""")
}
