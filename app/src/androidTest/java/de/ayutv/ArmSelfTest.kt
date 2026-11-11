package de.ayutv

import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ayutv.data.repo.Translators
import de.ayutv.data.translate.TranslatorSelfTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Wie auf dem Fire TV Stick 4K: App mit 32-Bit-ARM-Bibliotheken (im Emulator per ARM-Übersetzung).
 * Prüft Texterkennung und Übersetzung für mehrere Sprachen: zuerst mit ONNX Runtime (seit 0.33 auch auf 32 Bit
 * die Voreinstellung), dann mit dem DnA-Rechenkern (Rückfall, falls ONNX Runtime auf einem Gerät abstürzt).
 */
@RunWith(AndroidJUnit4::class)
class ArmSelfTest {
    @Test
    fun translatorOnArm32() {
        val app = ApplicationProvider.getApplicationContext<AyuApp>()
        val out = File(app.filesDir, "ci/arm.txt").apply { parentFile?.mkdirs(); writeText("") }
        fun log(text: String) {
            Log.i("AyuArm", text)
            out.appendText(text + "\n")
        }
        val failures = mutableListOf<String>()
        log("abis=${Build.SUPPORTED_ABIS.joinToString()} libs=${app.applicationInfo.nativeLibraryDir}")
        // Je Runde ein frischer Übersetzer-Prozess (Speicher-Höchststand je Runde).
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val override = File(app.filesDir, "mt_engine")
        File(app.filesDir, "ort_crashed").delete()
        for ((round, language) in listOf("de", "es", "id", "fr").withIndex()) {
            val kern = round >= 2
            if (kern) override.writeText("kern") else override.delete()
            val result = runBlocking { TranslatorSelfTest.run(language) }
            Translators.remote.release()
            log("round $round $language: ${result.replace("\n", " | ")}")
            failures += result.lines().filterNot { "ok" in it || it.startsWith("Speicher") || it.startsWith("Prozessor") || it.startsWith("Rechenkern") }
            val expected = if (kern) "Rechenkern: DnA" else "Rechenkern: ONNX"
            if (expected !in result) failures += "Runde $round: nicht mit „${expected.removePrefix("Rechenkern: ")}“ gerechnet"
            Thread.sleep(1_000)
            am.runningAppProcesses.orEmpty().filter { it.processName.endsWith(":mt") }.forEach { android.os.Process.killProcess(it.pid) }
            Thread.sleep(1_500)
        }
        override.delete()
        org.junit.Assert.assertTrue("Auf 32-Bit-ARM fehlgeschlagen: $failures", failures.isEmpty())
    }
}
