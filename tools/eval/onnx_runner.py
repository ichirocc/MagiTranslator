#!/usr/bin/env python3
"""PC port of the app's Madlad (Translator.MADLAD_CACHE) ONNX decoding, for baseline outputs.

Faithful (desk-verified, NOT yet executed against the real model) port of:
  Translator.performNNTextTranslation -> correctText -> Tokenizer.tokenize (MADLAD)
  -> executeEncoder (MADLAD_CACHE) -> executeCacheDecoder (greedy + beam) -> Tokenizer.decode
See MADLAD_INTERFACE.md (same directory) for every tensor name/shape/dtype with Java line refs.

Scope: this reproduces ONE call of performNNTextTranslation (= the raw Madlad system on one
segment). It does NOT reproduce, on purpose (TODO if needed):
  * performTextTranslation's BreakIterator sentence split + rejoin (Translator.java:883-910);
    for inputs < 200 tokens the rejoin makes it a single segment anyway (VERIFY per item).
  * the ja<->ne "jane" library (translateJaNeWithLibrary, Translator.java:202) used by the app
    for ja<->ne: that path calls this decoder with beam=JA_NE_BEAM_SIZE(4) and reranks
    candidates; evaluate it separately.
  * dictionary / Tatoeba short-circuits (Translator.java:921-935).

Output format matches run_eval.py: id<TAB>output.
"""
import argparse
import math
import os
import re
import sys

# --------------------------------------------------------------------------------------------
# Constants (Translator.java / Tokenizer.java)
# --------------------------------------------------------------------------------------------
VARIANTS = {
    # Global.USE_EXTERNAL_MEMORY_FOR_RESOURCES == true (Translator.java:363-370)
    "int8": dict(encoder="Int8WO/madlad_encoder_8bit.onnx",
                 decoder="Int8WO/madlad_decoder_8bit.onnx",
                 cache_initializer="Int8WO/madlad_cache_initializer_8bit.onnx",
                 embed="madlad_embed_8bit.onnx",
                 vocab="spiece.model"),
    # default, internal storage (Translator.java:377-383)
    "int4": dict(encoder="Int4Acc4/madlad_encoder_4bit.onnx",
                 decoder="Int4Acc4/madlad_decoder_4bit.onnx",
                 cache_initializer="Int4Acc4/madlad_cache_initializer_4bit.onnx",
                 embed="madlad_embed_8bit.onnx",
                 vocab="spiece.model"),
}

N_LAYERS = 32               # Translator.java:1206
HIDDEN_SIZE = 1024          # :1207 (only used for NLLB pre_logits; unused for Madlad)
HEAD_DIM = 128              # :1208 hiddenSizeAttention
N_HEADS = 16                # :1209
DECODER_START_ID = 0        # :1239 first decoder input_ids = {0}
# SentencePieceProcessorJava.PieceToID returns the index in the hard-coded table
# {"<s>","<pad>","</s>","<unk>"} (SentencePieceProcessorJava.java:21,40-45), so eos is ALWAYS 2,
# independent of spiece.model. VERIFY: spiece.model really maps "</s>" -> 2 (MADLAD-400 config
# says eos_token_id=2); the runner warns at load time if it does not.
EOS_ID = 2
DICTIONARY_LENGTH = 256000  # Tokenizer.java:41 (decode filter)

EOS_PENALTY = 0.0           # Translator.java:95
WORD_REWARD = 0.5           # :96
PATIENCE = 1.5              # :97 (float)
LENGTH_ALPHA = 0.0          # :98 -> normalizeScoreByLength is identity
SIBLING_PENALTY = 0.2       # :99
JA_NE_BEAM_SIZE = 4         # :179 (beam the app forces for ja<->ne, :888)
MAX_SEGMENT_TOKENS = 200    # :878-879


# --------------------------------------------------------------------------------------------
# Pure helpers (unit-tested without a model)
# --------------------------------------------------------------------------------------------
# Java regex classes used by correctText (Translator.java:1701-1707):
#   \R          = \r\n | [\n\x0B\f\r\u0085  ]
#   \p{Cntrl}   = [\x00-\x1F\x7F]   (POSIX, ASCII only, as Java default flags)
#   \s          = [ \t\n\x0B\f\r]   (ASCII only, no UNICODE_CHARACTER_CLASS)
_JAVA_R = re.compile("\r\n|[\n\x0b\x0c\r\u0085  ]")
_JAVA_CNTRL = re.compile("[\x00-\x1f\x7f]")
_JAVA_WS = re.compile("[ \t\n\x0b\x0c\r]+")


