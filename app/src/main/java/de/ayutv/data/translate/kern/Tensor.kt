package de.ayutv.data.translate.kern

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Datentypen wie in ONNX (TensorProto.DataType). */
enum class DType(val code: Int) {
    FLOAT(1), UINT8(2), INT8(3), INT32(6), INT64(7), BOOL(9);

    companion object {
        fun of(code: Int): DType = entries.firstOrNull { it.code == code } ?: throw UnsupportedOperationException("Datentyp $code")
    }
}

/**
 * Ein Tensor. Die Daten sind je nach Typ ein FloatArray, LongArray, IntArray, BooleanArray, ByteArray
 * oder (große Gewichte direkt aus der eingeblendeten Modelldatei) ein [Mapped]. Tensoren werden nie
 * verändert: Operationen dürfen die Daten ihrer Eingaben deshalb ohne Kopie weiterreichen.
 */
class Tensor(val type: DType, val shape: IntArray, val data: Any) {
    val size: Int get() = sizeOf(shape)
    val rank: Int get() = shape.size

    val floats: FloatArray get() = data as FloatArray
    val longs: LongArray get() = data as LongArray
    val ints: IntArray get() = data as IntArray
    val bools: BooleanArray get() = data as BooleanArray

    /** Bytes (UINT8/INT8), aus der Datei erst bei Bedarf gelesen. */
    val bytes: ByteArray get() = when (data) {
        is ByteArray -> data
        is Mapped -> data.readAll()
        is Transposed -> {
            val src = data.source.bytes
            val rows = data.source.shape[0]
            val cols = data.source.shape[1]
            ByteArray(src.size).also { out -> for (r in 0 until rows) for (c in 0 until cols) out[c * rows + r] = src[r * cols + c] }
        }
        else -> throw IllegalStateException("kein Byte-Tensor")
    }

    /** Ganzzahlige Werte (für Formen, Indizes, Achsen). */
    fun longAt(i: Int): Long = when (data) {
        is LongArray -> data[i]
        is IntArray -> data[i].toLong()
        is FloatArray -> data[i].toLong()
        is BooleanArray -> if (data[i]) 1 else 0
        is ByteArray -> if (type == DType.UINT8) (data[i].toInt() and 0xFF).toLong() else data[i].toLong()
        is Mapped -> data.byteAt(i).let { if (type == DType.UINT8) (it.toInt() and 0xFF).toLong() else it.toLong() }
        else -> throw IllegalStateException()
    }

    fun floatAt(i: Int): Float = when (data) {
        is FloatArray -> data[i]
        else -> longAt(i).toFloat()
    }

    fun toLongs(): LongArray = LongArray(size) { longAt(it) }

    fun reshaped(newShape: IntArray) = Tensor(type, newShape, data)

    override fun toString() = "Tensor($type ${shape.contentToString()})"

    /** Bytes in der eingeblendeten Datei (ohne Kopie im Arbeitsspeicher). */
    class Mapped(private val buffer: ByteBuffer, private val offset: Int, val length: Int) {
        fun byteAt(i: Int): Byte = buffer.get(offset + i)

        /** Liest [count] Bytes ab [from] nach [dst]. [view] ist eine eigene Kopie des Puffers je Thread. */
        fun read(view: ByteBuffer, from: Int, dst: ByteArray, dstOffset: Int, count: Int) {
            view.position(offset + from)
            view.get(dst, dstOffset, count)
        }

        fun view(): ByteBuffer = buffer.duplicate()

        fun readAll(): ByteArray = ByteArray(length).also { read(view(), 0, it, 0, length) }

        fun floats(count: Int): FloatArray {
            val v = view().order(ByteOrder.LITTLE_ENDIAN)
            v.position(offset)
            return FloatArray(count).also { v.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        }
    }

    /** Umgedrehte 2D-Matrix aus der Datei ([source] hat die Form [N,K], dieser Tensor [K,N]), ohne Kopie. */
    class Transposed(val source: Tensor)

    /**
     * Gewichte, die das Modell vor einer Matrixmultiplikation dequantisiert ((q − Nullpunkt) · Skala).
     * Ausgerechnet wären das beim Übersetzer 133 MB Gleitkommazahlen; so rechnet [Kernels] direkt mit den Bytes.
     */
    class Dequantized(val quantized: Tensor, val scale: Float, val zeroPoint: Int)

    companion object {
        fun sizeOf(shape: IntArray): Int {
            var n = 1
            for (d in shape) n *= d
            return n
        }

        fun floats(shape: IntArray, data: FloatArray) = Tensor(DType.FLOAT, shape, data)
        fun longs(shape: IntArray, data: LongArray) = Tensor(DType.INT64, shape, data)
        fun scalar(value: Float) = Tensor(DType.FLOAT, IntArray(0), floatArrayOf(value))
        fun scalar(value: Long) = Tensor(DType.INT64, IntArray(0), longArrayOf(value))

        /** Leerer Tensor des Typs (für Formen mit einer 0, z. B. leerer KV-Cache). */
        fun empty(type: DType, shape: IntArray): Tensor = Tensor(type, shape, allocate(type, sizeOf(shape)))

        fun allocate(type: DType, n: Int): Any = when (type) {
            DType.FLOAT -> FloatArray(n)
            DType.INT64 -> LongArray(n)
            DType.INT32 -> IntArray(n)
            DType.BOOL -> BooleanArray(n)
            DType.UINT8, DType.INT8 -> ByteArray(n)
        }
    }
}
