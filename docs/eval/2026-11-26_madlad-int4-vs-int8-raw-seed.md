# Madlad INT4（アプリ）vs INT8（CTranslate2 代理）— 素の Madlad、シードセット、PC

目的：第15部 §6「INT4 は原則採用しない」と、INT4 を本番配布している現状（第3部 notes A3）の矛盾を、実測で判断するための第一歩。

## 方法
- INT4：アプリと同じ `Madlad.zip`（Int4Acc4）を `onnx_runner.py` で再現（2026-11-25 の既存出力）。
- INT8：アプリ用の Int8WO 分割 ONNX は**入手できない**（`Madlad.zip` には Int4Acc4 のみ。`Translator.java` の Int8WO 経路は `Global.USE_EXTERNAL_MEMORY_FOR_RESOURCES=false` で無効、配布元 niedev/OnnxModelsEnhancer のリリースは本セッションから参照不可）。
  代理として **CTranslate2 の int8 版**（Hugging Face `Nextcloud-AI/madlad400-3b-mt-ct2-int8`、model.bin 2.95GB）を `tools/eval/ct2_runner.py` で実行。トークナイザー `spiece.model` はアプリと SHA-256 一致、入力接頭辞 `<2xx> ` と後処理も同一、beam 4・top-1。
- 違い（＝この比較の限界）：実行系（CTranslate2 vs ONNX Runtime）、量子化方式（CT2 int8 重み vs Int4Acc4）、ビーム探索の細部。**純粋な「INT8 vs INT4」ではない。**
- 所要：INT8 代理 40文で 1分58秒（4コア CPU、モデル読込込み）。
- **参照訳は未検証（AI 作成）。目安のみ。**

## 結果
| 方向 | 指標 | INT4（アプリ, ORT） | INT8（CT2 代理） |
|---|---|---:|---:|
| ja→ne | chrF++ | 63.59 | 63.27 |
| ja→ne | 自動 重大誤り率 | 5.0% | 5.0% |
| ja→ne | 数字 / 否定 / 用語 | 100% / 100% / 90% | 100% / 100% / 90% |
| ne→ja | chrF++ | 40.23 | 38.50 |
| ne→ja | 自動 重大誤り率 | 20.0% | 20.0% |
| ne→ja | 数字 / 否定 / 用語 | 66.7% / 100% / 42.9% | 66.7% / 100% / 42.9% |

出力が異なったのは 40 文中 6 文（jn01, jn02, jn08, jn17, nj08, nj17）。

## 所見
- この 40 文では、INT8 代理は INT4 より**良くならなかった**（chrF++ は同等〜やや低い、重大誤り率は同じ）。INT4 の量子化劣化を示す証拠は見つからなかった。
- ただし 40 文・未検証参照・実行系の違いがあり、「INT4 で品質が変わらない」とは**結論できない**（第3部 §23.4）。
- 本仕様（第15部 §6）に厳密に従うには、アプリと同じ形式の INT8（または FP32）を作り、ネイティブ確認済みの評価セット（16_eval_dataset）で重大誤り率を比べる必要がある。手順は第3部 notes の追記を参照。

## 再現
```
pip install ctranslate2 sentencepiece
# model: https://huggingface.co/Nextcloud-AI/madlad400-3b-mt-ct2-int8 （config.json, model.bin, shared_vocabulary.json, spiece.model）
python3 tools/eval/ct2_runner.py --model <dir> --in tools/eval/testset_seed.tsv --out docs/eval/outputs/2026-11-26_madlad-ct2int8-raw-beam4-seed.tsv
```
