package de.ayutv.data.parse

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Event-Sender (PPV, „Sendername“, „Sendername“): Viele Anbieter schreiben das Spiel direkt in den Sendernamen, einen
 * TV-Guide gibt es für diese Sender nicht. Daraus wird ein Programmeintrag, damit das Spiel im TV-Guide, unter
 * „Jetzt läuft“ und im Bereich Sport erscheint:
 *
 * - `LIVE | SV MEPPEN VS FC INGOLSTADT | Sun 01 Nov 11:00 CET (DE) | 8K EXCLUSIVE | DE: LEAGUES FOOTBALL PPV 1`
 * - `Next | FC St. Pauli vs. 1. FC Nürnberg | all | 01-11-2026 | 13:00 (GMT) | 8K EXCLUSIVE | DE: SOCCER PPV 21`
 * - „Sendername“
 * - `Live | Snooker: International Championship, 1. Runde | all | 8K EXCLUSIVE | DE: SOCCER PPV 10` (ohne Uhrzeit)
 *
 * Beendete Events und „NO EVENT STREAMING“ liefern nichts. Rein Kotlin, für Fire TV und Windows.
 */
object EventNames {
    enum class State { LIVE, NEXT }

    data class Event(val title: String, val start: Long, val stop: Long, val state: State)

    /** Dauer, wenn der Name nur den Beginn nennt. */
    const val DEFAULT_MS = 150 * 60_000L
    private const val STALE_LIVE_MS = 6 * 60 * 60_000L

    private val STATE = Regex(Hid.s("GkYtRCIzazSmcTASNF03OXozrW44HS5COipyNKd1IQAlSDAqcTOtYDcGJEl9CnU="), RegexOption.IGNORE_CASE)
    private val DAY_MONTH_TIME = Regex(Hid.s("GkZ+Fw8XOgCiJD4zOh94ZWoG7TYYHWoEa35LPrg4aFw8BAglPHKYSGk0IAAulZOZZ8rSrffuyJWrB7g6aBNocXppSynoIRgKOhx4ZGpz+SEYCjofKX8/ZflVN0VpdhV7TQe4O2haPAR9aQ=="))
    private val DMY = Regex("""^(\d{1,2})[-./](\d{1,2})[-./](\d{4})$""")
    private val YMD = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val TIME = Regex("""^(\d{1,2}):(\d{2})(?:\s*\(?([A-Z]{2,4})\)?)?""")
    private val NOISE = Regex(Hid.s("bFEoBAp+djavdSscKEo9OHY2t2YqEh1Jfz03P7tqKBsyRCIzaz+7aigbMkQiM2syp3UxBiVRYD1rYqggYA=="))
    private val MONTHS = mapOf(
        Hid.s("Lg8v") to 1, Hid.s("Igsj") to 2, Hid.s("KQ8z") to 3, Hid.s("Ka3lXw==") to 3, Hid.s("JR4z") to 4, Hid.s("KQ84") to 5, Hid.s("KQ8o") to 5, Hid.s("Lhsv") to 6, Hid.s("Lhst") to 7,
        Hid.s("JRsm") to 8, Hid.s("Nwsx") to 9, Hid.s("Kw01") to 10, Hid.s("KwU1") to 10, Hid.s("KgE3") to 11, Hid.s("IAsi") to 12, Hid.s("IAs7") to 12,
    )
    private val ZONES = mapOf(Hid.s("BysV") to 1, Hid.s("CSsb") to 1, Hid.s("BysSeQ==") to 2, Hid.s("CSsSdw==") to 2, Hid.s("AyMV") to 0, Hid.s("EToC") to 0, Hid.s("Bj0V") to 1, Hid.s("EysV") to 0, Hid.s("ASsV") to 2)

    /** Trennzeile der Playlist („Sendername“): kein Sender, braucht kein Programm. */
    fun isSeparator(name: String): Boolean {
        val n = name.trim()
        return n.length >= 4 && Regex("""^[#=*~_\-–—|•]{3,}""").containsMatchIn(n)
    }

    private val QUALITY = setOf("4k", "8k", Hid.s("MQYl"), Hid.s("IgYl"), Hid.s("LAo="), Hid.s("Nwo="), Hid.s("Ng82"), Hid.s("LAs3Tg=="), "h265", "2160p", "3840p", "1080p", "720p", Hid.s("cV4nXSc="), Hid.s("cl4nXSc="))

