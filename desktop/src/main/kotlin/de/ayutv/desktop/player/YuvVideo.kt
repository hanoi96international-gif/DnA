package de.ayutv.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import java.nio.ByteBuffer

/**
 * Schneller Videoweg für 4K: Die DNA Engine liefert das Bild als YUV 4:2:0 (drei Ebenen, bei 4K 12 MB statt 33 MB in RGB) und
 * die Grafikkarte rechnet die Farben per Shader um. Das spart die Umrechnung in RGB auf dem Prozessor (bei 4K mit
 * 50 Bildern die teuerste Arbeit) und eine Kopie je Bild.
 *
 * Dabei richtig: Farbmatrix nach Auflösung (BT.601 für SD, BT.709 für HD, BT.2020 für UHD), Seitenverhältnis aus dem
 * Stream (anamorphes SD in 16:9) und HDR: PQ (HDR10) und HLG werden auf einen normalen Bildschirm abgebildet
 * (Tonwerte und Farbraum), statt blass und flau zu erscheinen.
 */
object YuvVideo {
    /** HDR-Art des Bildes. */
    enum class Transfer { SDR, PQ, HLG }

    /**
     * Wie das Bild abgelegt ist.
     * - [I420]: drei Ebenen mit 8 Bit (bisheriger Weg, „Kompatibel“).
     * - [NV12]: Helligkeit + Farbe verschränkt, 8 Bit. So liefert die Hardware-Dekodierung das Bild ohnehin; die Engine muss
     *   nichts umsortieren (bei 4K mit 50 Bildern spart das je Sekunde rund 600 MB Speicherverkehr).
     * - [P010]: wie NV12, aber mit 10 Bit je Wert (in 16 Bit). Für HDR und 10-Bit-HEVC: keine Abstufungen („Banding“)
     *   in Himmel und Verläufen, weil die volle Genauigkeit bis in die Tonwertanpassung erhalten bleibt.
     * - [I010]: drei Ebenen mit 10 Bit (in 16 Bit, Wert in den unteren Bits). So liefert der Software-Decoder 10-Bit-HEVC
     *   (4K HDR ohne Grafikkarten-Dekodierung): keine Umwandlung nötig, die bei 4K50 den Prozessor ausbremsen würde.
     */
    enum class Layout(val chroma: String, val planes: Int) {
        I420("I420", 3),
        NV12("NV12", 2),
        P010("P010", 2),
        I010("I010", 3),
    }

    /** Farbmatrix (Luma-Anteile Rot/Blau) nach Norm. */
    enum class Matrix(val kr: Float, val kb: Float) {
        BT601(0.299f, 0.114f),
        BT709(0.2126f, 0.0722f),
        BT2020(0.2627f, 0.0593f),
    }

    /** Pitch (Zeilenlänge in Bytes) und Zeilen je Ebene für [layout] (ausgerichtet auf 32 bzw. 16). */
    fun planes(width: Int, height: Int, layout: Layout = Layout.I420): Pair<IntArray, IntArray> {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        return when (layout) {
            Layout.I420 -> intArrayOf(align(width, 32), align(cw, 32), align(cw, 32)) to intArrayOf(align(height, 16), align(ch, 16), align(ch, 16))
            // Farbebene: je Bildpunkt-Paar U und V nebeneinander (NV12: 2 Bytes, P010: 4 Bytes).
            Layout.NV12 -> intArrayOf(align(width, 32), align(cw * 2, 32)) to intArrayOf(align(height, 16), align(ch, 16))
            Layout.P010 -> intArrayOf(align(width * 2, 32), align(cw * 4, 32)) to intArrayOf(align(height, 16), align(ch, 16))
            Layout.I010 -> intArrayOf(align(width * 2, 32), align(cw * 2, 32), align(cw * 2, 32)) to intArrayOf(align(height, 16), align(ch, 16), align(ch, 16))
        }
    }