def java_trim(s):
    """String.trim(): strips leading/trailing chars <= U+0020."""
    i, j = 0, len(s)
    while i < j and s[i] <= " ":
        i += 1
    while j > i and s[j - 1] <= " ":
        j -= 1
    return s[i:j]


def correct_text_madlad(text):
    """Translator.correctText for mode MADLAD/MADLAD_CACHE (Translator.java:1688-1709).

    NOTE: the sentence-terminator branch (:1692-1699) is computed on `correctedText` but the Madlad
    branch (:1702) restarts from the ORIGINAL `text`, so for Madlad no terminator is ever added.
    """
    t = _JAVA_R.sub(" ", text)
    t = _JAVA_CNTRL.sub("", t)
    t = java_trim(t)
    return _JAVA_WS.sub(" ", t)


def madlad_prefixed_text(text, tgt_lang):
    """Tokenizer.java:60-62: "<2" + tgt + "> " + text (source language not used)."""
    return "<2" + tgt_lang + "> " + text


def build_encoder_inputs(sp_ids):
    """Tokenizer.java:94-115 (MADLAD): ids (no +1 shift) + [eos]; attention mask all 1."""
    ids = list(sp_ids) + [EOS_ID]
    return ids, [1] * len(ids)


def tokenize(encode_fn, text, tgt_lang):
    """encode_fn = plain SentencePiece Encode -> ids (no bos/eos, no sampling)."""
    return build_encoder_inputs(encode_fn(madlad_prefixed_text(text, tgt_lang)))


def decode_ids(id_to_piece, ids):
    """Tokenizer.decode, madlad branch (Tokenizer.java:221-232)."""
    out = "".join(id_to_piece(i) for i in ids if 3 < i < DICTIONARY_LENGTH)
    if out and out[0] == "▁":
        out = out[1:]
    return out.replace("▁", " ")


def early_stop_factor(n_input_ids, initial_prompt_len):
    """Translator.java:1577-1593. Returns factor f: stop once j > f * n_input_ids."""
    d = n_input_ids - initial_prompt_len
    if d > 30:
        return 3
    if d > 20:
        return 4
    if d > 10:
        return 5
    return 8


def patience_length(beam):
    """Math.round(PATIENCE*beamSize) (Translator.java:1229); float round-half-up."""
    return int(math.floor(PATIENCE * beam + 0.5))


def log_sum_exp_fast(row):
    """Utils.logSumExpFast: skips terms with diff <= -20 (not an exact LSE)."""
    import numpy as np
    r = np.asarray(row, dtype=np.float64)
    m = r.max()
    d = r - m
    # VERIFY: Java sums sequentially; numpy uses pairwise summation (last-ulp differences only)
    return float(m + math.log(np.exp(d[d > -20.0]).sum()))


def argmax_avoid(arr, avoid=()):
    """Utils.getIndexOfLargest(arr, indexesToAvoid): strict '>', first index wins ties;
    starts from -MAX_VALUE with index 0, so returns 0 if nothing qualifies (Java quirk)."""
    best_i, best = 0, None
    for i, v in enumerate(arr):
        if i in avoid:
            continue
        if best is None or v > best:
            best_i, best = i, v
    return best_i


def topk_java(row, k):
    """k successive getIndexOfLargest(row, avoid) calls == stable descending sort for finite
    values (ties -> lower index first). Vectorised to avoid a Python loop over 256k logits."""
    import numpy as np
    r = np.asarray(row)
    return [int(i) for i in np.argsort(-r, kind="stable")[:k]]


def argmax_row(row):
    """Utils.getIndexOfLargest(float[]) -- numpy argmax also returns the first max."""
    import numpy as np
    return int(np.argmax(row))


def past_names(n_layers=N_LAYERS):
    """Decoder cache input names in the order Java builds them (Translator.java:1325-1331)."""
    names = []
    for i in range(n_layers):
        names += [f"past_key_values.{i}.decoder.key", f"past_key_values.{i}.decoder.value",
                  f"past_key_values.{i}.encoder.key", f"past_key_values.{i}.encoder.value"]
    return names


def empty_decoder_past_shape():
    """Translator.java:1323: {1, nHeads, 0, hiddenSizeAttention} float32."""
    return (1, N_HEADS, 0, HEAD_DIM)


def batch_tile(arr, beam):
    """batchEncoderKvCache / batchDecoderKvCache / batchEncoderAttentionMask: take batch row 0
    and repeat it `beam` times along axis 0 (Translator.java:1741-1747, 1779-1781, 1714-1715)."""
    import numpy as np
    return np.repeat(arr[0:1], beam, axis=0)


