# 第4部 補遺B：§12.6 以降（翻訳メモリの残り・用語制約生成・学習・検証・テスト・受入条件）

> 受領原稿が §12.6 の途中で途切れていたため、続きを補う。章立ては追補A（`04a_dictionary_data_sources.md` §7）の再構成案に合わせる。型は Kotlin 風、実装は Java。状態欄：**実装済／部分／未実装**。

---

## 現行の翻訳メモリについての事実

- 現行の `jane/TranslationMemory` は**文の翻訳メモリではない**。会話中に採用された**用語・名前の訳表記**（原語→訳語）を LRU 200 件まで覚え、次の翻訳の用語注入に使う。採用された訳文に実際にその訳語が含まれていた場合だけ記憶する（`JaNeTranslator.remember`）。永続化はせず、会話の終了で消える（`resetConversationMemory`）。
- したがって本節の「文単位の翻訳メモリ（TM）」は**未実装**であり、現行の仕組みは第4部 §11 の「会話スコープの用語状態」に相当する。

---

## 12.6 類似度評価（続き）

```kotlin
data class TranslationMemoryMatchScore(
    val lexicalSimilarity: Float,
    val lemmaSimilarity: Float,
    val structuralSimilarity: Float,
    val semanticSimilarity: Float,
    val domainSimilarity: Float,
    val registerSimilarity: Float,
    val terminologySimilarity: Float,
    val contextSimilarity: Float,
    val criticalDifferences: List<CriticalDifference>   // 下記。スコアとは別に保持する
)

data class CriticalDifference(
    val kind: CriticalDifferenceKind,
    val sourceSpanInQuery: TextSpan?,
    val sourceSpanInMemory: TextSpan?
)

enum class CriticalDifferenceKind {
    NEGATION, NUMBER, DATE_TIME, CURRENCY, PROPER_NOUN, PERSON, TENSE, MODALITY, QUESTION, CONDITION
}
```

- 類似度は**再利用の可否を決める値ではない**。`criticalDifferences` が1つでもある TM 候補は、類似度に関係なく「そのまま再利用」できない。
- 重要差分の判定には第2部の Language IR（実装済の Stage1：否定・疑問・疑問語・モダリティ・時制・数・通貨・地名）を使う。**原文どうしの IR を比べる**ことで、現行 validator と同じ規則を TM にも適用できる。
- 閾値（例：fuzzy 0.85）を固定値として埋め込まない。方向別・ドメイン別に、評価データで校正する（第5部 §10.6）。

## 12.7 再利用の段階

| 段階 | 条件 | 扱い |
|---|---|---|
| EXACT_REUSE | 正規化原文が完全一致、ドメイン・敬意が一致、`criticalDifferences` 無し、`NATIVE_VERIFIED` または `USER_APPROVED` | 候補として提示（第5部で他の候補と同じ検査を受ける） |
| EXACT_UNVERIFIED | 完全一致だが機械翻訳由来 | 参考候補（`REFERENCE_ONLY`） |
| FUZZY_REPAIRABLE | 差分が「重要差分以外の語」に限られ、差分箇所の対応が取れる | FuzzyRepair を試み、修復後の訳を候補にする |
| FUZZY_REFERENCE | 類似するが重要差分あり | 用語・言い回しの参考情報のみ。訳文としては使わない |
| NO_REUSE | それ以外 | 使わない |

**TM の候補も、モデルの候補と同じ hard 検査（否定・数値・疑問語・通貨・地名等）を通す。** 「TM 由来だから正しい」とは扱わない（第5部 §2.4）。

## 12.8 TranslationMemoryIntegrator

### 12.8.1 SegmentAlignment
TM の原文と訳文の対応（語・句の対応）を取る。対応が取れない部分は修復の対象にしない。

### 12.8.2 FuzzyRepair
差分が名詞句1つ程度で、その箇所の対応が取れる場合に限り、その箇所だけを差し替える。差し替える訳語は第4部の辞書・用語エンジンから取り、ne 側の格標識（को, मा, लाई 等）を保持する。

