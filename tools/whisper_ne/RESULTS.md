# ネパール語 ASR ミニ WER/CER（FLEURS ne_np test 先頭15発話・277語）

PC(CPU)・greedy・fp32 ONNX（int8 未測）。正規化=NFC・デーヴァナーガリー数字→ASCII・句読点除去。
**サンプルが小さく（15発話）参考値**。生出力は `results_fleurs_ne15.json`。

| system | WER | CER |
|---|---:|---:|
| hf_base（openai/whisper-small, 現行相当） | 139.0% | 65.2% |
| hf_ne（ilprl/Whisper-Small-Nepali-ASR-OSLR54） | 50.9% | 34.9% |
| onnx_raw（hf_ne を6分割ONNX化・アプリ同等ループ・制約なし） | 50.9% | 34.9% |
| onnx_mask（＋許可トークンmask・反復罰・no-repeat-3gram） | 62.1% | 38.3% |
| onnx_prompt（＋NE_PROMPT のみ） | 65.7% | 50.6% |
| onnx_app（mask＋prompt＝現アプリ設定） | 76.5% | 54.7% |

所見:
1. 微調整モデルへの差し替えで WER 139→51%（base は反復崩壊 例「अगी अगी …」）。
2. ONNX 変換は HF と完全一致（エクスポート忠実）。
3. **アプリの mask と prompt は微調整モデルでは逆効果**（51→77%）。mask 出力の 7/15 に U+FFFD＝
   許可ビット集合がデーヴァナーガリーの部分バイト BPE トークンを禁じ、不正 UTF-8 を生む。
   → 微調整モデル使用時は mask/prompt を無効化（少なくとも mask にバイト片トークンを追加）すべき。Recognizer 側の変更が必要（未対応）。
未確認: int8 量子化劣化、実機速度/メモリ、雑音下・会話音声、学習データ(OpenSLR54)と評価の重複有無。
ライセンス/帰属はモデルカードを人手確認（TODO）。
