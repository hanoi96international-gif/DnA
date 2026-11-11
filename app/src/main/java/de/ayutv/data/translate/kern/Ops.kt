package de.ayutv.data.translate.kern

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Die ONNX-Operatoren (Semantik nach der ONNX-Spezifikation für die Opset-Version des Modells). */
internal class Ops(private val k: Kernels, private val opset: Int) {

    fun run(node: Node, raw: List<Tensor?>): List<Tensor> {
        // Ausgerechnete Gewichte nur, wo sie nicht direkt verwendet werden können (sehr selten).
        val inputs = if (node.op == "MatMul") raw else raw.map { it?.let(::materialize) }
        fun x(i: Int) = inputs[i] ?: throw IllegalStateException("${node.op}: Eingabe $i fehlt")
        fun opt(i: Int) = inputs.getOrNull(i)
        return when (node.op) {
            "Identity" -> listOf(x(0))
            "Add" -> listOf(binary(x(0), x(1), BinOp.ADD))
            "Sub" -> listOf(binary(x(0), x(1), BinOp.SUB))
            "Mul" -> listOf(binary(x(0), x(1), BinOp.MUL))
            "Div" -> listOf(binary(x(0), x(1), BinOp.DIV))
            "Pow" -> listOf(pow(x(0), x(1)))
            "Equal" -> listOf(compare(x(0), x(1)) { a, b -> a == b })
            "Less" -> listOf(compare(x(0), x(1)) { a, b -> a < b })
            "Greater" -> listOf(compare(x(0), x(1)) { a, b -> a > b })
            "Where" -> listOf(where(x(0), x(1), x(2)))
            "Sqrt" -> listOf(unary(x(0)) { sqrt(it) })
            "Sigmoid" -> listOf(unary(x(0)) { sigmoid(it) })
            "Relu" -> listOf(unary(x(0)) { max(it, 0f) })
            "Tanh" -> listOf(unary(x(0)) { kotlin.math.tanh(it) })
            "Neg" -> listOf(unary(x(0)) { -it })
            "HardSigmoid" -> {
                val alpha = node.float("alpha", 0.2f)
                val beta = node.float("beta", 0.5f)
                listOf(unary(x(0)) { max(0f, min(1f, alpha * it + beta)) })
            }
            "HardSwish" -> listOf(unary(x(0)) { it * max(0f, min(1f, it / 6f + 0.5f)) })
            "Cast" -> listOf(cast(x(0), DType.of(node.int("to", 1).toInt())))
            "Shape" -> listOf(shape(x(0), node))
            "Reshape" -> listOf(reshape(x(0), x(1), node.int("allowzero", 0) != 0L))
            "Unsqueeze" -> listOf(unsqueeze(x(0), axesOf(node, opt(1))!!))
            "Squeeze" -> listOf(squeeze(x(0), axesOf(node, opt(1))))
            "Concat" -> listOf(concat(inputs.filterNotNull(), node.int("axis", 0).toInt()))
            "Gather" -> listOf(gather(x(0), x(1), node.int("axis", 0).toInt()))
            "Range" -> listOf(range(x(0), x(1), x(2)))
            "ConstantOfShape" -> listOf(constantOfShape(x(0), node))
            "Expand" -> listOf(expand(x(0), x(1)))
            "Transpose" -> listOf(transpose(x(0), node.ints("perm")?.map { it.toInt() }?.toIntArray()))
            "Slice" -> listOf(slice(x(0), x(1), x(2), opt(3), opt(4)))
            "Softmax" -> listOf(softmax(x(0), node))
            "ReduceMean" -> listOf(reduceMean(x(0), axesOf(node, if (opset >= 18) opt(1) else null), node.int("keepdims", 1) != 0L))
            "MatMul" -> listOf(matmul(x(0), x(1)))
            "MatMulInteger" -> listOf(matmulInteger(x(0), x(1), opt(2), opt(3)))
            "DynamicQuantizeLinear" -> dynamicQuantize(x(0))
            "DequantizeLinear" -> listOf(dequantize(raw[0]!!, x(1), opt(2)))
            "Conv" -> listOf(conv(x(0), x(1), opt(2), node))
            "BatchNormalization" -> listOf(batchNorm(x(0), x(1), x(2), x(3), x(4), node.float("epsilon", 1e-5f)))
            "GlobalAveragePool" -> listOf(globalAveragePool(x(0)))
            "AveragePool" -> listOf(averagePool(x(0), node))
            else -> throw UnsupportedOperationException("Operator ${node.op} wird nicht unterstützt")
        }
    }

    fun constant(node: Node): Tensor {
        node.attrs["value"]?.t?.let { return it }
        node.attrs["value_float"]?.f?.let { return Tensor.scalar(it) }
        node.attrs["value_int"]?.i?.let { return Tensor.scalar(it) }
        node.attrs["value_floats"]?.floats?.let { return Tensor.floats(intArrayOf(it.size), it) }
        node.attrs["value_ints"]?.ints?.let { return Tensor.longs(intArrayOf(it.size), it) }
        throw UnsupportedOperationException("Constant ohne Wert")
    }

