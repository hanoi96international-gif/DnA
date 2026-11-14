package de.ayutv.ui.settings

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import de.ayutv.player.dna.engine.GlYuvRenderer
import de.ayutv.player.dna.engine.HdrVariant
import de.ayutv.player.dna.engine.NativeVideo
import de.ayutv.ui.components.Button
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * HDR-Testbild: Farbbalken als HDR10 (BT.2020, PQ) über dieselbe Anzeige wie der eigene Software-Decoder, darunter
 * dieselben Farben als normale Oberfläche. Die Variante, bei der oben Weiß weiß, Rot rot … aussieht (nur heller), nimmt
 * der Player für 4K-HDR im eigenen Decoder. „Aus“ zeigt, wie es ohne HDR aussieht (nach SDR umgerechnet).
 */
@Composable
fun HdrTestDialog(current: String, onChoose: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var variant by remember { mutableStateOf(HdrVariant.entries.firstOrNull { it.name == current }) }
        val painter = remember { HdrPainter() }
        DisposableEffect(Unit) { onDispose { painter.stop() } }
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("HDR-Testbild – oben HDR, unten dieselben Farben normal", color = Color.White, fontSize = 16.sp)
            AndroidView(
                factory = { context -> SurfaceView(context).apply { holder.addCallback(painter) } },
                update = { painter.variant = variant },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Row(Modifier.fillMaxWidth().height(36.dp)) {
                SWATCHES.forEach { (r, g, b) ->
                    Box(Modifier.weight(1f).fillMaxSize().background(Color(r, g, b)))
                }
            }
            Text(
                "Richtig ist die Variante, bei der oben dieselben Farben stehen wie unten (gern heller, aber nicht rot oder " +
                    "dunkel). Stimmt keine, „Aus“ lassen – dann wird HDR wie bisher umgerechnet.",
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { variant = null }) { Text(if (variant == null) "● Aus" else "Aus") }
                GlYuvRenderer.availableVariants.forEach { v ->
                    Button(onClick = { variant = v }) { Text(if (variant == v) "● ${v.label}" else v.label) }
                }
                Button(onClick = { onChoose(variant?.name.orEmpty()); onClose() }) { Text("Übernehmen") }
                Button(onClick = onClose) { Text("Abbrechen") }
            }
        }
    }
}

/** Zeichnet das Testbild auf einem eigenen Thread (EGL braucht ihn), neu bei jedem Wechsel der Variante. */
private class HdrPainter : SurfaceHolder.Callback {
    @Volatile var variant: HdrVariant? = null
    @Volatile private var holder: SurfaceHolder? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private val halfFrame by lazy { testFrame(half = true) }
    private val byteFrame by lazy { testFrame(half = false) }

    override fun surfaceCreated(h: SurfaceHolder) {
        holder = h
        start()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(h: SurfaceHolder) {
        stop()
        holder = null
    }

    private fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "dna-hdr-test").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1_000)
        thread = null
    }

    private fun loop() {
        var renderer: GlYuvRenderer? = null
        var shown: Any? = Unit
        while (running) {
            val wanted = variant
            val surface = holder?.surface ?: break
            if (wanted != shown || renderer == null) {
                renderer?.release()
                renderer = runCatching { GlYuvRenderer(surface, hdr = wanted != null, variant = wanted ?: HdrVariant.PQ10) }.getOrNull()
                shown = wanted
            }
            runCatching { renderer?.draw(if (wanted != null) halfFrame else byteFrame, System.nanoTime()) }
            Thread.sleep(150)
        }
        renderer?.release()
    }
}

/** Farben der Balken (sRGB, 0..1): Weiß, Rot, Grün, Blau, Gelb, Cyan, Magenta, Grau. */
private val SWATCHES = listOf(
    Triple(1f, 1f, 1f), Triple(1f, 0f, 0f), Triple(0f, 1f, 0f), Triple(0f, 0f, 1f),
    Triple(1f, 1f, 0f), Triple(0f, 1f, 1f), Triple(1f, 0f, 1f), Triple(0.5f, 0.5f, 0.5f),
)

