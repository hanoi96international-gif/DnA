package de.ayutv.data.translate.kern

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Die Rechenkerne: Matrixmultiplikation (Gleitkomma und 8 Bit) und Faltung. Reines Kotlin ohne
 * nativen Code: läuft auf jedem Gerät, auf dem die App läuft, und kann nicht nativ abstürzen.
 */
class Kernels(private val threads: Int, private val pool: ExecutorService?) {

    /** Teilt 0 until [n] in höchstens [threads] Stücke auf und rechnet sie parallel. */
    fun parallel(n: Int, minChunk: Int = 1, body: (from: Int, to: Int) -> Unit) {
        val parts = minOf(threads, (n + minChunk - 1) / minChunk).coerceAtLeast(1)
        if (parts == 1 || pool == null) {
            body(0, n)
            return
        }
        val step = (n + parts - 1) / parts
        val tasks = (0 until parts).map { p ->
            val from = p * step
            val to = minOf(n, from + step)
            Callable { if (from < to) body(from, to) }
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }

    /** C[M,N] = A[M,K] · B[K,N] (Gleitkomma, zeilenweise gespeichert). */
    fun sgemm(a: FloatArray, aOff: Int, b: FloatArray, bOff: Int, c: FloatArray, cOff: Int, m: Int, n: Int, k: Int) {
        val work = m.toLong() * n * k
        if (work < 32_768 || threads == 1) {
            sgemmRange(a, aOff, b, bOff, c, cOff, n, k, 0, m, 0, n)
        } else if (m >= threads * 2) {
            parallel(m) { from, to -> sgemmRange(a, aOff, b, bOff, c, cOff, n, k, from, to, 0, n) }
        } else {
            parallel(n, 64) { from, to -> sgemmRange(a, aOff, b, bOff, c, cOff, n, k, 0, m, from, to) }
        }
    }

    private fun sgemmRange(
        a: FloatArray, aOff: Int, b: FloatArray, bOff: Int, c: FloatArray, cOff: Int, n: Int, k: Int,
        m0: Int, m1: Int, n0: Int, n1: Int,
    ) {
        // Spalten in Blöcken, damit die Ergebniszeile im schnellen Zwischenspeicher bleibt. Die innerste
        // Schleife ist bewusst einfach: so kann der Android-Compiler sie mit NEON vektorisieren.
        var j0 = n0
        while (j0 < n1) {
            val j1 = minOf(n1, j0 + SGEMM_BLOCK)
            for (i in m0 until m1) {
                val ci = cOff + i * n
                val ai = aOff + i * k
                for (p in 0 until k) {
                    val av = a[ai + p]
                    if (av == 0f) continue
                    val bp = bOff + p * n
                    for (j in j0 until j1) c[ci + j] += av * b[bp + j]
                }
            }
            j0 = j1
        }
    }

    /**
     * Ganzzahl-Matrixmultiplikation mit 8-Bit-Gewichten: acc[M,N] = Σ_k a[m,k] · b[k,n].
     * [b] ist ein UINT8/INT8-Tensor [K,N], gerne direkt aus der Modelldatei ([Tensor.Mapped]).
     */
    fun igemm(a: IntArray, m: Int, k: Int, b: Tensor, n: Int): IntArray {
        val acc = IntArray(m * n)
        val unsigned = b.type == DType.UINT8
        val data = b.data
        parallel(n, 256) { from, to ->
            val row = ByteArray(minOf(BLOCK, to - from))
            val view = (data as? Tensor.Mapped)?.view()
            val array = data as? ByteArray
            var j0 = from
            while (j0 < to) {
                val len = minOf(BLOCK, to - j0)
                for (p in 0 until k) {
                    val src: ByteArray
                    val srcOff: Int
                    if (array != null) {
                        src = array
                        srcOff = p * n + j0
                    } else {
                        (data as Tensor.Mapped).read(view!!, p * n + j0, row, 0, len)
                        src = row
                        srcOff = 0
                    }
                    for (i in 0 until m) {
                        val av = a[i * k + p]
                        if (av == 0) continue
                        val ci = i * n + j0
                        if (unsigned) {
                            for (j in 0 until len) acc[ci + j] += av * (src[srcOff + j].toInt() and 0xFF)
                        } else {
                            for (j in 0 until len) acc[ci + j] += av * src[srcOff + j]
                        }
                    }
                }
                j0 += len
            }
        }
        return acc
    }

    /**
     * acc[M,N] = Σ_k a[m,k] · w[n,k]: Gewichte zeilenweise je Ausgabe ([w] hat die Form [N,K]), wie die
     * Wortschatz-Tabelle des Übersetzers. Jede Zeile wird einmal aus der Datei gelesen.
     */
    fun igemmTransposed(a: IntArray, m: Int, k: Int, w: Tensor, n: Int): IntArray {
        val acc = IntArray(m * n)
        val unsigned = w.type == DType.UINT8
        val data = w.data
        parallel(n, 256) { from, to ->
            val chunk = 64
            val buffer = ByteArray(chunk * k)
            val view = (data as? Tensor.Mapped)?.view()
            val array = data as? ByteArray
            var r0 = from
            while (r0 < to) {
                val rows = minOf(chunk, to - r0)
                val src: ByteArray
                val base: Int
                if (array != null) {
                    src = array
                    base = r0 * k
                } else {
                    (data as Tensor.Mapped).read(view!!, r0 * k, buffer, 0, rows * k)
                    src = buffer
                    base = 0
                }
                for (r in 0 until rows) {
                    val o = base + r * k
                    for (i in 0 until m) {
                        val ai = i * k
                        var s = 0
                        if (unsigned) {
                            for (p in 0 until k) s += a[ai + p] * (src[o + p].toInt() and 0xFF)
                        } else {
                            for (p in 0 until k) s += a[ai + p] * src[o + p]
                        }
                        acc[i * n + r0 + r] = s
                    }
                }
                r0 += rows
            }
        }
        return acc
    }

    /**
     * C[M,N] = A[M,K] · ((Bq − z) · s) für dequantisierte Gewichte, ohne sie auszurechnen.
     * A wird je Zeile symmetrisch auf 8 Bit gebracht (wie bei quantisierten Modellen üblich).
     */
    fun sgemmDequantized(a: FloatArray, aOff: Int, m: Int, k: Int, b: Tensor.Dequantized, n: Int): FloatArray {
        val qa = IntArray(m * k)
        val scales = FloatArray(m)
        val sums = LongArray(m)
        for (i in 0 until m) {
            var max = 0f
            for (p in 0 until k) max = maxOf(max, abs(a[aOff + i * k + p]))
            val scale = if (max == 0f) 1f else max / 127f
            scales[i] = scale
            var sum = 0L
            for (p in 0 until k) {
                val q = (a[aOff + i * k + p] / scale).roundToInt()
                qa[i * k + p] = q
                sum += q
            }
            sums[i] = sum
        }
        val q = b.quantized
        val acc = (q.data as? Tensor.Transposed)?.let { igemmTransposed(qa, m, k, it.source, n) } ?: igemm(qa, m, k, q, n)
        val c = FloatArray(m * n)
        for (i in 0 until m) {
            val f = scales[i] * b.scale
            val correction = b.zeroPoint * sums[i]
            for (j in 0 until n) c[i * n + j] = ((acc[i * n + j] - correction) * f).toFloat()
        }
        return c
    }

    /**
     * Faltung (NCHW) mit Gruppen, Schrittweite, Rand und Dilatation.
     * Tiefenweise Faltung direkt, sonst Spalten aufrollen (im2col) und multiplizieren.
     */
    fun conv(
        x: FloatArray, n: Int, c: Int, h: Int, w: Int,
        weight: FloatArray, m: Int, kh: Int, kw: Int, bias: FloatArray?,
        group: Int, sh: Int, sw: Int, pt: Int, pl: Int, pb: Int, pr: Int, dh: Int, dw: Int,
    ): Triple<FloatArray, Int, Int> {
        val oh = (h + pt + pb - dh * (kh - 1) - 1) / sh + 1
        val ow = (w + pl + pr - dw * (kw - 1) - 1) / sw + 1
        val out = FloatArray(n * m * oh * ow)
        val cg = c / group
        val mg = m / group
        val plane = oh * ow
        for (b in 0 until n) {
            if (cg == 1 && mg == 1) {
                parallel(c) { from, to ->
                    for (ch in from until to) {
                        depthwise(x, ((b * c) + ch) * h * w, h, w, weight, ch * kh * kw, kh, kw, out, ((b * m) + ch) * plane, oh, ow, sh, sw, pt, pl, dh, dw)
                        bias?.let { val v = it[ch]; val o = ((b * m) + ch) * plane; for (q in 0 until plane) out[o + q] += v }
                    }
                }
                continue
            }
            val kk = cg * kh * kw
            val pointwise = kh == 1 && kw == 1 && sh == 1 && sw == 1 && pt == 0 && pl == 0 && pb == 0 && pr == 0
            for (g in 0 until group) {
                val inOff = ((b * c) + g * cg) * h * w
                val col: FloatArray
                val colOff: Int
                if (pointwise) {
                    col = x
                    colOff = inOff
                } else {
                    col = FloatArray(kk * plane)
                    colOff = 0
                    im2col(x, inOff, cg, h, w, kh, kw, sh, sw, pt, pl, dh, dw, oh, ow, col)
                }
                val outOff = ((b * m) + g * mg) * plane
                sgemm(weight, g * mg * kk, col, colOff, out, outOff, mg, plane, kk)
                bias?.let {
                    for (o in 0 until mg) {
                        val v = it[g * mg + o]
                        val base = outOff + o * plane
                        for (q in 0 until plane) out[base + q] += v
                    }
                }
            }
        }
        return Triple(out, oh, ow)
    }

    private fun depthwise(
        x: FloatArray, xOff: Int, h: Int, w: Int, wt: FloatArray, wOff: Int, kh: Int, kw: Int,
        out: FloatArray, oOff: Int, oh: Int, ow: Int, sh: Int, sw: Int, pt: Int, pl: Int, dh: Int, dw: Int,
    ) {
        for (oy in 0 until oh) {
            for (ky in 0 until kh) {
                val iy = oy * sh - pt + ky * dh
                if (iy < 0 || iy >= h) continue
                val rowIn = xOff + iy * w
                val rowOut = oOff + oy * ow
                for (kx in 0 until kw) {
                    val wv = wt[wOff + ky * kw + kx]
                    val shift = kx * dw - pl
                    // Gültiger Ausgabebereich, in dem ix = ox·sw + shift im Bild liegt.
                    var ox0 = 0
                    while (ox0 < ow && ox0 * sw + shift < 0) ox0++
                    var ox1 = ow
                    while (ox1 > ox0 && (ox1 - 1) * sw + shift >= w) ox1--
                    if (sw == 1) {
                        val d = rowIn + shift
                        for (ox in ox0 until ox1) out[rowOut + ox] += wv * x[d + ox]
                    } else {
                        for (ox in ox0 until ox1) out[rowOut + ox] += wv * x[rowIn + ox * sw + shift]
                    }
                }
            }
        }
    }

    private fun im2col(
        x: FloatArray, off: Int, c: Int, h: Int, w: Int, kh: Int, kw: Int, sh: Int, sw: Int,
        pt: Int, pl: Int, dh: Int, dw: Int, oh: Int, ow: Int, col: FloatArray,
    ) {
        val plane = oh * ow
        var row = 0
        for (ch in 0 until c) {
            for (ky in 0 until kh) {
                for (kx in 0 until kw) {
                    val base = row * plane
                    for (oy in 0 until oh) {
                        val iy = oy * sh - pt + ky * dh
                        val o = base + oy * ow
                        if (iy < 0 || iy >= h) continue
                        val rowIn = off + (ch * h + iy) * w
                        for (ox in 0 until ow) {
                            val ix = ox * sw - pl + kx * dw
                            if (ix in 0 until w) col[o + ox] = x[rowIn + ix]
                        }
                    }
                    row++
                }
            }
        }
    }

    companion object {
        private const val BLOCK = 2048
        private const val SGEMM_BLOCK = 1024
    }
}