    private fun materialize(t: Tensor): Tensor {
        val d = t.data as? Tensor.Dequantized ?: return t
        val q = d.quantized
        val bytes = q.bytes
        val unsigned = q.type == DType.UINT8
        return Tensor.floats(t.shape, FloatArray(bytes.size) { ((if (unsigned) bytes[it].toInt() and 0xFF else bytes[it].toInt()) - d.zeroPoint) * d.scale })
    }

    // ---------- Formen ----------

    private fun axis(a: Int, rank: Int) = if (a < 0) a + rank else a

    private fun axesOf(node: Node, input: Tensor?): IntArray? =
        input?.toLongs()?.map { it.toInt() }?.toIntArray() ?: node.ints("axes")?.map { it.toInt() }?.toIntArray()

    private fun shape(x: Tensor, node: Node): Tensor {
        val rank = x.rank
        var start = node.int("start", 0).toInt().let { if (it < 0) it + rank else it }.coerceIn(0, rank)
        val end = (node.attrs["end"]?.i?.toInt() ?: rank).let { if (it < 0) it + rank else it }.coerceIn(0, rank)
        if (start > end) start = end
        return Tensor.longs(intArrayOf(end - start), LongArray(end - start) { x.shape[start + it].toLong() })
    }

    private fun reshape(x: Tensor, shapeT: Tensor, allowZero: Boolean): Tensor {
        val wanted = shapeT.toLongs()
        val out = IntArray(wanted.size)
        var unknown = -1
        var known = 1
        for (i in wanted.indices) {
            val d = wanted[i].toInt()
            out[i] = when {
                d == 0 && !allowZero -> x.shape[i]
                d == -1 -> { unknown = i; 1 }
                else -> d
            }
            if (i != unknown) known *= out[i]
        }
        if (unknown >= 0) out[unknown] = if (known == 0) 0 else x.size / known
        require(Tensor.sizeOf(out) == x.size) { "Reshape ${x.shape.contentToString()} → ${out.contentToString()}" }
        return x.reshaped(out)
    }

    private fun unsqueeze(x: Tensor, axes: IntArray): Tensor {
        val rank = x.rank + axes.size
        val norm = axes.map { axis(it, rank) }.toSet()
        val out = IntArray(rank)
        var src = 0
        for (i in 0 until rank) out[i] = if (i in norm) 1 else x.shape[src++]
        return x.reshaped(out)
    }

    private fun squeeze(x: Tensor, axes: IntArray?): Tensor {
        val norm = axes?.map { axis(it, x.rank) }?.toSet()
        val out = x.shape.indices.filter { i -> if (norm == null) x.shape[i] != 1 else i !in norm }.map { x.shape[it] }
        return x.reshaped(out.toIntArray())
    }

    private fun concat(list: List<Tensor>, axisAttr: Int): Tensor {
        val first = list.first { it.rank > 0 || list.size == 1 }
        val rank = first.rank
        val ax = axis(axisAttr, rank)
        val parts = list.filter { it.size > 0 || it.rank == rank }
        val outShape = first.shape.copyOf()
        outShape[ax] = parts.sumOf { it.shape[ax] }
        val outer = (0 until ax).fold(1) { acc, i -> acc * outShape[i] }
        val type = first.type
        val out = Tensor.allocate(type, Tensor.sizeOf(outShape))
        var offset = 0
        val outInner = (ax until rank).fold(1) { acc, i -> acc * outShape[i] }
        for (p in parts) {
            val inner = (ax until rank).fold(1) { acc, i -> acc * p.shape[i] }
            val src = if (p.data is Tensor.Mapped || p.data is Tensor.Transposed) p.bytes else p.data
            for (o in 0 until outer) System.arraycopy(src, o * inner, out, o * outInner + offset, inner)
            offset += inner
        }
        return Tensor(type, outShape, out)
    }

    private fun gather(data: Tensor, indices: Tensor, axisAttr: Int): Tensor {
        val ax = axis(axisAttr, data.rank)
        val outer = (0 until ax).fold(1) { acc, i -> acc * data.shape[i] }
        val inner = (ax + 1 until data.rank).fold(1) { acc, i -> acc * data.shape[i] }
        val dim = data.shape[ax]
        val idx = indices.toLongs()
        val outShape = data.shape.take(ax).toIntArray() + indices.shape + data.shape.drop(ax + 1).toIntArray()
        val out = Tensor.allocate(data.type, Tensor.sizeOf(outShape))
        val mapped = data.data as? Tensor.Mapped
        val view = mapped?.view()
        for (o in 0 until outer) {
            for (j in idx.indices) {
                val i = idx[j].toInt().let { if (it < 0) it + dim else it }
                require(i in 0 until dim) { "Gather: Index $i außerhalb 0..$dim" }
                val src = (o * dim + i) * inner
                val dst = (o * idx.size + j) * inner
                if (mapped != null) mapped.read(view!!, src, out as ByteArray, dst, inner) else System.arraycopy(data.data, src, out, dst, inner)
            }
        }
        return Tensor(data.type, outShape, out)
    }

