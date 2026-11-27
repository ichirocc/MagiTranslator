#!/usr/bin/env python3
"""Checks quantize_embed_4bit.py on a small fake embedding (same graph shape as madlad_embed_8bit.onnx):
the 4-bit model runs in onnxruntime (GatherBlockQuantized) and returns the expected rows."""
import os, subprocess, sys, tempfile
import numpy as np, onnx, onnxruntime as ort
from onnx import helper, numpy_helper, TensorProto

HERE = os.path.dirname(os.path.abspath(__file__))

def fake_embed_8bit(path, n=300, k=64, scale=0.37, zp=110):
    rng = np.random.default_rng(0)
    q = np.clip(np.round(rng.normal(zp, 20, (n, k))), 0, 255).astype(np.uint8)
    nodes = [helper.make_node('Reshape', ['input_ids', 'shape'], ['ids2']),
             helper.make_node('Gather', ['shared.weight_quantized', 'ids2'], ['g']),
             helper.make_node('DequantizeLinear', ['g', 'shared.weight_scale', 'shared.weight_zero_point'], ['embed_matrix'])]
    g = helper.make_graph(nodes, 'e', [helper.make_tensor_value_info('input_ids', TensorProto.INT64, ['b', 's'])],
                          [helper.make_tensor_value_info('embed_matrix', TensorProto.FLOAT, ['b', 's', k])],
                          [numpy_helper.from_array(q, 'shared.weight_quantized'), numpy_helper.from_array(np.array([-1, 0], np.int64), 'shape'),
                           numpy_helper.from_array(np.array(scale, np.float32), 'shared.weight_scale'), numpy_helper.from_array(np.array(zp, np.uint8), 'shared.weight_zero_point')])
    onnx.save(helper.make_model(g, opset_imports=[helper.make_opsetid('', 21)], ir_version=10), path)
    return (q.astype(np.float32) - zp) * scale

def main():
    d = tempfile.mkdtemp()
    src, dst = os.path.join(d, 'e8.onnx'), os.path.join(d, 'e4.onnx')
    W = fake_embed_8bit(src)
    subprocess.run([sys.executable, '-I', os.path.join(HERE, 'quantize_embed_4bit.py'), src, dst, '32'], check=True,
                   env={**os.environ, 'SAVE_DEQ': '1'})
    ids = np.array([[0, 5, 299, 5]], np.int64)
    out8 = ort.InferenceSession(src).run(None, {'input_ids': ids})[0]
    out4 = ort.InferenceSession(dst).run(None, {'input_ids': ids})[0]
    deq = np.load(dst + '.deq.npy')
    assert out4.shape == out8.shape == (1, 4, 64), out4.shape
    assert np.allclose(out8[0], W[ids[0]], atol=1e-5), 'fake 8-bit model'
    assert np.allclose(out4[0], deq[ids[0]], atol=1e-5), 'onnxruntime GatherBlockQuantized == numpy dequantisation'
    # asymmetric 4-bit blocks: error at most half a step of the block's own scale
    blk = W.reshape(W.shape[0], -1, 32)
    step = (np.maximum(blk.max(-1), 0) - np.minimum(blk.min(-1), 0)) / 15
    err = np.abs(deq - W).reshape(blk.shape).max(-1)
    assert (err <= step / 2 + 1e-4).all(), 'rounding error within half a step'
    print('ok quantize_embed_4bit: shape, onnxruntime output and error bound')

if __name__ == '__main__':
    main()
