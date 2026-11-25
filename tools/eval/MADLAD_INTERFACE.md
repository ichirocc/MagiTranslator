# Madlad (MADLAD_CACHE) ONNX interface — desk-extracted from the app

Source of truth: `app/src/main/java/nie/translator/rtranslator/voice_translation/neural_networks/translation/`
`Translator.java` (T), `Tokenizer.java` (Tok), `SentencePieceProcessorJava.java` (SPJ), plus
`tools/nn/TensorUtils.java`, `tools/nn/Utils.java`, `tools/nn/CacheContainerNative.java`, `cpp/src/CacheContainerNative.cpp`.
Line numbers refer to commit `dc6883d`. Nothing here was checked against the real ONNX files
(no download / no inference, by rule). Items marked **VERIFY** must be checked with
`sess.get_inputs()/get_outputs()` once the model is available.

## Files (T:362-384)
| role | internal storage (default) | `USE_EXTERNAL_MEMORY_FOR_RESOURCES` |
|---|---|---|
| encoder | `Int4Acc4/madlad_encoder_4bit.onnx` | `Int8WO/madlad_encoder_8bit.onnx` |
| decoder | `Int4Acc4/madlad_decoder_4bit.onnx` | `Int8WO/madlad_decoder_8bit.onnx` |
| cache initializer | `Int4Acc4/madlad_cache_initializer_4bit.onnx` | `Int8WO/madlad_cache_initializer_8bit.onnx` |
| embed (MADLAD_CACHE only, `embedSession`, T:438-439) | `madlad_embed_8bit.onnx` | same |
| vocab | `spiece.model` | same |

Tokenizer is built with `Tokenizer.MADLAD` (=3) for both MADLAD and MADLAD_CACHE (T:405-406).
Session options: EXTENDED_OPT, CPU arena + memory pattern (T:413-437). Downloaded Madlad is always MADLAD_CACHE (T:305).

## Preprocessing (T:1688-1709 `correctText`)
- The sentence-terminator branch (T:1692-1699) is **discarded** for Madlad: T:1702 restarts from the original `text`.
- Madlad: `text.replaceAll("\\R"," ").replaceAll("\\p{Cntrl}","").trim()`, then `replaceAll("\\s+"," ")` (T:1707).
  Java classes are ASCII-only (`\p{Cntrl}`=[\x00-\x1F\x7F], so TAB is deleted, not spaced).

## Tokenization (Tok:57-115)
- Text becomes `"<2" + tgt + "> " + text` (Tok:60-62); source language is not used. `tgt` = `CustomLocale.getCode()` (T:1925). **VERIFY** it is `"ne"` / `"ja"` for Nepali/Japanese (resolveModeForPair compares with `"ne"`, T:303, so `"ne"` is very likely).
- Plain SentencePiece `Encode` (cpp `SentencePieceProcessorInterface.cpp:42-47`), ids used as-is (no +1 shift for Madlad, Tok:70).
- Append eos, no BOS / language token (Tok:103-107). Attention mask all 1 (Tok:111-112). No padding (Tok:114-115).
- **eos = 2, hard-coded**: `Tokenizer.PieceToID("</s>")` -> `SPJ.PieceToID`, which returns the index in the table `{"<s>","<pad>","</s>","<unk>"}` (SPJ:21,40-45), never asking sentencepiece. **VERIFY** spiece.model's `</s>` is 2 (MADLAD-400 config: eos=2, pad=1, decoder_start=0). The runner warns if not.

## Encoder (T:1141-1191, MADLAD_CACHE branch T:1160-1169)
| session | input | dtype / shape | output |
|---|---|---|---|
| embed | `input_ids` | int64 [1, L] (`convertIntArrayToTensor`, TensorUtils:253-260) | requested `embed_matrix` (dtype/shape **VERIFY**, presumably float [1, L, 1024]) |
| encoder | `input_ids` | int64 [1, L] | `last_hidden_state` (**VERIFY** float [1, L, 1024]) |
| | `attention_mask` | int64 [1, L] | |
| | `embed_matrix` | embed output | |

## Cache initializer (T:1257-1265)
Input `encoder_hidden_states` = `last_hidden_state`. Outputs `present.{i}.encoder.key` / `.value`, i=0..31,
4-D float (`batchEncoderKvCache` casts to `float[][][][]` and takes `[0]`, T:1741) -> expected [1, 16, L, 128] (**VERIFY**).

## Decoder (T:1194-1656)
Constants for MADLAD_CACHE (T:1205-1209): nLayers=32, hiddenSize=1024, hiddenSizeAttention(head dim)=128, nHeads=16.

