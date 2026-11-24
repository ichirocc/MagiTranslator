#!/usr/bin/env python3
"""Check exported 6-file Whisper ONNX against the app contract (INTERFACE.md) and
against the HF reference.

    python verify_contract.py --dir out_tiny --model openai/whisper-tiny [--wav x.wav] [--lang ne]

Checks:
  1. I/O names / dtypes / ranks of every file (as Recognizer.java uses them).
  2. Initializer log-mel vs transformers WhisperFeatureExtractor (max abs diff).
  3. Greedy decode exactly like Recognizer.java (one token per step, empty decoder past at
     step 1, prefix <|startoftranscript|><|lang|><|transcribe|><|notimestamps|>, batch 1)
     vs HF model.generate(greedy) with the same forced prefix -> token ids + text must match.
  4. Batch-2 path (cache_initializer_batch + decoder with B=2) produces the same tokens.
--strict-app additionally requires 12 layers / 12 heads / head_dim 64 (what the app hard-codes).
"""
import argparse
import os
import sys

import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

FAIL = []


def check(cond, msg):
    print(("  OK   " if cond else "  FAIL ") + msg)
    if not cond:
        FAIL.append(msg)


def sess(path):
    so = ort.SessionOptions()
    so.register_custom_ops_library(get_library_path())
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def io(s):
    return ({i.name: (i.type, i.shape) for i in s.get_inputs()},
            {o.name: (o.type, o.shape) for o in s.get_outputs()})


