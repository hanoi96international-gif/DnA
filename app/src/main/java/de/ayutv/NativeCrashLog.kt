package de.ayutv

import java.io.File

/**
 * Liest nach einem nativen Absturz die Zeilen aus dem eigenen Systemprotokoll (das darf jede App für
 * sich selbst): Signal, Abbruchgrund und die obersten Stellen der Aufrufkette. So ist auf einem Foto
 * zu sehen, in welcher Bibliothek und Funktion es passiert ist.
 */
object NativeCrashLog {
    private const val FILE = "native_crash.txt"
    private val KEEP = listOf("Fatal signal", "Abort message", "Cause:", "signal ", "fault addr", "libc++abi", "terminating", "Scudo", "CHECK", "#0", "#1")

    /** Gefundene Zeilen (gekürzt) oder null. Speichert sie auch für den nächsten Selbsttest. */
    fun capture(filesDir: File): String? {
        val lines = read(listOf("-b", "main", "-b", "crash")) ?: read(listOf("-b", "main")) ?: return null
        val fatal = lines.indexOfLast { "Fatal signal" in it }
        if (fatal < 0) return null
        val window = lines.subList((fatal - 8).coerceAtLeast(0), (fatal + 60).coerceAtMost(lines.size))
        val picked = window.filter { line -> KEEP.any { it in line } }
            .map(::shorten)
            .distinct()
            .take(14)
        if (picked.isEmpty()) return null
        val text = picked.joinToString("\n")
        runCatching { File(filesDir, FILE).writeText(text) }
        return text
    }

    /** Zuletzt gespeicherter Absturz (für den Selbsttest), oder null. */
    fun last(filesDir: File): String? = runCatching { File(filesDir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    /** Kurzform für eine Statuszeile: Signal und oberste Stelle. */
    fun summary(text: String): String {
        val signal = Regex("""signal \d+ \(\w+\)(, code -?\d+ \(\w+\))?""").find(text)?.value
        val frame = text.lines().firstOrNull { "#00" in it }?.substringAfter("#00")?.trim()
        return listOfNotNull(signal, frame).joinToString(" · ")
    }

    private fun read(buffers: List<String>): List<String>? = runCatching {
        val process = ProcessBuilder(listOf("logcat", "-d", "-v", "brief", "-t", "4000") + buffers)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        out.takeIf { process.exitValue() == 0 && it.isNotEmpty() }
    }.getOrNull()

    /** Lange Pfade kürzen: "/data/app/~~x/de.ayutv-y/lib/arm/libonnxruntime.so" → "libonnxruntime.so". */
    private fun shorten(line: String): String = line
        .replace(Regex("""/data/app/\S*/"""), "")
        .replace(Regex("""^[VDIWEF]/[^:]*\(\s*\d+\):\s*"""), "")
        .trim()
        .take(160)
}
