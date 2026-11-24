# tools/eval — 日本語⇔ネパール語 翻訳品質評価（SPEC §20）

Android 不要。Python 3 のみ（依存なし。`sacrebleu` があればテストで chrF++ を照合）。

## ファイル
- `metrics.py` — chrF++（文字6・単語2・β=2、sacrebleu 互換）、数字保持率、否定一致率、用語正確率、原文文字残存率。
  否定・数字は `jane/TranslationValidator.java` / `QualityScorer.numbers()` の忠実な移植（変更時は両方を同期）。
- `run_eval.py` — テストセット×システム出力を採点し、方向別・分類別の Markdown 表を表示、`docs/eval/<日付>_<label>.json` に保存。
- `testset_seed.tsv` — 種セット（40行）。**未検証**：主張に使う前にネパール語ネイティブ話者の確認が必須。
- `onnx_runner.py` — PC で Madlad ONNX を回すための骨組み（未完成・TODO あり）。
- `test_metrics.py` — 単体テスト。

## テストセット形式（TSV, `#` 行はコメント）
`id  direction(ja-ne|ne-ja)  category  source  reference  [required_terms を | 区切り]`

## システム出力の作り方
1 システム 1 ファイル、`id<TAB>出力` の TSV（改行・タブは空白に置換）。
- **baseline / jane**：端末で各 `source` を翻訳し、結果を id 付きで書き出す（現状は手動またはデバッグ用出力）。
  PC で回す場合は `onnx_runner.py` の TODO を解消してから。
- **google 等の市販**：同じ `source` を投入し同形式で保存（§20.3-3。「市販以上」はこの比較で優位な場合のみ主張可）。

出力が無い id は除外され「missing」として列挙される（補完しない）。

## 実行
```
cd tools/eval
python3 -m unittest test_metrics
python3 run_eval.py --testset testset_seed.tsv \
  --system baseline=out/baseline.tsv --system jane=out/jane.tsv --system google=out/google.tsv \
  --label seed-baseline-jane --commit $(git rev-parse --short HEAD) --model madlad-int4
```
`--no-json` で保存を省略。指標は「単一指標で品質を決めない」（§20.2）。数値は実測のみを記録し、捏造しない。

## 指標の注意
- chrF++ はコーパス単位（統計を合算）。サンプル数の少ない分類の値は参考程度。
- 数字保持率は原文に数字がある行のみが分母（`num.pres (n)`）。
- 否定一致率は原文と出力の否定有無が一致する割合（アプリの検証器と同じ判定）。
- 用語正確率は `required_terms` を出力が含む割合。
- leftover src は出力に原文の文字（ja→ne でかな漢字、ne→ja でデーヴァナーガリー）が残った行の割合。