    private fun range(start: Tensor, limit: Tensor, delta: Tensor): Tensor = when (start.type) {
        DType.FLOAT -> {
            val s = start.floatAt(0)
            val d = delta.floatAt(0)
            val n = max(0, kotlin.math.ceil((limit.floatAt(0) - s) / d).toInt())
            Tensor.floats(intArrayOf(n), FloatArray(n) { s + it * d })
        }
        else -> {
            val s = start.longAt(0)
            val d = delta.longAt(0)
            val l = limit.longAt(0)
            val n = max(0L, (l - s + d - (if (d > 0) 1 else -1)) / d).toInt()
            val values = LongArray(n) { s + it * d }
            if (start.type == DType.INT32) Tensor(DType.INT32, intArrayOf(n), IntArray(n) { values[it].toInt() }) else Tensor.longs(intArrayOf(n), values)
        }
    }

    private fun constantOfShape(shapeT: Tensor, node: Node): Tensor {
        val shape = shapeT.toLongs().map { it.toInt() }.toIntArray()
        val value = node.attrs["value"]?.t ?: Tensor.scalar(0f)
        val n = Tensor.sizeOf(shape)
        val data: Any = when (value.type) {
            DType.FLOAT -> FloatArray(n).also { it.fill(value.floatAt(0)) }
            DType.INT64 -> LongArray(n).also { it.fill(value.longAt(0)) }
            DType.INT32 -> IntArray(n).also { it.fill(value.longAt(0).toInt()) }
            DType.BOOL -> BooleanArray(n).also { it.fill(value.longAt(0) != 0L) }
            DType.UINT8, DType.INT8 -> ByteArray(n).also { it.fill(value.longAt(0).toByte()) }
        }
        return Tensor(value.type, shape, data)
    }

    private fun broadcastShape(a: IntArray, b: IntArray): IntArray {
        val rank = max(a.size, b.size)
        return IntArray(rank) { i ->
            val da = a.getOrElse(i - (rank - a.size)) { 1 }
            val db = b.getOrElse(i - (rank - b.size)) { 1 }
            when {
                da == db -> da
                da == 1 -> db
                db == 1 -> da
                else -> throw IllegalArgumentException("Formen passen nicht: ${a.contentToString()} / ${b.contentToString()}")
            }
        }
    }

    /** Index in den Quelltensor für jeden Ausgabeindex (Broadcasting). */
    private fun broadcastIndex(src: IntArray, out: IntArray): IntArray? {
        if (src.contentEquals(out)) return null
        val n = Tensor.sizeOf(out)
        val rank = out.size
        val strides = IntArray(rank)
        var s = 1
        for (i in rank - 1 downTo 0) {
            val d = src.getOrElse(i - (rank - src.size)) { 1 }
            strides[i] = if (d == 1) 0 else s
            s *= d
        }
        val index = IntArray(n)
        val counter = IntArray(rank)
        var offset = 0
        for (k in 0 until n) {
            index[k] = offset
            var d = rank - 1
            while (d >= 0) {
                counter[d]++
                offset += strides[d]
                if (counter[d] < out[d]) break
                offset -= strides[d] * counter[d]
                counter[d] = 0
                d--
            }
        }
        return index
    }

    private fun expand(x: Tensor, shapeT: Tensor): Tensor {
        val out = broadcastShape(x.shape, shapeT.toLongs().map { it.toInt() }.toIntArray())
        val index = broadcastIndex(x.shape, out) ?: return x
        val n = index.size
        val data: Any = when (val d = x.data) {
            is FloatArray -> FloatArray(n) { d[index[it]] }
            is LongArray -> LongArray(n) { d[index[it]] }
            is IntArray -> IntArray(n) { d[index[it]] }
            is BooleanArray -> BooleanArray(n) { d[index[it]] }
            is ByteArray -> ByteArray(n) { d[index[it]] }
            else -> throw IllegalStateException()
        }
        return Tensor(x.type, out, data)
    }