Per step, first the embed session: `embedSession.run({input_ids}, ["embed_matrix"])` on the current decoder `input_ids` (T:1304-1313).

Decoder inputs (T:1312-1352):
| name | step 1 (j==1) | step j>=2 |
|---|---|---|
| `embed_matrix` | embed out for [[0]] | embed out for current tokens |
| `input_ids` | int64 [1,1] = [[0]] (T:1239; decoder start = 0) | int64 [B,1] (`createIntTensor` -> LongBuffer, TensorUtils:39-44; T:1409/1439) |
| `encoder_attention_mask` | int64 [1, L] | int64 [B, L] (tiled, T:1712-1722) when B>1 |
| `past_key_values.{i}.decoder.key/value` | float32 [1,16,0,128] zeros (T:1323-1327) | previous `present.{i}.decoder.key/value` |
| `past_key_values.{i}.encoder.key/value` | `present.{i}.encoder.*` of cache initializer | same, tiled to B when B>1 (T:1268,1348-1349) |

Total inputs = 3 + 4*32 = 131. Outputs used: `logits` (read as `float[][][]` -> [B, 1, V], T:1384-1387; last position `seqLen-1` is used),
`present.{i}.decoder.key/value` ([B,16,j,128]). **VERIFY** V (256000 expected, decode filter `id < 256000`, Tok:41,223) and that the
decoder does not need any other input (Java feeds exactly these; ORT would reject missing inputs on device, so this set is believed complete).

## Greedy (beam==1; app default for non-ja<->ne Madlad)
argmax of last logits (first index on ties, Utils:66-81) appended each step (T:1394-1396 / 1429-1432); next input = that token.
Stop when token == eos (T:1568-1569; eos is kept in the output and dropped by decode). Early stop (T:1577-1593), checked after `j++`:
with `n=len(input_ids)` (incl. `<2xx>` prefix + eos) and `p = len(tokenize(" "))` (T:1221, no correctText):
`n-p>30 -> j>3n`, `>20 -> j>4n`, `>10 -> j>5n`, else `j>8n`.

## Beam (beam>1; the app uses 4 for ja<->ne, T:179,888)
- Step 1: batch 1; `initBeamSearchData` (T:1795-1809): top-B tokens (successive argmax avoiding previous, i.e. stable top-k),
  score = logit - `logSumExpFast` (Utils:38-54: terms with `v-max <= -20` skipped). Then decoder cache tiled to B (`batchDecoderKvCache`, T:1766-1793).
- Steps j>=2 (`updateBeamSearchData`, T:1811-1902): per beam top-B tokens; cand = score + logit - LSE, then `-EOS_PENALTY(0.0)` if eos
  else `+WORD_REWARD(0.5)`. Pick B times the best non-picked candidate; after each pick subtract `SIBLING_PENALTY(0.2)` from all remaining
  candidates with the same token. A picked eos candidate goes to `finished` with its current (penalised) score
  (`normalizeScoreByLength` = identity since `LENGTH_ALPHA=0`, T:98,1665-1677) and does not consume a slot (i--).
- Cache reorder: `CacheContainerNative.reorder` gathers `new[i] = old[src[i]]`, `src[i] = pick[i] / B` (T:1890-1895; cpp:63-86), in place on the
  decoder `present` tensors, which are the next step's `past_key_values.*.decoder.*`.
- Stop when `len(finished) >= Math.round(1.5*B)` (T:1229,1571-1573), or the early stop above.
- Final: best finished score (first on ties); if none, best active (T:1619-1643).
- Partial-result / `PARTIAL_RESULT_MARGIN` logic (T:1456-1549) only affects UI streaming, not the returned ids.

## Decode (Tok:206-233, madlad branch 221-227)
Concatenate `IdToPiece(id)` for `3 < id < 256000`; strip one leading `▁`; replace `▁` with space. No sentencepiece Decode (byte-fallback pieces like `<0xE3>` would be emitted literally — **VERIFY** if they occur).

## Not ported (TODO)
- Sentence split / rejoin in `performTextTranslation` (T:883-910, `BreakIterator`, maxLength 200) — single segment assumed.
- ja<->ne `jane` library (T:202-235, candidates + round-trip reranking, NBEST sink T:1606-1617).
- Dictionary / Tatoeba short-circuits (T:921-935).
- Numeric equality with Java's sequential LSE sum (numpy pairwise sum may differ in the last ulp; can only flip exact ties).
