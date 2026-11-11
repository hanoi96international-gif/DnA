package de.ayutv.data.translate

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import de.ayutv.player.EmbeddedSubtitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Übersetzer und Texterkennung in einem eigenen Prozess (":mt"). Die Modelle brauchen viel Speicher
 * und laufen in nativem Code: Reicht der Speicher nicht oder stürzt ein Modell ab, beendet das
 * System nur diesen Prozess. Der Film läuft weiter, der Player meldet den Grund.
 */
class TranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var translator: OnDeviceTranslator
    private lateinit var ocr: SubtitleOcr

    @Volatile
    private var client: Messenger? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        handle(msg)
        true
    })

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        // Rechenkern: mehrere Threads (mit Hintergrund-Vorrang), er braucht kaum Speicher. Auf dem Fire TV
        // Stick 4K (4 Kerne, Bild und Ton im Video-Chip) drei, ein Kern bleibt für Oberfläche und Ton. ONNX Runtime: einer.
        val threads = if (Engine.select(filesDir) == Engine.Kind.KERN) (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 3) else 1
        translator = OnDeviceTranslator(filesDir, http, ::freeMemoryMb, threads = threads, lowMemory = ::lowMemory).apply {
            onProgress = ::progress
            trace = ::step
        }
        ocr = SubtitleOcr(filesDir, http).apply {
            onProgress = ::progress
            trace = ::step
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        // Laufende Anfragen abbrechen und die Modelle (zusammen gut 200 MB) freigeben, ohne auf
        // eine gerade laufende Rechnung zu warten (der Hauptthread darf nicht blockieren).
        scope.coroutineContext.cancelChildren()
        scope.launch {
            runCatching { translator.release() }
            runCatching { ocr.release() }
        }
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        // Die Nachricht wird nach dem Aufruf wiederverwendet: alles Nötige jetzt herausholen.
        val replyTo = msg.replyTo ?: return
        val what = msg.what
        val id = msg.arg1
        val data = Bundle(msg.data)
        client = replyTo
        scope.launch {
            val result = try {
                when (what) {
                    RemoteTranslator.TRANSLATE -> Bundle().apply {
                        val texts = data.getStringArrayList(RemoteTranslator.TEXTS).orEmpty()
                        val target = data.getString(RemoteTranslator.TARGET).orEmpty()
                        putStringArrayList(RemoteTranslator.RESULT, ArrayList(translator.translate(texts, target, data.getString(RemoteTranslator.SOURCE))))
                    }
                    RemoteTranslator.PREPARE -> Bundle().apply {
                        putStringArrayList(RemoteTranslator.RESULT, ArrayList(translator.preparePending()))
                    }
                    RemoteTranslator.RECOGNIZE -> Bundle().apply {
                        val image = EmbeddedSubtitles.GrayImage(
                            data.getInt(RemoteTranslator.WIDTH),
                            data.getInt(RemoteTranslator.HEIGHT),
                            data.getByteArray(RemoteTranslator.PIXELS) ?: ByteArray(0),
                        )
                        putString(RemoteTranslator.RESULT, ocr.recognize(image))
                    }
                    else -> Bundle().apply { putString(RemoteTranslator.ERROR, "Unbekannte Anfrage $what") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Bundle().apply { putString(RemoteTranslator.ERROR, e.message ?: e.javaClass.simpleName) }
            }
            memoryMb("VmHWM")?.let { result.putLong(RemoteTranslator.PEAK, it) }
            memoryMb("RssAnon")?.let { result.putLong(RemoteTranslator.ANON, it) }
            runCatching { replyTo.send(Message.obtain(null, what, id, 0).apply { this.data = result }) }
        }
    }

    /**
     * Aktuellen Schritt in eine Datei schreiben, bevor nativer Code läuft. Stürzt der Prozess dabei ab,
     * liest der Player die Datei und kann sagen, welches Modell bei welchem Schritt abgestürzt ist.
     */
    private fun step(text: String?) {
        runCatching {
            val file = java.io.File(filesDir, RemoteTranslator.STEP_FILE)
            if (text == null) file.delete() else file.writeText("$text · frei ${freeMemoryMb() ?: "?"} MB · ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        }
    }

    /** Speichersparmodus: auf schwachen Geräten; Datei "mt_lowmem" mit on/off erzwingt (Tests). */
    private fun lowMemory(): Boolean = when (runCatching { java.io.File(filesDir, "mt_lowmem").readText().trim() }.getOrNull()) {
        "on" -> true
        "off" -> false
        else -> de.ayutv.data.DeviceInfo.isWeak(this)
    }

    /**
     * Speicher dieses Prozesses in MB: VmHWM = Höchststand insgesamt (auch eingeblendete Dateien, die
     * Android jederzeit freigeben kann), RssAnon = echter Arbeitsspeicher, den nur dieser Prozess belegt.
     */
    private fun memoryMb(field: String): Long? = runCatching {
        java.io.File("/proc/self/status").readLines().first { it.startsWith("$field:") }
            .filter(Char::isDigit).toLong() / 1024
    }.getOrNull()

    private fun freeMemoryMb(): Long? = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(info)
        info.availMem / 1_000_000
    }.getOrNull()

    private fun progress(text: String) {
        val target = client ?: return
        runCatching {
            target.send(Message.obtain(null, RemoteTranslator.PROGRESS).apply { data = Bundle().apply { putString(RemoteTranslator.RESULT, text) } })
        }
    }
}