- **差分が否定・数値・時制・モダリティ・人称・条件にかかる場合は修復しない**（意味を変える箇所の機械的な差し替えは誤訳の典型）。
- 修復後の候補は `CandidateOrigin.TRANSLATION_MEMORY`、`parentCandidateIds` に TM エントリーを記録し、`MACHINE_POST_EDITED` 相当として扱う。

### 12.8.3 SpanReuse
長文の一部（文単位）が TM と完全一致する場合、その文だけを TM 訳で置き換える候補を作る。置き換えた文と前後の文の接続（人称・敬意・用語）は第5部で検査する。

### 12.8.4 ContextConsistencyChecker
TM 訳の敬意レベル・用語表記が、現在の会話・文書の用語状態（`TerminologyContext`）と食い違っていないかを確認する。

## 12.9 登録・更新・削除

- 登録は §12.3 の条件を満たすもののみ。**自動登録は行わない**のを既定とし、ユーザーが「この訳を保存」した場合（`USER_APPROVED`）またはネイティブ確認済みデータの取込（`NATIVE_VERIFIED`）に限る。
- 会話中の一時的な用語は現行どおり会話スコープのメモリに置き、会話終了で破棄する（実装済）。
- ユーザーは TM と用語集を一覧・削除・全削除できる。削除は即時に検索対象から外す。
- 永続化は第11部・第12部（DB）の仕様に従う（**未実装**）。

---

## 13. 用語制約の生成（TerminologyConstraintBuilder → TermConstraintProvider）

### 13.1 役割
第4部の辞書・用語・TM の結果を、第3部 §24 の `TermConstraint` に変換して翻訳モデル基盤へ渡す。

### 13.2 変換規則

| 第4部の強さ | `TermConstraint` | モデルへの適用（第3部 §24.4） | 翻訳後検査 |
|---|---|---|---|
| MANDATORY（ユーザー明示のみ） | `isMandatory=true`, preferredTargets | 入力前処理（用語注入）＋候補後評価 | 未達は hard（`MANDATORY_TERM_MISSING`）。未達理由を返す |
| PREFERRED | `isMandatory=false`, priority 高 | 候補後評価のみ（ja→ne の既定） | soft（同点崩し） |
| ALLOWED / REFERENCE_ONLY | 制約にしない | 適用しない | 検査しない |
| FORBIDDEN | prohibitedTargets | 候補後評価 | hard（`FORBIDDEN_TERM_USED`） |
| UNVERIFIED | 制約にしない | 適用しない | 検査しない |

- **ja→ne は PREFERRED を既定にする**（実測：用語注入は ne→ja で改善、ja→ne で悪化。第4部 notes D）。現行の `Glossary` 注入はこの表の「MANDATORY の入力前処理」に相当するので、ja→ne では強さに応じて注入をやめ、候補後評価に回す（**未実装**：現行は強さの区別なし）。
- `isMandatory=true` を満たせなかった場合は成功扱いにせず、`TermConstraintResult.warnings` と第5部の `QualityIssue` に未達を記録する（第3部 §24.4）。
- 活用・格標識の調整（第4部 §1.4）は、ne 側では語幹＋格標識の一致（現行 `TranslationValidator.containsTerm` が NFKC・ँ/ं 同一視で語幹一致を取る）を最低限とし、調整不能なら未達とする。

### 13.3 現状との対応
| 項目 | 現行 | 状態 |
|---|---|---|
| 用語注入 | `Glossary`（ja→ne 注入、ne→ja 逆注入 `injectReverse`） | 実装済（強さの区別なし） |
| 用語の翻訳後検査 | validator の TERM_MISSING（hard） | 実装済 |
| 禁止訳語 | validator の CURRENCY_MISMATCH（円・ドル）、PLACE_ADDED | 部分（個別規則） |
| `TermConstraintProvider` インターフェース | 無し | 未実装 |

---

## 14. 学習管理（DictionaryLearningManager）

### 14.1 原則
**ユーザーの修正を、自動的に正解として辞書や TM に入れない**（§0.2-14）。