    private fun transpose(x: Tensor, permAttr: IntArray?): Tensor {
        val rank = x.rank
        val perm = permAttr ?: IntArray(rank) { rank - 1 - it }
        // Große Gewichtsmatrix aus der Datei (z. B. Wortschatz × 512 beim Übersetzer): nur umgedreht ansehen.
        if (x.data is Tensor.Mapped && rank == 2 && perm[0] == 1) return Tensor(x.type, intArrayOf(x.shape[1], x.shape[0]), Tensor.Transposed(x))
        val outShape = IntArray(rank) { x.shape[perm[it]] }
        val inStrides = IntArray(rank)
        var s = 1
        for (i in rank - 1 downTo 0) {
            inStrides[i] = s
            s *= x.shape[i]
        }
        val strides = IntArray(rank) { inStrides[perm[it]] }
        val n = x.size
        val out = Tensor.allocate(x.type, n)
        val src = if (x.data is Tensor.Mapped || x.data is Tensor.Transposed) x.bytes else x.data
        if (n == 0) return Tensor(x.type, outShape, out)
        // Innerste Ausgabe-Dimension als Schleife, die äußeren als Zähler.
        val last = rank - 1
        val innerLen = outShape[last]
        val innerStride = strides[last]
        val counter = IntArray(rank)
        var offset = 0
        var k = 0
        while (k < n) {
            when (out) {
                is FloatArray -> { val a = src as FloatArray; for (j in 0 until innerLen) out[k + j] = a[offset + j * innerStride] }
                is LongArray -> { val a = src as LongArray; for (j in 0 until innerLen) out[k + j] = a[offset + j * innerStride] }
                is IntArray -> { val a = src as IntArray; for (j in 0 until innerLen) out[k + j] = a[offset + j * innerStride] }
                is ByteArray -> { val a = src as ByteArray; for (j in 0 until innerLen) out[k + j] = a[offset + j * innerStride] }
                is BooleanArray -> { val a = src as BooleanArray; for (j in 0 until innerLen) out[k + j] = a[offset + j * innerStride] }
            }
            k += innerLen
            var d = last - 1
            while (d >= 0) {
                counter[d]++
                offset += strides[d]
                if (counter[d] < outShape[d]) break
                offset -= strides[d] * counter[d]
                counter[d] = 0
                d--
            }
        }
        return Tensor(x.type, outShape, out)
    }

    private fun slice(x: Tensor, startsT: Tensor, endsT: Tensor, axesT: Tensor?, stepsT: Tensor?): Tensor {
        val rank = x.rank
        val starts = IntArray(rank)
        val steps = IntArray(rank) { 1 }
        val outShape = x.shape.copyOf()
        val st = startsT.toLongs()
        val en = endsT.toLongs()
        val axes = axesT?.toLongs()?.map { axis(it.toInt(), rank) } ?: st.indices.toList()
        val sp = stepsT?.toLongs()
        for ((j, ax) in axes.withIndex()) {
            val dim = x.shape[ax]
            val step = sp?.get(j)?.toInt() ?: 1
            fun clamp(v: Long, forStart: Boolean): Int {
                var p = if (v < 0) v + dim else v
                p = if (step > 0) p.coerceIn(0, dim.toLong()) else p.coerceIn(if (forStart) 0 else -1, (dim - 1).toLong())
                return p.toInt()
            }
            val s = clamp(st[j], true)
            val e = clamp(en[j], false)
            starts[ax] = s
            steps[ax] = step
            outShape[ax] = if (step > 0) max(0, (e - s + step - 1) / step) else max(0, (s - e - step - 1) / -step)
        }
        val n = Tensor.sizeOf(outShape)
        val out = Tensor.allocate(x.type, n)
        if (n == 0) return Tensor(x.type, outShape, out)
        val src = if (x.data is Tensor.Mapped || x.data is Tensor.Transposed) x.bytes else x.data
        val inStrides = IntArray(rank)
        var s = 1
        for (i in rank - 1 downTo 0) {
            inStrides[i] = s
            s *= x.shape[i]
        }
        val counter = IntArray(rank)
        for (k in 0 until n) {
            var offset = 0
            for (d in 0 until rank) offset += (starts[d] + counter[d] * steps[d]) * inStrides[d]
            when (out) {
                is FloatArray -> out[k] = (src as FloatArray)[offset]
                is LongArray -> out[k] = (src as LongArray)[offset]
                is IntArray -> out[k] = (src as IntArray)[offset]
                is ByteArray -> out[k] = (src as ByteArray)[offset]
                is BooleanArray -> out[k] = (src as BooleanArray)[offset]
            }
            var d = rank - 1
            while (d >= 0) {
                counter[d]++
                if (counter[d] < outShape[d]) break
                counter[d] = 0
                d--
            }
        }
        return Tensor(x.type, outShape, out)
    }

    // ---------- Elementweise ----------

    private enum class BinOp { ADD, SUB, MUL, DIV }

