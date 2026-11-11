package de.ayutv.player.dna.engine

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Zeigt Bilder des eigenen Software-Decoders ([NativeVideo]) über die GPU: drei 8-Bit-Ebenen (YUV 4:2:0) als Texturen,
 * Farbumrechnung im Shader (BT.601/709/2020, begrenzter oder voller Wertebereich). HDR10 (PQ) wird auf SDR
 * umgerechnet (Helligkeit abgebildet, BT.2020-Farben nach BT.709) – sonst wirkt es blass und dunkel. HLG ist weitgehend
 * SDR-verträglich und bekommt nur die Farbumrechnung.
 *
 * Über EGL statt direkt in die Fläche schreiben: EGL gibt die Fläche beim Freigeben wieder ab, danach kann ein
 * Hardware-Decoder sie wieder nutzen (nächster Sender). Alle Aufrufe auf einem Thread (dem des Software-Decoders).
 */
internal class GlYuvRenderer(output: Surface, val hdr: Boolean = false) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val textures = IntArray(3)
    private var texW = 0
    private var texH = 0
    private var texHalf = false
    private var aPos = 0
    private var uMatrix = 0
    private var uOffset = 0
    private var uMode = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private var released = false

    init {
        try {
            setUp(output)
        } catch (e: Throwable) {
            release()
            throw IllegalStateException("Anzeige des Software-Decoders: ${e.message}", e)
        }
    }

    private fun setUp(output: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "kein EGL-Display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        // HDR: 10 Bit je Farbe, OpenGL ES 3 (Halbfließkomma-Texturen) und eine Fläche in BT.2020/PQ – das System gibt
        // sie dann als HDR10 an das Display. Sonst 8 Bit SDR wie bisher.
        if (hdr) check(hasHdrExtension(display)) { "EGL ohne BT.2020/PQ" }
        val attribs = if (hdr) intArrayOf(
            EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10, EGL14.EGL_BLUE_SIZE, 10, EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        ) else intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "kein EGL-Format" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, if (hdr) 3 else 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        val surfaceAttribs = if (hdr) intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_PQ_EXT, EGL14.EGL_NONE) else intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(display, config, output, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uMatrix = GLES20.glGetUniformLocation(program, "uMatrix")
        uOffset = GLES20.glGetUniformLocation(program, "uOffset")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        GLES20.glUseProgram(program)
        GLES20.glGenTextures(3, textures, 0)
        for (k in 0 until 3) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + k)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[k])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex$k"), k)
        }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        val e = GLES20.glGetError()
        check(e == GLES20.GL_NO_ERROR) { "GL-Fehler $e" }
    }

    /** Bild zeigen (sofort; [presentNs]: gewünschter Zeitpunkt, System.nanoTime). */
    fun draw(frame: NativeVideo.Frame, presentNs: Long) {
        if (released) return
        val w = frame.width
        val h = frame.height
        val planes = arrayOf(frame.y, frame.u, frame.v)
        for (k in 0 until 3) {
            val pw = if (k == 0) w else w / 2
            val ph = if (k == 0) h else h / 2
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + k)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[k])
            val realloc = w != texW || h != texH || frame.half != texHalf
            if (frame.half) {
                // 10 Bit als Halbfließkomma (nur im HDR-Kontext, OpenGL ES 3): keine 8-Bit-Stufen in Verläufen.
                if (realloc) GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16F, pw, ph, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, planes[k])
                else GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, pw, ph, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, planes[k])
            } else if (realloc) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, pw, ph, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, planes[k])
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, pw, ph, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, planes[k])
            }
        }
        texW = w
        texH = h
        texHalf = frame.half
        val sw = IntArray(1)
        val sh = IntArray(1)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, sw, 0)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, sh, 0)
        GLES20.glViewport(0, 0, sw[0], sh[0])
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix3fv(uMatrix, 1, false, matrix(frame), 0)
        GLES20.glUniform3fv(uOffset, 1, offset(frame), 0)
        // 0 = SDR, 1 = PQ (HDR10) → SDR, 2 = BT.2020-Farben (HLG/SDR in BT.2020) → BT.709, 3 = HDR10 unverändert
        // (Fläche ist BT.2020/PQ, das Display zeigt echtes HDR).
        val mode = when {
            hdr && frame.transfer == TRC_PQ -> 3
            frame.transfer == TRC_PQ -> 1
            frame.colorSpace == CS_BT2020_NCL || frame.colorSpace == CS_BT2020_CL || frame.transfer == TRC_HLG -> 2
            else -> 0
        }
        GLES20.glUniform1i(uMode, mode)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, VsyncSampler.adjust(presentNs))
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    /** YUV → RGB (Spalten für GLSL), je nach Farbraum und Wertebereich. */
    private fun matrix(frame: NativeVideo.Frame): FloatArray {
        val (kr, kb) = when (frame.colorSpace) {
            CS_BT2020_NCL, CS_BT2020_CL -> 0.2627f to 0.0593f
            CS_BT470BG, CS_SMPTE170M -> 0.299f to 0.114f
            CS_BT709 -> 0.2126f to 0.0722f
            // Unbekannt: SD nach BT.601, sonst BT.709.
            else -> if (frame.height < 600) 0.299f to 0.114f else 0.2126f to 0.0722f
        }
        val kg = 1f - kr - kb
        // Begrenzter Wertebereich: 8 Bit 16–235/240, 10 Bit (Halbfließkomma, ÷1023) 64–940/960.
        val ys = if (frame.fullRange) 1f else if (frame.half) 1023f / 876f else 255f / 219f
        val cs = if (frame.fullRange) 1f else if (frame.half) 1023f / 896f else 255f / 224f
        val rv = 2f * (1f - kr) * cs
        val bu = 2f * (1f - kb) * cs
        val gu = -2f * kb * (1f - kb) / kg * cs
        val gv = -2f * kr * (1f - kr) / kg * cs
        // Spaltenweise: Spalte 0 = Y, 1 = U, 2 = V.
        return floatArrayOf(ys, ys, ys, 0f, gu, bu, rv, gv, 0f)
    }

    private fun offset(frame: NativeVideo.Frame): FloatArray = when {
        frame.half -> floatArrayOf(if (frame.fullRange) 0f else 64f / 1023f, 512f / 1023f, 512f / 1023f)
        frame.fullRange -> floatArrayOf(0f, 0.5f, 0.5f)
        else -> floatArrayOf(16f / 255f, 0.5f, 0.5f)
    }

    fun release() {
        if (released) return
        released = true
        runCatching {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
            }
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

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340

        private fun hasHdrExtension(display: EGLDisplay): Boolean =
            EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)?.contains("EGL_EXT_gl_colorspace_bt2020_pq") == true

        /** Kann die Grafik eine HDR10-Fläche (BT.2020/PQ) anlegen? Einmal geprüft. */
        val hdrAvailable: Boolean by lazy {
            runCatching {
                val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val v = IntArray(2)
                d != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(d, v, 0, v, 1) && hasHdrExtension(d)
            }.getOrDefault(false)
        }

        // AVColorSpace / AVColorTransferCharacteristic (FFmpeg).
        private const val CS_BT709 = 1
        private const val CS_BT470BG = 5
        private const val CS_SMPTE170M = 6
        private const val CS_BT2020_NCL = 9
        private const val CS_BT2020_CL = 10
        private const val TRC_PQ = 16
        private const val TRC_HLG = 18

        private const val VERTEX = """
attribute vec2 aPos;
varying vec2 vPos;
void main() {
    vPos = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        /**
         * PQ → SDR: PQ-Kennlinie in Licht (1.0 = 10 000 nits), SDR-Weiß bei 203 nits, Spitzen weich abgerundet
         * (erweiterter Reinhard, ausgelegt auf 1000 nits), BT.2020 → BT.709, Gamma 2.2.
         */
        private const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D sTex0;
uniform sampler2D sTex1;
uniform sampler2D sTex2;
uniform mat3 uMatrix;
uniform vec3 uOffset;
uniform int uMode;
varying vec2 vPos;
const mat3 TO709 = mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187);
vec3 pqToLinear(vec3 e) {
    vec3 p = pow(max(e, 0.0), vec3(1.0 / 78.84375));
    vec3 n = max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p);
    return pow(n, vec3(1.0 / 0.1593017578125));
}
void main() {
    vec3 yuv = vec3(texture2D(sTex0, vPos).r, texture2D(sTex1, vPos).r, texture2D(sTex2, vPos).r) - uOffset;
    vec3 rgb = clamp(uMatrix * yuv, 0.0, 1.0);
    if (uMode == 1) {
        vec3 l = pqToLinear(rgb) * (10000.0 / 203.0);
        l = max(TO709 * l, 0.0);
        float w = 1000.0 / 203.0;
        l = l * (1.0 + l / (w * w)) / (1.0 + l);
        rgb = pow(clamp(l, 0.0, 1.0), vec3(1.0 / 2.2));
    } else if (uMode == 3) {
        // HDR10: Werte sind schon PQ-kodiert in BT.2020 – genau so auf die PQ-Fläche.
    } else if (uMode == 2) {
        vec3 l = pow(rgb, vec3(2.2));
        rgb = pow(clamp(TO709 * l, 0.0, 1.0), vec3(1.0 / 2.2));
    }
    gl_FragColor = vec4(rgb, 1.0);
}
"""
    }
}