    /**
     * Breite der Ebene [index] als Graustufenbild in Bytes: Alle Ebenen werden als 8-Bit-Graustufen hochgeladen (das
     * kann jede Grafik); verschränkte Farbe (U/V) und 16-Bit-Werte setzt der Shader selbst zusammen.
     */
    fun byteWidth(layout: Layout, index: Int, width: Int): Int {
        val cw = (width + 1) / 2
        return when (layout) {
            Layout.I420 -> if (index == 0) width else cw
            Layout.NV12 -> if (index == 0) width else cw * 2
            Layout.P010 -> if (index == 0) width * 2 else cw * 4
            Layout.I010 -> if (index == 0) width * 2 else cw * 2
        }
    }

    private fun align(value: Int, to: Int) = (value + to - 1) / to * to

    /**
     * Der Shader: drei Ebenen (Y, U, V als Graustufen), Farbmatrix, Wertebereich, HDR-Anpassung.
     * Ausgabe in sRGB für den Bildschirm.
     */
    private const val SKSL = """
uniform shader luma;
uniform shader cb;
uniform shader cr;
uniform float kr;
uniform float kb;
uniform float fullRange;
uniform float transfer;   // 0 = SDR, 1 = PQ, 2 = HLG
uniform float peak;       // Spitzenhelligkeit des Inhalts (Nits) für die Tonwertanpassung
uniform float planes;     // 0 = I420 (drei Ebenen), 1 = NV12 (Farbe verschränkt), 2 = P010 (wie NV12, 10 Bit), 3 = I010 (drei Ebenen, 10 Bit)
uniform float2 lumaSize;  // Bildpunkte der Helligkeit (NV12/P010)
uniform float2 chromaSize; // Bildpunkte der Farbe (NV12/P010)

float3 pqToNits(float3 e) {
    float3 p = pow(clamp(e, 0.0, 1.0), float3(1.0 / 78.84375));
    float3 num = max(p - 0.8359375, float3(0.0));
    float3 den = 18.8515625 - 18.6875 * p;
    return pow(num / den, float3(1.0 / 0.1593017578125)) * 10000.0;
}

float3 hlgToNits(float3 e) {
    e = clamp(e, 0.0, 1.0);
    float3 low = e * e / 3.0;
    float3 high = (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
    float3 scene = mix(low, high, step(0.5, e));
    float ys = dot(scene, float3(0.2627, 0.6780, 0.0593));
    return scene * pow(max(ys, 1e-6), 0.2) * 1000.0;
}

float3 srgbEncode(float3 v) {
    v = clamp(v, 0.0, 1.0);
    return mix(v * 12.92, 1.055 * pow(v, float3(1.0 / 2.4)) - 0.055, step(0.0031308, v));
}

// Ein Bildpunkt (ganzzahlig) einer Ebene als Graustufe; die Ebenen werden dafür ohne Filter gelesen.
float texY(float x, float y) {
    return luma.eval(float2(x + 0.5, y + 0.5)).r;
}

float texC(float x, float y) {
    return cb.eval(float2(x + 0.5, y + 0.5)).r;
}

float texV(float x, float y) {
    return cr.eval(float2(x + 0.5, y + 0.5)).r;
}

// Helligkeit am Bildpunkt t: NV12 ein Byte, P010 zwei Bytes (Low, High), Ergebnis 0..1 bezogen auf 8 bzw. 16 Bit.
float lumaAt(float2 t) {
    t = clamp(t, float2(0.0), lumaSize - 1.0);
    if (planes < 1.5) return texY(t.x, t.y);
    return (texY(2.0 * t.x + 1.0, t.y) * 255.0 * 256.0 + texY(2.0 * t.x, t.y) * 255.0) / 65535.0;
}

float2 chromaAt(float2 t) {
    t = clamp(t, float2(0.0), chromaSize - 1.0);
    if (planes < 1.5) return float2(texC(2.0 * t.x, t.y), texC(2.0 * t.x + 1.0, t.y));
    if (planes > 2.5) {
        // I010: U und V in eigenen Ebenen, je zwei Bytes.
        float iu = texC(2.0 * t.x + 1.0, t.y) * 255.0 * 256.0 + texC(2.0 * t.x, t.y) * 255.0;
        float iv = texV(2.0 * t.x + 1.0, t.y) * 255.0 * 256.0 + texV(2.0 * t.x, t.y) * 255.0;
        return float2(iu, iv) / 65535.0;
    }
    float u = texC(4.0 * t.x + 1.0, t.y) * 255.0 * 256.0 + texC(4.0 * t.x, t.y) * 255.0;
    float v = texC(4.0 * t.x + 3.0, t.y) * 255.0 * 256.0 + texC(4.0 * t.x + 2.0, t.y) * 255.0;
    return float2(u, v) / 65535.0;
}

// Bilinear wie die Grafikkarte, aber selbst gerechnet (verschränkte Werte dürfen nicht gemischt werden).
float lumaLinear(float2 p) {
    float2 q = p - 0.5;
    float2 i = floor(q);
    float2 f = q - i;
    return mix(mix(lumaAt(i), lumaAt(i + float2(1.0, 0.0)), f.x), mix(lumaAt(i + float2(0.0, 1.0)), lumaAt(i + float2(1.0, 1.0)), f.x), f.y);
}

float2 chromaLinear(float2 p) {
    float2 q = p - 0.5;
    float2 i = floor(q);
    float2 f = q - i;
    return mix(mix(chromaAt(i), chromaAt(i + float2(1.0, 0.0)), f.x), mix(chromaAt(i + float2(0.0, 1.0)), chromaAt(i + float2(1.0, 1.0)), f.x), f.y);
}

half4 main(float2 p) {
    float y;
    float u;
    float v;
    if (planes < 0.5) {
        y = luma.eval(p).r;
        u = cb.eval(p * 0.5).r;
        v = cr.eval(p * 0.5).r;
    } else {
        y = lumaLinear(p);
        float2 c = chromaLinear(p * 0.5);
        u = c.x;
        v = c.y;
    }
    // In Codewerte umrechnen: 8 Bit 0–255, P010 0–1023 (10 Bit in den oberen Bits von 16), I010 0–1023 (untere Bits).
    float scale = planes > 2.5 ? 65535.0 : (planes > 1.5 ? 65535.0 / 64.0 : 255.0);
    float k = planes > 1.5 ? 4.0 : 1.0;
    float top = planes > 1.5 ? 1023.0 : 255.0;
    // Begrenzter Bereich (16–235/240, bei 10 Bit 64–940/960) ist bei Fernsehen üblich.
    float Y = fullRange > 0.5 ? y * scale / top : (y * scale - 16.0 * k) / (219.0 * k);
    float U = fullRange > 0.5 ? (u * scale - 128.0 * k) / top : (u * scale - 128.0 * k) / (224.0 * k);
    float V = fullRange > 0.5 ? (v * scale - 128.0 * k) / top : (v * scale - 128.0 * k) / (224.0 * k);
    float kg = 1.0 - kr - kb;
    float R = Y + 2.0 * (1.0 - kr) * V;
    float B = Y + 2.0 * (1.0 - kb) * U;
    float G = (Y - kr * R - kb * B) / kg;
    float3 rgb = float3(R, G, B);
    if (transfer < 0.5) return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);

    // HDR: in Licht (Nits) zurückrechnen, BT.2020 → BT.709, Tonwerte auf SDR (Weiß = 203 Nits) abbilden.
    float3 nits = transfer < 1.5 ? pqToNits(rgb) : hlgToNits(rgb);
    float3 lin = float3(
        1.6605 * nits.r - 0.5876 * nits.g - 0.0728 * nits.b,
        -0.1246 * nits.r + 1.1329 * nits.g - 0.0083 * nits.b,
        -0.0182 * nits.r - 0.1006 * nits.g + 1.1187 * nits.b
    ) / 203.0;
    lin = max(lin, float3(0.0));
    float l = dot(lin, float3(0.2126, 0.7152, 0.0722));
    float white = max(peak / 203.0, 1.0);
    // Bis 80 % des SDR-Weiß unverändert (Hauttöne, Mitten wie gemastert); darüber werden die Spitzlichter bis zur
    // Spitzenhelligkeit weich in den Rest gerundet statt abgeschnitten (ähnlich BT.2390).
    const float knee = 0.8;
    float mapped = l;
    if (l > knee) {
        float x = (l - knee) / (1.0 - knee);
        float w = max((white - knee) / (1.0 - knee), 1.0001);
        mapped = knee + (1.0 - knee) * min(x * (1.0 + x / (w * w)) / (1.0 + x), 1.0);
    }
    float3 out3 = l > 1e-6 ? lin * (mapped / l) : lin;
    return half4(half3(srgbEncode(out3)), 1.0);
}
"""

