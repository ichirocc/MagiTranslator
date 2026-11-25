# MagiTranslator 総合詳細機能実装仕様書
## 第5部：翻訳候補生成・品質推定・候補統合・誤訳検出エンジン 詳細実装仕様書

> 本文はユーザー提示の原稿（**§11.4 の途中で途切れている。続き未着**）。見出し・型定義・表は原文どおり、箇条書きの一部は一文に要約。現状との差分は `05_candidate_quality.notes.md`。

**文書バージョン：** 2.0　**対象：** 日本語 ⇔ ネパール語 / Android
**関連仕様：** 第1部 Provider管理、第2部 高度言語解析、第3部 翻訳モデル・機械翻訳基盤、第4部 辞書・用語・翻訳メモリ統合エンジン
**基本構成：** ローカル優先のハイブリッド方式
**最重要方針：** 意味保持、重大誤訳の検出、不確実性の管理、プライバシー保護

---

# 0. 文書の目的と適用範囲

## 0.1 目的
翻訳候補の生成、正規化、品質推定、比較、統合、誤訳検出、最終決定を担当するエンジンを定義する。モデルやProviderの出力をそのまま最終翻訳として採用する方式に依存しない。複数のモデル、Provider、翻訳メモリ、辞書、用語制約、言語解析結果を統合し、原文の意味を可能な限り保持した翻訳を選ぶ。候補や評価処理を増やすこと自体を品質向上と見なさない。**候補を確定できない状況を正しく認識することも重要な責務とする。**

## 0.2 最終目標
検出・抑制する問題：主語・目的語の省略による取り違え、否定・肯定の逆転、時制・相・モダリティの誤り、話者・聞き手・対象人物の取り違え、敬意・丁寧さの不適切な変更、人称・数・格標識・後置詞の誤り、数字・日時・金額・単位の変化、固有名詞の誤訳・欠落・誤同定、専門用語の誤用、原文にない情報の追加、重要情報の欠落、ネパール語として不自然な語順・活用、文脈を無視した多義語の誤訳、長文・会話全体での訳語不統一、翻訳メモリの誤った再利用、候補統合による意味の破壊。

選択する翻訳の条件：1 原文の意味を保持 2 文法的に成立 3 文脈と整合 4 必須・禁止用語等の制約を正しく扱う 5 根拠のない情報を追加しない 6 重要情報を欠落させない 7 評価できない部分を評価済みのように扱わない。

## 0.3 最重要設計原則
1. 品質を単一の数値だけで判断しない。
2. 意味の正確さと流暢さを別々に評価する。
3. 否定・数値・重要固有名詞・人称・条件の誤りを重大問題として扱う。
4. 辞書一致率だけで最終翻訳を決定しない。
5. Providerの自己申告品質を実測品質と混同しない。
6. 複数モデルの一致を正解の証明と見なさない。
7. 候補ごとの生成元・評価根拠・処理履歴を保持する。
8. 不確実な場合に無理な候補統合を行わない。
9. 原文にない意味を追加する候補を流暢さだけで高評価にしない。
10. STRICT_LOCALでは外部Provider・外部評価サービスを使用しない。
11. 評価できなかった項目と、評価結果が悪かった項目を区別する。
12. 重大な誤訳を軽微な文体上の違いと同等に扱わない。
13. 実測結果なしに品質改善を断言しない。
14. 自動評価だけで翻訳の完全な正しさを保証しない。

## 0.4 非目標
あらゆる文の完全理解、参照訳なしでの常時正解判定、複数モデル一致による正解保証、あらゆるハルシネーションの検出、自動評価による母語話者評価の完全代替、不確実な文の必ずの一意確定。

---

# 1. 全体アーキテクチャ

## 1.1 コンポーネント構成

