package de.ayutv.desktop.player

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import kotlin.math.abs

/**
 * Bildwiederholrate des Bildschirms an das Video anpassen (Windows): Deutsches TV läuft mit 50 Hz, Filme mit
 * 23,976 Hz – ein Bildschirm mit 60 Hz zeigt das ungleichmäßig (Ruckeln bei Schwenks). Gleiche Auflösung, nur die
 * Rate wechselt; Windows nimmt die Änderung beim Beenden von DnA-TV von selbst zurück ([CDS_FULLSCREEN]).
 */
object DisplayRate {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun EnumDisplaySettingsW(device: WString?, mode: Int, devMode: Pointer): Boolean
        fun ChangeDisplaySettingsExW(device: WString?, devMode: Pointer?, window: Pointer?, flags: Int, param: Pointer?): Int
    }

    private val user32: User32? by lazy { if (windows) runCatching { Native.load("user32", User32::class.java) }.getOrNull() else null }

    /** Rate vor dem ersten Umschalten (null = nicht umgeschaltet). */
    @Volatile private var original: Int? = null

    val supported: Boolean get() = user32 != null

    /** Aktuelle Rate des Hauptbildschirms (Hz, 0 = unbekannt). */
    fun current(): Int = read(ENUM_CURRENT_SETTINGS)?.rate ?: 0

    /**
     * Für ein Video mit [fps] Bildern pro Sekunde die passende Rate wählen und umschalten. Ergebnis: neue Rate,
     * oder null (passt schon, nichts Passendes oder kein Windows).
     */
    @Synchronized
    fun match(fps: Double): Int? {
        val lib = user32 ?: return null
        if (fps < 10 || fps > 125) return null
        val now = read(ENUM_CURRENT_SETTINGS) ?: return null
        val rates = modes().filter { it.width == now.width && it.height == now.height && it.bits == now.bits }.map { it.rate }.distinct()
        val target = choose(fps, rates) ?: return null
        if (target == now.rate) return null
        val mode = Memory(DEVMODE_SIZE).apply { clear() }
        mode.setShort(OFF_SIZE, DEVMODE_SIZE.toShort())
        mode.setInt(OFF_FIELDS, DM_DISPLAYFREQUENCY or DM_PELSWIDTH or DM_PELSHEIGHT)
        mode.setInt(OFF_WIDTH, now.width)
        mode.setInt(OFF_HEIGHT, now.height)
        mode.setInt(OFF_FREQUENCY, target)
        val result = lib.ChangeDisplaySettingsExW(null, mode, null, CDS_FULLSCREEN, null)
        if (result != DISP_CHANGE_SUCCESSFUL) return null
        if (original == null) original = now.rate
        return target
    }

    /** Wieder die Rate von vorher (beim Verlassen des Players). */
    @Synchronized
    fun restore() {
        val lib = user32 ?: return
        if (original == null) return
        original = null
        runCatching { lib.ChangeDisplaySettingsExW(null, null, null, 0, null) }
    }

    /**
     * Beste Rate für [fps] aus [rates]: ein ganzzahliges Vielfaches der Bildrate, möglichst zwischen 48 und 60 Hz
     * (25 Bilder/s → 50 Hz, 23,976 → 48 oder 24 Hz, 29,97 → 60 Hz). Windows nennt 23,976 Hz „23“ oder „24“.
     */
    fun choose(fps: Double, rates: List<Int>): Int? {
        val candidates = (1..5).map { fps * it }.filter { it in 23.0..121.0 }
            .sortedBy { if (it in 47.0..61.0) 0 else if (it in 23.0..31.0) 1 else 2 }
        for (wanted in candidates) {
            rates.filter { abs(it - wanted) < 0.6 || (abs(it + 1 - wanted * 1001 / 1000) < 0.6 && it.toDouble() < wanted) }
                .minByOrNull { abs(it - wanted) }?.let { return it }
        }
        return null
    }

    private data class Mode(val width: Int, val height: Int, val bits: Int, val rate: Int)

    private fun modes(): List<Mode> = buildList {
        var index = 0
        while (index < 2_000) add(read(index++) ?: break)
    }

    private fun read(index: Int): Mode? {
        val lib = user32 ?: return null
        val mode = Memory(DEVMODE_SIZE).apply { clear() }
        mode.setShort(OFF_SIZE, DEVMODE_SIZE.toShort())
        if (!lib.EnumDisplaySettingsW(null, index, mode)) return null
        return Mode(mode.getInt(OFF_WIDTH), mode.getInt(OFF_HEIGHT), mode.getInt(OFF_BITS), mode.getInt(OFF_FREQUENCY))
    }

    // DEVMODEW (220 Bytes): Lage der benutzten Felder.
    private const val DEVMODE_SIZE = 220L
    private const val OFF_SIZE = 68L
    private const val OFF_FIELDS = 72L
    private const val OFF_BITS = 168L
    private const val OFF_WIDTH = 172L
    private const val OFF_HEIGHT = 176L
    private const val OFF_FREQUENCY = 184L
    private const val ENUM_CURRENT_SETTINGS = -1
    private const val DM_PELSWIDTH = 0x00080000
    private const val DM_PELSHEIGHT = 0x00100000
    private const val DM_DISPLAYFREQUENCY = 0x00400000
    private const val CDS_FULLSCREEN = 0x00000004
    private const val DISP_CHANGE_SUCCESSFUL = 0
}