### 14.2 流れ
1. UserCorrectionImporter：ユーザーが訳を修正した、または「この訳語を使う」と指定した操作を、**候補**として記録する（端末内のみ）。
2. CandidateReviewManager：同じ修正が繰り返された、または明示的に「辞書に登録」された場合に、ユーザー辞書の候補として提示する。登録はユーザーの確認操作が必要。
3. DictionaryUpdateManager：登録されたものは `USER_DICTIONARY`、`verificationStatus=UNVERIFIED`、強さは既定 `PREFERRED`（ユーザーが明示した場合のみ `MANDATORY`）。

### 14.3 禁止事項
- 修正内容を外部へ送信しない（STRICT_LOCAL・採用済み判断：オンライン既定 OFF）。
- 修正内容を、配布用の共通辞書や学習データへ自動で混ぜない。
- 修正1回で `MANDATORY` にしない。

---

## 15. 辞書データの検証（DictionaryValidator）

| 検証器 | 内容 |
|---|---|
| SchemaValidator | 必須フィールド、型、ID 重複、言語コード、UTF-8 / NFC |
| LinguisticValidator | 品詞の整合、ne 活用形の妥当性（語尾規則）、見出しと訳語の言語（ja 見出しに Devanagari が混入していないか等）、否定・数値を含む見出しの検査 |
| LicenseValidator | エントリーの出典が `license_registry.tsv`（追補A §4）で同梱可になっているか。「要確認」のソース由来は配布パッケージに入れない |
| CompatibilityValidator | 辞書パッケージのスキーマ版と、アプリの対応版の一致 |
| SecurityValidator | パッケージのパス（Zip Slip。現行 `ZipSafety` を流用）、サイズ上限、SQLite の整合、署名（現行 `ManifestSignature` を流用） |

---

## 16. 計測（DictionaryTelemetry）

- 端末内のみで集計し、外部送信しない。
- 記録してよいもの：検索時間、ヒット件数、強さ別の適用件数、制約未達の件数、競合件数、辞書バージョン。
- 記録しないもの：原文、訳文、検索語そのもの（PrivacyLog の方針。リリースビルドでは本文を出さない＝実装済）。

---

## 17. テスト

| 種別 | 内容 |
|---|---|
| Unit | 正規化（原文保持＋照合キー）、Exact/Lemma/Alias 一致、同表記別語義を統合しないこと |
| Unit | 強さの変換表（§13.2）、MANDATORY 未達の検出と理由、FORBIDDEN 検出 |
| Unit | TM：重要差分（否定・数値・時制・疑問語・通貨・地名）があると再利用しないこと |
| Unit | FuzzyRepair：差分が否定・数値にかかる場合は修復しないこと |
| Unit | 学習：修正1回で辞書に登録されないこと、外部送信しないこと |
| Integration | 辞書パッケージの導入→検索→制約生成→翻訳後検査の一連 |
| Regression | 辞書更新の前後で、シードセットの validator 判定と chrF++ を比較（現行 tools/eval を流用） |
| Security | 不正パッケージ（パス・サイズ・署名・スキーマ） |
| データ品質 | 追補A §5 の検査（語義・品詞・活用・敬意・重複・出典） |

## 18. 受入条件

1. 辞書の元データの出典・ライセンス・検証状態がエントリー単位で追える。
2. 「要確認」のソース由来のデータが配布パッケージに入っていない。
3. 文字列置換で最終訳を作っていない。
4. 強さ（MANDATORY / PREFERRED / FORBIDDEN 等）が区別され、ja→ne は PREFERRED が既定。
5. MANDATORY 未達が成功扱いになっていない。
6. TM 候補が重要差分で弾かれ、モデル候補と同じ hard 検査を受ける。
7. ユーザーの修正が自動で正解扱いされない。
8. STRICT_LOCAL で外部の辞書・TM・検索を使わない。
9. 辞書の導入・更新・ロールバックが第3部 §17 の仕組みで行える。
10. 辞書更新前後の回帰結果が保存されている。
11. 高リスク用語はネイティブまたは専門家の確認状態が記録されている。