```text
TranslationQualityEngine
├── CandidateGenerationManager
│   ├── LocalCandidateGenerator / RemoteCandidateGenerator / ModelCandidateGenerator
│   ├── DictionaryCandidateGenerator / TranslationMemoryCandidateGenerator
├── CandidateNormalizationEngine
│   ├── UnicodeNormalizer / CandidateTextNormalizer / SentenceAlignmentNormalizer / CandidateDeduplicator
├── CandidateAnalysisEngine
│   ├── SemanticComparisonEngine / GrammarEvaluationEngine / TerminologyEvaluationEngine
│   ├── NamedEntityEvaluator / NumericConsistencyEvaluator / NegationEvaluator
│   ├── RegisterEvaluator / CompletenessEvaluator / ContextConsistencyEvaluator
├── QualityEstimationEngine
│   ├── RuleBasedQualityEstimator / LearnedQualityEstimator / ReferenceBasedEvaluator
│   ├── ReferenceFreeEvaluator / UncertaintyEstimator
├── CandidateRankingEngine
│   ├── HardConstraintEvaluator / MultiObjectiveRankingEngine / RiskAwareRankingEngine
│   ├── DirectionSpecificRankingPolicy
├── CandidateFusionEngine
│   ├── SafeFusionPlanner / SpanAlignmentEngine / SemanticConflictDetector
│   ├── TerminologyAwareFusion / FusionValidator
├── HallucinationDetectionEngine
│   ├── UnsupportedContentDetector / OmissionDetector / ContradictionDetector / SemanticDriftDetector
├── FinalDecisionEngine
│   ├── BestCandidateSelector / ConfidenceCalibrator / AbstentionController / ResultComposer
└── QualityAuditManager
    ├── EvaluationTraceRecorder / RegressionMonitor / QualityMetricsCollector
```

## 1.2 責務の分離

| コンポーネント | 責務 |
|---|---|
| CandidateGenerationManager | 翻訳候補の生成・取得 |
| CandidateNormalizationEngine | 表記・文境界・候補の正規化 |
| CandidateAnalysisEngine | 原文と候補の意味・文法・情報対応の分析 |
| QualityEstimationEngine | 候補の品質と不確実性の推定 |
| CandidateRankingEngine | 制約を考慮した候補比較 |
| CandidateFusionEngine | 安全な範囲に限定した候補統合 |
| HallucinationDetectionEngine | 情報追加・欠落・矛盾の検出 |
| FinalDecisionEngine | 最終候補の選択・確定保留 |
| QualityAuditManager | 評価根拠・処理履歴・回帰情報の管理 |

候補生成、品質評価、最終決定を一つの巨大クラスにまとめない。

## 1.3 データの流れ

```text
TranslationRequest → Language Analysis (Part 2) → Dictionary / Terminology / TM (Part 4)
→ CandidateGenerationManager → CandidateNormalizationEngine → CandidateAnalysisEngine
→ QualityEstimationEngine → Hard Constraint Evaluation → Multi-objective Candidate Ranking
   ├── 安全に統合できる場合 → CandidateFusionEngine → FusionValidator
→ FinalDecisionEngine → TranslationResult
```

重大な問題が検出された場合は追加候補生成や再翻訳を要求できる。ただし無制限に繰り返さない。

---

# 2. 前部仕様との接続

## 2.1 第1部 Provider管理
Provider ID、種別、実行結果、モデルID・バージョン、実行時間、エラー情報、言語方向、プライバシー設定、外部送信許可状態、利用制限、Rate Limit、Retry情報を取得する。自己申告の品質ランクやモデル説明は参考情報として保存してよいが、実測品質と混同しない。

## 2.2 第2部 Language IR
Token、Lemma、POS、Dependency、SemanticFrame、SemanticArgument、Coreference、Tense、Aspect、Modality、Negation、Register、HonorificLevel、Entity、InformationSource、Uncertainty を受け取る。不確実な解析結果を確定した意味情報として扱わない。

## 2.3 第3部 翻訳モデル基盤
TranslationModel、ModelArtifactManifest、Tokenizer情報、Decoding設定、ModelRevision、ExecutionTrace、ModelLease、実行環境、モデル固有の制約能力を取得する。モデルのベンチマーク性能と個別候補の品質を区別する。

## 2.4 第4部 辞書・用語・翻訳メモリ
DictionaryMatch、DictionaryTranslation、Mandatory/Preferred/Forbidden Terminology、ProperNounPolicy、TranslationMemoryCandidate、MorphologyInfo、Domain、TerminologyConflict、VerificationStatus、DictionaryEvidence を取得する。辞書候補やTM候補が存在するだけで正解として扱わない。

