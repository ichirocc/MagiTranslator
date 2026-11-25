#!/usr/bin/env python3
"""Mini WER/CER evaluation for the Nepali Whisper swap (SPEC §11.3-1).

    python eval_wer_cer.py --wavdir fleurs/wav --tsv fleurs/test.tsv --onnx-dir out_int8 \
        --allowed ../../app/src/main/res/raw/whisper_ne_allowed_tokens.bin --out results.json

Systems (same utterances for all):
  hf_base   openai/whisper-small, HF generate, language=ne, task=transcribe, greedy
  hf_ne     ilprl/Whisper-Small-Nepali-ASR-OSLR54, HF generate, same settings
  onnx_raw  exported split ONNX, app decoding loop (one token/step, plain argmax, no prompt)
  onnx_app  exported split ONNX, app loop + Recognizer.constrainedLogits (allowed-token bitset,
            repetition penalty 1.2, no-repeat 3-gram) + <|startofprev|> NE_PROMPT_IDS prefix
Not reproduced: dual-hypothesis keep-best, no-speech rejection, VAD/recorder, the ONNX detokenizer
(text is decoded with the HF tokenizer; contract equivalence is checked by verify_contract.py).

Normalization: Unicode NFC, Devanagari digits -> ASCII, drop all Unicode punctuation (category P*, includes danda U+0964/U+0965),
casefold, collapse whitespace. WER = word edit distance / ref words, CER = char edit distance
(spaces excluded) / ref chars, both corpus-level (sum of edits / sum of ref length).
"""
import argparse
import csv
import json
import os
import time
import unicodedata

import numpy as np

EOS, SOT, TRANSCRIBE, NOTS, SOP = 50257, 50258, 50359, 50363, 50361
NE_PROMPT_IDS = [48449, 8485, 101, 21981, 3941, 103, 17937, 46758, 31881, 8485, 105, 33926, 46758,
                 27099, 3941, 249, 8703, 223, 8703, 97]  # Recognizer.java NE_PROMPT_IDS
REP_PENALTY, NGRAM = 1.2, 3
MAX_TOKENS_PER_SECOND, MAX_TOKENS = 30, 445


DEV_DIGITS = {0x0966 + i: str(i) for i in range(10)}


def norm(s):
    s = unicodedata.normalize("NFC", s)
    s = s.translate(DEV_DIGITS)  # FLEURS refs mix ASCII and Devanagari digits
    s = "".join(" " if unicodedata.category(c).startswith("P") else c for c in s)
    return " ".join(s.casefold().split())