    /**
     * Kern des eigentlichen Sendernamens, ohne Qualität: „Sendername“ und „Sendername“ ergeben
     * beide „dynppv2“. So bekommt die 4K-/UHD-Fassung eines Event-Senders das Spiel aus dem Namen ihrer Schwester.
     * Null für zu kurze oder allgemeine Namen.
     */
    fun baseKey(name: String): String? {
        val last = name.substringAfterLast('|')
        val plain = java.text.Normalizer.normalize(last, java.text.Normalizer.Form.NFKD).lowercase(Locale.ROOT)
            .replace(Regex("""^\s*[a-z0-9]{1,6}\s*:\s*"""), "")
        val tokens = plain.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() && it !in QUALITY }
        // Nur nummerierte Event-Sender („Sendername“, „Sendername“): sonst bekäme „Sendername“ das Spiel eines Kanals.
        if (tokens.size < 2 || tokens.last().toIntOrNull() == null) return null
        return tokens.joinToString("")
    }

    private val ROUND_THE_CLOCK = Regex("""\b24\s*/\s*7\b""")
    private val EVENT_ONLY = Regex(Hid.s("bFEoBAg0Zyq1VSYSHXYIJT02qn8hNWwNCWlyLKZnMDIyBwkqS3Kfem5GLUQiM0x341R7R35IIjN5LuspKwAtVH1pSynpVW0="))

    /**
     * Warum ein Sender ohne TV-Guide gar keinen haben kann (für den Bericht, nicht als „fehlt“ gezählt), oder null,
     * wenn er wirklich fehlt: Dauerschleifen („24/7“), Event-Sender ohne laufendes oder angekündigtes Spiel.
     */
    fun noGuideReason(name: String, group: String): String? = when {
        isSeparator(name) -> Hid.s("EBwkQzoscjOvbCo=")
        STATE.containsMatchIn(name.trim()) || isIdle(name) -> Hid.s("ARgkQyB7RD+tbSEcYUI8OHJ6r2gxCCRDMDNkepB5LQst")
        ROUND_THE_CLOCK.containsMatchIn(group) || ROUND_THE_CLOCK.containsMatchIn(name) -> Hid.s("dlpuGnkFcjSnbDZOaWk1I3IosGosAiREMjM+")
        EVENT_ONLY.containsMatchIn(name) || EVENT_ONLY.containsMatchIn(group) -> Hid.s("ARgkQyB7RD+tbSEcYUI8OHJ6kHktCy1MOjF2OKY=")
        else -> null
    }

    /** Event-Sender ohne aktuelles Event („- NO EVENT STREAMING -“): leer, braucht kein Programm. */
    fun isIdle(name: String): Boolean = Regex(Hid.s("bFEoBDo5Nz+1bCoaaQ0nImU/omQtACYEayp8P6pnZAs3SDoiazWlbygHL0g=")).containsMatchIn(name)

    /**
     * Event aus dem Namen; null, wenn der Name keines nennt (normaler Sender, beendet, ohne Event).
     * [now] bestimmt das Jahr, wenn der Name keines nennt, und den Beginn bei „LIVE“ ohne Uhrzeit.
     */
    fun parse(name: String, now: Long): Event? {
        val parts = name.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val state = STATE.find(parts[0])?.groupValues?.get(1)?.lowercase() ?: return null
        if (state.startsWith(Hid.s("IQAl")) || state == Hid.s("IgcvRCc+cj4=")) return null
        val live = state == Hid.s("KAc3SA==")
        var start: Long? = null
        val title = ArrayList<String>()
        var i = 1
        while (i < parts.size) {
            val part = parts[i]
            DAY_MONTH_TIME.find(part)?.let { m ->
                val month = MONTHS[m.groupValues[2].lowercase().take(3)]
                if (month != null) {
                    start = at(nearestYear(month, m.groupValues[1].toInt(), now), month, m.groupValues[1].toInt(),
                        m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5])
                    break
                }
            }
            val dmy = DMY.find(part)
            val ymd = YMD.find(part)
            if (dmy != null || ymd != null) {
                val (y, mo, d) = if (dmy != null) Triple(dmy.groupValues[3].toInt(), dmy.groupValues[2].toInt(), dmy.groupValues[1].toInt())
                else Triple(ymd!!.groupValues[1].toInt(), ymd.groupValues[2].toInt(), ymd.groupValues[3].toInt())
                val time = parts.getOrNull(i + 1)?.let { TIME.find(it) }
                if (time != null) start = at(y, mo, d, time.groupValues[1].toInt(), time.groupValues[2].toInt(), time.groupValues[3])
                break
            }
            // Ab hier nur noch Technik („8K EXCLUSIVE“) und der eigentliche Sendername („Sendername“).
            if (part.contains(':') && Regex(Hid.s("bFEoBAg0Zyq1VSYSH3Y1e20HuDs5MjIHbg==")).containsMatchIn(part) && i > 1) break
            if (!NOISE.matches(part)) title += part
            i++
        }
        val text = title.joinToString(" – ").replace(Regex("""\s+"""), " ").trim()
        if (text.length < 3) return null
        val begin = start ?: if (live) now - 30 * 60_000L else return null
        // Läuft laut Name, aber der Beginn liegt in der Zukunft (Uhr des Anbieters) oder ist uralt (Name nicht
        // aktualisiert, „LIVE … Fri 23 Oct“): ab jetzt, statt einen tagelangen Block in den TV-Guide zu setzen.
        val from = if (live && (begin > now || now - begin > STALE_LIVE_MS)) now - 30 * 60_000L else begin
        return Event(text, from, maxOf(from + DEFAULT_MS, if (live) now + 60 * 60_000L else 0L), if (live) State.LIVE else State.NEXT)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: String?): Long? = runCatching {
        val offset = ZONES[zone?.uppercase(Locale.ROOT)] ?: 1 // ohne Angabe: deutsche Zeit (Winter)
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.ofHours(offset)).toEpochMilli()
    }.getOrNull()

    /** Jahr zu Tag und Monat ohne Jahresangabe: das, bei dem das Datum am nächsten an [now] liegt (Jahreswechsel). */
    private fun nearestYear(month: Int, day: Int, now: Long): Int {
        val today = java.time.Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate()
        return listOf(today.year - 1, today.year, today.year + 1).minByOrNull { y ->
            runCatching { kotlin.math.abs(LocalDate.of(y, month, day).toEpochDay() - today.toEpochDay()) }.getOrDefault(Long.MAX_VALUE)
        } ?: today.year
    }
}