---

# 3. 共通データモデル

## 3.1 言語コード

```kotlin
enum class LanguageCode { JA, NE }
```

## 3.2 TranslationCandidate

```kotlin
data class TranslationCandidate(
    val candidateId: String,
    val requestId: String,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val providerId: String?,
    val modelId: String?,
    val modelRevision: String?,
    val candidateOrigin: CandidateOrigin,
    val generationConfig: GenerationConfig?,
    val executionTraceId: String?,
    val parentCandidateIds: List<String>,
    val sentenceCandidates: List<SentenceCandidate>,
    val createdAtMillis: Long,
    val metadata: Map<String, String>
)
```

`parentCandidateIds` は統合で生成された候補の元候補を記録する。生成元が不明なら null または UNKNOWN とし、推測で埋めない。

## 3.3 CandidateOrigin

```kotlin
enum class CandidateOrigin {
    LOCAL_MODEL, REMOTE_MODEL, PROVIDER, DICTIONARY_ASSISTED, TRANSLATION_MEMORY,
    FUSED, LEGACY_TRANSLATOR, RULE_BASED_FALLBACK, UNKNOWN
}
```

## 3.4 TextSpan

```kotlin
data class TextSpan(val start: Int, val endExclusive: Int, val offsetUnit: OffsetUnit)

enum class OffsetUnit { UTF16, UNICODE_CODE_POINT }
```

アプリ内部でオフセット単位を混在させない。Androidの文字列インデックスを使う箇所はUTF-16とし、形態素解析やUnicode処理との変換規則を明示する。

## 3.5 DimensionScore

```kotlin
data class DimensionScore(
    val dimension: QualityDimension,
    val score: Float?,
    val status: EvaluationStatus,
    val confidence: Float?,
    val evidenceIds: List<String>,
    val explanationCode: String?
)
```

`score` が null の場合は未評価または評価不能。0点と未評価を混同しない。

## 3.6 EvaluationStatus

```kotlin
enum class EvaluationStatus { EVALUATED, PARTIALLY_EVALUATED, NOT_EVALUATED, INSUFFICIENT_EVIDENCE, UNSUPPORTED, FAILED }
```

## 3.7 QualityDimension

```kotlin
enum class QualityDimension {
    SEMANTIC_FIDELITY, GRAMMATICAL_ACCURACY, NATURALNESS, TERMINOLOGY_ACCURACY,
    NAMED_ENTITY_ACCURACY, NUMERIC_CONSISTENCY, NEGATION_CONSISTENCY, REGISTER_CONSISTENCY,
    COMPLETENESS, HALLUCINATION_RISK, CONTEXT_CONSISTENCY, MORPHOLOGY_ACCURACY, DISCOURSE_CONSISTENCY
}
```

## 3.8 QualityIssue

```kotlin
data class QualityIssue(
    val issueId: String,
    val type: TranslationErrorType,
    val severity: IssueSeverity,
    val sourceSpan: TextSpan?,
    val targetSpan: TextSpan?,
    val sourceEvidence: String?,
    val targetEvidence: String?,
    val explanationCode: String,
    val confidence: Float?,
    val evidenceIds: List<String>,
    val requiresHumanReview: Boolean
)
```

ログや診断情報に原文・訳文を無条件に保存しない。`sourceEvidence` / `targetEvidence` は表示用の一時データとし、永続化時はプライバシーポリシーを適用する。

## 3.9 CandidateQualityReport

```kotlin
data class CandidateQualityReport(
    val candidateId: String,
    val dimensionScores: List<DimensionScore>,
    val criticalIssues: List<QualityIssue>,
    val warnings: List<QualityIssue>,
    val uncertainty: Float?,
    val overallAssessment: OverallAssessment,
    val evaluatorVersion: String,
    val policyVersion: String,
    val evaluatedAtMillis: Long
)
```

## 3.10 OverallAssessment

```kotlin
enum class OverallAssessment {
    ACCEPTABLE, ACCEPTABLE_WITH_WARNINGS, LOW_CONFIDENCE, CRITICAL_PROBLEMS, INSUFFICIENT_EVIDENCE, EVALUATION_FAILED
}
```

