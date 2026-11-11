"""Kleine ONNX-Testmodelle für den DnA-Rechenkern, mit Sollwerten von ONNX Runtime.

Deckt alle Operatoren ab, die OPUS-MT und PaddleOCR brauchen (inkl. 8-Bit-Gewichte aus der Datei,
umgedrehte Wortschatz-Tabelle, If, Faltungen). Aufruf: python3 make_test_models.py <Zielordner>
"""
import json
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper

out = sys.argv[1]
rng = np.random.default_rng(7)


def init(name, array):
    return numpy_helper.from_array(array, name)


def const(name, array):
    return helper.make_node("Constant", [], [name], value=numpy_helper.from_array(np.asarray(array), name + "_v"))


def mt_like():
    V, D, H, L = 1100, 64, 1024, 5
    emb = rng.integers(0, 256, size=(V, D), dtype=np.uint8)  # ≥ 64 KB: bleibt in der Datei
    w1 = rng.integers(-128, 128, size=(D, H), dtype=np.int8)  # 64 KB
    w1_zp = np.zeros(H, dtype=np.int8)
    w1_zp[::7] = 3
    w2 = rng.integers(-128, 128, size=(H, D), dtype=np.int8)
    nodes = [
        const("one", np.array(1.0, np.float32)),
        const("two", np.array(2.0, np.float32)),
        # Einbettung: Gather auf 8-Bit-Tabelle, dann dequantisieren
        helper.make_node("Gather", ["emb_q", "ids"], ["e_q"]),
        helper.make_node("DequantizeLinear", ["e_q", "emb_scale", "emb_zp"], ["e"]),
        helper.make_node("Add", ["x", "e"], ["h"]),
        # Normalisierung wie LayerNorm
        helper.make_node("ReduceMean", ["h"], ["mu"], axes=[-1]),
        helper.make_node("Sub", ["h", "mu"], ["hc"]),
        helper.make_node("Pow", ["hc", "two"], ["hc2"]),
        helper.make_node("ReduceMean", ["hc2"], ["var"], axes=[-1]),
        helper.make_node("Add", ["var", "eps"], ["var_e"]),
        helper.make_node("Sqrt", ["var_e"], ["std"]),
        helper.make_node("Div", ["hc", "std"], ["hn"]),
        # 8-Bit-Matrixmultiplikation mit Spalten-Nullpunkten
        helper.make_node("DynamicQuantizeLinear", ["hn"], ["hq", "hq_s", "hq_z"]),
        helper.make_node("MatMulInteger", ["hq", "w1", "hq_z", "w1_zp"], ["y1i"]),
        helper.make_node("Cast", ["y1i"], ["y1f"], to=TensorProto.FLOAT),
        helper.make_node("Mul", ["hq_s", "w1_scale"], ["s1"]),
        helper.make_node("Mul", ["y1f", "s1"], ["y1"]),
        helper.make_node("Sigmoid", ["y1"], ["sg"]),
        helper.make_node("Mul", ["y1", "sg"], ["act"]),
        helper.make_node("DynamicQuantizeLinear", ["act"], ["aq", "aq_s", "aq_z"]),
        helper.make_node("MatMulInteger", ["aq", "w2", "aq_z"], ["y2i"]),
        helper.make_node("Cast", ["y2i"], ["y2f"], to=TensorProto.FLOAT),
        helper.make_node("Mul", ["aq_s", "w2_scale"], ["s2"]),
        helper.make_node("Mul", ["y2f", "s2"], ["y2"]),
        # Aufmerksamkeit: Reshape/Transpose/MatMul/Softmax
        const("shape4", np.array([1, -1, 4, 16], np.int64)),
        helper.make_node("Reshape", ["y2", "shape4"], ["r4"]),
        helper.make_node("Transpose", ["r4"], ["q"], perm=[0, 2, 1, 3]),
        helper.make_node("Transpose", ["r4"], ["kt"], perm=[0, 2, 3, 1]),
        helper.make_node("MatMul", ["q", "kt"], ["att"]),
        helper.make_node("Softmax", ["att"], ["p"], axis=-1),
        helper.make_node("MatMul", ["p", "q"], ["ctx"]),
        helper.make_node("Transpose", ["ctx"], ["ctxt"], perm=[0, 2, 1, 3]),
        const("shape3", np.array([0, 0, 64], np.int64)),
        helper.make_node("Reshape", ["ctxt", "shape3"], ["c3"]),
        # Wortschatz: umgedrehte 8-Bit-Tabelle (bleibt in der Datei)
        helper.make_node("Transpose", ["emb_q"], ["embT_q"], perm=[1, 0]),
        helper.make_node("DequantizeLinear", ["embT_q", "emb_scale", "emb_zp"], ["embT"]),
        helper.make_node("MatMul", ["c3", "embT"], ["logits_raw"]),
        helper.make_node("Add", ["logits_raw", "bias"], ["logits"]),
        # Formen-Operatoren
        helper.make_node("Shape", ["ids"], ["sh"]),
        const("idx1", np.array(1, np.int64)),
        helper.make_node("Gather", ["sh", "idx1"], ["len"]),
        helper.make_node("Unsqueeze", ["len"], ["len1"], axes=[0]),
        const("zero", np.array(0, np.int64)),
        helper.make_node("Range", ["zero", "len", "idx1"], ["pos"]),
        helper.make_node("Concat", ["len1", "len1"], ["sq"], axis=0),
        helper.make_node("ConstantOfShape", ["sq"], ["zeros"], value=numpy_helper.from_array(np.array([0.0], np.float32))),
        helper.make_node("Unsqueeze", ["pos"], ["pos_r"], axes=[0]),
        helper.make_node("Unsqueeze", ["pos"], ["pos_c"], axes=[1]),
        helper.make_node("Less", ["pos_r", "pos_c"], ["lower"]),
        helper.make_node("Where", ["lower", "zeros", "neg"], ["mask"]),
        helper.make_node("Equal", ["ids", "pad"], ["is_pad"]),
        helper.make_node("Cast", ["is_pad"], ["is_pad_f"], to=TensorProto.FLOAT),
        helper.make_node("Expand", ["is_pad_f", "sq3"], ["pad_e"]),
        helper.make_node("Slice", ["logits", "s_start", "s_end", "s_axes", "s_steps"], ["sliced"]),
        helper.make_node("Squeeze", ["sliced"], ["sq_out"], axes=[0]),
        helper.make_node(
            "If", ["cond"], ["branch"],
            then_branch=helper.make_graph([helper.make_node("Mul", ["hn", "two"], ["b_out"])], "then", [],
                                          [helper.make_tensor_value_info("b_out", TensorProto.FLOAT, None)]),
            else_branch=helper.make_graph([helper.make_node("Sub", ["hn", "one"], ["b_out2"])], "else", [],
                                          [helper.make_tensor_value_info("b_out2", TensorProto.FLOAT, None)]),
        ),
    ]
    inits = [
        init("emb_q", emb), init("emb_scale", np.array(0.02, np.float32)), init("emb_zp", np.array(128, np.uint8)),
        init("eps", np.array(1e-5, np.float32)), init("w1", w1), init("w1_zp", w1_zp), init("w1_scale", np.array(0.01, np.float32)),
        init("w2", w2), init("w2_scale", np.array(0.004, np.float32)), init("bias", rng.normal(size=V).astype(np.float32)),
        init("neg", np.array(-1e9, np.float32)), init("pad", np.array(2, np.int64)), init("sq3", np.array([2, 1, 1], np.int64)),
        init("s_start", np.array([1, 900], np.int64)), init("s_end", np.array([100, -3], np.int64)),
        init("s_axes", np.array([1, 2], np.int64)), init("s_steps", np.array([2, 5], np.int64)),
    ]
    outs = ["logits", "mask", "pad_e", "sq_out", "branch", "y1"]
    graph = helper.make_graph(
        nodes, "mt_like",
        [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, "L", D]),
         helper.make_tensor_value_info("ids", TensorProto.INT64, [1, "L"]),
         helper.make_tensor_value_info("cond", TensorProto.BOOL, [1])],
        [onnx.ValueInfoProto(name=o) for o in outs], inits)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 11)], ir_version=8)
    feeds = {"x": rng.normal(size=(1, L, D)).astype(np.float32), "ids": np.array([[5, 2, 999, 0, 1099]], np.int64)}
    return "mt_like", model, outs, [dict(feeds, cond=np.array([True])), dict(feeds, cond=np.array([False]))]


