# 第5部 整合性メモ（現状コード・確定判断・実測との差分）

## A. 要判断
- **A1 部番号**: 本部は「第5部＝候補・品質」を名乗り、第4部＝辞書と整合（04 notes A1 の推奨どおり）。→ **4＝辞書、5＝候補統合・品質で確定扱い**にしてよいか（本部の自己定義で両部が一致したため推奨）。
- **A2 MBR / 逆翻訳 chrF の位置付け**: 現行の主スコアは「逆翻訳 chrF＋MBR（候補間合意）」。本部 §11.1「合意は正解の証明ではない」・§11.3「同一モデルの異なる Decoding は独立投票でない」に照らすと、**現行 MBR は同一 Madlad の beam 間合意＝非独立**。実測でも ja→ne で意味ずれ候補を選んでいた（jn14/jn19）。推奨: MBR/逆翻訳は**同点崩し（tie-break）に格下げ**し、hard 検査（IR 比較）を先に置く現行の辞書式選択を維持・強化（既にその順。§10.2 と一致）。
- **A3 Abstention（§1.2 AbstentionController / 0.1「確定できない状況の認識」）**: 現行は全候補が hard でも最良を返し、警告を出さない。推奨: 全候補 hard のとき UI に「要確認」表示（第1部 TranslationResult.warnings 相当）。UI 変更を伴うので要承認。
- **A4 Learned QE（COMETKiwi 等）**: ne の適合性未検証・端末実行は重い。推奨: PC 評価用に限定（第3部 notes と同じ）。

## B. 既存コードとの対応
| 第5部 | 既存 | 状態 |
|---|---|---|
| CandidateGenerationManager | `JaNeTranslator`（whole / sentences / terms / name variants / beam N-best） | 単一モデル・複数デコード |
| HardConstraintEvaluator / 重大誤訳 | `TranslationValidator`（IR 比較: 否定・数値・疑問語・義務・依頼・過去・通貨・地名・用語・未翻訳文字・反復） | Rule-Based(A) として実装済。severity は hard/soft の2値 |
| 辞書式選択（§10.2） | 「hard 数最少 → スコア」 | 実装済（Pareto/Risk-aware は無し） |
| QualityEstimation | `QualityScorer`（逆翻訳 chrF＋MBR、raw logits） | Reference-Free(D) の簡易版 |
| Reference-Based(C) | `tools/eval`（chrF++、validator 指標、Java/Python 判定パリティ） | PC 評価のみ |
| EvaluationStatus（未評価≠0点, §3.5/§7.4） | 無し（検出できない＝問題なし扱い） | 未実装 |
| CandidateDeduplicator（否定・数値差は統合しない §5.5） | 文字列一致の重複除去のみ | 部分 |
| 数字正規化（デーヴァナーガリー数字, §5.4） | `QualityScorer.numbers` が ० -९ を ASCII 化 | 実装済 |
| CandidateFusion | 無し（文単位の結合はあるが候補間融合は無し） | 未実装（§1.3 は任意経路） |
| STRICT_LOCAL（§4.4） | オンライン経路なし＝常に該当 | 整合 |

## C. 文言・規約の整合
- C1 `TranslationCandidate` が第3部 §11.5 と同名・別構造 → 統合時に第3部版（モデル出力）を `ModelCandidate`、本部版を `TranslationCandidate` と改名して分離。
- C2 `TextSpan` に OffsetUnit 付き（第4部 notes C2 で UTF-16 統一を推奨）。本部も「混在させない」なので UTF16 固定で可。
- C3 `IssueSeverity`（5段階）と現行 hard/soft の対応: hard→CRITICAL/MAJOR、soft→MODERATE/MINOR。§8.5 の確信度による REJECT/NEEDS_REVIEW/WARNING 分岐は、現行 validator に確信度が無いため未対応。
- C4 `TranslationErrorType` と現行 `Kind` の対応表を統合時に作成（例: NEGATION_LOST→NEGATION_REVERSAL, PLACE_ADDED→HALLUCINATED_ENTITY, CURRENCY_MISMATCH→CURRENCY_MISMATCH, OBLIGATION_LOST→MODALITY_ERROR, PAST_LOST→TENSE_ERROR）。

## D. 実測からの示唆
- 本部の原則（hard 優先・流暢さで相殺しない・合意≠正解）は、v1〜v3 実測で得た結論（逆翻訳 chrF が意味ずれ候補を選ぶ）と一致。現行 v4 の IR hard 検査はこの方向の実装。効果のモデル再測定は未実施。

## E. 未定義型
`GenerationConfig`, `SentenceCandidate`, `DictionaryEvidence`, `TranslationPrivacyPolicy`, `PrivacyMode`（STRICT_LOCAL を含む値の確定）, `ResourceBudget`, `ProviderExecutionStatus`, `CandidateGenerationError`, `QualityEvaluationPolicy`, `UncertaintyReason`, `IndependenceAssessment`, §11.4 以降（受領途切れ：Fusion・FinalDecision・Audit の詳細）。
