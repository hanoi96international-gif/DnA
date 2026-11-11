package de.ayutv.data.translate.kern

import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Führt ein ONNX-Modell aus (DnA-Rechenkern). Unterstützt die Operatoren, die OPUS-MT (Übersetzer)
 * und PaddleOCR (Texterkennung) brauchen. Gewichte bleiben in der eingeblendeten Datei.
 */
class Session(file: File, threads: Int = 1) : Closeable {
    private val model = OnnxModel.load(file)
    private val pool: ExecutorService? = if (threads > 1) Executors.newFixedThreadPool(threads - 1) { r ->
        Thread(null, r, "dna-kern", STACK).apply { isDaemon = true }
    } else {
        null
    }
    private val ops = Ops(Kernels(threads, pool?.let { CallerRunsPool(it) }), model.opset)

    val inputNames: List<String> = model.graph.inputs.filter { it !in model.graph.initializers }
    val outputNames: List<String> = model.graph.outputs

    /** Rechenzeit je Operator (ns), nur zur Entwicklung. */
    var profile: HashMap<String, Long>? = null

    /** Konstanten einmal auswerten statt bei jedem Lauf. */
    private val plans = HashMap<Graph, Plan>()

    fun run(feeds: Map<String, Tensor>): Map<String, Tensor> {
        val scope = Scope(null)
        model.graph.initializers.forEach { (name, t) -> scope.values[name] = t }
        feeds.forEach { (name, t) -> scope.values[name] = t }
        execute(model.graph, scope)
        return model.graph.outputs.associateWith { scope.get(it) ?: throw IllegalStateException("Ausgabe $it fehlt") }
    }

    private class Scope(val parent: Scope?) {
        val values = HashMap<String, Tensor>()
        fun get(name: String): Tensor? = values[name] ?: parent?.get(name)
    }

    /** Je Graph: vorab ausgewertete Konstanten und wann ein Zwischenergebnis zuletzt gebraucht wird. */
    private class Plan(val constants: Map<Node, Tensor>, val lastUse: Map<String, Int>)

    private fun plan(graph: Graph): Plan = plans.getOrPut(graph) {
        val constants = HashMap<Node, Tensor>()
        val lastUse = HashMap<String, Int>()
        graph.nodes.forEachIndexed { index, node ->
            if (node.op == "Constant") constants[node] = ops.constant(node)
            node.inputs.forEach { if (it.isNotEmpty()) lastUse[it] = index }
            // Untergraphen (If) greifen auf Werte von außen zu: so lange behalten wie der Knoten.
            node.attrs.values.forEach { a -> a.g?.let { g -> outerNames(g).forEach { lastUse[it] = index } } }
        }
        graph.outputs.forEach { lastUse[it] = Int.MAX_VALUE }
        Plan(constants, lastUse)
    }

    private fun outerNames(graph: Graph): Set<String> {
        val produced = HashSet<String>(graph.initializers.keys)
        val used = HashSet<String>()
        for (node in graph.nodes) {
            node.inputs.forEach { if (it.isNotEmpty() && it !in produced) used += it }
            node.attrs.values.forEach { a -> a.g?.let { g -> outerNames(g).forEach { if (it !in produced) used += it } } }
            produced += node.outputs
        }
        return used
    }

    private fun execute(graph: Graph, scope: Scope) {
        val plan = plan(graph)
        graph.nodes.forEachIndexed { index, node ->
            val outputs: List<Tensor> = plan.constants[node]?.let { listOf(it) } ?: run {
                val inputs = node.inputs.map { name ->
                    if (name.isEmpty()) null else scope.get(name) ?: throw IllegalStateException("${node.op} ${node.name}: Eingabe $name fehlt")
                }
                val times = profile
                val t0 = if (times != null) System.nanoTime() else 0L
                val r = if (node.op == "If") runIf(node, inputs[0]!!, scope) else ops.run(node, inputs)
                if (times != null && node.op != "If") times.merge(if (System.getProperty("kern.byName") != null) node.op + ":" + node.name else node.op, System.nanoTime() - t0, Long::plus)
                r
            }
            node.outputs.forEachIndexed { k, name -> if (name.isNotEmpty() && k < outputs.size) scope.values[name] = outputs[k] }
            // Nicht mehr gebrauchte Zwischenergebnisse freigeben (nur eigene, nicht die von außen).
            node.inputs.forEach { name ->
                if (name.isNotEmpty() && plan.lastUse[name] == index && name !in graph.initializers) scope.values.remove(name)
            }
        }
    }

    private fun runIf(node: Node, cond: Tensor, scope: Scope): List<Tensor> {
        val branch = node.attrs[if (cond.longAt(0) != 0L) "then_branch" else "else_branch"]?.g
            ?: throw IllegalStateException("If ohne Zweig")
        val inner = Scope(scope)
        branch.initializers.forEach { (name, t) -> inner.values[name] = t }
        execute(branch, inner)
        return branch.outputs.map { inner.get(it) ?: throw IllegalStateException("If: Ausgabe $it fehlt") }
    }

    override fun close() {
        pool?.shutdownNow()
    }

    /** Der aufrufende Thread rechnet ein Stück selbst mit: n Threads = n − 1 Helfer + Aufrufer. */
    private class CallerRunsPool(private val helpers: ExecutorService) : ExecutorService by helpers {
        override fun <T> invokeAll(tasks: MutableCollection<out java.util.concurrent.Callable<T>>): MutableList<java.util.concurrent.Future<T>> {
            val list = tasks.toList()
            if (list.isEmpty()) return mutableListOf()
            val futures = list.drop(1).map { helpers.submit(it) }
            val own = java.util.concurrent.FutureTask(list.first()).also { it.run() }
            return (listOf<java.util.concurrent.Future<T>>(own) + futures).toMutableList()
        }
    }

    companion object {
        /** Großer Stapel für die Rechen-Threads (manche Geräte haben sehr kleine Standardstapel). */
        const val STACK = 4L * 1024 * 1024
    }
}
