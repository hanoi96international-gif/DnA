package de.ayutv

import java.io.File

/**
 * Liest nach einem nativen Absturz die Zeilen aus dem eigenen Systemprotokoll (das darf jede App für
 * sich selbst): Signal, Abbruchgrund und die obersten Stellen der Aufrufkette. So ist auf einem Foto
 * zu sehen, in welcher Bibliothek und Funktion es passiert ist.
 */
object NativeCrashLog {
    // Neuer Name ab 0.61.16: Die alte Datei konnte einen Absturz früherer Versionen enthalten (ohne Zeitpunkt).
    private const val FILE = "native_crash2.txt"
    private val KEEP = listOf("Fatal signal", "Abort message", "Cause:", "signal ", "fault addr", "libc++abi", "terminating", "Scudo", "CHECK", "#0", "#1")

    /**
     * Gefundene Zeilen (gekürzt) oder null. Speichert sie auch für den nächsten Selbsttest. [atMs]: Zeitpunkt des Absturzes
     * (vom System gemeldet); ohne ihn zählt nur ein Absturz der letzten 10 Minuten.
     */
    fun capture(filesDir: File, atMs: Long? = null): String? {
        val lines = read(listOf("-b", "main", "-b", "crash")) ?: read(listOf("-b", "main")) ?: return null
        val fatal = lines.indexOfLast { "Fatal signal" in it }
        if (fatal < 0) return null
        // Das Systemprotokoll hält alte Abstürze lange (auch von früheren App-Versionen): nur einen frischen nehmen.
        if (!isRecent(lines[fatal], atMs)) return null
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

    /** Wie [last], mit Zeitpunkt – nur, wenn er höchstens [maxAgeMs] zurückliegt (für den Kurzbericht). */
    fun lastRecent(filesDir: File, maxAgeMs: Long): String? = runCatching {
        val file = File(filesDir, FILE).takeIf { it.isFile } ?: return null
        if (System.currentTimeMillis() - file.lastModified() > maxAgeMs) return null
        val at = java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMAN).format(java.util.Date(file.lastModified()))
        "$at\n" + file.readText()
    }.getOrNull()

    /**
     * Gehört die Protokollzeile (Format „MM-DD HH:MM:SS.mmm …“) zu diesem Absturz: höchstens 2 Minuten neben [atMs] bzw.
     * ohne ihn höchstens 10 Minuten zurück? Ohne Zeit in der Zeile: ja.
     */
    private fun isRecent(line: String, atMs: Long?): Boolean {
        val m = Regex("""^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)""").find(line) ?: return true
        val (mo, d, h, mi, se) = m.destructured
        val now = java.util.Calendar.getInstance()
        val at = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.MONTH, mo.toInt() - 1)
            set(java.util.Calendar.DAY_OF_MONTH, d.toInt())
            set(java.util.Calendar.HOUR_OF_DAY, h.toInt())
            set(java.util.Calendar.MINUTE, mi.toInt())
            set(java.util.Calendar.SECOND, se.toInt())
            if (after(now)) add(java.util.Calendar.YEAR, -1) // Jahreswechsel
        }
        return if (atMs != null) kotlin.math.abs(atMs - at.timeInMillis) <= 2 * 60_000L
        else now.timeInMillis - at.timeInMillis <= 10 * 60_000L
    }

    /** Kurzform für eine Statuszeile: Signal und oberste Stelle. */
    fun summary(text: String): String {
        val signal = Regex("""signal \d+ \(\w+\)(, code -?\d+ \(\w+\))?""").find(text)?.value
        val frame = text.lines().firstOrNull { "#00" in it }?.substringAfter("#00")?.trim()
        return listOfNotNull(signal, frame).joinToString(" · ")
    }

    private fun read(buffers: List<String>): List<String>? = runCatching {
        val process = ProcessBuilder(listOf("logcat", "-d", "-v", "time", "-t", "4000") + buffers)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        out.takeIf { process.exitValue() == 0 && it.isNotEmpty() }
    }.getOrNull()

    /** Lange Pfade kürzen: "/data/app/~~x/de.ayutv-y/lib/arm/libonnxruntime.so" → "libonnxruntime.so". */
    private fun shorten(line: String): String = line
        .replace(Regex("""/data/app/\S*/"""), "")
        .replace(Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+"""), "")
        .replace(Regex("""^[VDIWEF]/[^:]*\(\s*\d+\):\s*"""), "")
        .trim()
        .take(160)
}
