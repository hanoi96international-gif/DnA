package de.ayutv.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs

/**
 * Passt die Bildwiederholrate des Fernsehers an die Bildrate des Videos an (z. B. 50 Hz für
 * deutsches TV, 23,976 Hz für Filme). Das beseitigt das typische Ruckeln bei Kameraschwenks.
 *
 * Ein Moduswechsel lässt HDMI neu synchronisieren (1–3 s schwarz). Deshalb:
 * - pro Ziel-Modus nur einmal anfordern, nie erneut, solange der Fernseher noch umschaltet
 * - 25/30 fps auf 50/60 Hz statt 25/30 Hz (das können alle Fernseher ruckelfrei, 25 Hz flimmert)
 * - [onSwitch] meldet Beginn und Ende des Wechsels, damit der Player so lange pausiert
 */
class FrameRateMatcher(private val activity: Activity) {
    private var requestedModeId = 0
    private var listener: DisplayManager.DisplayListener? = null
    private var timeout: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val phone = de.ayutv.data.DeviceInfo.isPhone(activity)

    /** true = Fernseher schaltet um, false = fertig (oder Zeitüberschreitung). */
    var onSwitch: (Boolean) -> Unit = {}

    /** Das System hat den Wechsel nicht ausgeführt (Fire TV: „Originalbildrate anpassen“ aus). Einmal pro App-Start. */
    var onIgnored: () -> Unit = {}

    var switching = false
        private set

    /** Während eines Wechsels gewünschte Bildrate (z. B. geschätzt umgeschaltet, dann gemeldet): danach nachholen. */
    private var pending = 0f