    private fun binary(a: Tensor, b: Tensor, op: BinOp): Tensor {
        val out = broadcastShape(a.shape, b.shape)
        val n = Tensor.sizeOf(out)
        val ia = broadcastIndex(a.shape, out)
        val ib = broadcastIndex(b.shape, out)
        return when (a.type) {
            DType.FLOAT -> {
                val x = a.floats
                val y = b.floats
                val r = FloatArray(n)
                if (ia == null && ib == null) {
                    when (op) {
                        BinOp.ADD -> for (i in 0 until n) r[i] = x[i] + y[i]
                        BinOp.SUB -> for (i in 0 until n) r[i] = x[i] - y[i]
                        BinOp.MUL -> for (i in 0 until n) r[i] = x[i] * y[i]
                        BinOp.DIV -> for (i in 0 until n) r[i] = x[i] / y[i]
                    }
                } else if (ia == null && y.size == 1) {
                    val v = y[0]
                    when (op) {
                        BinOp.ADD -> for (i in 0 until n) r[i] = x[i] + v
                        BinOp.SUB -> for (i in 0 until n) r[i] = x[i] - v
                        BinOp.MUL -> for (i in 0 until n) r[i] = x[i] * v
                        BinOp.DIV -> for (i in 0 until n) r[i] = x[i] / v
                    }
                } else {
                    for (i in 0 until n) {
                        val u = x[ia?.get(i) ?: i]
                        val v = y[ib?.get(i) ?: i]
                        r[i] = when (op) {
                            BinOp.ADD -> u + v
                            BinOp.SUB -> u - v
                            BinOp.MUL -> u * v
                            BinOp.DIV -> u / v
                        }
                    }
                }
                Tensor.floats(out, r)
            }
            DType.INT64 -> {
                val x = a.longs
                val y = b.toLongs()
                Tensor.longs(out, LongArray(n) { i ->
                    val u = x[ia?.get(i) ?: i]
                    val v = y[ib?.get(i) ?: i]
                    when (op) {
                        BinOp.ADD -> u + v
                        BinOp.SUB -> u - v
                        BinOp.MUL -> u * v
                        BinOp.DIV -> u / v
                    }
                })
            }
            DType.INT32 -> {
                val x = a.ints
                val y = b.ints
                Tensor(DType.INT32, out, IntArray(n) { i ->
                    val u = x[ia?.get(i) ?: i]
                    val v = y[ib?.get(i) ?: i]
                    when (op) {
                        BinOp.ADD -> u + v
                        BinOp.SUB -> u - v
                        BinOp.MUL -> u * v
                        BinOp.DIV -> u / v
                    }
                })
            }
            else -> throw UnsupportedOperationException("${op.name} für ${a.type}")
        }
    }

    private fun pow(a: Tensor, b: Tensor): Tensor {
        if (b.size == 1) {
            val e = b.floatAt(0)
            val x = a.floats
            return Tensor.floats(a.shape, if (e == 2f) FloatArray(x.size) { x[it] * x[it] } else FloatArray(x.size) { x[it].pow(e) })
        }
        val out = broadcastShape(a.shape, b.shape)
        val ia = broadcastIndex(a.shape, out)
        val ib = broadcastIndex(b.shape, out)
        val x = a.floats
        return Tensor.floats(out, FloatArray(Tensor.sizeOf(out)) { i -> x[ia?.get(i) ?: i].pow(b.floatAt(ib?.get(i) ?: i)) })
    }

    private fun value(t: Tensor, i: Int): Double = if (t.type == DType.FLOAT) t.floats[i].toDouble() else t.longAt(i).toDouble()

    private inline fun compare(a: Tensor, b: Tensor, crossinline test: (Double, Double) -> Boolean): Tensor {
        val out = broadcastShape(a.shape, b.shape)
        val ia = broadcastIndex(a.shape, out)
        val ib = broadcastIndex(b.shape, out)
        return Tensor(DType.BOOL, out, BooleanArray(Tensor.sizeOf(out)) { i -> test(value(a, ia?.get(i) ?: i), value(b, ib?.get(i) ?: i)) })
    }

    private fun where(c: Tensor, a: Tensor, b: Tensor): Tensor {
        val out = broadcastShape(broadcastShape(c.shape, a.shape), b.shape)
        val n = Tensor.sizeOf(out)
        val ic = broadcastIndex(c.shape, out)
        val ia = broadcastIndex(a.shape, out)
        val ib = broadcastIndex(b.shape, out)
        val cond = c.bools
        return when (a.type) {
            DType.FLOAT -> {
                val x = a.floats
                val y = b.floats
                Tensor.floats(out, FloatArray(n) { i -> if (cond[ic?.get(i) ?: i]) x[ia?.get(i) ?: i] else y[ib?.get(i) ?: i] })
            }
            DType.INT64 -> {
                val x = a.longs
                val y = b.longs
                Tensor.longs(out, LongArray(n) { i -> if (cond[ic?.get(i) ?: i]) x[ia?.get(i) ?: i] else y[ib?.get(i) ?: i] })
            }
            else -> throw UnsupportedOperationException("Where für ${a.type}")
        }
    }

    private inline fun unary(x: Tensor, f: (Float) -> Float): Tensor {
        val a = x.floats
        val r = FloatArray(a.size)
        for (i in a.indices) r[i] = f(a[i])
        return Tensor.floats(x.shape, r)
    }

    private fun sigmoid(v: Float): Float = if (v >= 0) 1f / (1f + exp(-v)) else exp(v).let { it / (1f + it) }

