#!/usr/bin/env python3
"""Splits the output layer off the app's Whisper decoder, and makes a Japanese/Nepali-only copy of it
(the same idea as split_output_layer.py for Madlad).

    python3 split_whisper_head.py Whisper_decoder.onnx ids.bin OUT_DIR

writes
  OUT_DIR/Whisper_decoder_body.onnx     the decoder without proj_out; output "pre_logits" [batch, 1, 768]
                                        (+ the same "present.*" cache outputs)
  OUT_DIR/Whisper_lm_head_full.onnx     pre_logits -> logits [.., 51865]  (proj_out unchanged)
  OUT_DIR/Whisper_lm_head_jane.onnx     pre_logits -> logits [.., len(ids)]: the columns of the tokens of
                                        whisper_vocab.py; logit i is token ids[i]

proj_out is a DynamicQuantizeMatMul with int8 weights [768, 51865] and one scale and zero point per
column (token), so a kept column gives exactly the logit of the full layer. The weights are 29% of what
the decoder reads per token; the ja/ne copy keeps 22% of the columns.
"""
import os, struct, sys
import numpy as np, onnx
from onnx import helper, numpy_helper, TensorProto

PROJ = "/proj_out/MatMul_quant"


def head_model(name, w, scale, zp, opsets, ir_version):
    k, n = w.shape
    node = helper.make_node("DynamicQuantizeMatMul", ["pre_logits", "W", "W_scale", "W_zero_point"], ["logits"],
                            name=PROJ, domain="com.microsoft")
    g = helper.make_graph([node], name,
        [helper.make_tensor_value_info("pre_logits", TensorProto.FLOAT, ["batch_size", "decoder_sequence_length", k])],
        [helper.make_tensor_value_info("logits", TensorProto.FLOAT, ["batch_size", "decoder_sequence_length", n])],
        [numpy_helper.from_array(w, "W"), numpy_helper.from_array(scale, "W_scale"), numpy_helper.from_array(zp, "W_zero_point")])
    m = helper.make_model(g, opset_imports=opsets)
    m.ir_version = ir_version
    return m


def main(src, ids_path, out):
    os.makedirs(out, exist_ok=True)
    raw = open(ids_path, "rb").read()
    ids = np.array(struct.unpack("<%di" % (len(raw) // 4), raw), dtype=np.int64)
    m = onnx.load(src)
    g = m.graph
    node = next(n for n in g.node if n.name == PROJ)
    assert node.op_type == "DynamicQuantizeMatMul" and len([i for i in node.input if i]) == 4, node
    inits = {i.name: i for i in g.initializer}
    w = numpy_helper.to_array(inits[node.input[1]])
    scale = numpy_helper.to_array(inits[node.input[2]])
    zp = numpy_helper.to_array(inits[node.input[3]])
    assert w.shape[1] == scale.shape[0] == zp.shape[0], "one scale and zero point per token expected"
    assert ids.min() >= 0 and ids.max() < w.shape[1] and np.all(np.diff(ids) > 0)
    opsets, irv = list(m.opset_import), m.ir_version

    onnx.save(head_model("whisper_lm_head_full", w, scale, zp, opsets, irv), os.path.join(out, "Whisper_lm_head_full.onnx"))
    onnx.save(head_model("whisper_lm_head_jane", np.ascontiguousarray(w[:, ids]), scale[ids], zp[ids], opsets, irv),
              os.path.join(out, "Whisper_lm_head_jane.onnx"))

    pre = node.input[0]
    g.node.remove(node)
    for name in node.input[1:4]:
        g.initializer.remove(inits[name])
    logits = next(o for o in g.output if o.name == node.output[0])
    idx = list(g.output).index(logits)
    g.output.remove(logits)
    g.output.insert(idx, helper.make_tensor_value_info(pre, TensorProto.FLOAT, ["batch_size", 1, w.shape[0]]))
    # the body's output is named after its producer; rename it to "pre_logits" through an Identity
    g.output[idx].name = "pre_logits"
    g.node.append(helper.make_node("Identity", [pre], ["pre_logits"], name="/pre_logits/Identity"))
    onnx.save(m, os.path.join(out, "Whisper_decoder_body.onnx"))
    print(f"body + full head ({w.shape[1]}) + ja/ne head ({len(ids)}) -> {out}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
