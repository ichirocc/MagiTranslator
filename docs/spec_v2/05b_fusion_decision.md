# 第5部 補遺B：§11.4 以降（合意の利用・候補統合・最終決定・監査・テスト・受入条件）

> 受領原稿が §11.4 の途中で途切れていたため、続きを補う。型は Kotlin 風、実装は Java。状態欄：**実装済／部分／未実装**。

---

## 現行の候補選択についての事実

- 候補：`JaNeTranslator` が whole／文分割（sentences）／用語注入（terms）／名前の表記違い／beam N-best を作る。**すべて同じ Madlad 3B**から出る。
- 選択：「hard 問題の数が最少 → その中でスコア最大」の辞書式。スコアは逆翻訳 chrF＋MBR（候補間の合意）で、raw logits を使う（`QualityScorer`）。
- ne→ja では、主候補がすべて hard のときだけ beam の別解（`#k`）を使う。
- hard 判定は `TranslationValidator`（Stage1 Language IR の原文×候補比較、実装済）。
- 全候補が hard でも最良のものを返し、利用者への警告は出さない（**未実装**：棄権・要確認表示）。

---

## 11.4 合意（Agreement）の利用

- 合意は**同点崩しの補助証拠**としてのみ使う。重大な意味矛盾や hard 問題を、合意の多さで打ち消さない。
- **同じモデルの異なる decoding（beam の別解、用語注入の有無）どうしの合意は、独立した投票ではない**（§11.3）。現行の MBR はこの種類の合意である。したがって：
  - MBR と逆翻訳 chrF は、hard 検査を通過した候補の**中での順位付け**にだけ使う（現行の辞書式選択の2段目。この順序は実装済）。
  - 「多くの候補が同じ訳をしている」ことを、その訳が正しい根拠として利用者に示さない。
- 逆翻訳 chrF は**同じモデルで逆方向に訳した結果**との比較であり、モデルの癖（例：特定の固有名を別の語に置き換える）が往復で相殺されることがある。実測でも ja→ne で意味のずれた候補（jn14, jn19）を選んでいた。逆翻訳スコアの高さを品質の証拠として扱わない。
- 独立性が確認できる候補源（別系統のモデル、ユーザー許可済みのオンライン Provider）が加わった場合も、合意は補助証拠のままとする。

## 12. 候補統合（CandidateFusionEngine）

### 12.1 方針
統合は**任意経路**であり、既定では行わない。統合による意味の破壊（文の接続の不整合、否定の作用域のずれ、用語表記の混在）の危険が、得られる改善より大きいことが多いため。

### 12.2 許可する統合
| 種類 | 条件 |
|---|---|
| 文単位の選択（sentence-level selection） | 文境界の対応が1対1で取れる長文で、各文ごとに最良候補を選ぶ。現行の「sentences」候補（文ごとに訳して連結）はこれに近い（部分） |
| 用語の差し替え | 候補Aの文全体を採用し、Aの用語だけが MANDATORY 未達のとき、候補Bの同じ位置の用語訳に差し替える。差し替え箇所の格標識を保持できる場合のみ |

### 12.3 禁止する統合
- 1文の中で、異なる候補の句をつなぎ合わせること。
- 否定・数値・時制・モダリティ・条件を含む部分を、別候補から差し替えること。
- 統合後の候補を検査せずに返すこと。

### 12.4 FusionValidator
統合後の候補に、元の候補と同じ hard 検査（validator 全種）を再実行する。統合前の各候補より hard 問題が増えた場合、統合結果を捨てる。統合結果は `CandidateOrigin.FUSED`、`parentCandidateIds` に元候補を記録する。

## 13. 最終決定（FinalDecisionEngine）