---

# 4. 候補生成エンジン

## 4.1 CandidateGenerationManager
候補源：1 ローカル翻訳モデル 2 オンライン翻訳Provider 3 複数の翻訳モデル 4 同一モデルの異なるDecoding設定 5 辞書・用語制約を適用したモデル 6 翻訳メモリ 7 旧Translator互換経路 8 ルールベースの限定的なフォールバック。結果は共通の TranslationCandidate 形式へ変換する。

## 4.2 CandidateGenerationRequest

```kotlin
data class CandidateGenerationRequest(
    val requestId: String,
    val sourceText: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val analysisResult: LanguageAnalysisResult?,
    val dictionaryEvidence: DictionaryEvidence?,
    val context: TranslationContext?,
    val generationPolicy: CandidateGenerationPolicy,
    val privacyPolicy: TranslationPrivacyPolicy,
    val deadlineMillis: Long
)
```

## 4.3 CandidateGenerationPolicy

```kotlin
data class CandidateGenerationPolicy(
    val maxCandidateCount: Int,
    val allowLocalModels: Boolean,
    val allowRemoteProviders: Boolean,
    val allowMultipleModels: Boolean,
    val allowAlternativeDecoding: Boolean,
    val allowTranslationMemory: Boolean,
    val allowDictionaryAssistance: Boolean,
    val progressiveDelivery: Boolean,
    val maxGenerationAttempts: Int,
    val minimumResourceBudget: ResourceBudget
)
```

`maxCandidateCount` は要求単位で管理する。無制限の候補生成は禁止する。

## 4.4 STRICT_LOCALの絶対制約

```kotlin
val allowRemoteExecution =
    privacyPolicy.mode != PrivacyMode.STRICT_LOCAL &&
    privacyPolicy.remoteTranslationConsent
```

候補生成側で独自に緩和しない。STRICT_LOCALでは RemoteCandidateGenerator の起動、外部Providerへの原文送信、外部品質評価サービスへの原文送信、外部翻訳メモリ検索、外部Embedding APIへの入力送信を禁止する。ローカル品質が低い場合も許可なくオンラインへ再翻訳しない。

## 4.5 候補生成の段階制御
すべての文で複数モデルを実行する必要はない。原文の長さ、多義語の数、Language IRの不確実性、Mandatory用語の数、固有名詞の数、専門分野、初回候補の重大問題、候補間の意味的不一致、端末のメモリ・CPU状況、ネットワーク状態、ユーザー設定、処理期限から追加候補の必要性を判断する。通常の短文会話は低遅延の単一候補経路を優先し、重大な曖昧さや低信頼度が検出された場合に追加候補を生成する。

## 4.6 TranslationCandidateProvider

```kotlin
interface TranslationCandidateProvider {
    val providerId: String
    suspend fun generate(request: CandidateGenerationRequest): CandidateGenerationResult
    suspend fun cancel(requestId: String)
}
```

生成元と実行結果を必ず返す。

## 4.7 CandidateGenerationResult

```kotlin
data class CandidateGenerationResult(
    val requestId: String,
    val candidates: List<TranslationCandidate>,
    val providerStatus: ProviderExecutionStatus,
    val executionTimeMillis: Long,
    val error: CandidateGenerationError?,
    val executionTraceId: String?
)
```

## 4.8 失敗時の処理
タイムアウト、モデルロード失敗、メモリ不足、ネットワーク切断、認証失敗、Rate Limit、非対応言語方向、Tokenizerエラー、出力形式異常、空の翻訳結果は候補源ごとに記録する。一つのProviderの失敗で他の候補源を不必要に止めない。ただし全体の期限・キャンセル・メモリ制限を超えない。

---

# 5. 候補正規化・重複排除

## 5.1 CandidateNormalizationEngine
比較用表現を生成する。目的は比較の安定化であり、原文や翻訳文の意味を変えることではない。

## 5.2 正規化処理
Unicode NFC正規化、制御文字の検査、改行コードの統一、比較用の空白正規化、文境界抽出、形態素解析結果との対応付け、表示文字列と比較文字列の分離。NFKCやフィラー除去を無条件に行わない（意味を持つ語句や表記を消す可能性がある）。