    private fun cast(x: Tensor, to: DType): Tensor {
        if (x.type == to) return x
        val n = x.size
        val data: Any = when (to) {
            DType.FLOAT -> when (val d = x.data) {
                is IntArray -> FloatArray(n) { d[it].toFloat() }
                else -> FloatArray(n) { x.floatAt(it) }
            }
            DType.INT64 -> LongArray(n) { if (x.type == DType.FLOAT) x.floats[it].toLong() else x.longAt(it) }
            DType.INT32 -> IntArray(n) { if (x.type == DType.FLOAT) x.floats[it].toInt() else x.longAt(it).toInt() }
            DType.BOOL -> BooleanArray(n) { if (x.type == DType.FLOAT) x.floats[it] != 0f else x.longAt(it) != 0L }
            DType.UINT8, DType.INT8 -> ByteArray(n) { x.longAt(it).toByte() }
        }
        return Tensor(to, x.shape, data)
    }

    // ---------- Normalisierung ----------

    private fun softmax(x: Tensor, node: Node): Tensor {
        val rank = x.rank
        val ax = axis(node.int("axis", if (opset >= 13) -1 else 1).toInt(), rank)
        val a = x.floats
        val r = FloatArray(a.size)
        if (opset < 13 || ax == rank - 1) {
            // Bis Opset 12: alles ab der Achse als eine Zeile; ab 13 nur die (letzte) Achse.
            val inner = if (opset < 13) (ax until rank).fold(1) { acc, i -> acc * x.shape[i] } else x.shape[ax]
            if (inner == 0) return Tensor.floats(x.shape, r)
            for (row in 0 until a.size / inner) {
                val o = row * inner
                var m = Float.NEGATIVE_INFINITY
                for (j in 0 until inner) m = max(m, a[o + j])
                var sum = 0f
                for (j in 0 until inner) {
                    val e = if (m == Float.NEGATIVE_INFINITY) 0f else exp(a[o + j] - m)
                    r[o + j] = e
                    sum += e
                }
                if (sum > 0f) for (j in 0 until inner) r[o + j] /= sum
            }
        } else {
            val dim = x.shape[ax]
            val inner = (ax + 1 until rank).fold(1) { acc, i -> acc * x.shape[i] }
            val outer = a.size / (dim * inner)
            for (o in 0 until outer) for (q in 0 until inner) {
                val base = o * dim * inner + q
                var m = Float.NEGATIVE_INFINITY
                for (j in 0 until dim) m = max(m, a[base + j * inner])
                var sum = 0f
                for (j in 0 until dim) {
                    val e = exp(a[base + j * inner] - m)
                    r[base + j * inner] = e
                    sum += e
                }
                for (j in 0 until dim) r[base + j * inner] /= sum
            }
        }
        return Tensor.floats(x.shape, r)
    }

    private fun reduceMean(x: Tensor, axesAttr: IntArray?, keep: Boolean): Tensor {
        val rank = x.rank
        val axes = (axesAttr?.map { axis(it, rank) } ?: (0 until rank).toList()).toSet()
        val outFull = IntArray(rank) { if (it in axes) 1 else x.shape[it] }
        val a = x.floats
        val r: FloatArray
        if (axes == setOf(rank - 1)) {
            val inner = x.shape[rank - 1]
            r = FloatArray(a.size / inner)
            for (row in r.indices) {
                var s = 0f
                val o = row * inner
                for (j in 0 until inner) s += a[o + j]
                r[row] = s / inner
            }
        } else {
            r = FloatArray(Tensor.sizeOf(outFull))
            val index = IntArray(rank)
            val outStrides = IntArray(rank)
            var s = 1
            for (i in rank - 1 downTo 0) {
                outStrides[i] = if (i in axes) 0 else s
                s *= outFull[i]
            }
            for (k in a.indices) {
                var o = 0
                for (d in 0 until rank) o += index[d] * outStrides[d]
                r[o] += a[k]
                var d = rank - 1
                while (d >= 0) {
                    index[d]++
                    if (index[d] < x.shape[d]) break
                    index[d] = 0
                    d--
                }
            }
            val count = axes.fold(1) { acc, i -> acc * x.shape[i] }
            for (i in r.indices) r[i] /= count
        }
        val outShape = if (keep) outFull else x.shape.indices.filter { it !in axes }.map { x.shape[it] }.toIntArray()
        return Tensor.floats(outShape, r)
    }

    private fun batchNorm(x: Tensor, scale: Tensor, bias: Tensor, mean: Tensor, variance: Tensor, eps: Float): Tensor {
        val c = x.shape[1]
        val inner = (2 until x.rank).fold(1) { acc, i -> acc * x.shape[i] }
        val a = x.floats
        val r = FloatArray(a.size)
        for (b in 0 until x.shape[0]) for (ch in 0 until c) {
            val f = scale.floats[ch] / sqrt(variance.floats[ch] + eps)
            val add = bias.floats[ch] - mean.floats[ch] * f
            val o = (b * c + ch) * inner
            for (j in 0 until inner) r[o + j] = a[o + j] * f + add
        }
        return Tensor.floats(x.shape, r)
    }

    // ---------- Matrizen ----------

