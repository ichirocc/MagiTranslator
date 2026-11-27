"""Madlad embedding table: 8-bit (one global scale) -> 4-bit blocks (GatherBlockQuantized, com.microsoft).
Usage: python3 quantize_embed_4bit.py madlad_embed_8bit.onnx madlad_embed_4bit.onnx [block_size]"""
import os, sys, numpy as np, onnx
from onnx import numpy_helper, helper, TensorProto
src, dst = sys.argv[1], sys.argv[2]
B = int(sys.argv[3]) if len(sys.argv) > 3 else 32
m = onnx.load(src)
init = {t.name: numpy_helper.to_array(t) for t in m.graph.initializer}
q8 = init['shared.weight_quantized'].astype(np.float32)
W = (q8 - float(init['shared.weight_zero_point'])) * float(init['shared.weight_scale'])   #the values the app uses today
N, K = W.shape
blk = W.reshape(N, K // B, B)
lo, hi = blk.min(-1), blk.max(-1)
lo = np.minimum(lo, 0); hi = np.maximum(hi, 0)                     #0 exactly representable
scale = (hi - lo) / 15.0
scale[scale == 0] = 1.0
zp = np.clip(np.round(-lo / scale), 0, 15)
q4 = np.clip(np.round(blk / scale[..., None]) + zp[..., None], 0, 15).astype(np.uint8).reshape(N, K)
data = (q4[:, 0::2] | (q4[:, 1::2] << 4)).astype(np.uint8)          #element 2i in the low nibble
zpi = zp.astype(np.uint8)
if zpi.shape[1] % 2: zpi = np.concatenate([zpi, np.zeros((N, 1), np.uint8)], 1)
zpk = (zpi[:, 0::2] | (zpi[:, 1::2] << 4)).astype(np.uint8)
deq = ((q4.reshape(N, K // B, B).astype(np.float32) - zp[..., None]) * scale[..., None]).reshape(N, K)
err = deq - W
print(f"block {B}: rms error {np.sqrt((err**2).mean()):.4f} (8-bit step {float(init['shared.weight_scale']):.3f}), "
      f"mean row cosine {np.mean((deq*W).sum(1)/(np.linalg.norm(deq,axis=1)*np.linalg.norm(W,axis=1)+1e-9)):.5f}")
nodes = [helper.make_node('GatherBlockQuantized', ['data', 'input_ids', 'scales', 'zero_points'], ['embed_matrix'],
                          domain='com.microsoft', gather_axis=0, quantize_axis=1, block_size=B, bits=4)]
g = helper.make_graph(nodes, 'madlad_embed_4bit',
    [helper.make_tensor_value_info('input_ids', TensorProto.INT64, ['batch_size', 'sequence_length'])],
    [helper.make_tensor_value_info('embed_matrix', TensorProto.FLOAT, ['batch_size', 'sequence_length', K])],
    [numpy_helper.from_array(data, 'data'), numpy_helper.from_array(scale.astype(np.float32), 'scales'), numpy_helper.from_array(zpk, 'zero_points')])
out = helper.make_model(g, opset_imports=[helper.make_opsetid('', 21), helper.make_opsetid('com.microsoft', 1)], producer_name='magitranslator-quantize', ir_version=10)
onnx.checker.check_model(out)
onnx.save(out, dst)
if os.environ.get("SAVE_DEQ"): np.save(dst + ".deq.npy", deq)   #only for evaluation
