package de.ayutv.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong

/**
 * Wie viel Bewegung die Oberfläche sich leisten kann, und wie flüssig sie tatsächlich läuft.
 *
 * Auf schwachen Geräten (Fire TV Stick 4K: 32 Bit, 2 GB, kleiner Grafikchip) entfallen Überblendungen zwischen
 * den Bereichen, das Start-Intro und das Federn der Seitenleiste: Jede Überblendung zeichnet den ganzen Bildschirm
 * zweimal halbdurchsichtig, das schafft der Grafikchip nicht ohne Ruckeln.
 */
object UiPerformance {
    /**
     * Weniger Bewegung: schwaches Gerät, vom System als Gerät mit wenig Speicher gemeldet, oder vom Nutzer gewählt
     * (*Einstellungen → Anzeige → Flüssige Bedienung*). Als Compose-Zustand: Umschalten wirkt sofort.
     */
    var reduced: Boolean by androidx.compose.runtime.mutableStateOf(false)
        private set
    @Volatile private var automatic = false
    @Volatile private var chosen = false

    /** Einstellung „Flüssige Bedienung“. */
    fun setSmooth(on: Boolean) {
        chosen = on
        reduced = automatic || on
    }

    /** Nur für Tests: wie auf einem schwachen Stick (ohne Übergänge, Hintergrund kleiner gezeichnet). */
    @androidx.annotation.VisibleForTesting
    fun force(value: Boolean) {
        reduced = value
    }

    fun init(context: Context) {
        val lowRam = runCatching { context.getSystemService(ActivityManager::class.java).isLowRamDevice }.getOrDefault(false)
        automatic = lowRam || de.ayutv.data.DeviceInfo.isWeak(context)
        reduced = automatic || chosen
    }

    // ------------------------------------------------------------------ Ruckel-Messung

    /** Zählt die Bilder getrennt: ruhige Bedienung und während im Hintergrund Playlist oder TV-Guide geladen werden. */
    private class Counter {
        val frames = AtomicLong()
        val slow = AtomicLong()
        val verySlow = AtomicLong()
        val worstMs = AtomicLong()
        fun add(ms: Long) {
            frames.incrementAndGet()
            if (ms > SLOW_MS) slow.incrementAndGet()
            if (ms > VERY_SLOW_MS) verySlow.incrementAndGet()
            worstMs.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
        }
        fun text(): String {
            val total = frames.get()
            fun share(n: Long) = String.format(java.util.Locale.GERMAN, "%.1f %%", n * 100.0 / total)
            return "$total Bilder, über ${SLOW_MS} ms: ${share(slow.get())}, über ${VERY_SLOW_MS} ms: ${share(verySlow.get())}, längstes ${worstMs.get()} ms"
        }
    }
    /**
     * Woran langsame Bilder (über [SLOW_MS]) ihre Zeit verlieren, aufsummiert in µs: Eingabe/Animation, Aufbau und
     * Anordnung (Compose), Zeichenbefehle erzeugen, an die Grafik übergeben und die Grafik selbst. So zeigt der
     * Kurzbericht, ob die App zu viel rechnet oder der Grafikchip am Limit ist.
     */
    private object SlowParts {
        val frames = AtomicLong()
        val input = AtomicLong()
        val layout = AtomicLong()
        val draw = AtomicLong()
        val sync = AtomicLong()
        val gpu = AtomicLong()
        val total = AtomicLong()
        val waiting = AtomicLong()
        fun text(): String? {
            val n = frames.get().takeIf { it > 0 } ?: return null
            fun ms(v: AtomicLong) = String.format(java.util.Locale.GERMAN, "%.1f", v.get() / 1000.0 / n)
            return "langsame Bilder im Mittel: vorher belegt (Hauptthread) ${ms(waiting)} ms, Eingabe/Animation ${ms(input)} ms, Aufbau/Anordnung ${ms(layout)} ms, " +
                "Zeichnen ${ms(draw)} ms, Übergabe ${ms(sync)} ms" +
                (if (gpu.get() > 0) ", Grafikchip ${ms(gpu)} ms" else "") +
                ", Rest (Warten auf Grafik/Bildschirm) " + String.format(java.util.Locale.GERMAN, "%.1f",
                    (total.get() - waiting.get() - input.get() - layout.get() - draw.get() - sync.get()).coerceAtLeast(0) / 1000.0 / n) + " ms"
        }
    }
    private val calm = Counter()
    private val loading = Counter()
    /** Je Bereich (Start, Live TV, TV-Guide …) eigene Zählung: zeigt im Gerätecheck, wo es ruckelt. */
    private val bySection = java.util.concurrent.ConcurrentHashMap<String, Counter>()
    /** Bereich, der gerade zu sehen ist (von der Oberfläche gesetzt). */
    @Volatile var section: String = ""

