#!/usr/bin/env python3
"""split_whisper_head.py on a small decoder of the same form (DynamicQuantizeMatMul proj_out with a scale
and zero point per column, a cache output): body + full head == the original, ja/ne head == kept columns."""
import os, struct, sys, tempfile
import numpy as np, onnx, onnxruntime as ort
from onnx import helper, numpy_helper, TensorProto

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import split_whisper_head as swh

K, V = 16, 300
rng = np.random.default_rng(0)
w = rng.integers(-127, 128, size=(K, V), dtype=np.int8)
scale = (rng.random(V) * 0.01 + 0.001).astype(np.float32)
zp = np.zeros(V, np.int8)
ln_w = rng.standard_normal(K).astype(np.float32)
nodes = [
    helper.make_node("Mul", ["x", "ln_w"], ["/model/decoder/layer_norm/Add_1_output_0"], name="norm"),
    helper.make_node("DynamicQuantizeMatMul", ["/model/decoder/layer_norm/Add_1_output_0", "Wq", "Ws", "Wz"], ["logits"],
                     name=swh.PROJ, domain="com.microsoft"),
    helper.make_node("Identity", ["x"], ["present.0.decoder.key"], name="cache"),
]
g = helper.make_graph(nodes, "tiny_decoder",
    [helper.make_tensor_value_info("x", TensorProto.FLOAT, ["batch_size", 1, K])],
    [helper.make_tensor_value_info("logits", TensorProto.FLOAT, ["batch_size", 1, V]),
     helper.make_tensor_value_info("present.0.decoder.key", TensorProto.FLOAT, ["batch_size", 1, K])],
    [numpy_helper.from_array(w, "Wq"), numpy_helper.from_array(scale, "Ws"), numpy_helper.from_array(zp, "Wz"),
     numpy_helper.from_array(ln_w, "ln_w")])
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 17), helper.make_opsetid("com.microsoft", 1)])
m.ir_version = 8

d = tempfile.mkdtemp()
onnx.save(m, os.path.join(d, "dec.onnx"))
ids = np.array(sorted(rng.choice(V, 40, replace=False)), np.int32)
open(os.path.join(d, "ids.bin"), "wb").write(struct.pack("<%di" % len(ids), *ids))
swh.main(os.path.join(d, "dec.onnx"), os.path.join(d, "ids.bin"), os.path.join(d, "out"))

x = rng.standard_normal((2, 1, K)).astype(np.float32)
orig = ort.InferenceSession(os.path.join(d, "dec.onnx")).run(None, {"x": x})
body = ort.InferenceSession(os.path.join(d, "out", "Whisper_decoder_body.onnx"))
b = dict(zip([o.name for o in body.get_outputs()], body.run(None, {"x": x})))
full = ort.InferenceSession(os.path.join(d, "out", "Whisper_lm_head_full.onnx")).run(None, {"pre_logits": b["pre_logits"]})[0]
jane = ort.InferenceSession(os.path.join(d, "out", "Whisper_lm_head_jane.onnx")).run(None, {"pre_logits": b["pre_logits"]})[0]
assert "logits" not in b and np.array_equal(b["present.0.decoder.key"], orig[1]), "body: same cache output, no logits"
assert np.array_equal(full, orig[0]), "full head must reproduce the decoder's logits exactly"
assert np.array_equal(jane, orig[0][..., ids]), "ja/ne head must give exactly the kept columns"
print("ok split_whisper_head: body + full head exact, ja/ne head = kept columns")
