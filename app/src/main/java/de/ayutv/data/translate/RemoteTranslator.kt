package de.ayutv.data.translate

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import de.ayutv.data.repo.Translator
import de.ayutv.player.EmbeddedSubtitles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spricht mit dem Übersetzer-Prozess ([TranslationService]). Für den Player sieht das aus wie ein
 * normaler Übersetzer; endet der Prozess (Speicher, Absturz), schlagen nur die laufenden Anfragen
 * mit dem Grund fehl, und die nächste startet ihn neu.
 */
class RemoteTranslator(private val context: Context) : Translator {
    override val name = OnDeviceTranslator.NAME

    override fun supports(language: String) = OnDeviceTranslator.route("en", language.take(2).lowercase()) != null

    /** Fortschritt beim einmaligen Laden eines Modells (für die Anzeige im Player). */
    @Volatile
    var onProgress: (String) -> Unit = {}

    /** Höchster Speicherverbrauch des Übersetzer-Prozesses (MB), zuletzt gemeldet. */
    @Volatile
    var lastPeakMb: Long? = null
        private set

    /** Echter Arbeitsspeicher des Übersetzer-Prozesses (MB, ohne eingeblendete Modelldateien). */
    @Volatile
    var lastAnonMb: Long? = null
        private set

    /** Womit der Übersetzer-Prozess rechnet. */
    val engine: Engine.Kind get() = Engine.select(context.filesDir)

    /** Zuletzt aufgezeichneter nativer Absturz (Signal, Stelle), oder null. */
    fun lastNativeCrash(): String? = de.ayutv.NativeCrashLog.last(context.filesDir)

    /** Übersetzer vorbereiten, die während eines Films keinen Platz hatten. Gibt die Sprachpaare zurück. */
    suspend fun preparePending(): List<String> = call(PREPARE, Bundle(), 600_000).getStringArrayList(RESULT).orEmpty()

