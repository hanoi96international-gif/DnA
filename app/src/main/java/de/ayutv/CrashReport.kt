package de.ayutv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Was beim letzten Mal schiefging: Programmfehler (mit den wichtigsten Zeilen), Abstürze im
 * nativen Code (Decoder, Modelle) und Beenden wegen Speichermangel. Wird beim nächsten Start
 * einmal angezeigt, damit man es fotografieren und melden kann.
 */
object CrashReport {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_report"
    private const val SEEN = "exit_seen"

    fun install(context: Context, process: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(context.filesDir, FILE).writeText("$process · ${thread.name}\n${error.stackTraceToString().take(8_000)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Bericht über den letzten Absturz, oder null. Jeder Vorfall wird nur einmal gemeldet. */
    fun takePending(context: Context): String? {
        val file = File(context.filesDir, FILE)
        val trace = file.takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull().also { f.delete() } }
        return listOfNotNull(exitReason(context), trace?.let(::summarize)).joinToString("\n\n").ifEmpty { null }
    }

    /** Erste Zeile und die eigenen Stellen im Code: genug, um den Fehler zu finden. */
    private fun summarize(trace: String): String {
        val lines = trace.lines()
        val own = lines.filter { "de.ayutv" in it && it.trim().startsWith("at ") }.take(4)
        val causes = lines.filter { it.startsWith("Caused by") }.take(2)
        return (lines.take(2) + own + causes).joinToString("\n")
    }

    private fun exitReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, -1L)
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(context.packageName, 0, 10)
        }.getOrNull().orEmpty()
        prefs.edit().putLong(SEEN, exits.maxOfOrNull { it.timestamp } ?: maxOf(seen, 0L)).apply()
        // Erster Start mit dieser Funktion: alte Vorfälle nicht melden.
        if (seen < 0) return null
        val bad = exits.filter { it.timestamp > seen && it.reason in BAD }.maxByOrNull { it.timestamp } ?: return null
        val what = when (bad.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Programmfehler"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Absturz im nativen Code (Decoder oder Modell)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "vom System beendet: zu wenig Speicher"
            ApplicationExitInfo.REASON_ANR -> "reagierte nicht mehr"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "zu viel Rechenlast"
            else -> "Grund ${bad.reason}"
        }
        val part = if (bad.processName.endsWith(":mt")) "Übersetzer" else "App"
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(bad.timestamp))
        // Nativer Absturz: Signal und Stelle aus dem eigenen Protokoll (falls noch vorhanden).
        val native = if (bad.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) NativeCrashLog.capture(context.filesDir) else null
        return "$part um $time: $what · ${bad.pss / 1024} MB belegt" + (bad.description?.let { "\n$it" } ?: "") +
            (native?.let { "\n$it" } ?: "")
    }

    private val BAD = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )
}
