#!/usr/bin/env python3
"""SKELETON (not functional): run the app's Madlad ONNX files on PC with onnxruntime.

File names are taken verbatim from Translator.onnxTranslationModelInitialization() (MADLAD branch).
Everything marked TODO is unknown and must be confirmed against Translator.java before use.
Do NOT report numbers produced by this script until the TODOs are resolved and outputs are
checked to match the device for a few sentences.

Output format matches run_eval.py: id<TAB>output.
"""
import argparse
import os
import sys

# Paths relative to <model_dir> (= ".../Translation/Madlad" on device).
VARIANTS = {
    # Global.USE_EXTERNAL_MEMORY_FOR_RESOURCES == true
    "int8": dict(encoder="Int8WO/madlad_encoder_8bit.onnx",
                 decoder="Int8WO/madlad_decoder_8bit.onnx",
                 cache_initializer="Int8WO/madlad_cache_initializer_8bit.onnx",
                 embed="madlad_embed_8bit.onnx",
                 vocab="spiece.model"),
    # default (internal storage)
    "int4": dict(encoder="Int4Acc4/madlad_encoder_4bit.onnx",
                 decoder="Int4Acc4/madlad_decoder_4bit.onnx",
                 cache_initializer="Int4Acc4/madlad_cache_initializer_4bit.onnx",
                 embed="madlad_embed_8bit.onnx",
                 vocab="spiece.model"),
}


class MadladRunner:
    def __init__(self, model_dir, variant="int4"):
        import onnxruntime as ort          # pip install onnxruntime
        import sentencepiece as spm        # pip install sentencepiece
        files = {k: os.path.join(model_dir, v) for k, v in VARIANTS[variant].items()}
        for k, p in files.items():
            if not os.path.exists(p):
                raise FileNotFoundError(f"{k}: {p}")
        self.sp = spm.SentencePieceProcessor(model_file=files["vocab"])
        self.sessions = {k: ort.InferenceSession(files[k]) for k in
                         ("encoder", "decoder", "cache_initializer", "embed")}
        # TODO: read input/output names via sess.get_inputs()/get_outputs() and map them to
        #       the tensors Translator.java feeds (input_ids, attention_mask, past key/values...).

    def translate(self, text, tgt_lang):
        # TODO: confirm target-language prefix used by Translator.java for Madlad
        #       (MADLAD-400 convention is "<2xx> " prepended to the source, e.g. "<2ne>"/"<2ja>").
        # TODO: embed step (madlad_embed_8bit.onnx) -> encoder -> cache_initializer -> decoder loop;
        #       replicate the app's decoding (greedy vs beam, beam size, max length, EOS id).
        # TODO: post-processing identical to the app (jane NePostEditor etc.) if evaluating "jane".
        raise NotImplementedError("Madlad ONNX decoding not ported yet; see TODOs")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--variant", choices=sorted(VARIANTS), default="int4")
    ap.add_argument("--testset", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from run_eval import read_testset
    runner = MadladRunner(a.model_dir, a.variant)
    with open(a.out, "w", encoding="utf-8") as f:
        for r in read_testset(a.testset):
            tgt = r["direction"].split("-")[1]
            out = runner.translate(r["source"], tgt).replace("\t", " ").replace("\n", " ")
            f.write(f"{r['id']}\t{out}\n")


if __name__ == "__main__":
    main()