    private fun matmul(a0: Tensor, b0: Tensor): Tensor {
        val a = materialize(a0)
        val aShape = if (a.rank == 1) intArrayOf(1, a.shape[0]) else a.shape
        val bShape = if (b0.rank == 1) intArrayOf(b0.shape[0], 1) else b0.shape
        val m = aShape[aShape.size - 2]
        val kk = aShape[aShape.size - 1]
        val n = bShape[bShape.size - 1]
        require(bShape[bShape.size - 2] == kk) { "MatMul: ${a.shape.contentToString()} · ${b0.shape.contentToString()}" }
        val batchA = aShape.copyOfRange(0, aShape.size - 2)
        val batchB = bShape.copyOfRange(0, bShape.size - 2)
        val batch = broadcastShape(batchA, batchB)
        val count = Tensor.sizeOf(batch)
        val outShapeFull = batch + intArrayOf(m, n)
        val dq = b0.data as? Tensor.Dequantized
        val result: FloatArray
        if (dq != null && batchB.isEmpty()) {
            // Gewichte bleiben 8 Bit: alle Zeilen von A auf einmal.
            result = k.sgemmDequantized(a.floats, 0, count * m, kk, dq, n)
        } else {
            val b = materialize(b0)
            result = FloatArray(count * m * n)
            val ia = broadcastIndex(batchA, batch)
            val ib = broadcastIndex(batchB, batch)
            for (p in 0 until count) {
                val pa = ia?.get(p) ?: p
                val pb = ib?.get(p) ?: p
                k.sgemm(a.floats, pa * m * kk, b.floats, pb * kk * n, result, p * m * n, m, n, kk)
            }
        }
        var outShape = outShapeFull.toList()
        if (a.rank == 1) outShape = outShape.filterIndexed { i, _ -> i != outShape.size - 2 }
        if (b0.rank == 1) outShape = outShape.dropLast(1)
        return Tensor.floats(outShape.toIntArray(), result)
    }

    private fun matmulInteger(a: Tensor, b: Tensor, aZero: Tensor?, bZero: Tensor?): Tensor {
        require(b.rank == 2) { "MatMulInteger: B muss 2D sein" }
        val kk = a.shape.last()
        val n = b.shape[1]
        val m = a.size / kk
        val bytes = a.bytes
        val unsignedA = a.type == DType.UINT8
        val za = aZero?.longAt(0)?.toInt() ?: 0
        val qa = IntArray(m * kk) { (if (unsignedA) bytes[it].toInt() and 0xFF else bytes[it].toInt()) - za }
        val acc = k.igemm(qa, m, kk, b, n)
        if (bZero != null) {
            val perColumn = bZero.size > 1
            for (i in 0 until m) {
                var sum = 0
                for (p in 0 until kk) sum += qa[i * kk + p]
                for (j in 0 until n) {
                    val z = bZero.longAt(if (perColumn) j else 0).toInt()
                    if (z != 0) acc[i * n + j] -= z * sum
                }
            }
        }
        return Tensor(DType.INT32, a.shape.copyOf().also { it[it.size - 1] = n }, acc)
    }

    /** y = sättigen(runden(x / s) + z) mit s, z aus dem Wertebereich von x (uint8). */
    private fun dynamicQuantize(x: Tensor): List<Tensor> {
        val a = x.floats
        var lo = 0f
        var hi = 0f
        for (v in a) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val scale = (hi - lo) / 255f
        val zero = if (scale == 0f) 0 else Math.rint((0.0 - lo / scale)).toInt().coerceIn(0, 255)
        val q = ByteArray(a.size)
        if (scale != 0f) for (i in a.indices) q[i] = (Math.rint((a[i] / scale).toDouble()).toInt() + zero).coerceIn(0, 255).toByte()
        else for (i in a.indices) q[i] = zero.toByte()
        return listOf(
            Tensor(DType.UINT8, x.shape, q),
            Tensor.scalar(scale),
            Tensor(DType.UINT8, IntArray(0), byteArrayOf(zero.toByte())),
        )
    }

    private fun dequantize(x: Tensor, scaleT: Tensor, zeroT: Tensor?): Tensor {
        require(scaleT.size == 1) { "DequantizeLinear: nur eine Skala unterstützt" }
        val scale = scaleT.floatAt(0)
        val zero = zeroT?.longAt(0)?.toInt() ?: 0
        // Große Gewichte aus der Datei: nicht ausrechnen, die Matrixmultiplikation nimmt die Bytes direkt.
        if (x.data is Tensor.Mapped || x.data is Tensor.Transposed) return Tensor(DType.FLOAT, x.shape, Tensor.Dequantized(x, scale, zero))
        val n = x.size
        val r = FloatArray(n)
        when (x.type) {
            DType.UINT8 -> { val b = x.bytes; for (i in 0 until n) r[i] = ((b[i].toInt() and 0xFF) - zero) * scale }
            DType.INT8 -> { val b = x.bytes; for (i in 0 until n) r[i] = (b[i] - zero) * scale }
            DType.INT32 -> { val b = x.ints; for (i in 0 until n) r[i] = (b[i] - zero) * scale }
            else -> throw UnsupportedOperationException("DequantizeLinear für ${x.type}")
        }
        return Tensor.floats(x.shape, r)
    }

