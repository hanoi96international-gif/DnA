package de.ayutv.data.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Ein OPUS-MT-Übersetzungsmodell (Marian-Transformer, int8-quantisiert) für ein Sprachpaar.
 * Encoder liest den Satz einmal, der Decoder erzeugt die Übersetzung Wort für Wort und nutzt dabei
 * den Zwischenspeicher (KV-Cache) der vorigen Schritte, damit jeder Schritt nur ein Token rechnet.
 */
class OpusMtModel(dir: File, private val env: OrtEnvironment, threads: Int, lowMemory: Boolean = false) : TranslationModel {
    override val tokenizer = UnigramTokenizer.load(File(dir, TOKENIZER))
    private val eos: Int
    private val pad: Int
    private val start: Int
    private val heads: Int
    private val headDim: Int
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val pastNames: List<String>
    private val options = ArrayList<OrtSession.SessionOptions>()
    /** Eingeblendete Modelldateien (ORT-Format); müssen so lange leben wie die Sitzungen. */
    private val mapped = ArrayList<java.nio.MappedByteBuffer>()

    init {
        val config = Json.parseToJsonElement(File(dir, CONFIG).readText()) as JsonObject
        fun int(name: String) = config.getValue(name).jsonPrimitive.int
        eos = int("eos_token_id")
        pad = int("pad_token_id")
        start = int("decoder_start_token_id")
        heads = int("decoder_attention_heads")
        headDim = int("d_model") / heads
        fun session(file: File): OrtSession {
            val sessionOptions = OrtSession.SessionOptions().apply { configure(threads, lowMemory) }
            options += sessionOptions
            // Vorbereitet (ORT-Format): Datei nur einblenden statt einlesen. Sonst klassisch laden.
            if (!OrtLoader.isPrepared(env, file)) return env.createSession(file.path, sessionOptions)
            val (session, buffer) = OrtLoader.open(env, file, sessionOptions)
            mapped += buffer
            return session
        }
        encoder = session(File(dir, ENCODER))
        decoder = session(File(dir, DECODER))
        pastNames = decoder.inputNames.filter { it.startsWith("past_key_values") }
    }

    private fun OrtSession.SessionOptions.configure(threads: Int, lowMemory: Boolean) {
        apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            // EXTENDED statt ALL: ohne Layout-Umbauten, die auf manchen ARM-Geräten Probleme machen.
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            // Formen ändern sich bei jedem Schritt: Speicher-Vorplanung bringt nichts.
            setMemoryPatternOptimization(false)
            // Ohne Speicher-Arena: belegt nur, was gerade gebraucht wird (wenig RAM neben 4K-Wiedergabe).
            setCPUArenaAllocator(false)
            // Wenig RAM (Fire TV Stick neben 4K): Gewichte nicht zusätzlich umgepackt im Speicher halten.
            // Etwas langsamer, braucht aber deutlich weniger Arbeitsspeicher.
            if (lowMemory) addConfigEntry("session.disable_prepacking", "1")
            // Rechen-Threads nicht aktiv warten lassen: sonst nehmen sie dem Video-Decoder CPU weg.
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
        }
    }

    override fun translate(text: String, prefix: Int?): String {
        val ids = ArrayList<Long>()
        prefix?.let { ids += it.toLong() }
        tokenizer.encode(text).take(MAX_INPUT_TOKENS).forEach { ids += it.toLong() }
        ids += eos.toLong()
        val n = ids.size.toLong()
        val inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, n))
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), longArrayOf(1, n))
        val empty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads.toLong(), 0, headDim.toLong()))
        val output = ArrayList<Int>()
        try {
            encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask)).use { encoded ->
                val hidden = encoded.get(0) as OnnxTensor
                var past: Map<String, OnnxTensor> = pastNames.associateWith { empty }
                // Ergebnisse bleiben offen, solange ihre Tensoren als Cache weiterverwendet werden:
                // der erste Schritt liefert den Encoder-Teil für alle weiteren Schritte.
                var first: OrtSession.Result? = null
                var previous: OrtSession.Result? = null
                try {
                    var token = start
                    val maxSteps = (ids.size * 3 + 10).coerceAtMost(MAX_OUTPUT_TOKENS)
                    for (step in 0 until maxSteps) {
                        val tokenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(token.toLong())), longArrayOf(1, 1))
                        val cacheBranch = OnnxTensor.createTensor(env, booleanArrayOf(step > 0))
                        val result = try {
                            val inputs = HashMap<String, OnnxTensor>(past)
                            inputs["input_ids"] = tokenTensor
                            inputs["encoder_attention_mask"] = mask
                            inputs["encoder_hidden_states"] = hidden
                            inputs["use_cache_branch"] = cacheBranch
                            decoder.run(inputs)
                        } finally {
                            tokenTensor.close()
                            cacheBranch.close()
                        }
                        val next = argmax((result.get("logits").get() as OnnxTensor).floatBuffer)
                        past = pastNames.associateWith { name ->
                            if (step == 0 || name.contains(".decoder.")) {
                                result.get(name.replace("past_key_values", "present")).get() as OnnxTensor
                            } else {
                                past.getValue(name)
                            }
                        }
                        if (step == 0) first = result else if (previous !== first) previous?.close()
                        previous = result
                        if (next == eos) break
                        output += next
                        token = next
                    }
                } finally {
                    if (previous !== first) previous?.close()
                    first?.close()
                }
            }
        } finally {
            inputIds.close()
            mask.close()
            empty.close()
        }
        return tokenizer.decode(output.toIntArray(), setOf(eos, pad))
    }

    /** Wahrscheinlichstes nächstes Token; das Füllzeichen ist (wie im Original) ausgeschlossen. */
    private fun argmax(logits: FloatBuffer): Int {
        var best = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until logits.limit()) {
            if (i == pad) continue
            val v = logits.get(i)
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }

    override fun close() {
        encoder.close()
        decoder.close()
        options.forEach { it.close() }
        mapped.clear()
    }

    companion object {
        const val ENCODER = "encoder.onnx"
        const val DECODER = "decoder.onnx"
        const val TOKENIZER = "tokenizer.json"
        const val CONFIG = "config.json"
        private const val MAX_INPUT_TOKENS = 400
        private const val MAX_OUTPUT_TOKENS = 500
    }
}
