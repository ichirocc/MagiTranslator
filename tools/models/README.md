# Model files built by this repository

`models/models.tsv` lists every model file the app downloads, with its SHA-256. Most are copied from
upstream; a line whose URL is `build:<script>` is built by `.github/workflows/mirror-models.yml`.

## Madlad-v2.zip (Nepali)

> **Status: not built, not published, not used by the app yet.** The tools and their tests are here; the
> `build:` line in `models/models.tsv`, the CI build and the app side (loading the 4-bit table and the two
> heads, `Translator.java`) are in `archive/work-2026-11` (commits f81a474, 20076e8) and wait for the owner's
> decision to publish the file to the models-v1 release (a published file cannot be replaced: its hash is
> pinned). Until then the app downloads and uses `Madlad.zip`.


Built from `Madlad.zip` by `build_madlad_v2.py`. The encoder, the cache initializer and `spiece.model`
are copied byte for byte (already 4-bit MatMulNBits, block 128, accuracy level 4). Two changes:

1. **Embedding table in 4-bit blocks** (`quantize_embed_4bit.py`): `madlad_embed_8bit.onnx` (8 bits, one
   scale for the whole 256000 x 1024 table, 262 MB) becomes `madlad_embed_4bit.onnx`: blocks of 32 values
   with their own scale and zero point, read by onnxruntime's `GatherBlockQuantized` (168 MB).
2. **Output layer split off, with a Japanese/Nepali-only copy** (`output_vocab.py`, `split_output_layer.py`):
   `Int4Acc4/madlad_decoder_4bit_body.onnx` is the decoder without its last node (output `pre_logits`);
   `madlad_lm_head_full.onnx` is that layer unchanged (256000 tokens) and `madlad_lm_head_jane.onnx` keeps
   only the 34576 tokens a Japanese or Nepali translation can use (Devanagari, Japanese, ASCII digits and
   punctuation, short Latin pieces, acronyms, a few names; every token of the 3788 ja/ne phrase pairs).
   The app uses the ja/ne head for a Japanese or Nepali target and the full head for any other target
   (loaded only when needed), so every language Madlad translates keeps working.

| | Madlad.zip | Madlad-v2.zip |
|---|---|---|
| download | 1482.6 MB | 1462.2 MB |
| installed | 1702 MB | 1627 MB |
| RAM for a ja/ne translation (full head not loaded) | 1702 MB | 1487 MB |

Checks (Python, the app's ONNX files, 40 ja + 40 ne sentences, desktop CPU):

- 4-bit embedding: chrF against the official translations ja->ne 25.0 -> 24.9, ne->ja 14.5 -> 15.1 (noise).
- Split: body + full head reproduces the original logits bit for bit, the cache outputs are identical.
- ja/ne head: greedy translations 80/80 identical; beam 4: 71/80 identical, the others reworded (no lost
  meaning). The removed tokens hold ~8% of the probability per step (a long tail over 221k tokens), so
  beam scores are renormalised over the kept tokens.
- Speed per decoder step (4 beams): model ~1.1x faster (head on one thread without busy-waiting, decoder
  thread pool unchanged), Java top-k + logSumExp 5.3 -> 0.9 ms, logits copied to Java 7.4x smaller.
  Measure on the phone: the Tensor G5 numbers will differ.

Build (needs `pip install -r requirements.txt`; the output is byte-identical to the pinned hash):

    python3 build_madlad_v2.py Madlad.zip Madlad-v2.zip

Tests: `python3 test_quantize_embed.py`, `python3 test_split_output_layer.py`.

Old installations from `Madlad.zip` keep working (the app falls back to the 8-bit table and the single
decoder file) and are not asked to download again.