    // ---------- Bilder ----------

    private fun conv(x: Tensor, w: Tensor, b: Tensor?, node: Node): Tensor {
        require(x.rank == 4) { "Conv: nur 2D" }
        val m = w.shape[0]
        val kh = w.shape[2]
        val kw = w.shape[3]
        val strides = node.ints("strides") ?: longArrayOf(1, 1)
        val dil = node.ints("dilations") ?: longArrayOf(1, 1)
        val group = node.int("group", 1).toInt()
        val h = x.shape[2]
        val wd = x.shape[3]
        val pads = pads(node, h, wd, kh, kw, strides, dil)
        val (out, oh, ow) = k.conv(
            x.floats, x.shape[0], x.shape[1], h, wd, w.floats, m, kh, kw, b?.floats, group,
            strides[0].toInt(), strides[1].toInt(), pads[0], pads[1], pads[2], pads[3], dil[0].toInt(), dil[1].toInt(),
        )
        return Tensor.floats(intArrayOf(x.shape[0], m, oh, ow), out)
    }

    /** Rand oben, links, unten, rechts (auch für auto_pad SAME_UPPER/SAME_LOWER). */
    private fun pads(node: Node, h: Int, w: Int, kh: Int, kw: Int, strides: LongArray, dil: LongArray): IntArray {
        val auto = node.string("auto_pad") ?: "NOTSET"
        if (auto == "SAME_UPPER" || auto == "SAME_LOWER") {
            fun total(size: Int, k: Int, s: Int, d: Int): Int {
                val out = (size + s - 1) / s
                return max(0, (out - 1) * s + (k - 1) * d + 1 - size)
            }
            val th = total(h, kh, strides[0].toInt(), dil[0].toInt())
            val tw = total(w, kw, strides[1].toInt(), dil[1].toInt())
            return if (auto == "SAME_UPPER") intArrayOf(th / 2, tw / 2, th - th / 2, tw - tw / 2)
            else intArrayOf(th - th / 2, tw - tw / 2, th / 2, tw / 2)
        }
        val p = node.ints("pads") ?: return IntArray(4)
        return intArrayOf(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt())
    }

    private fun globalAveragePool(x: Tensor): Tensor {
        val n = x.shape[0]
        val c = x.shape[1]
        val inner = (2 until x.rank).fold(1) { acc, i -> acc * x.shape[i] }
        val a = x.floats
        val r = FloatArray(n * c) { i ->
            var s = 0f
            val o = i * inner
            for (j in 0 until inner) s += a[o + j]
            s / inner
        }
        return Tensor.floats(intArrayOf(n, c) + IntArray(x.rank - 2) { 1 }, r)
    }

    private fun averagePool(x: Tensor, node: Node): Tensor {
        val kernel = node.ints("kernel_shape")!!.map { it.toInt() }
        val strides = node.ints("strides")?.map { it.toInt() } ?: listOf(1, 1)
        val ceil = node.int("ceil_mode", 0) != 0L
        val includePad = node.int("count_include_pad", 0) != 0L
        val n = x.shape[0]
        val c = x.shape[1]
        val h = x.shape[2]
        val w = x.shape[3]
        val pads = pads(node, h, w, kernel[0], kernel[1], strides.map { it.toLong() }.toLongArray(), longArrayOf(1, 1))
        fun outSize(size: Int, k: Int, s: Int, p0: Int, p1: Int): Int {
            val span = size + p0 + p1 - k
            var o = (if (ceil) (span + s - 1) / s else span / s) + 1
            // Letztes Fenster muss im Bild (ohne rechten Rand) beginnen.
            if (ceil && (o - 1) * s >= size + p0) o--
            return o
        }
        val oh = outSize(h, kernel[0], strides[0], pads[0], pads[2])
        val ow = outSize(w, kernel[1], strides[1], pads[1], pads[3])
        val a = x.floats
        val r = FloatArray(n * c * oh * ow)
        for (b in 0 until n * c) {
            val inOff = b * h * w
            for (oy in 0 until oh) for (ox in 0 until ow) {
                val y0 = oy * strides[0] - pads[0]
                val x0 = ox * strides[1] - pads[1]
                var s = 0f
                var count = 0
                for (ky in 0 until kernel[0]) for (kx in 0 until kernel[1]) {
                    val iy = y0 + ky
                    val ix = x0 + kx
                    if (iy in 0 until h && ix in 0 until w) {
                        s += a[inOff + iy * w + ix]
                        count++
                    }
                }
                if (includePad) {
                    val yEnd = min(y0 + kernel[0], h + pads[2])
                    val xEnd = min(x0 + kernel[1], w + pads[3])
                    count = (yEnd - y0) * (xEnd - x0)
                }
                r[(b * oh + oy) * ow + ox] = if (count > 0) s / count else 0f
            }
        }
        return Tensor.floats(intArrayOf(n, c, oh, ow), r)
    }
}
