"""Raw Madlad translation of the seed set with CTranslate2 (proxy for an INT8 Madlad; NOT the app runtime).

Same tokenizer (spiece.model sha256 identical to the app's) and the same "<2xx> " prefix as onnx_runner.py,
beam 4, top-1. Differences from the app: runtime (CTranslate2 vs ONNX Runtime), quantization scheme
(CT2 int8 weights vs the app's Int4Acc4), CT2's own beam search/length handling.

  python3 ct2_runner.py --model DIR --in testset_seed.tsv --out OUT.tsv [--compute-type int8]
"""
import argparse, sys, time
import ctranslate2, sentencepiece as spm
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from onnx_runner import madlad_prefixed_text, correct_text_madlad

ap = argparse.ArgumentParser()
ap.add_argument("--model", required=True); ap.add_argument("--in", dest="inp", required=True)
ap.add_argument("--out", required=True); ap.add_argument("--compute-type", default="int8")
ap.add_argument("--beam", type=int, default=4)
a = ap.parse_args()
sp = spm.SentencePieceProcessor(model_file=a.model + "/spiece.model")
tr = ctranslate2.Translator(a.model, device="cpu", compute_type=a.compute_type, intra_threads=4)
t0 = time.time()
with open(a.out, "w", encoding="utf-8") as out:
    for line in open(a.inp, encoding="utf-8"):
        if line.startswith("#") or line.startswith("id\t") or not line.strip(): continue
        c = line.rstrip("\n").split("\t")
        tgt = c[1].split("-")[1]
        toks = sp.encode(madlad_prefixed_text(c[3], tgt), out_type=str) + ["</s>"]
        r = tr.translate_batch([toks], beam_size=a.beam, max_decoding_length=256)
        o = correct_text_madlad(sp.decode(r[0].hypotheses[0])).replace("\t", " ").replace("\n", " ")
        out.write(c[0] + "\t" + o + "\n"); out.flush()
print(f"{time.time()-t0:.1f}s", file=sys.stderr)