def load_audio(path):
    if path is None:
        rng = np.random.default_rng(0)  # 3 s synthetic signal (content meaningless; parity check only)
        t = np.arange(16000 * 3) / 16000
        return (0.1 * np.sin(2 * np.pi * 220 * t) + 0.01 * rng.standard_normal(t.size)).astype(np.float32)
    import soundfile as sf
    x, sr = sf.read(path, dtype="float32")
    if x.ndim > 1:
        x = x.mean(1)
    if sr != 16000:
        raise SystemExit("WAV must be 16 kHz mono (resample first)")
    return x


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--processor", default=None)
    ap.add_argument("--wav", default=None)
    ap.add_argument("--lang", default="ne")
    ap.add_argument("--max-tokens", type=int, default=40)
    ap.add_argument("--strict-app", action="store_true")
    a = ap.parse_args()

    import torch
    from transformers import WhisperForConditionalGeneration, WhisperProcessor
    model = WhisperForConditionalGeneration.from_pretrained(a.model).eval()
    proc = WhisperProcessor.from_pretrained(a.processor or a.model)
    cfg = model.config
    L, H, dh = cfg.decoder_layers, cfg.decoder_attention_heads, cfg.d_model // cfg.decoder_attention_heads
    tok = proc.tokenizer
    p = lambda n: os.path.join(a.dir, n)

    print("[1] contract")
    if a.strict_app:
        check((L, H, dh) == (12, 12, 64), f"app geometry 12/12/64 (got {L}/{H}/{dh})")
    s_init, s_enc = sess(p("Whisper_initializer.onnx")), sess(p("Whisper_encoder.onnx"))
    s_c1, s_c2 = sess(p("Whisper_cache_initializer.onnx")), sess(p("Whisper_cache_initializer_batch.onnx"))
    s_dec, s_det = sess(p("Whisper_decoder.onnx")), sess(p("Whisper_detokenizer.onnx"))
    i, o = io(s_init)
    check(list(i) == ["audio_pcm"] and i["audio_pcm"][0] == "tensor(float)" and len(i["audio_pcm"][1]) == 2,
          f"initializer in audio_pcm float rank2: {i}")
    check(len(o) == 1 and list(o.values())[0][0] == "tensor(float)", f"initializer 1 float output: {o}")
    i, o = io(s_enc)
    check(list(i) == ["input_features"] and i["input_features"][1][0] == "batch_size",
          f"encoder in input_features[batch_size,..]: {i}")
    for s, b in ((s_c1, 1), (s_c2, 2)):
        i, o = io(s)
        check(list(i) == ["encoder_hidden_states"] and i["encoder_hidden_states"][1][0] == b,
              f"cache init batch {b} input: {i}")
        want = {f"present.{k}.encoder.{kv}" for k in range(L) for kv in ("key", "value")}
        check(set(o) == want, f"cache init batch {b} outputs present.*.encoder.* ({len(o)})")
    i, o = io(s_dec)
    want_in = {"input_ids"} | {f"past_key_values.{k}.{pt}.{kv}" for k in range(L)
                               for pt in ("decoder", "encoder") for kv in ("key", "value")}
    check(set(i) == want_in, f"decoder inputs ({len(i)})")
    check(i["input_ids"][0] == "tensor(int64)", "decoder input_ids int64 (TensorUtils.convertIntArrayToTensor)")
    want_out = {"logits"} | {f"present.{k}.decoder.{kv}" for k in range(L) for kv in ("key", "value")}
    check(set(o) == want_out, f"decoder outputs ({len(o)})")
    i, o = io(s_det)
    check(list(i) == ["sequences"] and i["sequences"][0] == "tensor(int32)" and len(i["sequences"][1]) == 3,
          f"detokenizer sequences int32 rank3: {i}")
    check(list(o.values())[0][0] == "tensor(string)", f"detokenizer string output: {o}")

    audio = load_audio(a.wav)
    print("[2] features")
    feat = s_init.run(None, {"audio_pcm": audio[None, :]})[0]
    ref = proc.feature_extractor(audio, sampling_rate=16000, return_tensors="np").input_features
    d = float(np.abs(feat - ref).max())
    check(feat.shape == ref.shape and d < 1e-3, f"log-mel shape {feat.shape} max|diff| {d:.2e}")

    print("[3] greedy decode (app loop, batch 1) vs HF generate")
    enc = s_enc.run(None, {"input_features": feat})[0]
    eos = tok.eos_token_id
    prefix = [tok.convert_tokens_to_ids(t) for t in
              ("<|startoftranscript|>", f"<|{a.lang}|>", "<|transcribe|>", "<|notimestamps|>")]
    print("  prefix ids", prefix)

    def run(batch):
        cross = (s_c1 if batch == 1 else s_c2).run(None, {"encoder_hidden_states": np.repeat(enc, batch, 0)})
        cross = dict(zip([x.name for x in (s_c1 if batch == 1 else s_c2).get_outputs()], cross))
        past = {f"present.{k}.decoder.{kv}": np.zeros((batch, H, 0, dh), np.float32)
                for k in range(L) for kv in ("key", "value")}
        out, j, nxt = [], 1, None
        while True:
            ids = prefix[j - 1] if j <= len(prefix) else nxt
            feed = {"input_ids": np.full((batch, 1), ids, np.int64)}
            for k in range(L):
                for kv in ("key", "value"):
                    feed[f"past_key_values.{k}.decoder.{kv}"] = past[f"present.{k}.decoder.{kv}"]
                    feed[f"past_key_values.{k}.encoder.{kv}"] = cross[f"present.{k}.encoder.{kv}"]
            res = dict(zip([x.name for x in s_dec.get_outputs()], s_dec.run(None, feed)))
            past = res
            nxt = int(res["logits"][0, 0].argmax())
            if j >= len(prefix):
                if nxt == eos or len(out) >= a.max_tokens:
                    break
                out.append(nxt)
            j += 1
        return out

    ours = run(1)
    with torch.no_grad():
        # plain HF forward greedy (no generation_config suppress lists, same as the app loop)
        seq, hf = list(prefix), []
        while len(hf) < a.max_tokens:
            nt = int(model(input_features=torch.tensor(ref),
                           decoder_input_ids=torch.tensor([seq])).logits[0, -1].argmax())
            if nt == eos:
                break
            hf.append(nt)
            seq.append(nt)
    check(ours == hf,
          f"token ids match (onnx {len(ours)} / hf {len(hf)})")
    text = s_det.run(None, {"sequences": np.array(ours, np.int32)[None, None, :]})[0]
    text = text.reshape(-1)[0]
    hf_text = tok.decode(hf, skip_special_tokens=True)
    print("  onnx :", repr(text))
    print("  hf   :", repr(hf_text))
    check(text.strip() == hf_text.strip(), "detokenized text matches HF tokenizer.decode")

    print("[4] batch-2 path")
    check(run(2) == ours, "batch 2 decode == batch 1")

    print("RESULT:", "PASS" if not FAIL else f"FAIL ({len(FAIL)})")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
