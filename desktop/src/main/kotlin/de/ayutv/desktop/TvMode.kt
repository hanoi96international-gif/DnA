package de.ayutv.desktop

import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.Timer

/**
 * TV-Modus (Einstellung, standardmäßig aus): Der PC verhält sich wie eine TV-Box. DnA-TV startet mit Windows direkt im
 * Vollbild, der Mauszeiger verschwindet, solange die Maus ruht, und aus der App heraus lässt sich der PC schlafen legen
 * oder ausschalten. Die normale Fensterbedienung bleibt die Hauptversion; der TV-Modus ist eine Alternative für einen
 * Mini-PC am Fernseher.
 */
object TvMode {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "DnA-TV"
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Pfad der DnA-TV.exe (vom Installer gesetzt); null beim Entwickeln. */
    private fun exePath(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.endsWith(".exe", ignoreCase = true) }
        ?: ProcessHandle.current().info().command().orElse(null)?.takeIf { it.endsWith("DnA-TV.exe", ignoreCase = true) }

    /** Mit Windows starten (nur der eigene Benutzer, keine Adminrechte nötig). false, wenn es nicht ging. */
    fun setAutostart(on: Boolean): Boolean {
        if (!isWindows) return false
        return runCatching {
            val hkcu = com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
            if (on) {
                val exe = exePath() ?: return false
                com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(hkcu, RUN_KEY, VALUE, "\"$exe\" --tv")
            } else if (com.sun.jna.platform.win32.Advapi32Util.registryValueExists(hkcu, RUN_KEY, VALUE)) {
                com.sun.jna.platform.win32.Advapi32Util.registryDeleteValue(hkcu, RUN_KEY, VALUE)
            }
            true
        }.onFailure { Log.warn("Autostart: ${it.message}") }.getOrDefault(false)
    }

    fun autostartActive(): Boolean = isWindows && runCatching {
        com.sun.jna.platform.win32.Advapi32Util.registryValueExists(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
    }.getOrDefault(false)

    /** PC in den Energiesparmodus (Fernbedienung weckt ihn nicht; Maus, Tastatur oder Einschalttaste schon). */
    fun sleep() = run("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0")

    fun shutdown() = run("shutdown", "/s", "/t", "0")

    private fun run(vararg command: String) {
        if (!isWindows) return
        runCatching { ProcessBuilder(*command).start() }.onFailure { Log.warn("TV-Modus: ${command.first()}: ${it.message}") }
    }

    // ---------------------------------------------------------------- Mauszeiger

    private var hider: Timer? = null
    private var listener: AWTEventListener? = null
    private val blank: Cursor by lazy {
        Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "leer")
    }

    /** Mauszeiger nach 3 s Ruhe ausblenden (an) bzw. normal lassen (aus). */
    fun hideIdleCursor(window: Window, on: Boolean) {
        hider?.stop()
        listener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        hider = null
        listener = null
        window.cursor = Cursor.getDefaultCursor()
        if (!on) return
        val timer = Timer(3_000) { window.cursor = blank }.apply { isRepeats = false; start() }
        val l = AWTEventListener { e ->
            if (e is MouseEvent && (e.id == MouseEvent.MOUSE_MOVED || e.id == MouseEvent.MOUSE_DRAGGED)) {
                window.cursor = Cursor.getDefaultCursor()
                timer.restart()
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(l, java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK)
        hider = timer
        listener = l
    }
}