def reorder_cache(arr, indexes):
    """CacheContainerNative.reorder: new[i] = old[indexes[i]] (gather; CacheContainerNative.cpp:63-86)."""
    import numpy as np
    return np.ascontiguousarray(arr[np.asarray(indexes)])


def init_beam(last_logits_row, beam):
    """initBeamSearchData (Translator.java:1795-1809). Returns (tokens, scores)."""
    toks = topk_java(last_logits_row, beam)
    lse = log_sum_exp_fast(last_logits_row)
    return toks, [float(last_logits_row[t]) - lse for t in toks]


def update_beam(last_logits, beam, scores, beams, finished, finished_scores, eos=EOS_ID):
    """updateBeamSearchData (Translator.java:1811-1902), minus the native cache work.

    last_logits: [beam, V] (logits[k][seqLen-1]). Mutates finished/finished_scores.
    Returns (next_tokens, new_scores, new_beams, src_indexes) where src_indexes feeds reorder_cache.
    """
    beam_max = [topk_java(last_logits[k], beam) for k in range(beam)]
    temp = [0.0] * (beam * beam)
    for k in range(beam):
        lse = log_sum_exp_fast(last_logits[k])
        for i in range(beam):
            v = scores[k] + float(last_logits[k][beam_max[k][i]]) - lse
            v = v - EOS_PENALTY if beam_max[k][i] == eos else v + WORD_REWARD
            temp[k * beam + i] = v
    avoid = []
    max_prob = [0] * beam
    i = 0
    while i < beam:
        if len(avoid) >= len(temp):
            # Java would spin forever / reuse index 0 here (all beam*beam candidates were eos).
            # TODO/VERIFY: unreachable in practice; we stop instead of looping.
            raise RuntimeError("beam: every candidate finished; Java behaviour undefined here")
        li = argmax_avoid(temp, avoid)
        avoid.append(li)
        tok = beam_max[li // beam][li % beam]
        for jj in range(len(temp)):
            if jj not in avoid and beam_max[jj // beam][jj % beam] == tok:
                temp[jj] -= SIBLING_PENALTY
        if tok == eos:
            sent = list(beams[li // beam]) + [tok]
            finished.append(sent)
            finished_scores.append(temp[li])     # normalizeScoreByLength == identity (alpha 0)
            continue                               # Java: i-- then i++
        max_prob[i] = li
        i += 1
    new_scores = [temp[m] for m in max_prob]
    new_beams = [list(beams[m // beam]) + [beam_max[m // beam][m % beam]] for m in max_prob]
    src = [m // beam for m in max_prob]
    nxt = [beam_max[m // beam][m % beam] for m in max_prob]
    return nxt, new_scores, new_beams, src


def first_index_of_max(values):
    """Utils.getIndexOfLargest(ArrayList<Double>/double[]): strict '>', first wins; -1 if empty."""
    if not values:
        return -1
    return argmax_avoid(values)


# --------------------------------------------------------------------------------------------
# Runner
# --------------------------------------------------------------------------------------------
class MadladRunner:
    def __init__(self, model_dir=None, variant="int4", sessions=None, sp=None):
        """sessions/sp may be injected (tests); otherwise loaded from model_dir."""
        if sessions is None:
            import onnxruntime as ort          # pip install onnxruntime
            import sentencepiece as spm        # pip install sentencepiece
            files = check_files(model_dir, variant)
            opts = ort.SessionOptions()
            # Java: EXTENDED_OPT, arena + mem pattern on (Translator.java:413-419)
            opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
            sessions = {k: ort.InferenceSession(files[k], opts, providers=["CPUExecutionProvider"])
                        for k in ("encoder", "decoder", "cache_initializer", "embed")}
            sp = spm.SentencePieceProcessor(model_file=files["vocab"])
            if sp.piece_to_id("</s>") != EOS_ID:
                print(f"WARNING: spiece '</s>' id={sp.piece_to_id('</s>')} but the app hard-codes "
                      f"{EOS_ID} (SentencePieceProcessorJava.java:21)", file=sys.stderr)
        self.s = sessions
        self.encode = lambda t: list(sp.encode(t))
        self.id_to_piece = sp.id_to_piece

    # ---- model steps -----------------------------------------------------------------------
    def _embed(self, ids_2d):
        return self.s["embed"].run(["embed_matrix"], {"input_ids": ids_2d})[0]

    def _encode(self, ids, mask):
        import numpy as np
        ids_t = np.asarray([ids], dtype=np.int64)
        mask_t = np.asarray([mask], dtype=np.int64)
        emb = self._embed(ids_t)
        hs = self.s["encoder"].run(["last_hidden_state"],
                                   {"input_ids": ids_t, "attention_mask": mask_t, "embed_matrix": emb})[0]
        return hs, mask_t

    def _init_cache(self, hs):
        names = [f"present.{i}.encoder.{s}" for i in range(N_LAYERS) for s in ("key", "value")]
        vals = self.s["cache_initializer"].run(names, {"encoder_hidden_states": hs})
        return dict(zip(names, vals))

    def _decode_step(self, input_ids, enc_mask, dec_past, enc_cache):
        """dec_past: dict layer->(key,value); enc_cache: dict 'present.i.encoder.key' -> array."""
        feeds = {"embed_matrix": self._embed(input_ids), "input_ids": input_ids,
                 "encoder_attention_mask": enc_mask}
        for i in range(N_LAYERS):
            feeds[f"past_key_values.{i}.decoder.key"] = dec_past[i][0]
            feeds[f"past_key_values.{i}.decoder.value"] = dec_past[i][1]
            feeds[f"past_key_values.{i}.encoder.key"] = enc_cache[f"present.{i}.encoder.key"]
            feeds[f"past_key_values.{i}.encoder.value"] = enc_cache[f"present.{i}.encoder.value"]
        out_names = ["logits"] + [f"present.{i}.decoder.{s}" for i in range(N_LAYERS) for s in ("key", "value")]
        vals = self.s["decoder"].run(out_names, feeds)
        logits = vals[0]
        present = {i: (vals[1 + 2 * i], vals[2 + 2 * i]) for i in range(N_LAYERS)}
        return logits, present

    # ---- full call ---------------------------------------------------------------------------
    def translate_ids(self, text, tgt_lang, beam=1):
        import numpy as np
        ids, mask = tokenize(self.encode, correct_text_madlad(text), tgt_lang)
        hs, mask_t = self._encode(ids, mask)
        enc_cache = self._init_cache(hs)
        # initialPromptLength = tokenize(" ") (Translator.java:1221); correctText is NOT applied there
        initial_prompt_len = len(tokenize(self.encode, " ", tgt_lang)[0])
        factor = early_stop_factor(len(ids), initial_prompt_len)

        empty = np.zeros(empty_decoder_past_shape(), dtype=np.float32)
        dec_past = {i: (empty, empty) for i in range(N_LAYERS)}
        input_ids = np.asarray([[DECODER_START_ID]], dtype=np.int64)   # shape [1,1] (:1239, :257)
        logits, dec_past = self._decode_step(input_ids, mask_t, dec_past, enc_cache)
        last = logits[0][logits.shape[1] - 1]

        if beam <= 1:
            out = [argmax_row(last)]
            j = 2
            while True:
                if out[-1] == EOS_ID:
                    break
                if j > factor * len(ids):
                    break
                input_ids = np.asarray([[out[-1]]], dtype=np.int64)
                logits, dec_past = self._decode_step(input_ids, mask_t, dec_past, enc_cache)
                out.append(argmax_row(logits[0][logits.shape[1] - 1]))
                j += 1
            return out

        # ---- beam (Translator.java:1390-1392, 1424-1428, 1567-1573, 1619-1643) ----
        toks, scores = init_beam(last, beam)
        beams = [[t] for t in toks]
        finished, finished_scores = [], []
        mask_b = batch_tile(mask_t, beam)
        enc_b = {k: batch_tile(v, beam) for k, v in enc_cache.items()}
        dec_past = {i: (batch_tile(k, beam), batch_tile(v, beam)) for i, (k, v) in dec_past.items()}
        p_len = patience_length(beam)
        j = 2
        stop = False
        # loop condition evaluated after j++ of iteration 1 (stop is never true after j=1)
        while not stop and not (j > factor * len(ids)):
            input_ids = np.asarray(toks, dtype=np.int64).reshape(beam, 1)
            logits, present = self._decode_step(input_ids, mask_b, dec_past, enc_b)
            lastk = [logits[k][logits.shape[1] - 1] for k in range(beam)]
            toks, scores, beams, src = update_beam(lastk, beam, scores, beams, finished, finished_scores)
            dec_past = {i: (reorder_cache(k, src), reorder_cache(v, src)) for i, (k, v) in present.items()}
            j += 1
            stop = len(finished) >= p_len
        if finished_scores:
            return finished[first_index_of_max(finished_scores)]
        return beams[first_index_of_max(scores)]

    def translate(self, text, tgt_lang, beam=1):
        return decode_ids(self.id_to_piece, self.translate_ids(text, tgt_lang, beam))


def check_files(model_dir, variant):
    files = {k: os.path.join(model_dir, v) for k, v in VARIANTS[variant].items()}
    missing = [f"{k}: {p}" for k, p in files.items() if not os.path.exists(p)]
    if missing:
        raise FileNotFoundError("missing model files:\n  " + "\n  ".join(missing))
    return files


def planned_feeds(n_src_tokens=12, beam=1):
    """Human-readable plan of every session call (used by --dry-run)."""
    L, B = n_src_tokens, beam
    lines = [
        f"embed.run([embed_matrix], input_ids int64[1,{L}])",
        f"encoder.run([last_hidden_state], input_ids int64[1,{L}], attention_mask int64[1,{L}], "
        f"embed_matrix <embed out>)",
        "cache_initializer.run(encoder_hidden_states=<last_hidden_state>) -> "
        f"present.{{0..{N_LAYERS-1}}}.encoder.{{key,value}}  (expected float32[1,{N_HEADS},{L},{HEAD_DIM}], VERIFY)",
        f"step 1: embed(input_ids int64[1,1]=[[{DECODER_START_ID}]]); decoder feeds: embed_matrix, input_ids int64[1,1], "
        f"encoder_attention_mask int64[1,{L}], past_key_values.i.decoder.{{key,value}} float32{list(empty_decoder_past_shape())}, "
        f"past_key_values.i.encoder.{{key,value}} <cache_initializer>, i=0..{N_LAYERS-1}  ({4*N_LAYERS + 3} inputs)",
        f"  outputs: logits float32[1,1,V], present.i.decoder.{{key,value}} float32[1,{N_HEADS},1,{HEAD_DIM}]",
    ]
    if B > 1:
        lines.append(f"step j>=2: everything tiled to batch {B}: input_ids int64[{B},1], encoder_attention_mask "
                     f"int64[{B},{L}], encoder cache [{B},{N_HEADS},{L},{HEAD_DIM}], decoder past "
                     f"[{B},{N_HEADS},j-1,{HEAD_DIM}] reordered by beam origin each step")
    else:
        lines.append(f"step j>=2: input_ids int64[1,1]=[[prev argmax]], decoder past [1,{N_HEADS},j-1,{HEAD_DIM}]")
    lines.append(f"stop: argmax==eos({EOS_ID}) [greedy] / finished>=round(1.5*beam) [beam]; "
                 f"early stop j > f*len(input_ids), f in {{3,4,5,8}}")
    return lines


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--models", required=True, help="dir containing Int4Acc4/ (or Int8WO/), madlad_embed_8bit.onnx, spiece.model")
    ap.add_argument("--variant", choices=sorted(VARIANTS), default="int4")
    ap.add_argument("--src", default=None, help="only rows whose direction starts with this (e.g. ja)")
    ap.add_argument("--tgt", default=None, help="only rows whose direction ends with this (e.g. ne)")
    ap.add_argument("--in", dest="inp", required=True, help="testset.tsv (run_eval.py format)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--beam", type=int, default=1,
                    help="1 = greedy (app default for plain Madlad); the app forces 4 for ja<->ne")
    ap.add_argument("--dry-run", action="store_true", help="check files + print planned feeds, no inference")
    a = ap.parse_args(argv)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from run_eval import read_testset
    rows = [r for r in read_testset(a.inp)
            if (a.src is None or r["direction"].split("-")[0] == a.src)
            and (a.tgt is None or r["direction"].split("-")[1] == a.tgt)]
    if a.dry_run:
        ok = True
        for k, v in VARIANTS[a.variant].items():
            p = os.path.join(a.models, v)
            present = os.path.exists(p)
            ok &= present
            print(f"[{'ok' if present else 'MISSING'}] {k}: {p}")
        print(f"rows selected: {len(rows)}  beam={a.beam}")
        for r in rows[:3]:
            tgt = r["direction"].split("-")[1]
            print(f"  {r['id']}: encoder text = {madlad_prefixed_text(correct_text_madlad(r['source']), tgt)!r}")
        print("planned feeds:")
        for ln in planned_feeds(beam=a.beam):
            print("  " + ln)
        return 0 if ok else 1
    runner = MadladRunner(a.models, a.variant)
    with open(a.out, "w", encoding="utf-8") as f:
        for r in rows:
            tgt = r["direction"].split("-")[1]
            out = runner.translate(r["source"], tgt, a.beam).replace("\t", " ").replace("\n", " ")
            f.write(f"{r['id']}\t{out}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