## 5.3 ネパール語の正規化
NFC基本。結合文字を保持し、母音記号を削除せず、子音結合を壊さず、意味のある反復を削除せず、原文表示用文字列を保持する。

## 5.4 数字の正規化
ASCII数字、デーヴァナーガリー数字、その他の対応するUnicode数字を比較できるようにする。表示上の表記と数値の意味を分離し、表記差と意味差を別々に記録する。

## 5.5 CandidateDeduplicator
Unicode正規化後の一致、文単位・形態素単位の一致、意味的な近似、否定・数字・固有名詞の差異を考慮する。否定や数値が異なる候補を文字列類似度が高いだけで同一にまとめない。重複と判定しても生成元情報は統合して保持する。

---

# 6. 候補分析エンジン

## 6.1 目的
候補文を原文と比較し、意味・文法・情報の対応関係を評価する。単語の一致率だけで意味を判定しない。

## 6.2 入力

```kotlin
data class CandidateAnalysisRequest(
    val sourceText: String,
    val candidate: TranslationCandidate,
    val sourceAnalysis: LanguageAnalysisResult?,
    val targetAnalysis: LanguageAnalysisResult?,
    val dictionaryEvidence: DictionaryEvidence?,
    val context: TranslationContext?
)
```

対象言語側の解析器が不十分な場合は、利用できない解析項目を明示する。

## 6.3 SemanticComparisonEngine
主体、対象、行為、状態、場所、時間、原因、目的、条件、結果、可能性、義務、否定、話者の意図、依頼・命令・質問を検査する。意味役割が明確でない場合は誤りと断定せず不確実性を記録する。

## 6.4 GrammarEvaluationEngine
品詞、語順、係り受け、格標識、後置詞、動詞活用、人称、数、時制、相、モダリティ、敬意レベル、文末表現、助詞を検査する。原文の語順や形態をそのまま対象言語へ適用しない。

## 6.5 NegationEvaluator
否定辞、作用範囲、二重否定、部分否定、全体否定、否定疑問、否定と可能・義務の組み合わせ、否定の対象を確認する。否定語の有無だけで判断しない（両方に否定があっても対象が異なれば意味が変わる）。

## 6.6 NumericConsistencyEvaluator
数値、日付、時刻、金額、通貨、単位、割合、小数、範囲、順序、回数、年齢を抽出・比較する。正当な単位換算と誤った数値変更を区別し、換算した場合は規則と結果を記録する。読み取れない数値を推測で一致と判定しない。

## 6.7 NamedEntityEvaluator
人名、地名、組織名、商品名、サービス名、作品名、日付に関連する固有名称、略語を比較する。第4部のProperNounPolicyを参照し、原文のまま保持すべきものの無理な翻訳・音訳を検出する。正当な現地表記・公式訳・音訳は許容する。

## 6.8 RegisterEvaluator
丁寧さ、敬意、謙譲、尊敬、命令の強さ、依頼の柔らかさ、話者と聞き手の関係、対象人物への敬意を比較する。日本語の敬語とネパール語の敬意表現を一対一変換として扱わない。

## 6.9 CompletenessEvaluator
情報の欠落・追加、主体・対象・条件・時間情報・数量情報・否定・重要な修飾情報の消失を検出する。ただし言語間で自然な省略・補完の違いを考慮する。

---

# 7. 品質推定エンジン

## 7.1 評価方式の分類
- **A. Rule-Based**：ルール・辞書・構造比較（数値・否定・Mandatory用語・固有名詞・明確な欠落）。説明可能だが意味の微妙な違いは完全には判定できない。
- **B. Learned**：学習済み品質推定モデル。対象言語・学習データ・評価データ・ライセンス・実行環境を確認する。日本語・ネパール語に適用できるか実測せず他言語での性能を採用しない。
- **C. Reference-Based**：参照訳がある場合。開発・テスト・オフライン評価で使い、リアルタイム翻訳で参照訳があるとは仮定しない。
- **D. Reference-Free**：参照訳なしで原文・候補・文脈・解析情報から推定。本番の基本方式。ただし正解判定そのものではない。

