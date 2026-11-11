package de.ayutv.data.translate

import java.io.File

/**
 * Womit Übersetzer und Texterkennung rechnen:
 * - ONNX Runtime (nativ, schnell), auf allen Geräten zuerst. Auf 32-Bit-ARM (Fire TV Stick 4K) war der
 *   DnA-Rechenkern bisher fest eingestellt, weil neuere ONNX-Runtime-Versionen dort abstürzten; 1.22 läuft im
 *   32-Bit-Test, und der Rechenkern ist dort für Übersetzung beim Schauen zu langsam.
 * - DnA-Rechenkern (reines Kotlin, kann nicht nativ abstürzen, braucht kaum Arbeitsspeicher) dort, wo ONNX Runtime
 *   in dieser Version schon einmal abgestürzt ist (automatisch, mit der nächsten Version wird es wieder versucht).
 */
object Engine {
    enum class Kind(val label: String) {
        ORT("ONNX Runtime"),
        KERN("DnA-Rechenkern (ohne native Bibliothek)"),
    }

    private const val OVERRIDE = "mt_engine"
    private const val ORT_CRASHED = "ort_crashed"

    fun select(baseDir: File): Kind {
        when (runCatching { File(baseDir, OVERRIDE).readText().trim() }.getOrNull()) {
            "kern" -> return Kind.KERN
            "ort" -> return Kind.ORT
        }
        val crashed = File(baseDir, ORT_CRASHED)
        if (crashed.isFile) {
            // Mit jeder neuen Version einmal wieder das schnelle ONNX Runtime versuchen (der Absturz kann
            // behoben sein); stürzt es wieder ab, schreibt markOrtCrash die neue Version hinein.
            val version = runCatching { crashed.readText().trim() }.getOrNull()
            if (version == de.ayutv.BuildConfig.VERSION_NAME || version.isNullOrEmpty()) return Kind.KERN
            crashed.delete()
        }
        return Kind.ORT
    }

    /** ONNX Runtime ist hier nativ abgestürzt: ab jetzt immer mit dem Rechenkern. */
    fun markOrtCrash(baseDir: File) {
        runCatching { File(baseDir, ORT_CRASHED).writeText(de.ayutv.BuildConfig.VERSION_NAME) }
    }
}
