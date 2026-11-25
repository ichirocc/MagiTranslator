# Baseline: raw Madlad (no jane), beam 4 — seed test set

- Model: Madlad 3B Int4Acc4 (`models-v1`, SHA-256 verified), PC `tools/eval/onnx_runner.py`, onnxruntime CPU
- Contract check: real ONNX I/O matched `tools/eval/MADLAD_INTERFACE.md` (131 decoder inputs, 32 layers, vocab 256000, 1024/16/128)
- Run: 40 rows, 88 s on 4 CPU cores, ~2.4 GB RSS
- **References are UNVERIFIED (AI-written seed).** Numbers are indicative only; not usable for quality claims.

| direction | chrF++ | numbers kept | negation consistent | terms |
|---|---:|---:|---:|---:|
| ja→ne | 63.59 | 100% (3) | 100% | 80% (10) |
| ne→ja | 40.23 | 66.7% (3) | 100% | 42.9% (7) |

Observed failure modes (raw model):
- nj12 `१,००० रुपैयाँ` → 「賞金は100万円」: number and currency hallucinated
- nj16 `काठमाडौं` → カタルーニャ, nj17 `नेपाल` → ネバダ州: proper names in ne→ja
- jn09 adds `तपाईँसँग` (you) not in the source; nj11 `आउँछु` (come) → 戻ります
- jn08 `खान्न` is a correct colloquial negation; the validator missed it (fixed in the same change; table re-scored: 95% -> 100%)

Full table: `2026-11-25_madlad-raw-beam4-seed.json`; outputs: `outputs/2026-11-25_madlad-raw-beam4-seed.tsv`.