### 13.1 決定の手順
1. hard 問題を、確信度別に分類する（§8.5）：高確信度→REJECT 候補、中→NEEDS_REVIEW、低→WARNING、評価不能→INSUFFICIENT_EVIDENCE。現行 validator は確信度を持たないため、当面は「hard＝高確信度」として扱う。
2. REJECT 候補を除外する。
3. 残りの中で、重大度の重い問題が少ない候補を優先する（辞書式。現行の「hard 数最少」を重大度別に拡張）。
4. 同点は、意味保持系のスコア（逆翻訳 chrF＋MBR）で崩す。
5. 自然さは最後の同点崩しにのみ使う。

### 13.2 棄権・要確認（AbstentionController）
**全候補が REJECT 候補になった場合、最良の候補を「要確認」付きで返す。黙って確定訳として返さない。**

```kotlin
data class FinalDecision(
    val selectedCandidateId: String?,
    val status: DecisionStatus,
    val issues: List<QualityIssue>,       // 利用者に示す問題（例：「金額の単位を確認してください」）
    val alternatives: List<String>,       // 他の候補ID（利用者が切り替えられる）
    val decisionTraceId: String
)

enum class DecisionStatus {
    CONFIDENT,              // 重大問題なし
    ACCEPTED_WITH_WARNINGS, // 軽微な問題のみ
    NEEDS_REVIEW,           // 全候補に重大問題、または中確信度の重大疑い
    ABSTAINED,              // 有効な候補が無い（空出力・エラー）
    CANCELLED
}
```

- UI では、`NEEDS_REVIEW` の訳に小さな印を付け、タップで問題の種類（否定・数値・疑問語・通貨・地名など）を短く示す。**UI 変更を伴うため実装は承認後**（第5部 notes A3）。
- 音声通訳（第9部）では、`NEEDS_REVIEW` の訳も読み上げを止めない（会話を止めないため）。代わりに画面の印と、任意で短い効果音・振動で知らせる（Android 16 の haptics は実装済の仕組みを流用）。

### 13.3 確信度の校正（ConfidenceCalibrator）
- 現行のスコア（逆翻訳 chrF、MBR、raw logits）を、**確率のような値として利用者に見せない**。
- 校正には、ネイティブ確認済みの評価データ（方向別）が必要。データが揃うまでは `DecisionStatus` の区分だけを使い、数値の確信度は出さない。

## 14. 監査・追跡（QualityAuditManager）

### 14.1 記録するもの（端末内）
```kotlin
data class DecisionTrace(
    val decisionTraceId: String,
    val requestId: String,
    val direction: String,                 // "ja-ne" 等
    val candidateLabels: List<String>,     // "whole", "sentences", "terms", "#1" 等
    val issueKindsPerCandidate: Map<String, List<String>>,  // 問題の種類のみ
    val selectedLabel: String?,
    val status: DecisionStatus,
    val evaluatorVersion: String,
    val policyVersion: String,
    val elapsedMs: Long
)
```

- **原文・訳文・候補の本文は記録しない**（PrivacyLog の方針、リリースビルドでは本文を出さない＝実装済）。候補はラベルと問題の種類だけで追跡する。
- 診断のため本文が必要な場合は、ユーザーが明示的に許可した開発者向け設定の間だけ、端末内に限って保存する（第3部 §18.4）。

### 14.2 回帰監視（RegressionMonitor）
- 開発時：`tools/eval` のシードセットで、方向別に chrF++・数値保持率・用語保持率・validator の問題件数を記録し、`docs/eval/` に保存する（実装済の手順）。
- 検査規則の変更時：保存済みの出力に対して新旧の判定を比較し、**参照訳に新たな hard 判定が付かないこと**を確認する（今回の各修正で実施済の机上確認と同じ手順）。
- Java と Python の判定一致：CI の `check_validator_parity.py`（実装済）。

## 15. 現行 validator の問題種別との対応