    /** Lädt gerade etwas im Hintergrund (Playlist, TV-Guide)? Von der App gesetzt. */
    @Volatile var busy: () -> Boolean = { false }
    private var thread: HandlerThread? = null
    private val listeners = HashMap<Window, Window.OnFrameMetricsAvailableListener>()

    /** Dauer jedes gezeichneten Bildes der Menüs mitzählen (kostet nichts, solange nichts gezeichnet wird). */
    fun watch(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val window = activity.window
        if (window in listeners) return
        val handler = Handler((thread ?: HandlerThread("frame-stats").apply { start() }.also { thread = it }).looper)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
            val busyNow = runCatching { busy() }.getOrDefault(false)
            (if (busyNow) loading else calm).add(ms)
            if (!busyNow && ms > SLOW_MS) {
                fun part(id: Int) = runCatching { metrics.getMetric(id) / 1000 }.getOrDefault(0L)
                SlowParts.frames.incrementAndGet()
                SlowParts.total.addAndGet(part(FrameMetrics.TOTAL_DURATION))
                SlowParts.waiting.addAndGet(part(FrameMetrics.UNKNOWN_DELAY_DURATION))
                SlowParts.input.addAndGet(part(FrameMetrics.INPUT_HANDLING_DURATION) + part(FrameMetrics.ANIMATION_DURATION))
                SlowParts.layout.addAndGet(part(FrameMetrics.LAYOUT_MEASURE_DURATION))
                SlowParts.draw.addAndGet(part(FrameMetrics.DRAW_DURATION))
                SlowParts.sync.addAndGet(part(FrameMetrics.SYNC_DURATION) + part(FrameMetrics.COMMAND_ISSUE_DURATION) + part(FrameMetrics.SWAP_BUFFERS_DURATION))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SlowParts.gpu.addAndGet(part(FrameMetrics.GPU_DURATION))
            }
            if (!busyNow && section.isNotEmpty()) bySection.getOrPut(section) { Counter() }.add(ms)
        }
        runCatching { window.addOnFrameMetricsAvailableListener(listener, handler) }.onSuccess { listeners[window] = listener }
    }

    fun unwatch(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val window = activity.window
        listeners.remove(window)?.let { runCatching { window.removeOnFrameMetricsAvailableListener(it) } }
    }

    /** Für den Fehlerbericht: Anteil ruckeliger Bilder seit dem Start. */
    fun summary(): String {
        if (calm.frames.get() == 0L && loading.frames.get() == 0L) return "noch keine Messung"
        val parts = listOfNotNull(
            calm.takeIf { it.frames.get() > 0 }?.let { "ruhig: ${it.text()}" },
            loading.takeIf { it.frames.get() > 0 }?.let { "während Playlist/TV-Guide laden: ${it.text()}" },
        )
        // Die Bereiche mit dem größten Anteil langsamer Bilder (mindestens 60 Bilder gemessen).
        val worst = bySection.entries.filter { it.value.frames.get() >= 60 }
            .sortedByDescending { it.value.slow.get().toDouble() / it.value.frames.get() }
            .take(3)
            .joinToString(" · ") { (name, c) ->
                "$name " + String.format(java.util.Locale.GERMAN, "%.0f %%", c.slow.get() * 100.0 / c.frames.get()) + " über ${SLOW_MS} ms"
            }
        return parts.joinToString(" · ") + (if (worst.isNotEmpty()) " · am langsamsten: $worst" else "") +
            (if (reduced) " (weniger Bewegung aktiv)" else "") + (SlowParts.text()?.let { " · $it" } ?: "")
    }

    private const val SLOW_MS = 25L
    private const val VERY_SLOW_MS = 50L
}