    /** Übersetzter Shader; null, wenn die Grafik das nicht kann (dann nimmt der Player den RGB-Weg). */
    val effect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(SKSL) }.getOrNull() }
    val available: Boolean get() = effect != null

    /** Ebene aus dem Puffer des Decoders kopieren (eine Kopie im nativen Speicher, ohne Umweg über Java). */
    fun copyPlane(source: ByteBuffer, pitch: Int, width: Int, height: Int): Image {
        val info = ImageInfo(width, height, ColorType.GRAY_8, ColorAlphaType.OPAQUE)
        val bitmap = Bitmap()
        if (!bitmap.allocPixels(info, pitch)) error("kein Speicher für ${width}×$height")
        // Skia legt nur (Zeilen − 1) × Abstand + Breite an: die letzte Zeile ohne Polster (sonst schreibt die Kopie
        // über das Ende hinaus, sobald der Zeilenabstand größer ist als die Breite).
        val size = pitch * (height - 1) + width
        val pixels = bitmap.peekPixels() ?: error("Pixel nicht erreichbar")
        val target = Pointer(pixels.addr).getByteBuffer(0, size.toLong())
        val view = source.duplicate()
        view.rewind()
        view.limit(minOf(size, view.capacity()))
        target.put(view)
        bitmap.setImmutable()
        return Image.makeFromBitmap(bitmap).also { bitmap.close() }
    }

    /** Für Tests: Ebene aus einem Byte-Feld. */
    fun planeFrom(bytes: ByteArray, pitch: Int, width: Int, height: Int): Image {
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        return copyPlane(buffer, pitch, width, height)
    }

    /** Für Tests und den Starttest: einfarbiges Bild in [layout] aus 8-Bit-Codewerten (bei P010 ×4). */
    fun solid(layout: Layout, width: Int, height: Int, y: Int, u: Int, v: Int, transfer: Transfer = Transfer.SDR): YuvFrame {
        val (pitches, lines) = planes(width, height, layout)
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        fun plane(index: Int, w: Int, h: Int, pattern: ByteArray): Image {
            val bytes = ByteArray(pitches[index] * lines[index]) { pattern[(it % pitches[index]) % pattern.size] }
            return planeFrom(bytes, pitches[index], byteWidth(layout, index, width), h)
        }
        fun p010(code8: Int): ByteArray { val word = (code8 * 4) shl 6; return byteArrayOf(word.toByte(), (word shr 8).toByte()) }
        fun i010(code8: Int): ByteArray { val word = code8 * 4; return byteArrayOf(word.toByte(), (word shr 8).toByte()) }
        return when (layout) {
            Layout.I420 -> YuvFrame(plane(0, width, height, byteArrayOf(y.toByte())), plane(1, cw, ch, byteArrayOf(u.toByte())), plane(2, cw, ch, byteArrayOf(v.toByte())), width, height, width.toFloat() / height, transfer)
            Layout.NV12 -> YuvFrame(plane(0, width, height, byteArrayOf(y.toByte())), plane(1, cw, ch, byteArrayOf(u.toByte(), v.toByte())), null, width, height, width.toFloat() / height, transfer, layout = layout)
            Layout.P010 -> YuvFrame(plane(0, width, height, p010(y)), plane(1, cw, ch, p010(u) + p010(v)), null, width, height, width.toFloat() / height, transfer, layout = layout)
            Layout.I010 -> YuvFrame(plane(0, width, height, i010(y)), plane(1, cw, ch, i010(u)), plane(2, cw, ch, i010(v)), width, height, width.toFloat() / height, transfer, layout = layout)
        }
    }

    /** Adresse eines direkten Puffers (nur zur Kontrolle, ob der Decoder wirklich native Puffer liefert). */
    fun isNative(buffer: ByteBuffer): Boolean = buffer.isDirect && Pointer.nativeValue(Native.getDirectBufferPointer(buffer)) != 0L

    /** Passende Farbmatrix: UHD/HDR = BT.2020, HD = BT.709, SD = BT.601. */
    fun matrixFor(height: Int, transfer: Transfer): Matrix = when {
        transfer != Transfer.SDR || height >= 2000 -> Matrix.BT2020
        height >= 700 -> Matrix.BT709
        else -> Matrix.BT601
    }
}

