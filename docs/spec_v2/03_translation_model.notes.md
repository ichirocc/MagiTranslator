# 第3部 整合性メモ（現状コード・確定判断・実測との差分）

## A. 要判断
- **A1 部の番号付けの衝突**: 本部 §0.1 は「第1部＝Provider管理／第4部＝候補統合・品質評価／第5部＝辞書」。受領済みの第1部は「全体設計・基本アーキテクチャ」で、Provider は第1部内の一層。目次（00_README）の予定は 4＝辞書、5＝候補。**推奨: 本部の番号（4＝候補・品質、5＝辞書）を正とし目次を更新**。本部の「第1部：Provider管理」は「第1部の Provider Layer（詳細は第6部）」と読み替え。
- **A2 対象言語**: 本部も ja⇔ne のみ。現行は D3 で ja⇔vi に HY-MT 1.8B を使用（第1部メモ A2 と同件）。推奨: VI を維持し、本部の基盤（Registry/Manifest）は言語ペア非依存のまま HY-MT も載せる。
- **A3 INT4**: 本部 §13.1 は「INT4 は対応時のみ追加評価」。現行は **Madlad 3B を int4 で本番配布済み**、かつ fp32/int8 との品質比較は未実施（§13.2/§5.4「量子化後の品質を未確認のまま配布しない」に抵触）。推奨: PC の onnx_runner でシードセット上の int4 vs int8 比較を追加（CPU 負荷大→机上見積り後、小規模から）。
- **A4 学習（§9）**: 端末上では行わない。PC/クラウドで行い、成果物を Manifest 経由で配布（第1部メモ A5 と同じ）。計算資源とデータ（ネイティブ確認済み対訳）が前提で、現時点は未着手。
- **A5 署名（§4.3）**: 現行は SHA-256 固定（`ModelSources.java`, `models/models.tsv`）のみで署名なし。推奨: 公開鍵をアプリに埋め込み、manifest に Ed25519 署名（秘密鍵は CI secret）。ユーザー側で鍵管理が必要。

## B. 既存コードとの対応
| 第3部 | 既存 | 状態 |
|---|---|---|
| ModelArtifactManifest / ModelFileEntry | `ModelSources.java` + `models/models.tsv`（16ファイル・SHA-256・サイズ） | ハッシュのみ。tokenizer互換・ライセンス・署名欄なし |
| ModelVerifier | ダウンロード後の SHA-256 照合, `ZipSafety` | 実装済 |
| ModelSelectionEngine / LanguagePairSelector | `Translator.resolveModeForPair`（ne→MADLAD_CACHE, vi→HY-MT） | 固定ルール |
| EncoderRunner / DecoderRunner / KV cache | Madlad の encoder + cached decoder（MADLAD_CACHE） | 実装済 |
| Beam / N-best | `silentTranslateNBest`, beam N-best、raw logits でスコア（D7） | 実装済 |
| ModelLease / 二重ロード防止 | fair `ReentrantLock` で翻訳を直列化 | 直列化のみ。Lease・アンロードポリシーなし |
| キャンセル（§3.2） | なし（ORT `RunOptions.setTerminate` で実現可能、第1部メモ E） | 未実装 |
| 推論エラー分類（§10.5） | 例外ごとの個別分類なし | 未実装 |
| 評価基盤（§2.1 Evaluator） | `tools/eval`（chrF++・validator指標・onnx_runner・JaneEval）, `docs/eval/` | PC 側に実装済。双方向別記録は既に実施 |
| 評価データ形式（§6.2） | シード40行（参照訳は AI 作成・未検証） | `critical_facts`・`review_status` 欄なし |

## C. 文言・規約の整合
- C1 正規化: 本部 §7.4 も NFC 基本・NFKC 無条件適用の禁止。現行の照合キーは NFKC（第2部メモ C1 と同件）→ 同じ推奨（原文は NFC 保持、照合キーのみ寛容）。
- C2 反復（§11.4）: 現行 validator の `REPETITION`（直後に同じカタカナ語が続くと重大）は削除はしないが、正当な反復を誤検出しうる（例: 「ドキドキ」「バラバラ」は1語なので対象外だが、「ゆっくりゆっくり」型の強調は要確認）。推奨: 反復の擬音語・強調辞書による除外。
- C3 minSdk 36 は D5 と一致。arm64 のみ・16KB ページ対応済み（§12.5 の Release 欠落検証は CI の ELF 整列レポートで一部担保）。
- C4 §11.5「生成確率は品質の正解ではない」は現行の方針（逆翻訳chrF＋MBR＋HARD優先）と一致。

## D. 実測からの示唆
- 現行の比較は raw Madlad vs jane のみ。**NLLB-200 は CC-BY-NC**（私用の端末配布なら可否はユーザー判断、再配布・商用は不可）。比較するなら PC で distilled-600M をシードセットに当てるのが最小コスト。
- 参照訳が未検証のため、§5.3 の重み付き採用判定はまだできない（§6.3 ネイティブ確認が前提）。

## E. 未定義型
`ModelInitializationResult`, `ModelRuntimeStatus`, `RuntimeRequirements`, `ModelQualityProfile`, `ExecutionProvider`, `RuntimeCompatibility`, `ModelPrecision`, `QuantizationInfo`, `SpecialTokenSet`, `TokenizationWarning`, `TranslationConstraints`, `TermConstraint`, `StructuralHint`, `ModelTranslationOutput`, `DecodingStrategy`, `ModelArtifact`, `RuntimeInput/Output`, `RuntimeLoadResult`, `ModelLease`, `ResourceStatus`。§10.5 のエラーコードと第1部 `MagiError` の対応表が必要。
