package de.ayutv.data.translate.kern

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Ein Knoten im Rechengraphen. Leere Eingabenamen stehen für weggelassene optionale Eingaben. */
class Node(val op: String, val name: String, val inputs: List<String>, val outputs: List<String>, val attrs: Map<String, Attr>) {
    fun int(name: String, default: Long): Long = attrs[name]?.i ?: default
    fun float(name: String, default: Float): Float = attrs[name]?.f ?: default
    fun ints(name: String): LongArray? = attrs[name]?.ints
    fun string(name: String): String? = attrs[name]?.s
}

class Attr(
    val f: Float? = null,
    val i: Long? = null,
    val s: String? = null,
    val t: Tensor? = null,
    val g: Graph? = null,
    val floats: FloatArray? = null,
    val ints: LongArray? = null,
)

class Graph(val nodes: List<Node>, val initializers: Map<String, Tensor>, val inputs: List<String>, val outputs: List<String>)

/**
 * Liest eine ONNX-Datei (Protobuf) ohne Bibliothek. Die Datei wird eingeblendet; große Gewichte
 * bleiben als [Tensor.Mapped] in der Datei, statt in den Arbeitsspeicher kopiert zu werden.
 */
class OnnxModel private constructor(val graph: Graph, val opset: Int, @Suppress("unused") private val buffer: ByteBuffer) {
    companion object {
        /** Ab dieser Größe bleiben Byte-Gewichte in der Datei. */
        private const val MAP_BYTES = 64 * 1024

        fun load(file: File): OnnxModel {
            val buffer = RandomAccessFile(file, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val reader = Reader(buffer, 0, buffer.limit())
            var graph: Graph? = null
            var opset = 0
            while (reader.more()) {
                val (field, wire) = reader.tag()
                when (field) {
                    7 -> graph = reader.message { readGraph(it, buffer) }
                    8 -> reader.message { r ->
                        var domain = ""
                        var version = 0L
                        while (r.more()) {
                            val (f, w) = r.tag()
                            when (f) {
                                1 -> domain = r.string()
                                2 -> version = r.varint()
                                else -> r.skip(w)
                            }
                        }
                        if (domain.isEmpty() || domain == "ai.onnx") opset = version.toInt()
                    }
                    else -> reader.skip(wire)
                }
            }
            return OnnxModel(graph ?: throw IOException("Kein Graph in ${file.name}"), opset, buffer)
        }

        private fun readGraph(r: Reader, buffer: ByteBuffer): Graph {
            val nodes = ArrayList<Node>()
            val initializers = HashMap<String, Tensor>()
            val inputs = ArrayList<String>()
            val outputs = ArrayList<String>()
            while (r.more()) {
                val (field, wire) = r.tag()
                when (field) {
                    1 -> nodes += r.message { readNode(it, buffer) }
                    5 -> r.message { readTensor(it, buffer) }.let { (name, t) -> initializers[name] = t }
                    11 -> inputs += r.message(::valueInfoName)
                    12 -> outputs += r.message(::valueInfoName)
                    else -> r.skip(wire)
                }
            }
            return Graph(nodes, initializers, inputs, outputs)
        }

        private fun valueInfoName(r: Reader): String {
            var name = ""
            while (r.more()) {
                val (f, w) = r.tag()
                if (f == 1) name = r.string() else r.skip(w)
            }
            return name
        }

        private fun readNode(r: Reader, buffer: ByteBuffer): Node {
            val inputs = ArrayList<String>()
            val outputs = ArrayList<String>()
            var name = ""
            var op = ""
            var domain = ""
            val attrs = HashMap<String, Attr>()
            while (r.more()) {
                val (f, w) = r.tag()
                when (f) {
                    1 -> inputs += r.string()
                    2 -> outputs += r.string()
                    3 -> name = r.string()
                    4 -> op = r.string()
                    5 -> r.message { readAttr(it, buffer) }.let { (n, a) -> attrs[n] = a }
                    7 -> domain = r.string()
                    else -> r.skip(w)
                }
            }
            if (domain.isNotEmpty() && domain != "ai.onnx") throw IOException("Operator $domain.$op wird nicht unterstützt")
            return Node(op, name, inputs, outputs, attrs)
        }

        private fun readAttr(r: Reader, buffer: ByteBuffer): Pair<String, Attr> {
            var name = ""
            var f: Float? = null
            var i: Long? = null
            var s: String? = null
            var t: Tensor? = null
            var g: Graph? = null
            val floats = ArrayList<Float>()
            val ints = ArrayList<Long>()
            var hasFloats = false
            var hasInts = false
            while (r.more()) {
                val (field, wire) = r.tag()
                when (field) {
                    1 -> name = r.string()
                    2 -> f = r.fixed32Float()
                    3 -> i = r.varint()
                    4 -> s = r.string()
                    5 -> t = r.message { readTensor(it, buffer) }.second
                    6 -> g = r.message { readGraph(it, buffer) }
                    7 -> { hasFloats = true; r.repeatedFloats(wire, floats) }
                    8 -> { hasInts = true; r.repeatedVarints(wire, ints) }
                    else -> r.skip(wire)
                }
            }
            return name to Attr(
                f, i, s, t, g,
                if (hasFloats) floats.toFloatArray() else null,
                if (hasInts) ints.toLongArray() else null,
            )
        }

        private fun readTensor(r: Reader, buffer: ByteBuffer): Pair<String, Tensor> {
            val dims = ArrayList<Long>()
            var type = 0
            var name = ""
            var rawOffset = -1
            var rawLength = 0
            val floatData = ArrayList<Float>()
            val intData = ArrayList<Long>()
            val longData = ArrayList<Long>()
            while (r.more()) {
                val (field, wire) = r.tag()
                when (field) {
                    1 -> r.repeatedVarints(wire, dims)
                    2 -> type = r.varint().toInt()
                    4 -> r.repeatedFloats(wire, floatData)
                    5 -> r.repeatedVarints(wire, intData)
                    7 -> r.repeatedVarints(wire, longData)
                    8 -> name = r.string()
                    9 -> r.bytes().let { (o, l) -> rawOffset = o; rawLength = l }
                    13, 14 -> throw IOException("Externe Gewichte werden nicht unterstützt")
                    else -> r.skip(wire)
                }
            }
            val dtype = DType.of(type)
            val shape = IntArray(dims.size) { dims[it].toInt() }
            val n = Tensor.sizeOf(shape)
            val data: Any = if (rawOffset >= 0) {
                val le = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                le.position(rawOffset)
                val raw = le.slice().order(ByteOrder.LITTLE_ENDIAN)
                when (dtype) {
                    DType.FLOAT -> FloatArray(n).also { raw.asFloatBuffer().get(it) }
                    DType.INT64 -> LongArray(n).also { raw.asLongBuffer().get(it) }
                    DType.INT32 -> IntArray(n).also { raw.asIntBuffer().get(it) }
                    DType.BOOL -> BooleanArray(n) { raw.get(it).toInt() != 0 }
                    DType.UINT8, DType.INT8 ->
                        if (rawLength >= MAP_BYTES) Tensor.Mapped(buffer, rawOffset, rawLength) else ByteArray(rawLength).also { raw.get(it) }
                }
            } else {
                when (dtype) {
                    DType.FLOAT -> floatData.toFloatArray()
                    DType.INT64 -> longData.toLongArray()
                    DType.INT32 -> IntArray(intData.size) { intData[it].toInt() }
                    DType.BOOL -> BooleanArray(intData.size) { intData[it] != 0L }
                    DType.UINT8, DType.INT8 -> ByteArray(intData.size) { intData[it].toByte() }
                }
            }
            return name to Tensor(dtype, shape, data)
        }
    }

    /** Protobuf-Leser über einem Ausschnitt des Puffers. */
    private class Reader(private val buffer: ByteBuffer, start: Int, private val end: Int) {
        private var pos = start

        fun more() = pos < end

        fun tag(): Pair<Int, Int> {
            val key = varint()
            return (key ushr 3).toInt() to (key and 7).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buffer.get(pos++).toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun fixed32Float(): Float {
            val v = buffer.getFloat(pos)
            pos += 4
            return v
        }

        /** Offset und Länge eines length-delimited Felds. */
        fun bytes(): Pair<Int, Int> {
            val len = varint().toInt()
            val offset = pos
            pos += len
            return offset to len
        }

        fun string(): String {
            val (offset, len) = bytes()
            val array = ByteArray(len)
            for (k in 0 until len) array[k] = buffer.get(offset + k)
            return String(array, Charsets.UTF_8)
        }

        fun <T> message(read: (Reader) -> T): T {
            val (offset, len) = bytes()
            return read(Reader(buffer, offset, offset + len))
        }

        fun repeatedVarints(wire: Int, out: MutableList<Long>) {
            if (wire == 2) {
                val (offset, len) = bytes()
                val sub = Reader(buffer, offset, offset + len)
                while (sub.more()) out += sub.varint()
            } else {
                out += varint()
            }
        }

        fun repeatedFloats(wire: Int, out: MutableList<Float>) {
            if (wire == 2) {
                val (offset, len) = bytes()
                for (k in 0 until len / 4) out += buffer.getFloat(offset + 4 * k)
            } else {
                out += fixed32Float()
            }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> {
                    val len = varint().toInt()
                    pos += len
                }
                5 -> pos += 4
                else -> throw IOException("Protobuf: unbekannter Feldtyp $wire bei $pos (bis $end)")
            }
        }
    }
}
