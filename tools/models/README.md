# Model files built by this repository

`models/models.tsv` lists every model file the app downloads, with its SHA-256. Most are copied from
upstream; a line whose URL is `build:<script>` is built by `.github/workflows/mirror-models.yml`.

## Madlad-v2.zip (Nepali)

> **Status: not used (decided 2026-11-27).** The owner's rule: if something better than Madlad-v2 exists, do
> not use Madlad-v2. For Japanese <-> Nepali, PLaMo 3 Translate is far better on the same 80 test sentences
> (chrF ja->ne / ne->ja: PLaMo 2B 52.7 / 38.0, PLaMo 0.8B 45.3 / 34.1, with the personal adapter 53.1 / 44.4;
> Madlad 24.9 / 15.1, and Madlad-v2 has the same quality: it only makes Madlad smaller and faster). So
> Madlad-v2 is not built or published; the tools and their tests stay here in case Madlad matters again.
> The app keeps `Madlad.zip` for the languages PLaMo does not translate and as the fallback.


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

## The same two ideas for the other models (measured 2026-11-27: not used)

Madlad-v2's gains came from Madlad's vocabulary: 256000 tokens, most of them other scripts, so a
Japanese/Nepali output layer keeps 13%. For the models the app uses for Japanese <-> Nepali, what such a
layer removes is mostly English words, and those appear in conversations (names, brands).

**4-bit embedding table**: PLaMo's GGUF already stores `token_embd` in Q4_K. Whisper's embedding is
int8 (38 MB); per token only one row is read, so 4 bits would only save download and RAM (about 19 MB).

**Whisper, ja/ne output layer** (`whisper_vocab.py`, `split_whisper_head.py`, test
`test_split_whisper_head.py`): proj_out is int8 with one scale per token, so the kept columns are exact;
11550 of 51865 tokens kept (Japanese, Devanagari, byte pieces, ASCII up to 3 characters, the special
tokens, every token the app allows for Nepali). The app's decoding (ja + ne at once) on FLEURS Japanese
test audio (CC-BY 4.0), 150 utterances:

| | original | ja/ne layer |
|---|---|---|
| same tokens (ja row / ne row) | | 147 / 148 of 150 |
| language decision right | 150 | 150 (all the same) |
| ONNX decoder time | 328.0 s | 312.6 s (-4.7%) |

Java per step: 1.6 -> 0.4 ms (copy, argmax, two softmax over the vocabulary): about 8% in total. The 3
differences are all one word, "Amazon" -> "Amazonger" / "Amazong": the English token was removed.
Not worth it: a few percent, English words broken, and new files to publish.

**PLaMo, ja/ne output layer**: the output layer is 22.8% of what 0.8B reads per token (Q6_K, 107520 rows;
2B: 10.6%). A ja/ne layer would keep about 57000 rows (Japanese 49225, Devanagari only 78: Nepali is
written mostly with byte pieces, ASCII up to 3 characters). Timed with the first 57000 rows (a timing
experiment, not a real selection): 0.8B 39.2 / 43.3 -> 46.8 / 49.8 generated tokens/s (about +17%, PC,
4 threads). In PLaMo's translations of the test sentences 3 of 9788 tokens were outside such a set
(the ASCII token `", "`), but the removed rows are English words (46081 ASCII tokens longer than 3
characters), so names and brands would break as with Whisper, and llama.cpp would need a patch (its
logits buffer is sized by the vocabulary). Not used: translation quality first. If speed ever matters
more, this is the next thing to try (as an option, with a check on sentences that contain English names).
