package de.ayutv.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Protokoll im Datenordner (`%APPDATA%\DnA-TV\logs\dna-tv.log`): Start, Fehler, Abstürze, Stream-Probleme.
 * Wird bei 2 MB umbenannt (eine ältere Datei bleibt). Nichts Vertrauliches: Adressen mit Zugangsdaten werden gekürzt.
 */
object Log {
    private const val MAX_BYTES = 2_000_000L
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    @Volatile private var file: File? = null

    val current: File? get() = file

    fun init(dataDir: File) {
        val dir = File(dataDir, "logs").apply { mkdirs() }
        val target = File(dir, "dna-tv.log")
        if (target.length() > MAX_BYTES) {
            val old = File(dir, "dna-tv.1.log")
            old.delete()
            target.renameTo(old)
        }
        file = File(dir, "dna-tv.log")
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> error("Absturz in ${thread.name}", error) }
        info("Start ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
        runCatching { collectCrashFiles(dir) }
    }

    /**
     * Harte Abstürze (Java selbst oder VLC) landen nicht im Protokoll, sondern als „hs_err_pid….log“ im Temp- oder
     * Programmordner. Beim nächsten Start in den Protokollordner holen (höchstens die letzten 3), damit sie im
     * Fehlerbericht mitkommen.
     */
    fun collectCrashFiles(dir: File, now: Long = System.currentTimeMillis()) {
        val places = listOfNotNull(System.getProperty("java.io.tmpdir"), System.getProperty("user.dir")).map(::File)
        val found = places.flatMap { place -> place.listFiles { f -> f.isFile && f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }.orEmpty().toList() }
            .filter { now - it.lastModified() < 14 * 24 * 3_600_000L }
        for (crash in found) {
            val name = "absturz-" + java.time.Instant.ofEpochMilli(crash.lastModified()).atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".log"
            val target = File(dir, name)
            if (runCatching { crash.copyTo(target, overwrite = true); crash.delete() }.isSuccess) {
                // Erste Zeilen sagen, wo es passiert ist (z. B. „libvlc.dll“).
                val where = target.useLines { lines -> lines.firstOrNull { "Problematic frame" in it || it.startsWith("# C ") || it.startsWith("# j ") } }
                warn("Programmabsturz beim letzten Mal (${target.name})${where?.let { ": " + it.removePrefix("#").trim() } ?: ""}")
            }
        }
        dir.listFiles { f -> f.name.startsWith("absturz-") }.orEmpty().sortedByDescending { it.name }.drop(3).forEach { it.delete() }
    }

    fun info(message: String) = write("INFO", message, null)
    fun warn(message: String, error: Throwable? = null) = write("WARN", message, error)
    fun error(message: String, error: Throwable? = null) = write("FEHLER", message, error)

    @Synchronized
    private fun write(level: String, message: String, error: Throwable?) {
        val target = file ?: return
        val line = buildString {
            append(LocalDateTime.now().format(TIME)).append(' ').append(level).append(' ').append(redact(message))
            if (error != null) {
                append('\n')
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                append(redact(trace.lines().take(40).joinToString("\n")))
            }
            append('\n')
        }
        runCatching { target.appendText(line) }
    }

    /** Zugangsdaten aus Adressen entfernen (username=…, password=…, /live/<user>/<pass>/). */
    fun redact(text: String): String = text
        .replace(Regex("""(?i)(username|password|pass|user)=[^&\s]+"""), "$1=***")
        .replace(Regex("""/(live|movie|series)/[^/\s]+/[^/\s]+/"""), "/$1/***/***/")
}
