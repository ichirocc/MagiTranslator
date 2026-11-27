# 蒸留用データの生成（PC 用）

アプリは端末で学習しない。ここは PC で小型モデル（0.8B〜2B）を追加学習するときのデータを作るツール。

```bash
python3 tools/distill/build_ja_ne_distill_dataset.py --out /path/to/out
python3 tools/distill/build_ja_ne_distill_dataset.py --input-dir /path/to/extra_tsv --out /path/to/out
```

| 出力 | 中身 |
|---|---|
| `sft_mhd_grouped.jsonl` | 1つの原文に対し、正しい訳を複数（言い換え）まとめたもの。日→ネ・ネ→日の両方向 |
| `sft_chatml.jsonl` | 上を1行1組の ChatML に展開（LLaMA-Factory 等にそのまま入る形） |
| `dpo_hard_negatives.jsonl` | 正解（chosen）と、わざと壊した誤訳（rejected）の組 |
| `dataset_report.json` | 件数 |

- 入力：`dictionaries/phrases.tsv`（既定）と、`--input-dir` のヘッダ付き TSV（id, ja_spoken, ne_polite …）。
- 数字：既定でネパール数字（१२३）をアラビア数字に揃える。揃えない場合は `--keep-ne-digits`。
- 誤訳（rejected）の品質管理：ネパール語側は、アプリの検査（`tools/eval/metrics.py`）で重大な誤りと判定されるものだけを残す（否定を足した反転は「否定の追加」で可）。正解側が検査に引っかかる組は作らない。敬語崩し（तपाईं→तिमी）は検査の対象外なのでそのまま残す。
- 置き換えは語単位（「काम 」の末尾の म を तपाईं にしない）。
- 2026-11-27 の実行例：辞書4,874文 → MHD 9,522件、ChatML 9,663件、DPO 541組（否定反転222・敬語崩し112・数字167・日本語側の反転28・語の取り違え10・人称2）。
