package de.ayutv.data.translate.kern

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * DnA-Rechenkern gegen ONNX Runtime: kleine Modelle mit allen Operatoren von OPUS-MT und PaddleOCR
 * (erzeugt von ci/kern/make_test_models.py, Sollwerte von ONNX Runtime).
 */
class KernTest {
    private fun resource(name: String) = File(javaClass.classLoader!!.getResource("kern/$name")!!.toURI())

    private fun tensor(spec: JsonObject): Tensor {
        val shape = spec.getValue("shape").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        val data = spec.getValue("data").jsonArray
        return when (spec.getValue("type").jsonPrimitive.content) {
            "float32" -> Tensor.floats(shape, FloatArray(data.size) { data[it].jsonPrimitive.double.toFloat() })
            "int64" -> Tensor.longs(shape, LongArray(data.size) { data[it].jsonPrimitive.long })
            "bool" -> Tensor(DType.BOOL, shape, BooleanArray(data.size) { data[it].jsonPrimitive.boolean })
            else -> error("Typ ${spec["type"]}")
        }
    }

    private fun values(t: Tensor): DoubleArray = DoubleArray(t.size) { i ->
        when (val d = t.data) {
            is FloatArray -> d[i].toDouble()
            is BooleanArray -> if (d[i]) 1.0 else 0.0
            else -> t.longAt(i).toDouble()
        }
    }

    @Test
    fun matchesOnnxRuntime() {
        val cases = Json.parseToJsonElement(resource("cases.json").readText()) as JsonArray
        val sessions = HashMap<String, Session>()
        for ((index, case) in cases.withIndex()) {
            val c = case.jsonObject
            val model = c.getValue("model").jsonPrimitive.content
            for (threads in listOf(1, 3)) {
                val session = sessions.getOrPut("$model/$threads") { Session(resource(model), threads) }
                val feeds = c.getValue("inputs").jsonObject.mapValues { (_, v) -> tensor(v.jsonObject) }
                val result = session.run(feeds)
                for ((name, expectedSpec) in c.getValue("outputs").jsonObject) {
                    val spec = expectedSpec.jsonObject
                    val actual = result[name] ?: error("Ausgabe $name fehlt")
                    val shape = spec.getValue("shape").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
                    assertArrayEquals("Fall $index, $name: Form", shape, actual.shape)
                    val expected = spec.getValue("data").jsonArray.map { it.jsonPrimitive.double }
                    val got = values(actual)
                    val scale = expected.maxOf { abs(it).coerceAtMost(1e6) }.coerceAtLeast(1.0)
                    // Die Wortschatz-Multiplikation rechnet mit 8 Bit statt Gleitkomma: dort 2 % Toleranz.
                    val tolerance = if (name == "logits" || name == "sq_out") 0.02 * scale else 1e-3 * scale
                    var worst = 0.0
                    for (i in expected.indices) {
                        val e = expected[i]
                        if (abs(e) >= 1e8) {
                            assertEquals("Fall $index, $name[$i]", e, got[i], abs(e) * 1e-6)
                            continue
                        }
                        worst = maxOf(worst, abs(e - got[i]))
                    }
                    assertTrue("Fall $index ($threads Threads), $name: Abweichung $worst > $tolerance", worst <= tolerance)
                }
            }
        }
        sessions.values.forEach { it.close() }
    }

    @Test
    fun keepsLargeWeightsInTheFile() {
        val model = OnnxModel.load(resource("mt_like.onnx"))
        assertTrue(model.graph.initializers.getValue("emb_q").data is Tensor.Mapped)
        assertTrue(model.graph.initializers.getValue("w1").data is Tensor.Mapped)
        assertEquals(11, model.opset)
    }
}
