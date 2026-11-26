# Model files built by this repository

`models/models.tsv` lists every model file the app downloads, with its SHA-256. Most are copied from
upstream; a line whose URL is `build:<script>` is built by `.github/workflows/mirror-models.yml`.

## Madlad-v2.zip (Nepali)

Same Madlad files as `Madlad.zip` (4-bit encoder / decoder / cache initializer, byte for byte), but the
embedding table `madlad_embed_8bit.onnx` (8 bits, one scale for the whole 256000 x 1024 table) is
replaced by `madlad_embed_4bit.onnx`: 4-bit blocks of 32 values, each with its own scale and zero point,
read by onnxruntime's `GatherBlockQuantized` (com.microsoft).

| | Madlad.zip | Madlad-v2.zip |
|---|---|---|
| download | 1482.6 MB | 1445.1 MB |
| on the phone (RAM / storage) | 1702 MB | 1608 MB (embedding 262 -> 168 MB) |

Quality check (`madlad_eval` in the commit message, greedy decoding with the app's ONNX files, 40 ja + 40 ne
sentences, chrF against the official translations): ja->ne 25.0 -> 24.9, ne->ja 14.5 -> 15.1 (noise level;
block 16 gave 25.3 / 14.4). Decoding speed is unchanged: the embedding only gathers a few rows per step.

Build (needs `pip install -r requirements.txt`; the output is byte-identical to the pinned hash):

    python3 build_madlad_v2.py Madlad.zip Madlad-v2.zip

Old installations from `Madlad.zip` keep working (the app uses whichever embedding file is present) and
are not asked to download again.
