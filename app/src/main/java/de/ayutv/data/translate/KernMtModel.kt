package de.ayutv.data.translate

import de.ayutv.data.translate.kern.DType
import de.ayutv.data.translate.kern.Session
import de.ayutv.data.translate.kern.Tensor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * OPUS-MT mit dem DnA-Rechenkern: dieselben ONNX-Dateien und dieselbe Rechnung wie mit ONNX Runtime,
 * aber ohne native Bibliothek. Die 8-Bit-Gewichte bleiben in der eingeblendeten Datei.
 */
class KernMtModel(dir: File, threads: Int) : TranslationModel {
    override val tokenizer = UnigramTokenizer.load(File(dir, OpusMtModel.TOKENIZER))
    private val eos: Int
    private val pad: Int
    private val start: Int
    private val heads: Int
    private val headDim: Int
    private val encoder: Session
    private val decoder: Session
    private val pastNames: List<String>

    init {
        val config = Json.parseToJsonElement(File(dir, OpusMtModel.CONFIG).readText()) as JsonObject
        fun int(name: String) = config.getValue(name).jsonPrimitive.int
        eos = int("eos_token_id")
        pad = int("pad_token_id")
        start = int("decoder_start_token_id")
        heads = int("decoder_attention_heads")
        headDim = int("d_model") / heads
        encoder = Session(File(dir, OpusMtModel.ENCODER), threads)
        decoder = try {
            Session(File(dir, OpusMtModel.DECODER), threads)
        } catch (e: Exception) {
            encoder.close()
            throw e
        }
        pastNames = decoder.inputNames.filter { it.startsWith("past_key_values") }
    }

    override fun translate(text: String, prefix: Int?): String {
        val ids = ArrayList<Long>()
        prefix?.let { ids += it.toLong() }
        tokenizer.encode(text).take(MAX_INPUT_TOKENS).forEach { ids += it.toLong() }
        ids += eos.toLong()
        val n = ids.size
        val inputIds = Tensor.longs(intArrayOf(1, n), ids.toLongArray())
        val mask = Tensor.longs(intArrayOf(1, n), LongArray(n) { 1L })
        val hidden = encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask)).values.first()
        val empty = Tensor.empty(DType.FLOAT, intArrayOf(1, heads, 0, headDim))
        var past: Map<String, Tensor> = pastNames.associateWith { empty }
        val output = ArrayList<Int>()
        var token = start
        val maxSteps = (n * 3 + 10).coerceAtMost(MAX_OUTPUT_TOKENS)
        for (step in 0 until maxSteps) {
            val inputs = HashMap<String, Tensor>(past)
            inputs["input_ids"] = Tensor.longs(intArrayOf(1, 1), longArrayOf(token.toLong()))
            inputs["encoder_attention_mask"] = mask
            inputs["encoder_hidden_states"] = hidden
            inputs["use_cache_branch"] = Tensor(DType.BOOL, intArrayOf(1), booleanArrayOf(step > 0))
            val result = decoder.run(inputs)
            val next = argmax(result.getValue("logits").floats)
            // Der Encoder-Teil des Zwischenspeichers entsteht im ersten Schritt und bleibt dann gleich.
            past = pastNames.associateWith { name ->
                if (step == 0 || name.contains(".decoder.")) result.getValue(name.replace("past_key_values", "present")) else past.getValue(name)
            }
            if (next == eos) break
            output += next
            token = next
        }
        return tokenizer.decode(output.toIntArray(), setOf(eos, pad))
    }

    /** Wahrscheinlichstes nächstes Token (des letzten Schritts); das Füllzeichen ist ausgeschlossen. */
    private fun argmax(logits: FloatArray): Int {
        var best = -1
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (i == pad) continue
            if (logits[i] > bestValue) {
                bestValue = logits[i]
                best = i
            }
        }
        return best
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    private companion object {
        const val MAX_INPUT_TOKENS = 400
        const val MAX_OUTPUT_TOKENS = 500
    }
}
