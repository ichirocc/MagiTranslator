# Whisper 6-file ONNX interface (as consumed by the app)

Source of truth: `app/src/main/java/nie/translator/rtranslator/voice_translation/neural_networks/voice/Recognizer.java`
(`R:` = line in that file at commit f5a7345), `tools/nn/TensorUtils.java` (`TU:`), `Global.java` (`G:`).
whisper-small geometry: L=12 decoder layers, H=12 heads, dh=64, d_model=768, vocab V=51865, 80 mel bins.

All sessions register the onnxruntime-extensions custom op library (`registerCustomOpLibrary(OrtxPackage...)`,
R:197, 205, 213, 221, 248) and, except the detokenizer, run with `OptLevel.NO_OPT`.
Files are read from `getFilesDir()` (R:186-190, 242) and downloaded by `Global.getInitialDownloadInfo()`
(G:470-553, base URL G:472).

| # | File | Inputs (name: dtype shape) | Outputs | Java |
|---|------|---------------------------|---------|------|
| 1 | `Whisper_initializer.onnx` | `audio_pcm`: float32 `[1, N]` (16 kHz mono, N = samples) | output **index 0** (name free): float32 `[1, 80, 3000]` log-mel | R:313-314, 327-330 |
| 2 | `Whisper_encoder.onnx` | `input_features`: float32 `[batch_size, 80, 3000]`; first dim must be the symbolic name **`batch_size`** (app sets it to 1) | output **index 0**: float32 `[1, 1500, 768]` | R:252, 335-338 |
| 3 | `Whisper_cache_initializer.onnx` | `encoder_hidden_states`: float32 `[1, 1500, 768]` | `present.{i}.encoder.key`, `present.{i}.encoder.value` (i=0..11): float32 `[1, 12, 1500, 64]` | R:365-369, 433-434 |
| 4 | `Whisper_cache_initializer_batch.onnx` | `encoder_hidden_states`: float32 `[2, 1500, 768]` (encoder output duplicated, R:372-381) | same names, `[2, 12, 1500, 64]` | R:370-385 |
| 5 | `Whisper_decoder.onnx` | `input_ids`: **int64** `[B, 1]` (TU:253-268; B=1 or 2, R:418-420, 481-483); `past_key_values.{i}.decoder.key/value`: float32 `[B, 12, P, 64]` (P=0 on first step, R:427-432); `past_key_values.{i}.encoder.key/value`: float32 `[B, 12, 1500, 64]` from #3/#4 | `logits`: float32 `[B, 1, V]` (read as `float[][][]`, `value[b][0]`, R:460-462, 476); `present.{i}.decoder.key/value`: `[B, 12, P+1, 64]` (fed back, R:437-440) | R:412-460 |
| 6 | `Whisper_detokenizer.onnx` | `sequences`: **int32** `[1, 1, N]` (TU:46, R:537, 553, 564) | output **index 0**: string `[1, 1]` (read as `String[][]`, `[0][0]`, R:539-540) | R:533-566 |

Decoding protocol (what the exported decoder must support):
- One token per step, always `[B,1]`; the forced prefix is fed token by token: `50258 <|startoftranscript|>`,
  language id, `50359 <|transcribe|>`, `50363 <|notimestamps|>` (R:167-169, 401-402). For `ne` dual mode a
  `<|startofprev|>` prompt precedes it (R:403-407, 625-635).
- Hard-coded token ids: EOS 50257 (R:342), no-speech 50362 (R:622). These are the standard multilingual Whisper
  ids; a fine-tune must keep the same tokenizer (VERIFY with `tokenizer.convert_tokens_to_ids`).
- Layer/head counts **12 / 12 / 64 are hard-coded** (R:427-442). A checkpoint with different geometry
  (tiny/base/medium) needs app changes.
- Detokenizer must skip special tokens (the app passes content tokens only, but keep `skip_special_tokens=True`).

`verify_contract.py` checks all of the above names / dtypes / ranks automatically.