## 7.2 QualityEstimator

```kotlin
interface QualityEstimator {
    val evaluatorId: String
    suspend fun evaluate(request: QualityEvaluationRequest): CandidateQualityReport
}
```

## 7.3 QualityEvaluationRequest

```kotlin
data class QualityEvaluationRequest(
    val sourceText: String,
    val candidate: TranslationCandidate,
    val sourceAnalysis: LanguageAnalysisResult?,
    val targetAnalysis: LanguageAnalysisResult?,
    val dictionaryEvidence: DictionaryEvidence?,
    val context: TranslationContext?,
    val referenceTranslations: List<String>?,
    val evaluationPolicy: QualityEvaluationPolicy
)
```

## 7.4 評価器の独立性
評価した項目と評価できなかった項目を明確に返す。対象言語の形態解析が失敗した場合、文法評価を0点にせず `NOT_EVALUATED` または `INSUFFICIENT_EVIDENCE` とする。

## 7.5 品質推定モデルの採用条件
モデル名、バージョン、学習データの概要、対象言語、言語方向、評価指標、ライセンス、サイズ、推論時間、メモリ、量子化条件、校正方法、既知の制限を記録する。両方向で適合性を検証し、一方向の結果を逆方向に流用しない。

## 7.6 不確実性推定
不確実性は候補スコアが低いことと同じではない。評価器間の不一致、Language IRの不確実性、対象言語解析器が未対応、重要情報の対応が特定できない、候補間で意味が異なる、品質推定モデルの適合性が不明、評価データ不足を区別する。

```kotlin
data class UncertaintyReport(
    val uncertaintyLevel: UncertaintyLevel,
    val reasons: List<UncertaintyReason>,
    val affectedSpans: List<TextSpan>,
    val evaluatorDisagreement: Float?,
    val requiresReview: Boolean
)

enum class UncertaintyLevel { LOW, MODERATE, HIGH, UNKNOWN }
```

---

# 8. 重大誤訳検出エンジン

## 8.1 基本方針
重大誤訳の検出は通常の品質スコアとは別の優先経路で実行する。流暢さや自然さで重大誤訳を相殺しない。

## 8.2 TranslationErrorType

```kotlin
enum class TranslationErrorType {
    NEGATION_REVERSAL, NEGATION_SCOPE_ERROR,
    SUBJECT_MISMATCH, OBJECT_MISMATCH, ENTITY_ROLE_MISMATCH,
    NUMBER_MISMATCH, DATE_MISMATCH, TIME_MISMATCH, CURRENCY_MISMATCH, UNIT_MISMATCH,
    NAMED_ENTITY_ERROR, PROPER_NOUN_OMISSION,
    TENSE_ERROR, ASPECT_ERROR, MODALITY_ERROR,
    REGISTER_ERROR, HONORIFIC_ERROR,
    TERMINOLOGY_ERROR, FORBIDDEN_TERM_USED, MANDATORY_TERM_MISSING,
    IMPORTANT_OMISSION, UNSUPPORTED_ADDITION, HALLUCINATED_ENTITY,
    MORPHOLOGY_ERROR, GRAMMAR_ERROR,
    CONTEXT_CONTRADICTION, SEMANTIC_DRIFT,
    TM_MISMATCH, FUSION_CONFLICT,
    OTHER
}
```

## 8.3 IssueSeverity

```kotlin
enum class IssueSeverity { CRITICAL, MAJOR, MODERATE, MINOR, INFO }
```

## 8.4 Critical Issueの対象
原則 Critical または Major：否定・肯定の逆転、主体・対象の重大な取り違え、金額・日時・数量の重大な変化、重要な固有名詞の誤訳・欠落、原文にない重要な事実・人物・条件の追加、重要情報の欠落、高リスク分野の重大な用語誤り、義務・禁止・許可の意味の逆転。検出器の確信度が低い場合は誤りを確定せず `NEEDS_REVIEW` とする。

