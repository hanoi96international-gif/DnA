package de.ayutv.data.parse

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class XmltvProgramme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val subtitle: String? = null,
    val icon: String? = null,
    /** de.ayutv.data.db.ProgrammeFlags */
    val flags: Int = 0,
)

/**
 * Streaming-Parser für XMLTV. [wanted] filtert Programme früh heraus (unbekannte Sender,
 * Zeitraum außerhalb des Fensters), damit nur das in die Datenbank kommt, was angezeigt wird.
 */
class XmltvParser(private val parser: XmlPullParser) {
    private companion object {
        // Gleiche Werte wie de.ayutv.data.db.ProgrammeFlags (der Parser bleibt ohne Datenbank-Abhängigkeit).
        const val REPEAT = 1
        const val LIVE = 2
        const val PREMIERE = 4
    }

    /** Sender des EPGs (Kennung → Anzeigenamen), um Sender der Playlist zuzuordnen ([EpgMatcher]). */
    val channels = LinkedHashMap<String, MutableList<String>>()

    /** Senderlogo laut EPG (`<channel><icon src>`), für Sender, deren Playlist kein Logo hat. */
    val channelIcons = HashMap<String, String>()

    fun parse(
        input: InputStream,
        wanted: (channelId: String, start: Long, stop: Long) -> Boolean,
        onProgramme: (XmltvProgramme) -> Unit,
    ) {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Viele EPGs enthalten "&nbsp;" oder ein loses "&": tolerant lesen statt den ganzen Import abzubrechen.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(input, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel()
                    "programme" -> readProgramme(wanted)?.let(onProgramme)
                }
            }
            event = parser.next()
        }
    }

    private fun readChannel() {
        val id = parser.getAttributeValue(null, "id")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val names = channels.getOrPut(id) { ArrayList(2) }
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                val name = readText()
                if (name.isNotBlank()) names += name.trim()
            } else if (event == XmlPullParser.START_TAG && parser.name == "icon" && id !in channelIcons) {
                parser.getAttributeValue(null, "src")?.trim()?.takeIf { it.startsWith("http") }?.let { channelIcons[id] = it }
            }
        }
    }

    private fun readProgramme(wanted: (String, Long, Long) -> Boolean): XmltvProgramme? {
        val channel = parser.getAttributeValue(null, "channel")?.trim()
        val start = parser.getAttributeValue(null, "start")?.let(XmltvTime::parse)
        val stop = parser.getAttributeValue(null, "stop")?.let(XmltvTime::parse)
        if (channel == null || start == null || stop == null || !wanted(channel, start, stop)) {
            skipElement()
            return null
        }

        var title: String? = null
        var subtitle: String? = null
        var icon: String? = null
        var flags = 0
        var description: String? = null
        var category: String? = null
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (title == null) title = readText()
                    "sub-title" -> if (subtitle == null) subtitle = readText()
                    "icon" -> {
                        if (icon == null) icon = parser.getAttributeValue(null, "src")?.trim()?.takeIf { it.startsWith("http") }
                        skipElement()
                    }
                    "previously-shown" -> { flags = flags or REPEAT; skipElement() }
                    "live" -> { flags = flags or LIVE; skipElement() }
                    "premiere", "new" -> { flags = flags or PREMIERE; skipElement() }
                    "desc" -> if (description == null) description = readText()
                    "category" -> if (category == null) category = readText()
                    else -> skipElement()
                }
            }
        }
        return XmltvProgramme(
            channel, start, stop, title?.trim().orEmpty(), description?.trim(), category?.trim(),
            subtitle?.trim()?.takeIf { it.isNotEmpty() },
            icon,
            flags,
        )
    }

    /** Liest den Text eines Elements und steht danach auf dessen END_TAG. */
    private fun readText(): String {
        val builder = StringBuilder()
        val depth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> builder.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.depth == depth) return builder.toString()
                XmlPullParser.END_DOCUMENT -> return builder.toString()
            }
        }
    }

    private fun skipElement() {
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
        }
    }
}

/** XMLTV-Zeitstempel wie "20240101203000 +0100" ohne java.time (nicht auf allen Fire-OS-Versionen da). */
object XmltvTime {
    fun parse(value: String): Long? {
        val text = value.trim()
        if (text.length < 12) return null
        val year = text.number(0, 4) ?: return null
        val month = text.number(4, 2) ?: return null
        val day = text.number(6, 2) ?: return null
        val hour = text.number(8, 2) ?: return null
        val minute = text.number(10, 2) ?: return null
        // Sekunden sind optional ("202401012030 +0100"); die Zeitzone folgt nach den Ziffern.
        var digits = 8
        while (digits < text.length && text[digits].isDigit()) digits++
        val second = if (digits >= 14) text.number(12, 2) ?: 0 else 0

        var offsetMinutes = 0
        val rest = text.substring(digits).trim()
        if (rest.length >= 3 && (rest[0] == '+' || rest[0] == '-')) {
            val hours = rest.number(1, 2) ?: 0
            // "+0100" und "+01:00"
            val minuteText = rest.substring(3).removePrefix(":")
            val minutes = if (minuteText.length >= 2) minuteText.number(0, 2) ?: 0 else 0
            offsetMinutes = (hours * 60 + minutes) * if (rest[0] == '-') -1 else 1
        }

        val days = daysFromCivil(year, month, day)
        val seconds = days * 86_400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1000L
    }

    private fun String.number(start: Int, length: Int): Int? {
        if (start + length > this.length) return null
        var result = 0
        for (i in start until start + length) {
            val digit = this[i] - '0'
            if (digit !in 0..9) return null
            result = result * 10 + digit
        }
        return result
    }

    /** Tage seit 1970-01-01 (Algorithmus von Howard Hinnant). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
