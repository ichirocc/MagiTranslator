#!/usr/bin/env python3
"""Checks split_output_layer.py on a small decoder-shaped graph ending with a 4-bit MatMulNBits:
body + full head == original logits, the ja/ne head == the original logits of the kept tokens."""
import os, struct, subprocess, sys, tempfile
import numpy as np, onnx, onnxruntime as ort
from onnx import helper, numpy_helper, TensorProto

HERE = os.path.dirname(os.path.abspath(__file__))
K, N, BS = 64, 300, 32

def fake_decoder(path):
    rng = np.random.default_rng(1)
    nblk = K // BS
    B = rng.integers(0, 256, (N, nblk, BS // 2), dtype=np.uint8)
    S = rng.uniform(0.01, 0.1, N * nblk).astype(np.float32)
    Z = rng.integers(0, 256, (N * nblk + 1) // 2, dtype=np.uint8)
    nodes = [helper.make_node("Mul", ["x", "w"], ["/decoder/final_layer_norm/Mul_1_output_0"]),
             helper.make_node("Identity", ["x"], ["present.0.decoder.key"]),
             helper.make_node("MatMulNBits", ["/decoder/final_layer_norm/Mul_1_output_0", "B", "S", "Z"], ["logits"],
                              domain="com.microsoft", K=K, N=N, bits=4, block_size=BS, accuracy_level=4)]
    g = helper.make_graph(nodes, "d", [helper.make_tensor_value_info("x", TensorProto.FLOAT, ["b", "s", K])],
        [helper.make_tensor_value_info("logits", TensorProto.FLOAT, ["b", "s", N]),
         helper.make_tensor_value_info("present.0.decoder.key", TensorProto.FLOAT, ["b", "s", K])],
        [numpy_helper.from_array(rng.normal(1, 0.1, K).astype(np.float32), "w"), numpy_helper.from_array(B, "B"),
         numpy_helper.from_array(S, "S"), numpy_helper.from_array(Z, "Z")])
    onnx.save(helper.make_model(g, opset_imports=[helper.make_opsetid("", 18), helper.make_opsetid("com.microsoft", 1)], ir_version=9), path)

def main():
    d = tempfile.mkdtemp()
    src, idsf, out = os.path.join(d, "dec.onnx"), os.path.join(d, "ids.bin"), os.path.join(d, "split")
    fake_decoder(src)
    ids = np.array(sorted(np.random.default_rng(2).choice(N, 40, replace=False)), np.int32)
    open(idsf, "wb").write(struct.pack("<%di" % len(ids), *ids))
    subprocess.run([sys.executable, "-I", os.path.join(HERE, "split_output_layer.py"), src, idsf, out], check=True)
    x = np.random.default_rng(3).normal(0, 1, (4, 1, K)).astype(np.float32)
    S = lambda p: ort.InferenceSession(p, providers=["CPUExecutionProvider"])
    orig = dict(zip([o.name for o in S(src).get_outputs()], S(src).run(None, {"x": x})))
    body = S(os.path.join(out, "madlad_decoder_4bit_body.onnx"))
    rb = dict(zip([o.name for o in body.get_outputs()], body.run(None, {"x": x})))
    assert list(rb)[0] == "pre_logits" and "logits" not in rb, list(rb)
    assert np.array_equal(rb["present.0.decoder.key"], orig["present.0.decoder.key"]), "cache outputs kept"
    full = S(os.path.join(out, "madlad_lm_head_full.onnx")).run(None, {"pre_logits": rb["pre_logits"]})[0]
    jane = S(os.path.join(out, "madlad_lm_head_jane.onnx")).run(None, {"pre_logits": rb["pre_logits"]})[0]
    assert np.array_equal(full, orig["logits"]), "body + full head == original logits"
    assert jane.shape[-1] == len(ids) and np.allclose(jane, orig["logits"][..., ids], atol=1e-4), "ja/ne head == kept columns"
    print("ok split_output_layer: body + full head exact, ja/ne head = kept columns")

if __name__ == "__main__":
    main()