## 8.5 Hard Constraintの適用
重大問題の検出結果は通常の加重平均より優先する。ただしすべてを無条件にREJECTへ直結させない。
- 高確信度の重大違反：REJECT候補
- 中確信度の重大疑い：NEEDS_REVIEW
- 低確信度の疑い：WARNING
- 評価不能：INSUFFICIENT_EVIDENCE

---

# 9. ハルシネーション・情報欠落検出

## 9.1 UnsupportedContentDetector
人物、行動、理由、時刻、数量、場所、条件、意図、感情、評価、因果関係、専門的説明について、原文に根拠のない追加を検査する。単語が原文にないだけでハルシネーションと判定しない（文法上必要な語や、対象言語で明示が必要な情報は補われうる）。意味的に根拠のない追加かを評価する。

## 9.2 OmissionDetector
否定、数値、時間、条件、主体、対象、行為、義務・許可、固有名詞、専門用語の欠落を重点的に確認する。

## 9.3 ContradictionDetector
原文と訳文の意味矛盾、文中の内部矛盾、前後文との矛盾、会話文脈との矛盾、用語制約との矛盾を検出する。

## 9.4 SemanticDriftDetector
長文・複数文で文ごとの意味のずれが蓄積していないか検査し、文書全体の一貫性を確認する。

---

# 10. 候補ランキングエンジン

## 10.1 単一スコア最大化の禁止
例：候補A＝自然だが否定が逆転、候補B＝やや不自然だが意味・数値・否定が正しい、候補C＝流暢だが原文にない説明を追加。この場合、AやCを自動採用しない。

## 10.2 評価段階
1 候補の構造・生成元確認 2 必須・禁止制約の確認 3 重大誤訳の検出 4 意味保持 5 数値・否定・固有名詞 6 情報完全性 7 文法・形態・敬意 8 自然さ 9 文脈一貫性 10 不確実性。

## 10.3 Multi-Objective Ranking

```kotlin
data class CandidateRankingVector(
    val semanticFidelity: Float?,
    val grammaticalAccuracy: Float?,
    val naturalness: Float?,
    val terminologyAccuracy: Float?,
    val numericConsistency: Float?,
    val negationConsistency: Float?,
    val entityAccuracy: Float?,
    val completeness: Float?,
    val contextConsistency: Float?,
    val hallucinationRisk: Float?,
    val uncertainty: Float?
)
```

比較方式：Hard Constraint Filtering、Pareto Dominance、Risk-Aware Ranking、Direction-Specific Ranking、Domain-Specific Policy。

## 10.4 Pareto評価
一つの評価軸だけが優れている候補を無条件に最良としない。他の候補にすべての重要評価軸で劣っていない候補をPareto候補として保持できるようにする。ただしPareto最適は正しさを保証しない。

## 10.5 Risk-Aware Ranking
重大誤訳の可能性と評価の不確実性を考慮する。高リスク領域では自然さより意味保持・数値・用語・情報完全性を優先する。

## 10.6 固定閾値の禁止
`0.72` などの固定値を根拠なく全言語方向・全分野・全文章に適用しない。日→ネ、ネ→日、会話文、専門文書、短文、長文、高リスク分野、評価器のバージョン、使用モデル、校正データの分布を考慮して校正する。校正データが不足する場合は閾値による自動確定を控える。

---

# 11. 複数モデル・Provider間の合意評価

## 11.1 合意は正解の証明ではない
同じ学習データ、モデル系列、翻訳方式を使っている場合、誤りが相関しうる。

## 11.2 CandidateAgreement

```kotlin
data class CandidateAgreement(
    val candidateIds: List<String>,
    val semanticAgreement: Float?,
    val lexicalAgreement: Float?,
    val sharedEntityDecisions: List<String>,
    val sharedNumberDecisions: List<String>,
    val modelFamilyGroups: List<String>,
    val independenceAssessment: IndependenceAssessment
)
```

## 11.3 独立性の扱い
モデル系列、基盤モデル、学習データの重複可能性、Providerの共通バックエンド、同一モデルの異なるDecoding設定、同一Tokenizer・推論基盤から推定する。情報が不明な場合は独立した投票として扱わない。

## 11.4 Agreementの利用
合意情報は補助的な証拠として使用する。重大な意味矛盾や……（**受領原稿はここで途切れている。続きを待つ**）
