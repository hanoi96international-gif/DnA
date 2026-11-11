package de.ayutv.data

import android.content.Context
import java.io.File

/**
 * Speicher auf dem Gerät im Blick behalten: Ein Fire TV Stick hat nur wenige GB frei. Läuft er voll, wird das ganze
 * Gerät träge und das System beendet Apps. Übersetzungsmodelle (je Sprache gut 100 MB), Untertitel, übersetzte Zeilen,
 * nächtlich gelesene Untertitel und geladene Updates werden deshalb nach einer Weile ohne Nutzung gelöscht; wird der
 * Platz knapp, früher.
 */
object StorageCleaner {
    data class Part(val label: String, val bytes: Long)

    private const val DAY = 24 * 3_600_000L
    /** Unter so viel freiem Platz wird gründlicher aufgeräumt. */
    private const val LOW_FREE_BYTES = 600L * 1024 * 1024

    /** Markierung „zuletzt benutzt“ in einem Modellordner (der Ordner selbst ändert sich beim Lesen nicht). */
    const val USED_MARK = ".used"

    /** Belegung nach Bereichen, größter zuerst. */
    fun usage(context: Context): List<Part> {
        val files = context.filesDir
        val cache = context.cacheDir
        val parts = listOf(
            Part("Übersetzungsmodelle", size(File(files, "mt"))),
            Part("Texterkennung", size(File(files, "ocr"))),
            Part("Untertitel", size(File(files, "subtitles")) + size(File(files, "subtitle-lines")) + size(File(files, "embedded-lines"))),
            Part("Logos und Poster", size(File(cache, "images"))),
            Part("Zeitversatz", size(File(cache, "timeshift"))),
            Part("Updates", size(File(cache, "update"))),
            Part("Aufnahmen", size(File(context.getExternalFilesDir(null) ?: files, "recordings"))),
            Part("Datenbank", context.databaseList().sumOf { context.getDatabasePath(it).length() }),
        )
        return parts.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    }

    fun freeBytes(context: Context): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)

    fun isLow(context: Context): Boolean = freeBytes(context) in 1 until LOW_FREE_BYTES

    /**
     * Aufräumen; liefert, was frei wurde (für Anzeige und Protokoll). [manual]: Knopf „Aufräumen“ in den Einstellungen –
     * dann gründlich: Bilder-Zwischenspeicher leeren (Logos und Poster laden bei Bedarf neu), alle Update-Dateien bereits
     * installierter Versionen, Übersetzungsmodelle nach 7 statt 30 Tagen, Reste vom Zeitversatz und die Datenbank
     * verdichten ([database]; gelöschte TV-Guide-Daten geben ihren Platz sonst nie frei).
     */
    fun clean(
        context: Context,
        now: Long = System.currentTimeMillis(),
        manual: Boolean = false,
        database: androidx.room.RoomDatabase? = null,
    ): String {
        val low = isLow(context)
        val files = context.filesDir
        var freed = 0L
        var models = 0
        // Übersetzungsmodelle: 30 Tage unbenutzt (bei wenig Platz 3 Tage).
        val modelAge = if (low) 3 * DAY else if (manual) 7 * DAY else 30 * DAY
        File(files, "mt").listFiles().orEmpty().filter { it.isDirectory }.forEach { folder ->
            val used = File(folder, USED_MARK).takeIf { it.isFile }?.lastModified() ?: folder.lastModified()
            if (now - used > modelAge) {
                freed += size(folder)
                if (folder.deleteRecursively()) models++
            }
        }
        // Untertitel, übersetzte und nächtlich gelesene Zeilen: 60 Tage (bei wenig Platz 14 Tage).
        val subtitleAge = if (low) 14 * DAY else 60 * DAY
        var subtitles = 0
        for (name in listOf("subtitles", "subtitle-lines", "embedded-lines")) {
            File(files, name).listFiles().orEmpty().filter { it.isFile && now - it.lastModified() > subtitleAge }.forEach {
                freed += it.length()
                if (it.delete()) subtitles++
            }
        }
        // Geladene Updates werden nach der Installation nicht mehr gebraucht: sofort weg, wenn die Version schon
        // installiert ist (oder älter), sonst nach einem Tag. Ein gerade laufender Download ist jünger als 10 Minuten.
        var updates = 0L
        File(context.cacheDir, "update").listFiles().orEmpty().forEach { file ->
            val version = Regex("""(\d+(?:\.\d+)+)""").find(file.name)?.value
            val installed = version != null && compareVersions(version, de.ayutv.BuildConfig.VERSION_NAME) <= 0
            if ((installed && now - file.lastModified() > 10 * 60_000L) || now - file.lastModified() > DAY) {
                updates += file.length()
                file.delete()
            }
        }
        freed += updates
        // Reste vom Zeitversatz (Puffer eines beendeten Players). Erst nach 12 Stunden: Ein laufender Puffer hält bis zu
        // mehreren Stunden, seine ältesten Teile darf das Aufräumen (auch nachts während der Wiedergabe) nicht löschen.
        File(context.cacheDir, "timeshift").listFiles().orEmpty().filter { now - it.lastModified() > 12 * 3_600_000L }.forEach {
            freed += size(it)
            it.deleteRecursively()
        }
        var images = 0L
        var compacted = 0L
        if (manual) {
            // Logos und Poster: laden bei Bedarf neu.
            val before = size(File(context.cacheDir, "images"))
            runCatching {
                val loader = coil3.SingletonImageLoader.get(context)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            images = (before - size(File(context.cacheDir, "images"))).coerceAtLeast(0)
            freed += images
            // Datenbank verdichten: Nach jedem TV-Guide-Laden werden alte Sendungen gelöscht, die Datei schrumpft aber nicht.
            if (database != null) {
                val dbBefore = context.databaseList().sumOf { context.getDatabasePath(it).length() }
                runCatching {
                    val db = database.openHelper.writableDatabase
                    db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
                    db.execSQL("VACUUM")
                }
                compacted = (dbBefore - context.databaseList().sumOf { context.getDatabasePath(it).length() }).coerceAtLeast(0)
                freed += compacted
            }
        }
        if (freed <= 0) return "nichts zu löschen"
        val what = buildList {
            if (images > 0) add("Bilder ${format(images)}")
            if (compacted > 0) add("Datenbank ${format(compacted)}")
            if (updates > 0) add("Updates ${format(updates)}")
            if (models > 0) add(if (models == 1) "1 Übersetzungsmodell" else "$models Übersetzungsmodelle")
            if (subtitles > 0) add("$subtitles Untertitel-Dateien")
        }
        return "${format(freed)} frei geworden" + if (what.isEmpty()) "" else " (${what.joinToString(", ")})"
    }

    /** Versionsnummern vergleichen („0.44.1“ < „0.45.0“). */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Für Anzeige: „1,2 GB“ oder „350 MB“. */
    fun format(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) {
        String.format(java.util.Locale.GERMAN, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    } else "${bytes / (1024 * 1024)} MB"

    private fun size(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
