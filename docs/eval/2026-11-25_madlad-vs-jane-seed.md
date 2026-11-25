# raw Madlad vs jane (v1) — seed test set, PC

- Same model and machine as the raw baseline; jane = the app's library (`tools/eval/JaneEval.java`) over
  `onnx_runner.py --serve`, beam 4, n-best 3, fresh translator per row. 40 rows in 323 s.
- **References are UNVERIFIED (AI-written seed).** Indicative only.

| direction | metric | raw | jane v1 |
|---|---|---:|---:|
| ne→ja | chrF++ | 40.23 | 44.32 |
| ne→ja | terms | 42.9% | 57.1% |
| ne→ja | proper_name chrF++ | 22.18 | 51.16 |
| ja→ne | chrF++ | 63.59 | 60.46 |

Row review (what changed and why):
- nj16 カタルーニャ → カトマンズ (reverse glossary). nj17 ネバダ州 unchanged: the annotated input produced the same output.
- nj12 「100万円」 unchanged: ne→ja had no n-best, so the validator had no alternative → **fixed after this run** (n-best in ne→ja).
- jn17 ラム → रामु (+ feminine verb): katakana adds a final u to foreign names → **bare variant राम added**.
- jn15 missing danda, nj01/nj08 leading "-" → **finishing fixed**.
- jn14 「先生はもう帰られました」: jane chose "your teacher is at home" (meaning changed) — back-translation scoring picked
  the wrong candidate; no general fix yet (open issue).
