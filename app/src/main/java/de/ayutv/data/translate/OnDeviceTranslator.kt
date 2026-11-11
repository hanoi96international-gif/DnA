package de.ayutv.data.translate

import de.ayutv.data.parse.Hid

import ai.onnxruntime.OrtEnvironment
import android.os.Process
import android.util.Log
import de.ayutv.data.Languages
import de.ayutv.data.repo.Translator
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Übersetzung direkt auf dem Fire TV mit OPUS-MT (Universität Helsinki, Lizenz CC BY 4.0):
 * kein Konto, kein Schlüssel, keine Zeichengrenze, nach dem ersten Laden auch offline.
 * Pro Sprachrichtung wird einmalig ein Modell (ca. 110 MB) geladen. Fehlt eine direkte Richtung,
 * geht es über Englisch oder über das Mehrsprachen-Modell mit Sprachkennung (">>pol<<").
 */
class OnDeviceTranslator(
    baseDir: File,
    private val http: OkHttpClient,
    /** Freier Arbeitsspeicher in MB (null = unbekannt), um vor dem Laden eines Modells zu prüfen. */
    private val freeMemoryMb: () -> Long? = { null },
    /** Rechen-Threads pro Modell. Neben 4K-Wiedergabe 1: Bild und Ton gehen vor. */
    private val threads: Int = THREADS,
    /** Speichersparmodus für Geräte mit wenig RAM. */
    private val lowMemory: () -> Boolean = { false },
) : Translator {
    private val baseDir = baseDir

    /** Womit gerechnet wird (kann nach einem Absturz von ONNX Runtime wechseln). */
    val engine: Engine.Kind get() = Engine.select(baseDir)

    /** Meldet jeden Schritt in nativem Code vorher (und null danach): Bei einem Absturz ist klar, wo. */
    @Volatile
    var trace: (String?) -> Unit = {}

    override val name = NAME

    /** Fortschritt beim einmaligen Laden eines Sprachmodells (für die Anzeige im Player). */
    @Volatile
    var onProgress: (String) -> Unit = {}

    private val dir = File(baseDir, "mt")
    private val env by lazy { OrtEnvironment.getEnvironment().also { runCatching { it.setTelemetry(false) } } }
    private val loaded = LinkedHashMap<String, TranslationModel>()
    private val lock = Mutex()
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        // Hintergrund-Vorrang: Bild und Ton gehen immer vor (Übersetzung hat 30–60 s Vorlauf).
        // Großer Stapel: manche Geräte geben neuen Threads sehr wenig, die Rechenkerne brauchen mehr.
        Thread(null, {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "dna-translate", STACK)
    }.asCoroutineDispatcher()

    data class Hop(val pair: String, val token: String? = null)

    override fun supports(language: String) = route("en", language.take(2).lowercase()) != null

    override suspend fun translate(texts: List<String>, target: String): List<String> = translate(texts, target, null)

    override suspend fun translate(texts: List<String>, target: String, source: String?): List<String> = withContext(dispatcher) {
        val to = Languages.normalize(target) ?: target.take(2).lowercase()
        // Unbekannte Vorlage: Untertitel sind fast immer Englisch. MKV nennt Deutsch "ger".
        val from = Languages.normalize(source) ?: Languages.guess(texts) ?: "en"
        val hops = route(from, to) ?: throw IOException("${Languages.label(from)} → ${Languages.label(to)} geht auf dem Gerät nicht")
        val models = hops.map { it to model(it.pair) }
        texts.map { text ->
            ensureActive()
            translateText(text, models)
        }
    }

    private fun translateText(text: String, models: List<Pair<Hop, TranslationModel>>): String {
        val lines = text.replace(TAGS, "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || models.isEmpty()) return text
        // Dialog ("- Hallo.\n- Hi.") Zeile für Zeile, sonst den ganzen Satz über Zeilenumbrüche hinweg.
        val dialog = lines.size > 1 && lines.all { it.startsWith("-") || it.startsWith("–") }
        val segments = if (dialog) lines.map { it.trimStart('-', '–', ' ') } else listOf(lines.joinToString(" "))
        val translated = segments.map { segment ->
            if (segment.none(Char::isLetter)) {
                segment
            } else {
                models.fold(segment) { current, (hop, model) ->
                    val token = hop.token?.let { model.tokenizer.id(it) }
                    trace("Übersetzer ${hop.pair} rechnet (${current.length} Zeichen)")
                    model.translate(current, token).ifBlank { current }.also { trace(null) }
                }
            }
        }
        return if (dialog) translated.joinToString("\n") { "- $it" } else SubtitleSentences.wrap(translated.single())
    }

    /** Lädt (einmalig herunter und) in den Speicher. Höchstens zwei Modelle gleichzeitig (Brücke über Englisch). */
    private suspend fun model(pair: String): TranslationModel = lock.withLock {
        loaded.remove(pair)?.let { model ->
            loaded[pair] = model
            return@withLock model
        }
        val folder = File(dir, pair)
        val kind = engine
        if (!isInstalled(folder, kind)) download(pair, folder, kind)
        // Benutzt: Modelle, die lange niemand braucht, räumt StorageCleaner weg.
        runCatching { File(folder, de.ayutv.data.StorageCleaner.USED_MARK).writeText(System.currentTimeMillis().toString()) }
        while (loaded.size >= 2) {
            val eldest = loaded.keys.first()
            loaded.remove(eldest)?.close()
        }
        if (kind == Engine.Kind.KERN) return@withLock loadKern(pair, folder)
        // Einmalig ins speichersparende ORT-Format umwandeln. Das braucht kurz viel Speicher: Läuft gerade
        // ein Film und reicht er nicht, nach dem Film nachholen (statt vom System beendet zu werden).
        val freeMb = freeMemoryMb()
        val models = listOf(File(folder, OpusMtModel.ENCODER), File(folder, OpusMtModel.DECODER))
        // Nur umwandeln, was noch nicht fertig da ist: Oft kommt eine Datei schon umgewandelt aus dem Release,
        // die andere als ONNX-Original (von der fertigen gibt es dann gar kein Original).
        var unprepared = models.filter { !OrtLoader.isPrepared(env, it) }
        // Weder umgewandelt noch als Original da (abgebrochener Download, aufgeräumt): fehlende Datei neu laden.
        if (unprepared.any { it.length() == 0L }) {
            download(pair, folder, kind)
            unprepared = models.filter { !OrtLoader.isPrepared(env, it) }
        }
        if (unprepared.isNotEmpty()) {
            val needMb = unprepared.sumOf { it.length() } * 3 / 1_000_000
            if (freeMb != null && freeMb < needMb) {
                pendingPreparation(pair)
                throw IOException(
                    "Übersetzer ${describe(pair)} wird einmalig vorbereitet, sobald kein Film läuft (jetzt frei $freeMb MB, " +
                        "nötig ca. $needMb MB). Sofort: Film beenden, Einstellungen → Untertitel → Übersetzer testen"
                )
            }
            onProgress("Bereite Übersetzer ${describe(pair)} einmalig vor …")
            trace("Übersetzer $pair wird vorbereitet")
            unprepared.forEach { file -> OrtLoader.prepare(env, file) { setIntraOpNumThreads(threads) } }
            trace(null)
        }
        onProgress("Starte Übersetzer ${describe(pair)} …" + (freeMb?.let { " (frei: $it MB)" } ?: ""))
        val model = try {
            trace("Übersetzer $pair startet")
            OpusMtModel(folder, env, threads, lowMemory()).also { trace(null) }
        } catch (e: Exception) {
            // Beschädigte Datei (z. B. Strom weg beim Laden): beim nächsten Mal neu herunterladen.
            folder.deleteRecursively()
            throw IOException("Übersetzungsmodell defekt, wird neu geladen: ${e.message}", e)
        }
        loaded[pair] = model
        model
    }

    /** Mit dem Rechenkern: ONNX-Dateien direkt einblenden, keine Vorbereitung, kaum Arbeitsspeicher. */
    private fun loadKern(pair: String, folder: File): TranslationModel {
        // Fürs ONNX-Runtime-Format umgewandelte Dateien braucht der Rechenkern nicht (je ~110 MB Platz).
        folder.listFiles()?.filter { it.name.endsWith(".ort") || it.name.endsWith(".ort.part") }?.forEach { it.delete() }
        onProgress("Starte Übersetzer ${describe(pair)} …")
        val model = try {
            KernMtModel(folder, threads)
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw IOException("Übersetzungsmodell defekt, wird neu geladen: ${e.message}", e)
        }
        loaded[pair] = model
        return model
    }

    /** Für später vormerken: Nach dem Film wird der Übersetzer im Hintergrund vorbereitet. */
    private fun pendingPreparation(pair: String) {
        runCatching { File(dir, PENDING_FILE).appendText("$pair\n") }
    }

    /** Vorgemerkte Sprachpaare vorbereiten (ohne Film, genug Speicher). Gibt die erledigten zurück. */
    suspend fun preparePending(): List<String> = withContext(dispatcher) {
        val file = File(dir, PENDING_FILE)
        // Der Rechenkern braucht keine Vorbereitung.
        if (engine == Engine.Kind.KERN) {
            file.delete()
            return@withContext emptyList()
        }
        val pairs = file.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        file.delete()
        pairs.filter { pair -> runCatching { model(pair) }.isSuccess }.also { release() }
    }

    /** Vollständig da: Konfiguration, Tokenizer und je Teilmodell die ONNX-Datei (oder für ONNX Runtime die ORT-Datei). */
    private fun isInstalled(folder: File, kind: Engine.Kind = engine) = FILES.keys.all { local ->
        File(folder, local).length() > 0 || (kind == Engine.Kind.ORT && local in MODEL_FILES && OrtLoader.hasPrepared(File(folder, local)))
    }

    private fun download(pair: String, folder: File, kind: Engine.Kind) {
        if (dir.apply { mkdirs() }.usableSpace < MIN_FREE_BYTES) throw IOException("Zu wenig Speicher für das Übersetzungsmodell (ca. 120 MB nötig)")
        folder.mkdirs()
        val label = describe(pair)
        var done = 0L
        val total = TOTAL_BYTES_ESTIMATE
        for ((local, remote) in FILES) {
            if (File(folder, local).length() > 0) continue
            // Modelle zuerst fertig umgewandelt (ORT-Format) aus dem DnA-TV-Release: Das Umwandeln selbst
            // bräuchte ~500 MB und klappt auf einem Fire TV Stick nicht. Gibt es keins, das ONNX-Original.
            val prepared = if (local in MODEL_FILES && kind == Engine.Kind.ORT) OrtLoader.prepared(env, File(folder, local)) else null
            if (prepared != null && prepared.length() > 0) continue
            val sources = listOfNotNull(
                prepared?.let { "$RELEASES/models-ort-${env.version}/opus-mt-$pair.${local.substringBefore('.')}.ort" to it },
                "$BASE/opus-mt-$pair/resolve/main/$remote" to File(folder, local),
            )
            var lastError: IOException? = null
            for ((url, target) in sources) {
                try {
                    fetch(url, target) { n -> done += n; report(label, done, total) }
                    lastError = null
                    break
                } catch (e: IOException) {
                    lastError = e
                }
            }
            lastError?.let { throw it }
        }
        Log.i(TAG, "Modell $pair geladen: ${folder.walk().sumOf { it.length() } / 1_000_000} MB")
    }

    private var lastPercent = -1

    private fun report(label: String, done: Long, total: Long) {
        val percent = (done * 100 / total).toInt().coerceAtMost(99)
        if (percent != lastPercent && percent % 5 == 0) {
            lastPercent = percent
            onProgress("Lade Übersetzer $label: $percent % (einmalig ca. ${total / 1_000_000} MB)")
        }
    }

    /** Eine Datei laden, nach einem Abbruch fortsetzen; vollständig erst nach dem Umbenennen. */
    private fun fetch(url: String, target: File, progress: (Long) -> Unit) {
        val part = File(target.path + ".part")
        // Nach einem WLAN-Abbruch dort weitermachen, wo der Download aufgehört hat.
        val have = part.length()
        val request = Request.Builder().url(url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Übersetzungsmodell nicht erreichbar (HTTP ${response.code})")
            val append = response.code == 206
            if (append) progress(have)
            // Erwartete Gesamtgröße, um abgebrochene oder verstümmelte Downloads zu erkennen.
            val expected = response.body.contentLength().takeIf { it > 0 }?.let { if (append) it + have else it }
            response.body.byteStream().use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        progress(n.toLong())
                    }
                }
            }
            // Verbindung mittendrin abgerissen: Teil behalten (wird beim nächsten Mal fortgesetzt), aber nicht verwenden.
            if (expected != null && part.length() != expected) {
                throw IOException("Download des Übersetzungsmodells unvollständig, bitte erneut versuchen")
            }
        }
        if (!part.renameTo(target)) throw IOException("Übersetzungsmodell konnte nicht gespeichert werden")
    }

    /** Belegter Speicher aller geladenen Sprachmodelle. */
    fun installedBytes(): Long = dir.walk().filter { it.isFile }.sumOf { it.length() }

    fun installedPairs(): List<String> = engine.let { kind -> dir.listFiles().orEmpty().filter { isInstalled(it, kind) }.map { it.name }.sorted() }

    /** Auf dem Übersetzer-Thread: Modelle werden nie freigegeben, während gerade mit ihnen gerechnet wird. */
    suspend fun deleteAll() = withContext(dispatcher) {
        lock.withLock {
            loaded.values.forEach { it.close() }
            loaded.clear()
            dir.deleteRecursively()
        }
    }

    /** Speicher freigeben (Player geschlossen oder System knapp an Speicher). Modelle bleiben auf der Platte. */
    suspend fun release() = withContext(dispatcher) {
        lock.withLock {
            loaded.values.forEach { it.close() }
            loaded.clear()
        }
    }

    private fun describe(pair: String): String {
        val (a, b) = pair.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        fun name(code: String) = when (code) {
            "mul" -> "Mehrsprachig"
            "ROMANCE" -> "Romanisch"
            else -> Languages.label(code)
        }
        return "${name(a)} → ${name(b)}"
    }

    companion object {
        const val NAME = "DnA-Übersetzer (auf dem Gerät)"
        private const val PENDING_FILE = "prepare_pending.txt"

        /** Wartet ein Übersetzer darauf, ohne laufenden Film vorbereitet zu werden? */
        fun hasPendingPreparation(baseDir: File): Boolean =
            File(File(baseDir, "mt"), PENDING_FILE).isFile && Engine.select(baseDir) == Engine.Kind.ORT
        private const val TAG = "OnDeviceTranslator"
        private const val BASE = "https://huggingface.co/Xenova"
        /** Fertig umgewandelte Modelle (ORT-Format, Workflow „Models“) als Release-Dateien von DnA-TV. */
        private const val RELEASES = "https://github.com/hanoi96international-gif/DnA/releases/download"
        private val MODEL_FILES = setOf(OpusMtModel.ENCODER, OpusMtModel.DECODER)
        private const val THREADS = 2
        private const val STACK = 16L * 1024 * 1024
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val TOTAL_BYTES_ESTIMATE = 115_000_000L
        private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

        /** Lokaler Dateiname → Datei im Modell-Repository (int8-quantisiert, Decoder mit KV-Cache). */
        private val FILES = linkedMapOf(
            OpusMtModel.CONFIG to "config.json",
            OpusMtModel.TOKENIZER to "tokenizer.json",
            OpusMtModel.ENCODER to "onnx/encoder_model_quantized.onnx",
            OpusMtModel.DECODER to "onnx/decoder_model_merged_quantized.onnx",
        )

        /** Direkte Sprachrichtungen (OPUS-MT als ONNX). */
        private val DIRECT = setOf(
            "af-en", "ar-en", "cs-en", "da-de", "da-en", "de-en", "de-es", "de-fr", "en-af", "en-ar", "en-cs", "en-da",
            "en-de", "en-es", "en-fi", "en-fr", "en-hi", "en-hu", "en-id", "en-it", "en-nl", "en-ro", "en-ru", "en-sv",
            "en-uk", "en-vi", "en-zh", "es-de", "es-en", "es-fr", "es-it", "es-ru", "et-en", "fi-de", "fi-en", "fr-de",
            "fr-en", "fr-es", "fr-ro", "fr-ru", "hi-en", "hu-en", "id-en", "it-en", "it-es", "it-fr", "ja-en", "ko-en",
            "nl-en", "nl-fr", "no-de", "pl-en", "ro-fr", "ru-en", "ru-es", "ru-fr", "ru-uk", "sv-en", "th-en", "tr-en",
            "uk-en", "uk-ru", "vi-en", "zh-en",
        )

        /** Romanische Zielsprachen im Modell en-ROMANCE. */
        private val ROMANCE = mapOf("pt" to ">>pt<<", "ca" to ">>ca<<", "gl" to ">>gl<<")

        /** Weitere Zielsprachen über das Mehrsprachen-Modell en-mul (Kennung nach ISO 639-3). */
        private val MUL = mapOf(
            "pl" to "pol", "tr" to "tur", "el" to "ell", "sl" to "slv", "bg" to "bul", Hid.s("LBw=") to "hrv", Hid.s("Nxw=") to "srp_Latn",
            "bs" to "bos_Latn", "sq" to "sqi", "no" to "nob", "et" to "est", "lv" to "lav", "lt" to "lit", "he" to "heb",
            "fa" to "pes", "ku" to "kur_Latn", "ur" to "urd", "th" to "tha", "ja" to "jpn",
        ).mapValues { ">>${it.value}<<" }

        private val ROMANCE_SOURCES = setOf("pt", "ca", "gl")

        fun route(from: String, to: String): List<Hop>? {
            if (from == to) return emptyList()
            if ("$from-$to" in DIRECT) return listOf(Hop("$from-$to"))
            if (from != "en" && to != "en") {
                val first = route(from, "en") ?: return null
                val second = route("en", to) ?: return null
                return first + second
            }
            if (from == "en") {
                ROMANCE[to]?.let { return listOf(Hop("en-ROMANCE", it)) }
                MUL[to]?.let { return listOf(Hop("en-mul", it)) }
                return null
            }
            // Ziel Englisch ohne direktes Modell
            return listOf(Hop(if (from in ROMANCE_SOURCES) "ROMANCE-en" else "mul-en"))
        }
    }
}