    @Volatile
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    private val bindLock = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val nextId = AtomicInteger()

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == PROGRESS) {
            msg.data.getString(RESULT)?.let { onProgress(it) }
        } else {
            pending.remove(msg.arg1)?.complete(Bundle(msg.data))
        }
        true
    })

    override suspend fun translate(texts: List<String>, target: String): List<String> = translate(texts, target, null)

    /** Antwortet der Übersetzer nicht in der erwarteten Zeit (Modell lädt ewig, Prozess hängt). */
    class NoAnswer(message: String) : IOException(message)

    /** Sprachpaare und Texterkennung, die schon einmal geantwortet haben (danach ist das Modell geladen). */
    private val warm = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Modell dieser Richtung ist schon geladen (hat schon einmal geantwortet). */
    fun isWarm(source: String?, target: String): Boolean = "${source ?: "en"}-$target" in warm

    override suspend fun translate(texts: List<String>, target: String, source: String?): List<String> {
        val pair = "${source ?: "en"}-$target"
        val reply = call(TRANSLATE, Bundle().apply {
            putStringArrayList(TEXTS, ArrayList(texts))
            putString(TARGET, target)
            putString(SOURCE, source)
        }, if (pair in warm) WARM_TIMEOUT_MS * texts.size.coerceAtLeast(1) else COLD_TIMEOUT_MS)
        warm += pair
        return reply.getStringArrayList(RESULT) ?: throw IOException("Übersetzer lieferte nichts")
    }

    /** Texterkennung für ein Bild-Untertitel-Bild. */
    suspend fun recognize(image: EmbeddedSubtitles.GrayImage): String = call(RECOGNIZE, Bundle().apply {
        putInt(WIDTH, image.width)
        putInt(HEIGHT, image.height)
        putByteArray(PIXELS, image.pixels)
    }, if ("ocr" in warm) WARM_TIMEOUT_MS else COLD_TIMEOUT_MS).getString(RESULT).orEmpty().also { warm += "ocr" }

    /**
     * Hängt der Übersetzer: Prozess beenden, damit er nicht weiter rechnet (und dem Film Rechenzeit nimmt).
     * Der nächste Aufruf startet ihn neu.
     */
    fun abort(reason: String) {
        release()
        failAll(reason)
        warm.clear()
        runCatching {
            context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
                .filter { it.processName.endsWith(":mt") }
                .forEach { android.os.Process.killProcess(it.pid) }
        }
    }

    /** Verbindung trennen: Das System beendet den Übersetzer-Prozess, der Speicher ist wieder frei. */
    fun release() {
        val conn = connection ?: return
        connection = null
        service = null
        runCatching { context.unbindService(conn) }
        failAll("Übersetzer beendet")
    }

    private suspend fun call(what: Int, data: Bundle, timeoutMs: Long = COLD_TIMEOUT_MS): Bundle {
        val target = connect()
        val id = nextId.incrementAndGet()
        val answer = CompletableDeferred<Bundle>()
        pending[id] = answer
        try {
            target.send(Message.obtain(null, what, id, 0).apply {
                this.data = data
                replyTo = replies
            })
            val reply = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { answer.await() }
                ?: throw NoAnswer("Übersetzer antwortet nicht (nach ${timeoutMs / 1000} s)")
            if (reply.containsKey(PEAK)) lastPeakMb = reply.getLong(PEAK)
            if (reply.containsKey(ANON)) lastAnonMb = reply.getLong(ANON)
            reply.getString(ERROR)?.let { throw IOException(it) }
            return reply
        } catch (e: RemoteException) {
            service = null
            throw IOException("Übersetzer nicht erreichbar", e)
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun connect(): Messenger = bindLock.withLock {
        service?.let { return@withLock it }
        connection?.let { old -> runCatching { context.unbindService(old) } }
        connection = null
        val ready = CompletableDeferred<Messenger>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val messenger = Messenger(binder)
                service = messenger
                ready.complete(messenger)
            }

            // Prozess beendet (Speicher oder Absturz): offene Anfragen mit dem Grund beenden.
            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                // Neu gestarteter Prozess muss die Modelle wieder laden: nächste Anfrage mit der langen Wartezeit.
                warm.clear()
                failAll(exitReason())
            }

            override fun onBindingDied(name: ComponentName) {
                service = null
                warm.clear()
                failAll(exitReason())
            }
        }
        val bound = withContext(Dispatchers.Main) {
            // BIND_NOT_FOREGROUND: Wird der Speicher knapp, beendet das System lieber den Übersetzer als den Film.
            context.bindService(Intent(context, TranslationService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND)
        }
        if (!bound) throw IOException("Übersetzer konnte nicht gestartet werden")
        connection = conn
        withTimeout(15_000) { ready.await() }
    }

    private fun failAll(reason: String) {
        val error = Bundle().apply { putString(ERROR, reason) }
        pending.values.toList().forEach { it.complete(error) }
        pending.clear()
    }

    /** Warum das System den Übersetzer beendet hat (ab Android 11 bekannt) und woran er gerade gerechnet hat. */
    private fun exitReason(): String {
        // Schreibt der Übersetzer-Prozess vor jedem nativen Schritt (Modell starten, rechnen).
        val step = File(context.filesDir, STEP_FILE).let { file ->
            file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }.also { file.delete() }
        }
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                ?.firstOrNull { it.processName.endsWith(":mt") }
        }.getOrNull() else null
        val why = when (info?.reason) {
            null -> "vom System (vermutlich zu wenig Speicher)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "zu wenig Speicher"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Absturz im Modell (nativ, Signal ${info.status})"
            ApplicationExitInfo.REASON_CRASH -> "Programmfehler"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "zu viel Rechenlast"
            else -> "Grund ${info.reason}"
        }
        // Nativer Absturz in ONNX Runtime: Ursache aus dem Protokoll lesen und ab jetzt den Rechenkern nehmen.
        val native = info?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || (info == null && step != null)
        val detail = if (native) de.ayutv.NativeCrashLog.capture(context.filesDir) else null
        val retry = if (native && step != null && Engine.select(context.filesDir) == Engine.Kind.ORT) {
            Engine.markOrtCrash(context.filesDir)
            " · ab jetzt mit dem DnA-Rechenkern (ohne native Bibliothek)"
        } else {
            ""
        }
        val cause = detail?.let(de.ayutv.NativeCrashLog::summary)?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
        return "Übersetzer wurde beendet: $why$cause" + (step?.let { " bei: $it" } ?: "") + retry
    }

    internal companion object {
        /** Erster Aufruf: Modell laden (auf langsamen Geräten mit dem DnA-Rechenkern bis zu einigen Minuten). */
        const val COLD_TIMEOUT_MS = 180_000L
        /** Danach je Zeile. */
        const val WARM_TIMEOUT_MS = 30_000L
        const val TRANSLATE = 1
        const val RECOGNIZE = 2
        const val PROGRESS = 3
        const val TEXTS = "texts"
        const val TARGET = "target"
        const val SOURCE = "source"
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val PIXELS = "pixels"
        const val RESULT = "result"
        const val ERROR = "error"
        const val PEAK = "peak"
        const val ANON = "anon"
        const val PREPARE = 4
        const val STEP_FILE = "mt_step.txt"
    }
}
