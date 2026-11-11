package de.ayutv.player.dna.engine

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Halbbilder auf 50 Bilder/s (Bob-Deinterlacing auf der GPU, wie Kodi/mpv „bob“): Der Decoder schreibt jedes Vollbild
 * (zwei verwobene Halbbilder, 1080i) in eine eigene Fläche; daraus entstehen zwei Bilder – erst das obere Halbbild, 20 ms
 * später das untere –, jeweils auf volle Höhe gerechnet und auf einen Bildwechsel des Displays gelegt. Ohne das zeigt
 * das Gerät 1080i mit nur 25 Bildern/s: Bewegung und Laufschriften ruckeln.
 *
 * Liefert der Decoder schon 50 Bilder/s (deinterlact selbst), wird jedes Bild unverändert durchgereicht.
 * Jeder Fehler beim Einrichten wirft im Konstruktor – die Engine spielt dann ohne Umrechnung.
 */
internal class GlDeinterlacer(
    private val output: Surface,
    @Volatile var videoHeight: Int,
    /** false: jedes Bild unverändert durchreichen (Decoder schreibt über die GPU statt direkt in die Anzeige). */
    private val deinterlace: Boolean = true,
) : SurfaceTexture.OnFrameAvailableListener {
    /** HDR10 (PQ): beim Zeichnen auf SDR abbilden. */
    @Volatile var pq = false
    private val thread = HandlerThread("dna-deinterlace").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    /** Hierhin dekodiert der Decoder. */
    lateinit var inputSurface: Surface
        private set

    private val transform = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private var aPos = 0
    private var uTex = 0
    private var uH = 0
    private var uParity = 0
    private var uPq = 0

    /** Gewünschte Anzeigezeit (System.nanoTime, noch nicht auf den Bildwechsel gelegt) je Bildzeit (µs). */
    private val wanted = ConcurrentHashMap<Long, Long>()
    private var lastPtsUs = Long.MIN_VALUE
    private var intervalUs = 40_000L
    @Volatile private var released = false
    /** Halbbild-Reihenfolge: oberes zuerst (Standard bei 1080i50 im DVB). */
    private val topFirst = true

    init {
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                setUp()
            } catch (e: Throwable) {
                error = e
            } finally {
                ready.countDown()
            }
        }
        if (!ready.await(3, TimeUnit.SECONDS)) error = IllegalStateException("GL-Einrichtung hängt")
        error?.let {
            release()
            throw IllegalStateException("Deinterlacing nicht möglich: ${it.message}", it)
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "kein EGL-Display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "kein EGL-Format" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uH = GLES20.glGetUniformLocation(program, "uH")
        uParity = GLES20.glGetUniformLocation(program, "uParity")
        uPq = GLES20.glGetUniformLocation(program, "uPq")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setOnFrameAvailableListener(this, handler)
        inputSurface = Surface(surfaceTexture)
        checkGl("Einrichtung")
    }

    /** Die Engine gibt gleich das Bild mit [ptsUs] frei; es soll zu [wantedNs] erscheinen. */
    fun expect(ptsUs: Long, wantedNs: Long) {
        wanted[ptsUs] = wantedNs
        if (wanted.size > 64) wanted.keys.filter { it < ptsUs - 5_000_000 }.forEach { wanted.remove(it) }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released) return
        try {
            draw()
        } catch (e: Throwable) {
            DnaLog.w(TAG, "Deinterlacing: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun draw() {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(transform)
        val ptsUs = surfaceTexture.timestamp / 1000
        val at = wanted.remove(ptsUs) ?: System.nanoTime()
        if (lastPtsUs != Long.MIN_VALUE) {
            val d = ptsUs - lastPtsUs
            if (d in 10_000..100_000) intervalUs = d
        }
        lastPtsUs = ptsUs
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        GLES20.glViewport(0, 0, w[0], h[0])
        // Kommen schon ~50 Bilder/s (Decoder deinterlact selbst): unverändert. Sonst zwei Halbbilder je Vollbild.
        if (!deinterlace || intervalUs < 30_000) {
            present(-1f, at)
        } else {
            present(if (topFirst) 0f else 1f, at)
            present(if (topFirst) 1f else 0f, at + intervalUs * 1000 / 2)
        }
    }

    private fun present(parity: Float, atNs: Long) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(uTex, 1, false, transform, 0)
        GLES20.glUniform1f(uH, videoHeight.coerceAtLeast(2).toFloat())
        GLES20.glUniform1f(uParity, parity)
        GLES20.glUniform1f(uPq, if (pq) 1f else 0f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, VsyncSampler.adjust(atNs))
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        if (released) return
        released = true
        // Warten, bis die Fläche wirklich frei ist (höchstens 0,5 s): Gleich danach kann ein anderer Decoder oder die
        // Anzeige des Software-Decoders sie belegen wollen – solange EGL sie hält, scheitert das (EGL_BAD_ALLOC).
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                releaseNow()
            } finally {
                done.countDown()
            }
        }
        if (posted && Thread.currentThread() != thread) runCatching { done.await(500, TimeUnit.MILLISECONDS) }
    }

    private fun releaseNow() {
        run {
            runCatching { if (::surfaceTexture.isInitialized) surfaceTexture.setOnFrameAvailableListener(null) }
            runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
            runCatching { if (::surfaceTexture.isInitialized) surfaceTexture.release() }
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    // Kein eglTerminate: Die Standard-Anzeige teilen sich alle (auch die Anzeige des Software-Decoders).
                }
            }
            thread.quitSafely()
        }
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Programm: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun checkGl(what: String) {
        val e = GLES20.glGetError()
        check(e == GLES20.GL_NO_ERROR) { "$what: GL-Fehler $e" }
    }

    private companion object {
        const val TAG = "DnaDeinterlace"

        const val VERTEX = """
attribute vec2 aPos;
varying vec2 vPos;
void main() {
    vPos = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        /**
         * Bob mit linearer Zwischenzeile: Für das gewählte Halbbild (Zeilen [uParity], +2, +4 …) liegt jede Ausgabezeile
         * zwischen zwei Zeilen dieses Halbbilds und wird aus beiden gemischt – so entsteht ein volles Bild ohne Kammeffekt.
         * Gelesen wird immer genau in Zeilenmitte (keine Mischung mit dem anderen Halbbild). uParity < 0: unverändert.
         */
        const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES sTex;
uniform mat4 uTex;
uniform float uH;
uniform float uParity;
uniform float uPq;
varying vec2 vPos;
const mat3 TO709 = mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187);
vec4 sdr(vec4 c) {
    if (uPq < 0.5) return c;
    // PQ → Licht (SDR-Weiß 203 nits), BT.2020 → BT.709, Spitzen weich (1000 nits), Gamma 2.2.
    vec3 p = pow(max(c.rgb, 0.0), vec3(1.0 / 78.84375));
    vec3 l = pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125)) * (10000.0 / 203.0);
    l = max(TO709 * l, 0.0);
    float w = 1000.0 / 203.0;
    l = l * (1.0 + l / (w * w)) / (1.0 + l);
    return vec4(pow(clamp(l, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
}
vec4 row(float x, float r) {
    float v = 1.0 - (r + 0.5) / uH;
    return texture2D(sTex, (uTex * vec4(x, v, 0.0, 1.0)).xy);
}
void main() {
    if (uParity < 0.0) {
        gl_FragColor = sdr(texture2D(sTex, (uTex * vec4(vPos, 0.0, 1.0)).xy));
        return;
    }
    float y = (1.0 - vPos.y) * uH - 0.5;
    float f = (y - uParity) / 2.0;
    float f0 = floor(f);
    float t = clamp(f - f0, 0.0, 1.0);
    float r0 = clamp(f0 * 2.0 + uParity, 0.0, uH - 1.0);
    float r1 = clamp(r0 + 2.0, 0.0, uH - 1.0);
    gl_FragColor = sdr(mix(row(vPos.x, r0), row(vPos.x, r1), t));
}
"""
    }
}
