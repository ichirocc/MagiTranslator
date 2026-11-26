#!/usr/bin/env python3
"""Splits the Madlad decoder's output layer off, and makes a Japanese/Nepali-only copy of it.

    python3 split_output_layer.py madlad_decoder_4bit.onnx ids.bin OUT_DIR

writes
  OUT_DIR/madlad_decoder_4bit_body.onnx   the decoder without its last node; output "pre_logits"
                                          [batch, seq, 1024] (+ the same "present.*" cache outputs)
  OUT_DIR/madlad_lm_head_full.onnx        pre_logits -> logits [.., 256000]  (the original layer, unchanged)
  OUT_DIR/madlad_lm_head_jane.onnx        pre_logits -> logits [.., len(ids)]: only the rows of the tokens
                                          a Japanese or Nepali translation can use (output_vocab.py);
                                          logit i is token ids[i]

The output layer is a 4-bit MatMulNBits (K=1024, N=256000). The ja/ne head keeps the weights, block
scales and packed zero points of the kept rows, unchanged. The app runs the body, then the ja/ne head
for a Japanese or Nepali target (smaller layer, 7.4x fewer logits to copy and scan) or the full head for
any other target (loaded only when needed), so every language Madlad translates keeps working.
"""
import os, struct, sys
import numpy as np, onnx
from onnx import helper, numpy_helper, TensorProto

def head_model(node, inits, n_out, opsets, ir_version, name):
    attrs = {a.name: helper.get_attribute_value(a) for a in node.attribute}
    attrs["N"] = n_out
    mm = helper.make_node("MatMulNBits", ["pre_logits"] + list(node.input[1:]), ["logits"], name="/lm_head/MatMul_Q4",
                          domain="com.microsoft", **attrs)
    g = helper.make_graph([mm], name,
        [helper.make_tensor_value_info("pre_logits", TensorProto.FLOAT, ["batch_size", "decoder_sequence_length", attrs["K"]])],
        [helper.make_tensor_value_info("logits", TensorProto.FLOAT, ["batch_size", "decoder_sequence_length", n_out])],
        inits)
    return helper.make_model(g, opset_imports=opsets, ir_version=ir_version, producer_name="magitranslator-split-output-layer")

def main():
    src, ids_path, out = sys.argv[1:4]
    raw = open(ids_path, "rb").read()
    ids = np.array(struct.unpack("<%di" % (len(raw) // 4), raw), dtype=np.int64)
    assert np.all(np.diff(ids) > 0), "ids must be ascending and unique"
    m = onnx.load(src)
    g = m.graph
    node = next(n for n in g.node if "logits" in n.output)
    assert node.op_type == "MatMulNBits", node.op_type
    attrs = {a.name: helper.get_attribute_value(a) for a in node.attribute}
    N, K, bs = attrs["N"], attrs["K"], attrs["block_size"]
    nblk = (K + bs - 1) // bs
    weight_names = [i for i in node.input[1:] if i]
    for n in g.node:
        if n is not node: assert not set(n.input) & set(weight_names), "output-layer weights shared with another node"
    init = {t.name: numpy_helper.to_array(t) for t in g.initializer if t.name in weight_names}
    opsets = [helper.make_opsetid("", 18), helper.make_opsetid("com.microsoft", 1)]

    # full head: the original layer
    full = head_model(node, [numpy_helper.from_array(init[n], n) for n in weight_names], N, opsets, m.ir_version, "madlad_lm_head_full")
    # ja/ne head: kept rows only
    B = init[node.input[1]][ids]
    S = init[node.input[2]].reshape(N, nblk)[ids].reshape(-1)
    sl = [numpy_helper.from_array(np.ascontiguousarray(B), node.input[1]), numpy_helper.from_array(np.ascontiguousarray(S), node.input[2])]
    if len(node.input) > 3 and node.input[3]:
        Z = init[node.input[3]]; per_row = Z.size // N
        assert per_row * N == Z.size
        sl.append(numpy_helper.from_array(np.ascontiguousarray(Z.reshape(N, per_row)[ids].reshape(-1)), node.input[3]))
    jane = head_model(node, sl, len(ids), opsets, m.ir_version, "madlad_lm_head_jane")
    jane.metadata_props.add(key="magitranslator.output_vocab", value=f"{len(ids)} of {N} tokens; logit i is token ids[i] of madlad_output_ids.bin")

    # body: the decoder without the output layer
    pre = node.input[0]
    g.node.remove(node)
    keep_init = [t for t in g.initializer if t.name not in weight_names]
    del g.initializer[:]
    g.initializer.extend(keep_init)
    outs = [o for o in g.output if o.name != "logits"]
    del g.output[:]
    g.output.append(helper.make_tensor_value_info(pre, TensorProto.FLOAT, ["batch_size", "decoder_sequence_length", K]))
    g.output.extend(outs)
    # rename the output to "pre_logits" (the name the app uses, like the NLLB files)
    for n in g.node:
        for i, x in enumerate(n.output):
            if x == pre: n.output[i] = "pre_logits"
        for i, x in enumerate(n.input):
            if x == pre: n.input[i] = "pre_logits"
    g.output[0].name = "pre_logits"
    os.makedirs(out, exist_ok=True)
    onnx.save(m, os.path.join(out, "madlad_decoder_4bit_body.onnx"))
    onnx.save(full, os.path.join(out, "madlad_lm_head_full.onnx"))
    onnx.save(jane, os.path.join(out, "madlad_lm_head_jane.onnx"))
    print(f"body + full head (N={N}) + ja/ne head (N={len(ids)}) -> {out}")

if __name__ == "__main__":
    main()