def ocr_like():
    W = 40
    w = lambda *s: rng.normal(scale=0.3, size=s).astype(np.float32)
    nodes = [
        helper.make_node("Conv", ["x", "c1w", "c1b"], ["c1"], kernel_shape=[3, 3], strides=[2, 2], pads=[1, 1, 1, 1]),
        helper.make_node("BatchNormalization", ["c1", "bn_s", "bn_b", "bn_m", "bn_v"], ["bn"], epsilon=1e-5),
        helper.make_node("HardSwish", ["bn"], ["hs"]),
        helper.make_node("Conv", ["hs", "dw"], ["dwo"], kernel_shape=[3, 3], strides=[2, 1], pads=[1, 1, 1, 1], group=8),
        helper.make_node("Conv", ["dwo", "pw", "pwb"], ["pwo"], kernel_shape=[1, 1]),
        helper.make_node("Relu", ["pwo"], ["re"]),
        helper.make_node("GlobalAveragePool", ["re"], ["gap"]),
        helper.make_node("Conv", ["gap", "se", "seb"], ["seo"], kernel_shape=[1, 1]),
        helper.make_node("HardSigmoid", ["seo"], ["hsg"], alpha=0.2, beta=0.5),
        helper.make_node("Mul", ["re", "hsg"], ["scaled"]),
        helper.make_node("AveragePool", ["scaled"], ["ap"], kernel_shape=[2, 2], strides=[2, 2]),
        helper.make_node("Conv", ["ap", "c3w"], ["c3"], kernel_shape=[1, 3], pads=[0, 1, 0, 1], dilations=[1, 1], group=2),
        helper.make_node("Squeeze", ["c3", "ax2"], ["sq"]),
        helper.make_node("Transpose", ["sq"], ["t"], perm=[0, 2, 1]),
        helper.make_node("MatMul", ["t", "fc"], ["fco"]),
        helper.make_node("Add", ["fco", "fcb"], ["fca"]),
        helper.make_node("Softmax", ["fca"], ["sm"], axis=2),
        helper.make_node("Softmax", ["fca"], ["sm1"], axis=1),
        helper.make_node("Unsqueeze", ["sm", "ax0"], ["us"]),
        helper.make_node("Tanh", ["fca"], ["th"]),
    ]
    inits = [
        init("c1w", w(8, 3, 3, 3)), init("c1b", w(8)),
        init("bn_s", w(8) + 1), init("bn_b", w(8)), init("bn_m", w(8)), init("bn_v", np.abs(w(8)) + 0.5),
        init("dw", w(8, 1, 3, 3)), init("pw", w(12, 8, 1, 1)), init("pwb", w(12)),
        init("se", w(12, 12, 1, 1)), init("seb", w(12)),
        init("c3w", w(6, 6, 1, 3)), init("ax2", np.array([2], np.int64)), init("ax0", np.array([0], np.int64)),
        init("fc", w(6, 9)), init("fcb", w(9)),
    ]
    outs = ["sm", "sm1", "us", "th", "bn"]
    graph = helper.make_graph(
        nodes, "ocr_like", [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 3, 8, "W"])],
        [onnx.ValueInfoProto(name=o) for o in outs], inits)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 14)], ir_version=8)
    return "ocr_like", model, outs, [{"x": rng.normal(size=(1, 3, 8, W)).astype(np.float32)}]


cases = []
for name, model, outs, feeds_list in [mt_like(), ocr_like()]:
    onnx.save(model, f"{out}/{name}.onnx")
    session = ort.InferenceSession(f"{out}/{name}.onnx", providers=["CPUExecutionProvider"])
    for k, feeds in enumerate(feeds_list):
        results = session.run(outs, feeds)
        cases.append({
            "model": f"{name}.onnx",
            "inputs": {n: {"type": str(v.dtype), "shape": list(v.shape), "data": v.flatten().tolist()} for n, v in feeds.items()},
            "outputs": {n: {"type": str(r.dtype), "shape": list(r.shape), "data": r.flatten().astype(np.float64).tolist()} for n, r in zip(outs, results)},
        })
json.dump(cases, open(f"{out}/cases.json", "w"))
print("ok", len(cases))
