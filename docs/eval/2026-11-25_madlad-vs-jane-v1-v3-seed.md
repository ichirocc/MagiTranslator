# raw Madlad vs jane v1–v3 — seed set, PC (references UNVERIFIED, indicative only)

All systems rescored with the current metric (term match NFKC + candrabindu == anusvara).

| direction | metric | raw | v1 | v2 | v3 |
|---|---|---:|---:|---:|---:|
| ne→ja | chrF++ | 40.23 | 44.32 | 40.17 | **44.59** |
| ne→ja | numbers kept | 66.7% | 66.7% | 100% | **100%** |
| ne→ja | terms | 42.9% | 57.1% | 71.4% | 57.1% |
| ja→ne | chrF++ | **63.59** | 60.46 | 60.78 | 60.78 |
| ja→ne | terms | **90%** | 90% | 80% | 80% |

What each version changed (row evidence):
- v1: reverse glossary → nj16 カタルーニャ→カトマンズ.
- v2: ne→ja beam n-best → nj12 「100万円」→「価格は1000円」 (number kept; currency still wrong), but free competition changed meanings
  (nj13 「私は10歳です」 for "my son is 10") and a degenerate candidate won nj17 (「ネパールネパールの首都…」).
- v3: beam alternatives only as fallback + repetition check → nj13 and nj17 degenerate output fixed, nj12 kept;
  **regression: nj04 `यो कति हो?` v2 「いくらですか？」 (correct) → v3 「これは何ですか。」** (the fallback rule blocks a correct alternative).

Open problems (not fixed):
- ja→ne is still below raw Madlad: back-translation scoring picks meaning-drifted candidates (jn14 先生はもう帰られました →
  "your teacher is at home"; jn19 何時に → कहिले "when").
- jn17 ラム → रामु: the bare variant राम is generated but the scorer prefers the full transliteration.
- nj17 नेपाल → ネバダ州シアトル (annotation ignored by the model); nj12 currency ルピー → 円.

## v4 (ja→ne IR-lite agreement, desk check only — no model run)
New `TranslationValidator` ja→ne checks: WH_LOST / OBLIGATION_LOST / REQUEST_LOST / PAST_LOST (hard),
CONJECTURE_LOST / PERMISSION_LOST (soft); `NAME_EXTRA_VOWEL` (hard) when a bare name rendering exists (ラム → राम, not रामु).
Run over the stored seed outputs (strings only): references 0/20 and raw Madlad 0/20 new hard issues (no false
positives on this set); jane v3 flags exactly jn14 (PAST_LOST, "is at home") and jn19 (WH_LOST 何時 → कहिले).
Whether the rerank then picks the raw-like candidate depends on the beam containing one: needs a model run to confirm.