    /** Liefert true, wenn ein Wechsel angefordert wurde. */
    @Suppress("DEPRECATION")
    fun match(frameRate: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || frameRate <= 0f) return false
        // Handy: kein HDMI-Moduswechsel (Pause, schwarzes Bild). Der Player teilt dem System die Bildrate selbst mit
        // (Surface.setFrameRate), das Handy wählt nahtlos eine passende Bildwiederholrate (z. B. 50/100 Hz für 25 fps).
        if (phone) return false
        if (switching) {
            pending = frameRate
            return false
        }
        val display = activity.windowManager.defaultDisplay ?: return false
        // Hat das System den letzten Wechsel nicht ausgeführt, nicht bei jedem Sender erneut 5 s pausieren.
        // Kam der Modus doch noch (Fernseher war nur langsam), gilt es wieder.
        // Nur kurz und nur für diesen einen Modus: Früher blieb die Anpassung nach einem einzigen nicht ausgeführten
        // Wechsel (Fernseher/Soundbar langsam) bis zum App-Neustart aus – danach liefen viele Sender auf 60 Hz.
        if (ignoredModeId != 0) {
            if (display.mode.modeId == ignoredModeId || SystemClock.elapsedRealtime() - ignoredAtMs > IGNORE_MS) ignoredModeId = 0
        }
        val modes = display.supportedModes.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val current = display.mode
        val best = pickMode(modes, DisplayModeInfo(current.modeId, current.physicalWidth, current.physicalHeight, current.refreshRate), frameRate)
            ?: return false
        // Für die Menüs merken (nur 50/60 Hz, bei 24 Hz liefe die Oberfläche ruckelig).
        modes.firstOrNull { it.id == best }?.takeIf { it.refreshRate >= 47f }?.let { keptModeId = best }
        if (best == current.modeId || best == requestedModeId || best == ignoredModeId) return false
        requestedModeId = best
        startSwitch(best)
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = best
        activity.window.attributes = attributes
        return true
    }

    data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    @Suppress("DEPRECATION")
    fun currentRefreshRate(): Float = runCatching { activity.windowManager.defaultDisplay.refreshRate }.getOrDefault(0f)

    companion object {
        /**
         * Zuletzt für Sender und Filme gewählter 50/60-Hz-Modus. Die Menüs bleiben dabei, statt beim Verlassen des
         * Players auf den Standard zurückzuschalten: Sonst wird das Bild beim nächsten Sender wieder schwarz.
         */
        @Volatile var keptModeId = 0

        /**
         * Modus, den das System zweimal hintereinander nicht eingestellt hat (0 = keiner): für [IGNORE_MS] nicht erneut
         * anfordern (sonst bei jedem Sender 5 s Pause). Andere Modi bleiben erlaubt.
         */
        @Volatile var ignoredModeId = 0
        @Volatile private var ignoredAtMs = 0L
        /** Nicht ausgeführte Wechsel in Folge (ein langsamer Fernseher allein sperrt noch nichts). */
        @Volatile private var misses = 0
        private const val IGNORE_MS = 10 * 60_000L

        /** In [activity] (Menüs) den gemerkten Modus anfordern, oder mit [enabled] = false wieder den Standard. */
        fun applyKept(activity: Activity, enabled: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val wanted = if (enabled) keptModeId else 0
            val attributes = activity.window.attributes
            if (attributes.preferredDisplayModeId == wanted) return
            attributes.preferredDisplayModeId = wanted
            activity.window.attributes = attributes
        }

        /**
         * Passender Anzeigemodus für [frameRate], bei gleicher Auflösung (kein Umschalten zwischen 4K und 1080p).
         * 25/30 fps bevorzugt mit 50/60 Hz, Filme (23,976/24) exakt. Je Ziel der nächstliegende Modus,
         * damit 23,976 und 24 Hz nicht verwechselt werden. null = nichts Passendes (oder schon richtig).
         */
        fun pickMode(modes: List<DisplayModeInfo>, current: DisplayModeInfo, frameRate: Float): Int? {
            if (frameRate <= 0f) return null
            val sameSize = modes.filter { it.width == current.width && it.height == current.height }
            val targets = if (frameRate in 24.5f..30.5f) listOf(frameRate * 2, frameRate) else listOf(frameRate, frameRate * 2)
            for (target in targets) {
                val best = sameSize.filter { abs(it.refreshRate - target) < 0.05f }.minByOrNull { abs(it.refreshRate - target) }
                if (best != null) return best.id
            }
            return null
        }
    }

    fun reset() {
        pending = 0f
        finishSwitch()
        if (requestedModeId == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = 0
        activity.window.attributes = attributes
        requestedModeId = 0
    }

    @Suppress("DEPRECATION")
    private fun startSwitch(modeId: Int) {
        finishSwitch()
        switching = true
        onSwitch(true)
        val manager = activity.getSystemService(DisplayManager::class.java)
        val displayId = activity.windowManager.defaultDisplay.displayId
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id != displayId || activity.windowManager.defaultDisplay.mode.modeId != modeId) return
                misses = 0
                // Modus steht, der Fernseher braucht aber noch einen Moment für das neue HDMI-Signal.
                timeout?.let { handler.removeCallbacks(it) }
                handler.postDelayed({ finishSwitch() }, Timing.SETTLE_MS)
            }
        }
        listener = l
        manager?.registerDisplayListener(l, handler)
        // Manche Geräte melden den Wechsel nicht (oder lehnen ihn ab): spätestens dann weiterspielen.
        val t = Runnable {
            if (activity.windowManager.defaultDisplay.mode.modeId != modeId) {
                misses++
                // Erst beim zweiten Mal in Folge als „schaltet nicht um“ werten (Hinweis, kurze Sperre dieses Modus).
                if (misses >= 2) {
                    ignoredModeId = modeId
                    ignoredAtMs = SystemClock.elapsedRealtime()
                    keptModeId = 0
                    onIgnored()
                }
                // Nicht gleich wieder denselben Modus anfordern; beim nächsten Sender neu versuchen.
                requestedModeId = 0
            } else misses = 0
            finishSwitch()
        }
        timeout = t
        handler.postDelayed(t, Timing.MAX_SWITCH_MS)
    }

    private fun finishSwitch() {
        listener?.let { activity.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        listener = null
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        handler.removeCallbacksAndMessages(null)
        if (switching) {
            switching = false
            onSwitch(false)
            val next = pending
            pending = 0f
            if (next > 0f) handler.post { match(next) }
        }
    }

    private object Timing {
        const val SETTLE_MS = 1_200L
        const val MAX_SWITCH_MS = 5_000L
    }
}