| 現行 `TranslationValidator.Kind` | hard/soft | `TranslationErrorType` | `IssueSeverity` |
|---|---|---|---|
| EMPTY | hard | OTHER（空出力） | CRITICAL |
| NUMBER_LOST | hard | NUMBER_MISMATCH | CRITICAL |
| NUMBER_ADDED | soft | NUMBER_MISMATCH | MODERATE |
| NEGATION_LOST | hard | NEGATION_REVERSAL | CRITICAL |
| NEGATION_ADDED | soft | NEGATION_REVERSAL | MODERATE（慣用表現の誤検出があるため） |
| TERM_MISSING | hard | MANDATORY_TERM_MISSING | MAJOR |
| UNTRANSLATED_SOURCE_SCRIPT | hard | IMPORTANT_OMISSION | MAJOR |
| QUESTION_LOST | hard | MODALITY_ERROR（発話行為） | MAJOR |
| REPETITION | hard | GRAMMAR_ERROR（異常反復） | MAJOR |
| QUESTION_WORD_LOST | hard | IMPORTANT_OMISSION | CRITICAL |
| CURRENCY_MISMATCH | hard | CURRENCY_MISMATCH | CRITICAL |
| PLACE_MISSING | hard | PROPER_NOUN_OMISSION | MAJOR |
| PLACE_ADDED | hard | HALLUCINATED_ENTITY | MAJOR |
| WH_LOST | hard | IMPORTANT_OMISSION | CRITICAL |
| OBLIGATION_LOST | hard | MODALITY_ERROR | MAJOR |
| REQUEST_LOST | hard | MODALITY_ERROR | MAJOR |
| PAST_LOST | hard | TENSE_ERROR | MAJOR |
| CONJECTURE_LOST | soft | MODALITY_ERROR | MINOR |
| PERMISSION_LOST | soft | MODALITY_ERROR | MINOR |
| NAME_EXTRA_VOWEL | hard | NAMED_ENTITY_ERROR | MODERATE |

- 現行は hard/soft の2値。上表の重大度は、統合時に `Kind` へ付ける既定値の案であり、選択の順序（hard を先に数える）は当面変えない。
- `NAME_EXTRA_VOWEL` は現行 hard だが、誤訳の重さとしては MODERATE が妥当。カタカナ名の実際の語尾に u がある場合に誤って下げる危険があるため（Task A の報告）、重大度付きの選択に移るときに hard から外すことを検討する。

## 16. テスト

| 種別 | 内容 |
|---|---|
| Unit | 辞書式選択：hard 最少が流暢な候補より優先されること（§10.1 の候補A/B/C の例を固定テストにする） |
| Unit | 合意：同一モデルの候補どうしの合意で hard 候補が選ばれないこと |
| Unit | 統合：否定・数値を含む部分を差し替えないこと、統合後に hard が増えたら捨てること |
| Unit | 最終決定：全候補 hard → `NEEDS_REVIEW`、空出力 → `ABSTAINED`、キャンセル → `CANCELLED` |
| Unit | 監査：`DecisionTrace` に本文が入らないこと |
| Unit | `Kind`→`TranslationErrorType`/`IssueSeverity` の対応表が網羅的であること（新しい Kind の追加漏れを検出） |
| Regression | シードセットの保存済み出力で、参照訳に hard 判定が付かないこと（机上確認の自動化） |
| Integration | 実モデルでの再測定：v4（IR 検査）と v3 を方向別に比較（**未実施**。CPU 負荷が大きいため机上見積り後に実施） |
| Parity | Java と Python の判定一致（CI、実装済） |

## 17. 受入条件

1. 選択が単一スコアの最大化になっていない（hard 優先の辞書式）。
2. 同一モデル由来の合意を独立した証拠として扱っていない。
3. 全候補に重大問題があるとき、要確認として利用者に分かる（UI 承認後）。
4. 未評価の項目が「問題なし」と区別されている。
5. 統合を行う場合、統合後の検査で悪化した結果を返さない。
6. 監査記録に原文・訳文が含まれない。
7. 検査規則の変更ごとに、参照訳への新たな hard 判定が無いことを確認している。
8. 実モデルでの方向別の再測定結果が保存されている（品質改善の主張はこの結果に基づいてのみ行う）。
