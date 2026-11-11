package de.ayutv.data.translate

import java.io.Closeable

/** Ein geladenes Sprachmodell (ONNX Runtime oder DnA-Rechenkern). */
interface TranslationModel : Closeable {
    val tokenizer: UnigramTokenizer

    /** Übersetzt einen Satz. [prefix]: Sprachkennung für Modelle mit mehreren Zielsprachen (">>fra<<"). */
    fun translate(text: String, prefix: Int? = null): String
}