private const val W = 1280
private const val H = 720

/**
 * Testbild als YUV 4:2:0 in BT.2020/PQ, begrenzter Wertebereich – genau so, wie der Decoder ein HDR10-Bild liefert.
 * [half]: 10 Bit als Halbfließkomma (HDR-Anzeige), sonst 8 Bit (Umrechnung nach SDR).
 */
private fun testFrame(half: Boolean): NativeVideo.Frame {
    val bytes = if (half) 2 else 1
    val y = ByteBuffer.allocateDirect(W * H * bytes).order(ByteOrder.nativeOrder())
    val u = ByteBuffer.allocateDirect(W * H / 4 * bytes).order(ByteOrder.nativeOrder())
    val v = ByteBuffer.allocateDirect(W * H / 4 * bytes).order(ByteOrder.nativeOrder())
    val bars = SWATCHES.map { (r, g, b) -> yuvPq(r, g, b) }
    fun put(buffer: ByteBuffer, value: Float, chroma: Boolean) {
        if (half) {
            val code = if (chroma) 512f + 896f * value else 64f + 876f * value
            buffer.putShort(toHalf(code / 1023f))
        } else {
            val code = if (chroma) 128f + 224f * value else 16f + 219f * value
            buffer.put(code.toInt().coerceIn(0, 255).toByte())
        }
    }
    for (row in 0 until H) for (col in 0 until W) put(y, bars[col * bars.size / W][0], chroma = false)
    for (row in 0 until H / 2) for (col in 0 until W / 2) {
        val bar = bars[col * 2 * bars.size / W]
        put(u, bar[1], chroma = true)
        put(v, bar[2], chroma = true)
    }
    y.flip(); u.flip(); v.flip()
    return NativeVideo.Frame(W, H, colorSpace = 9, transfer = 16, fullRange = false, y = y, u = u, v = v).also { it.half = half }
}

/** sRGB-Farbe bei SDR-Weiß (203 nits) → BT.2020 linear → PQ → Y'CbCr (BT.2020). */
private fun yuvPq(r: Float, g: Float, b: Float): FloatArray {
    fun lin(c: Float) = c.toDouble().pow(2.2)
    val (lr, lg, lb) = Triple(lin(r), lin(g), lin(b))
    // BT.709 → BT.2020 (linear).
    val r2 = 0.6274 * lr + 0.3293 * lg + 0.0433 * lb
    val g2 = 0.0691 * lr + 0.9195 * lg + 0.0114 * lb
    val b2 = 0.0164 * lr + 0.0880 * lg + 0.8956 * lb
    fun pq(l: Double): Double {
        val yy = (l * 203.0 / 10_000.0).coerceIn(0.0, 1.0).pow(0.1593017578125)
        return ((0.8359375 + 18.8515625 * yy) / (1 + 18.6875 * yy)).pow(78.84375)
    }
    val (pr, pg, pb) = Triple(pq(r2), pq(g2), pq(b2))
    val luma = 0.2627 * pr + 0.6780 * pg + 0.0593 * pb
    return floatArrayOf(luma.toFloat(), ((pb - luma) / 1.8814).toFloat(), ((pr - luma) / 1.4746).toFloat())
}

/** Float (0..1) → IEEE 754 Halbfließkomma (Android-eigenes Half erst ab API 26). */
private fun toHalf(f: Float): Short {
    if (f <= 0f) return 0
    val bits = java.lang.Float.floatToIntBits(f)
    val exp = ((bits ushr 23) and 0xFF) - 127 + 15
    val mant = bits and 0x7FFFFF
    if (exp <= 0) return 0
    if (exp >= 31) return 0x7BFF
    var value = (exp shl 10) or (mant ushr 13)
    if (mant and 0x1000 != 0) value++
    return value.toShort()
}