def ed(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def score(pairs):
    we = wn = ce = cn = 0
    for ref, hyp in pairs:
        r, h = norm(ref), norm(hyp)
        we += ed(r.split(), h.split()); wn += len(r.split())
        rc, hc = r.replace(" ", ""), h.replace(" ", "")
        ce += ed(list(rc), list(hc)); cn += len(rc)
    return {"wer": we / wn, "cer": ce / cn, "ref_words": wn, "ref_chars": cn}


def load_allowed(path):
    bits = np.frombuffer(open(path, "rb").read(), np.uint8)
    return np.unpackbits(bits, bitorder="little").astype(bool)  # bit i of byte i>>3 = (1 << (i & 7))


def constrained(raw, allowed, generated):
    """Port of Recognizer.constrainedLogits (languageCode == "ne")."""
    lg = raw.copy()
    ok = np.zeros(lg.shape[0], bool)
    n = min(len(allowed), lg.shape[0])
    ok[:n] = allowed[:n]
    ok[EOS] = True
    lg[~ok] = -np.inf
    start = min(3, len(generated))
    seen = set()
    for t in generated[start:]:
        if 0 <= t < lg.shape[0] and t != EOS and t not in seen:
            seen.add(t)
            lg[t] = lg[t] / REP_PENALTY if lg[t] > 0 else lg[t] * REP_PENALTY
    L = len(generated)
    if L - start >= NGRAM - 1:
        tail = generated[L - (NGRAM - 1):]
        for k in range(start, L - NGRAM + 1):
            if generated[k:k + NGRAM - 1] == tail:
                b = generated[k + NGRAM - 1]
                if 0 <= b < lg.shape[0] and b != EOS:
                    lg[b] = -np.inf
    return lg


class Onnx:
    def __init__(self, d):
        import onnxruntime as ort
        from onnxruntime_extensions import get_library_path
        def s(n):
            so = ort.SessionOptions()
            so.register_custom_ops_library(get_library_path())
            so.intra_op_num_threads = 4
            return ort.InferenceSession(os.path.join(d, n), so, providers=["CPUExecutionProvider"])
        self.init, self.enc = s("Whisper_initializer.onnx"), s("Whisper_encoder.onnx")
        self.c1, self.dec = s("Whisper_cache_initializer.onnx"), s("Whisper_decoder.onnx")
        self.L = sum(1 for o in self.c1.get_outputs()) // 2

    def decode(self, audio, lang_id, allowed=None, prompt=False):
        """Mirrors the Recognizer.java batch-1 loop (j, prefixLen, firstRecordedStep, maxTokens)."""
        feat = self.init.run(None, {"audio_pcm": audio[None, :]})[0]
        enc = self.enc.run(None, {"input_features": feat})[0]
        cross = dict(zip([o.name for o in self.c1.get_outputs()], self.c1.run(None, {"encoder_hidden_states": enc})))
        prefix = [SOT, lang_id, TRANSCRIBE, NOTS]
        if prompt:
            prefix = [SOP] + NE_PROMPT_IDS + prefix
        P = len(prefix)
        first_rec = P - 3
        max_tokens = min(int(len(audio) // 16000) * MAX_TOKENS_PER_SECOND, MAX_TOKENS)
        past = {f"present.{k}.decoder.{kv}": np.zeros((1, 12, 0, 64), np.float32)
                for k in range(self.L) for kv in ("key", "value")}
        out, j, mx = [], 1, -1
        while mx != EOS:
            ids = prefix[j - 1] if j <= P else mx
            feed = {"input_ids": np.array([[ids]], np.int64)}
            for k in range(self.L):
                for kv in ("key", "value"):
                    feed[f"past_key_values.{k}.decoder.{kv}"] = past[f"present.{k}.decoder.{kv}"]
                    feed[f"past_key_values.{k}.encoder.{kv}"] = cross[f"present.{k}.encoder.{kv}"]
            past = dict(zip([o.name for o in self.dec.get_outputs()], self.dec.run(None, feed)))
            raw = past["logits"][0, 0]
            lg = constrained(raw, allowed, out) if (allowed is not None and j >= P) else raw
            mx = int(np.argmax(lg))
            if j >= first_rec:
                out.append(mx)
            if j >= max_tokens + (P - 4):
                mx = EOS
            if j < P and mx == EOS:
                mx = -1
            j += 1
        # completeOutput layout: 3 prefix-step predictions, content..., EOS
        return [t for t in out[3:] if t != EOS and t >= 0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wavdir", required=True)
    ap.add_argument("--tsv", required=True)
    ap.add_argument("--onnx-dir", required=True)
    ap.add_argument("--allowed", required=True)
    ap.add_argument("--n", type=int, default=30)
    ap.add_argument("--out", default="results.json")
    ap.add_argument("--systems", default="hf_base,hf_ne,onnx_raw,onnx_app")
    a = ap.parse_args()

    import soundfile as sf
    import torch
    from transformers import WhisperForConditionalGeneration, WhisperProcessor
    torch.set_num_threads(4)

    refs = {r[1]: r[2] for r in csv.reader(open(a.tsv, encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE)}
    files = sorted(f for f in os.listdir(a.wavdir) if f.endswith(".wav") and f in refs)[:a.n]
    audio = {}
    for f in files:
        x, sr = sf.read(os.path.join(a.wavdir, f), dtype="float32")
        assert sr == 16000 and x.ndim == 1, f
        audio[f] = x
    res = {"files": files, "refs": [refs[f] for f in files], "systems": {}}
    if os.path.exists(a.out):
        res = json.load(open(a.out, encoding="utf-8"))
    want = a.systems.split(",")
    ne_proc = WhisperProcessor.from_pretrained("ilprl/Whisper-Small-Nepali-ASR-OSLR54")
    tok = ne_proc.tokenizer
    lang_id = tok.convert_tokens_to_ids("<|ne|>")

    for name, mid in (("hf_base", "openai/whisper-small"), ("hf_ne", "ilprl/Whisper-Small-Nepali-ASR-OSLR54")):
        if name not in want:
            continue
        proc = WhisperProcessor.from_pretrained(mid)
        model = WhisperForConditionalGeneration.from_pretrained(mid).eval()
        if mid != "openai/whisper-small":  # fine-tunes often ship an outdated generation_config
            from transformers import GenerationConfig
            model.generation_config = GenerationConfig.from_pretrained("openai/whisper-small")
        hyps, t0 = [], time.time()
        for f in files:
            feat = proc.feature_extractor(audio[f], sampling_rate=16000, return_tensors="pt").input_features
            with torch.no_grad():
                ids = model.generate(feat, language="ne", task="transcribe", num_beams=1, do_sample=False,
                                     max_new_tokens=440)
            hyps.append(proc.tokenizer.decode(ids[0], skip_special_tokens=True))
            print(name, f, hyps[-1][:60], flush=True)
        res["systems"][name] = {"hyps": hyps, "sec": time.time() - t0, **score(zip(res["refs"], hyps))}
        json.dump(res, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        del model

    ox = Onnx(a.onnx_dir)
    allowed = load_allowed(a.allowed)
    for name, kw in (("onnx_raw", {}), ("onnx_app", {"allowed": allowed, "prompt": True})):
        if name not in want:
            continue
        hyps, t0 = [], time.time()
        for f in files:
            hyps.append(tok.decode(ox.decode(audio[f], lang_id, **kw), skip_special_tokens=True))
            print(name, f, hyps[-1][:60], flush=True)
        res["systems"][name] = {"hyps": hyps, "sec": time.time() - t0, **score(zip(res["refs"], hyps))}
        json.dump(res, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    for k, v in res["systems"].items():
        print(f"{k:9s} WER {v['wer']*100:6.2f}%  CER {v['cer']*100:6.2f}%  ({v['sec']:.0f}s)")


if __name__ == "__main__":
    main()
