package de.ayutv.data.translate

import android.util.JsonReader
import java.io.File

/**
 * SentencePiece-Unigram-Tokenizer der OPUS-MT-Modelle (aus tokenizer.json im Hugging-Face-Format).
 * Zerlegt jedes Wort ("▁wort") per Viterbi in die Teilstücke mit der höchsten Gesamtwahrscheinlichkeit,
 * genau wie die Referenz-Implementierung.
 */
class UnigramTokenizer private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val unkId: Int,
) {
    private val index = HashMap<String, Int>(pieces.size * 2).apply {
        pieces.forEachIndexed { i, piece -> putIfAbsent(piece, i) }
    }
    private val maxPieceLength = pieces.maxOf { it.length }

    fun id(piece: String): Int? = index[piece]

    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        for (word in text.split(WHITESPACE)) {
            if (word.isNotEmpty()) encodeWord(SPACE + word, out)
        }
        return out.toIntArray()
    }

    private fun encodeWord(word: String, out: MutableList<Int>) {
        val n = word.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val pieceAt = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            var found = false
            for (j in i + 1..minOf(n, i + maxPieceLength)) {
                val id = index[word.substring(i, j)] ?: continue
                found = true
                val score = best[i] + scores[id]
                if (score > best[j]) {
                    best[j] = score
                    from[j] = i
                    pieceAt[j] = id
                }
            }
            // Zeichen ohne passendes Teilstück: als <unk> überspringen (mit Strafe).
            if (!found && best[i] - UNK_PENALTY > best[i + 1]) {
                best[i + 1] = best[i] - UNK_PENALTY
                from[i + 1] = i
                pieceAt[i + 1] = unkId
            }
        }
        val ids = ArrayList<Int>()
        var k = n
        while (k > 0 && from[k] >= 0) {
            ids += pieceAt[k]
            k = from[k]
        }
        for (i in ids.indices.reversed()) out += ids[i]
    }

    /** Ids zurück in Text; Sonderzeichen (Ende, Füllzeichen, Unbekannt) fallen weg. */
    fun decode(ids: IntArray, skip: Set<Int>): String = buildString {
        for (id in ids) if (id !in skip && id != unkId && id in pieces.indices) append(pieces[id])
    }.replace(SPACE, ' ').trim()

    companion object {
        private const val SPACE = '▁'
        private const val UNK_PENALTY = 10.0
        private val WHITESPACE = Regex("\\s+")

        /** Liest nur model.vocab und model.unk_id – streamend, damit 5 MB JSON wenig Speicher kosten. */
        fun load(file: File): UnigramTokenizer {
            val pieces = ArrayList<String>(64_000)
            val scores = ArrayList<Float>(64_000)
            var unk = 1
            JsonReader(file.bufferedReader()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "model") {
                        reader.skipValue()
                        continue
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "unk_id" -> unk = reader.nextInt()
                            "vocab" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    reader.beginArray()
                                    pieces += reader.nextString()
                                    scores += reader.nextDouble().toFloat()
                                    while (reader.hasNext()) reader.skipValue()
                                    reader.endArray()
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                reader.endObject()
            }
            require(pieces.isNotEmpty()) { "Tokenizer ohne Vokabular" }
            // Die konvertierten Dateien nennen teils eine falsche unk_id; maßgeblich ist das Stück "<unk>".
            val unkId = pieces.indexOf("<unk>").takeIf { it >= 0 } ?: unk
            return UnigramTokenizer(pieces.toTypedArray(), scores.toFloatArray(), unkId)
        }
    }
}
