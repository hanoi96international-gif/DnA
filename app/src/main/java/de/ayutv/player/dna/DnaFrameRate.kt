package de.ayutv.player.dna

import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceView

/**
 * Bildwiederholrate des Handy-Bildschirms an den Stream anpassen – für jede Engine (Media3 macht das nur
 * bei nahtlosen Wechseln). Ein 25-fps-Sender auf einem 120-Hz-Bildschirm wird sonst im Wechsel 4 und 5 Bildwechsel lang
 * gezeigt: sichtbares Ruckeln bei Kameraschwenks. Mit „immer wechseln“ wählt Android den passendsten Modus (50/100 Hz,
 * wo es ihn gibt; 24/30/60 für Filme und US-Sender) – notfalls mit einem kurzen Moduswechsel.
 */
object DnaFrameRate {
    private const val TAG = "DnaPlayer"
    private var applied: Pair<Int, Float>? = null

    /** Für [view] (Bildfläche des Players) die Bildrate [fps] anfragen; 0 = keine Vorgabe (z. B. noch unbekannt). */
    fun apply(view: SurfaceView?, fps: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || view == null) return
        val surface = view.holder?.surface?.takeIf { it.isValid } ?: return
        val rate = if (fps in 10f..120f) fps else 0f
        val key = System.identityHashCode(surface) to rate
        if (applied == key) return
        applied = key
        runCatching {
            surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
            if (rate > 0) {
                val modes = view.display?.supportedModes?.map { it.refreshRate.toInt() }?.distinct()?.sorted()
                Log.i(TAG, "Bildwiederholrate: $rate fps angefragt, Bildschirm kann ${modes?.joinToString("/")} Hz")
            }
        }.onFailure { Log.w(TAG, "Bildwiederholrate nicht gesetzt: ${it.message}") }
    }
}