/**
 * Ein Bild in drei Ebenen. [aspect] ist das Seitenverhältnis, wie es gezeigt werden soll (Breite/Höhe).
 * Gehört nach dem Zeichnen der Oberfläche; [close] gibt den Speicher sofort frei.
 */
class YuvFrame(
    val y: Image,
    /** Farbe: bei I420 nur U, bei NV12/P010 U und V verschränkt. */
    val u: Image,
    /** Nur bei I420. */
    val v: Image?,
    val width: Int,
    val height: Int,
    val aspect: Float,
    val transfer: YuvVideo.Transfer,
    val fullRange: Boolean = false,
    val peakNits: Float = 1000f,
    val layout: YuvVideo.Layout = YuvVideo.Layout.I420,
) : AutoCloseable {
    val matrix: YuvVideo.Matrix = YuvVideo.matrixFor(height, transfer)

    /** Bild eingepasst (mit schwarzen Rändern) in [dstWidth] × [dstHeight] zeichnen. */
    /** [fit]: 0 = ganzes Bild (Ränder), 1 = Bild füllen (Ränder abschneiden), 2 = strecken. */
    fun draw(canvas: Canvas, dstWidth: Float, dstHeight: Float, fit: Int = 0) {
        val effect = YuvVideo.effect ?: return
        if (dstWidth <= 0f || dstHeight <= 0f) return
        val wider = dstWidth / dstHeight > aspect
        val (shownWidth, shownHeight) = when {
            fit == 2 -> dstWidth to dstHeight
            fit == 1 && wider -> dstWidth to dstWidth / aspect
            fit == 1 -> dstHeight * aspect to dstHeight
            wider -> dstHeight * aspect to dstHeight
            else -> dstWidth to dstWidth / aspect
        }
        val left = (dstWidth - shownWidth) / 2f
        val top = (dstHeight - shownHeight) / 2f
        val builder = RuntimeShaderBuilder(effect)
        // I420: die Grafikkarte filtert; NV12/P010: Bytes unverändert lesen, der Shader filtert selbst.
        val sampling = if (layout == YuvVideo.Layout.I420) SamplingMode.LINEAR else SamplingMode.DEFAULT
        builder.child("luma", y.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null))
        builder.child("cb", u.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null))
        builder.child("cr", (v ?: u).makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, sampling, null))
        builder.uniform("lumaSize", width.toFloat(), height.toFloat())
        builder.uniform("chromaSize", ((width + 1) / 2).toFloat(), ((height + 1) / 2).toFloat())
        builder.uniform("kr", matrix.kr)
        builder.uniform("kb", matrix.kb)
        builder.uniform("fullRange", if (fullRange) 1f else 0f)
        builder.uniform("transfer", transfer.ordinal.toFloat())
        builder.uniform("peak", peakNits)
        builder.uniform("planes", layout.ordinal.toFloat())
        // Bildschirmpunkt → Bildpunkt der Y-Ebene.
        val sx = width / shownWidth
        val sy = height / shownHeight
        val local = Matrix33(1f / sx, 0f, left, 0f, 1f / sy, top, 0f, 0f, 1f)
        val shader = builder.makeShader(local)
        Paint().use { paint ->
            paint.shader = shader
            canvas.drawRect(Rect.makeXYWH(maxOf(left, 0f), maxOf(top, 0f), minOf(shownWidth, dstWidth), minOf(shownHeight, dstHeight)), paint)
        }
        shader.close()
        builder.close()
    }

    override fun close() {
        y.close()
        u.close()
        v?.close()
    }
}
