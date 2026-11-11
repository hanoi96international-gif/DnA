package de.ayutv.player.dna.engine

import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer

/**
 * Bildwechsel des Displays (Vsync) mitverfolgen, damit jedes Bild genau auf einen Bildwechsel fällt (wie Media3,
 * VideoFrameReleaseHelper). Ohne das landen Anzeigezeiten bei 25 Bildern/s auf 50 Hz (oder 23,976 auf 24 Hz) oft knapp
 * neben einem Bildwechsel: Je nach winziger Schwankung steht ein Bild dann 1 oder 3 statt gleichmäßig 2 Bildwechsel –
 * sichtbar als leichtes, regelmäßiges Ruckeln.
 *
 * Läuft auf einem eigenen Thread, nur solange eine Wiedergabe es braucht ([add]/[remove]).
 */
internal object VsyncSampler : Choreographer.FrameCallback {
    @Volatile private var lastVsyncNs = 0L
    /** Dauer eines Bildwechsels (ns), 0 = unbekannt. */
    @Volatile var periodNs = 0L
        private set
    private var users = 0
    private var handler: Handler? = null
    private var choreographer: Choreographer? = null
    private var lastPeriodCheckNs = 0L

    @Synchronized fun add() {
        if (users++ > 0) return
        val h = handler ?: Handler(HandlerThread("dna-vsync").apply { isDaemon = true; start() }.looper).also { handler = it }
        h.post {
            runCatching {
                val c = choreographer ?: Choreographer.getInstance().also { choreographer = it }
                c.postFrameCallback(this)
            }
        }
    }

    @Synchronized fun remove() {
        if (users > 0) users--
        if (users == 0) handler?.post { runCatching { choreographer?.removeFrameCallback(this) } }
    }

    override fun doFrame(frameTimeNanos: Long) {
        lastVsyncNs = frameTimeNanos
        // Bildwechselrate des Displays (ändert sich bei der Anpassung an den Film, 50/60 Hz): alle 2 s neu lesen.
        if (periodNs == 0L || frameTimeNanos - lastPeriodCheckNs > 2_000_000_000L) {
            lastPeriodCheckNs = frameTimeNanos
            periodNs = displayPeriodNs()
        }
        val keep = synchronized(this) { users > 0 }
        if (keep) runCatching { choreographer?.postFrameCallback(this) }
    }

    private fun displayPeriodNs(): Long {
        val rate = runCatching {
            val context = DnaEngineCore.appContext ?: return@runCatching 0f
            val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        }.getOrDefault(0f)
        return if (rate in 20f..250f) (1_000_000_000.0 / rate).toLong() else 0L
    }

    /** Lage von [timeNs] zwischen zwei Bildwechseln: −0,5 … 0,5 (0 = genau auf einem); null ohne Vsync-Daten. */
    fun phase(timeNs: Long): Double? {
        val vsync = lastVsyncNs
        val period = periodNs
        if (vsync == 0L || period <= 0L || System.nanoTime() - vsync > 1_000_000_000L) return null
        val p = Math.floorMod(timeNs - vsync, period).toDouble() / period
        return if (p >= 0.5) p - 1.0 else p
    }

    /**
     * Anzeigezeit [releaseNs] (System.nanoTime) auf den nächstgelegenen Bildwechsel legen, etwas davor (80 % einer
     * Periode, wie Media3), damit das Bild sicher zu genau diesem Bildwechsel fertig ist. Ohne Vsync-Daten unverändert.
     */
    fun adjust(releaseNs: Long): Long {
        val vsync = lastVsyncNs
        val period = periodNs
        if (vsync == 0L || period <= 0L) return releaseNs
        // Vsync-Daten älter als eine Sekunde (Thread hing): lieber nicht verwenden.
        if (System.nanoTime() - vsync > 1_000_000_000L) return releaseNs
        val n = Math.round((releaseNs - vsync).toDouble() / period)
        val snapped = vsync + n * period
        return snapped - period * 80 / 100
    }
}
