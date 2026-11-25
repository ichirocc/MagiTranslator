# MagiTranslator 総合詳細実装仕様書 v2（統合版・正本）

> **本書が正本である。** `docs/spec_v2/` の各部ファイル（01〜14、補遺 03b/04a/04b/05b、各 `.notes.md`）は本書の出典として残す。本書と各部ファイルが食い違う場合は本書を優先する。
> `docs/SPEC.md`（v1）は本書で置き換えられた（**superseded**）。履歴として残し、v1 の決定 D1〜D10 は本書 §1 に引き継ぐ。
> 実装言語は Java。本書の型定義は読みやすさのため Kotlin 風に書くが、実装は Java の不変クラス・enum・`ExecutorService`/`Future` で行う（§2 C5）。
> 状態表記：**実装済**＝コードにあり CI でビルド・テスト済み／**部分**＝一部のみ／**未実装**。品質・速度の数値は §17 の実測記録にあるものだけを引用し、それ以外は主張しない。

---

## 目次

0. 位置づけ・対象・正本規則
1. 決定ログ
2. 用語・番号・規約の統一（矛盾の解消）
3. 全体アーキテクチャ
4. 共通型カタログ
5. 翻訳エンジン入口・処理フロー・エラー（第1部）
6. 言語解析エンジンと Language IR（第2部）
7. 翻訳モデル基盤（第3部）
8. 辞書・用語・翻訳メモリ（第4部）
9. 候補生成・品質推定・誤訳検出・最終決定（第5部）
10. Provider 管理・プライバシー（第6部）
11. 音声認識 ASR（第7部）
12. 音声合成 TTS（第8部）
13. リアルタイム音声通訳（第9部）
14. 資源の導入管理（第10部）
15. Android 性能・安定性・セキュリティ（第11部）
16. 保存・履歴・設定・キャッシュ（第12部）
17. 統合・テスト・品質評価（第13部）
18. 移行・最終受入基準（第14部）
19. 実装状態の一覧
20. オーナー（ユーザー）にしかできない作業
付録A. 重複の解消記録　付録B. validator 種別の対応表　付録C. 出典対応表

---

# 0. 位置づけ・対象・正本規則

- **目的**：日本語⇔ネパール語（最優先）と日本語⇔ベトナム語を、端末内で高精度に翻訳・通訳する。速度より正確さを優先する（D2）。
- **対象端末**：Pixel 10 Pro XL、Android 16 以降（minSdk/targetSdk/compileSdk 36、arm64 のみ）。
- **対象言語**：ja / ne / vi。翻訳は直接訳のみで、英語を経由しない（D1）。
- **正本規則**：
  1. 本書 → 2. 各部ファイル → 3. `docs/eval/` の実測記録 の順に優先する。
  2. 本書の記述がコードと食い違う場合は、コードの事実を調べて本書を直す（コードに合わせて仕様を曲げるのではなく、事実の記録を正す）。
  3. コードを変えたら、影響する節の状態表記（§19）を同じコミットで更新する。

---

# 1. 決定ログ

## 1.1 v1 から引き継ぐ決定（`docs/SPEC.md` §4）

| ID | 決定 |
|---|---|
| D1 | 英語を経由しない直接翻訳のみ |
| D2 | 速度より正確さを優先 |
| D3 | ne の翻訳は Madlad 3B int4（`MADLAD_CACHE`）、ja⇔vi は HY-MT 1.8B int8 |
| D4 | 言語は ja/ne/vi の3つ。既定は ja⇔ne。初回はトランシーバー（WalkieTalkie）モード |
| D5 | Android 16 以降専用 |
| D6/D7 | ASR は keep-best（2仮説から選ぶ）。スコアは生 logits で計算 |
| D8 | オンラインは任意・既定 OFF |
| D9 | 「市販以上」等の主張は実測なしにしない |
| D10 | MAGI 最適化器のアルゴリズムは流用しない |

## 1.2 v2 で採用した決定（ユーザー「推奨で」2026-11-25、2回）

| ID | 決定 | 出典 |
|---|---|---|
| V1 | 実装言語は Java のまま。Kotlin/coroutine の記述は Java で読み替える | 01 notes A1 |
| V2 | VI（ja⇔vi, HY-MT）を維持。基盤は言語ペアに依存しない | 01 notes A2, 03 notes A2 |
| V3 | オンライン経路は既定 OFF・同意制。実質 STRICT_LOCAL | 01 notes A4 |
| V4 | 学習は PC/クラウドのみ。端末では行わない | 01 notes A5, 03 notes A4 |
| V5 | 言語解析は段階制（Stage1 規則ベース IR → 日本語形態素 → 係り受け・SRL）。IR はまず検証と候補選択に使う | 02 notes A1/A2 |
| V6 | 正規化：原文は NFC で保持。照合キーだけ寛容正規化 | 02 notes C1 |
| V7 | 部番号：第4部＝辞書・用語・翻訳メモリ、第5部＝候補統合・品質評価・誤訳検出 | 04/05 notes A1 |
| V8 | 量子化：INT8 版を作り FP32 基準と比較（机上見積り先行） | 03 notes A3/A8 |
| V9 | モデル manifest 署名は Ed25519（公開鍵を埋め込み、秘密鍵は CI secret） | 03 notes A5 |
| V10 | 全候補が hard のときは結果に「要確認」を付けて表示する | 05 notes A3, 05b §13 |
| V11 | Bluetooth・位置情報の権限は会話モードで使うため維持 | 11 §5.4 |
| V12 | 辞書データは調達・構築・検証・配布まで仕様化（第4部追補A、14章構成） | 04a |
| V13 | ja→ne の用語強度は PREFERRED を既定、MANDATORY はユーザー明示時のみ | 04 notes D, 04b §13 |
| V14 | MBR・逆翻訳 chrF は非独立の根拠。hard 検査を通った候補間の同点崩しにのみ使う | 05 notes A2, 05b §11.4 |
| V15 | 候補の融合（fusion）は既定 OFF。文単位の選択と単一用語の置換のみ許可し、融合後に再検査 | 05b §12 |
| V16 | 新しい発話で古い発話の翻訳を取り消さない（発話を落とさない） | 09 §5 |
| V17 | 会話履歴の保存はオプトイン・暗号化・期限付き・既定 OFF | 12 §5 |
| V18 | 細かい CPU 測定は事前に机上検証する（ユーザーの恒久ルール） | — |

---

# 2. 用語・番号・規約の統一（矛盾の解消）

| # | 矛盾 | 解消 |
|---|---|---|
| C1 | 部の番号：第3部 §0.1 は「4＝候補、5＝辞書」、第3部 §24・第4部・第5部は「4＝辞書、5＝候補」。第3部 §0.1 の「第1部＝Provider管理」 | **4＝辞書・用語・TM、5＝候補統合・品質**（V7）。Provider 管理は第6部。第3部の参照はこの番号で読み替える |
| C2 | 正規化：第1部 7.2・第2部・第3部 7.4・第4部 8.1・第5部 5.2 は NFC 基本、現行コードの照合は NFKC（ja）＋ँ/ं 同一視（ne） | **保持は NFC 原文。照合キーは別に持ち**、ja は NFKC キー、ne は NFC＋ँ→ं のキー（V6）。表示・出力・保存に NFKC を適用しない |
| C3 | TextSpan のオフセット単位（第2部は UTF-16、第4部・第5部はコードポイントも可） | **UTF-16（Java String の index）に固定**。外部解析器のコードポイント位置はアダプターで変換。`OffsetUnit` は型に残すが値は常に UTF16 |
| C4 | LanguageCode：第1部 {JA,NE,AUTO}、第4部・第5部 {JA,NE}、現行は vi も使う | **{JA, NE, VI, AUTO}** に統一。AUTO は要求側だけで使い、解析・候補・辞書の内部では確定した言語のみ |
| C5 | Kotlin + coroutine 前提の記述 vs 実装は Java | Java の `Future` + `TranslationCancellation`（実装済）で読み替え。`suspend fun` は「キャンセル可能な非同期処理」の意味とする |
| C6 | `TranslationCandidate` が第3部 §11.5（モデル出力）と第5部 §3.2（評価対象）で別構造 | 第3部のものを **`ModelCandidate`** に改名。第5部の `TranslationCandidate` が評価・選択の単位 |
| C7 | `ModelResourceManager` が第3部 §14.1（acquire/release）と §17.7（resolveBundle）で別シグネチャ | §14.1 の機能は **`ModelRuntimeManager`**（§16.1 と統合）、§17.7 の機能は **`ModelResourceManager`** に分ける（付録A） |
| C8 | 量子化が第3部 §13 と §15 に重複 | §7.6 に一本化（付録A） |
| C9 | メモリ管理・Lease が第3部 §14 と §16 に重複 | §7.7 に一本化（付録A） |
| C10 | エラー：第1部 `MagiError`、第3部 §10.5 の推論エラー、§18.1 `TranslationEngineErrorCode`、現行 `ErrorCodes` | 内部の正は **`TranslationEngineErrorCode`**（実装済）。`MagiError` は UI 向けの分類として §4.8 の対応表で写像 |
| C11 | 文脈：第1部 `TranslationContext.relationship/preferredRegister`、第2部 `DiscourseContext.relationship`、第4部 `ConversationContext` | **`DiscourseContext`** に一本化し、`TranslationContext` はそれを参照（§4.3） |
| C12 | 第1部「低性能端末でも動作」 vs D5（Pixel 10 Pro XL 専用） | 対象は D5。低性能端末は保証対象外（第3部 §21.3 の端末試験は対象端末＋可能なら1台追加） |
| C13 | 第3部 §23.2「minSdk を根拠なく強制しない」 | 根拠は D5（ユーザー指示）。適合 |

---

# 3. 全体アーキテクチャ

出典：01 §3–§4、03 §2、04 §2、05 §1、06 §1、07 §1、08 §1、09 §2。

```text
Presentation   Translation UI / WalkieTalkie / Conversation / Settings / Licenses(NOTICE)
Application    TranslationUseCase / VoiceTranslationUseCase / ResourceManagementUseCase
Translation    TranslationEngine
 Core            ├─ InputValidator → Normalizer(NFC 保持＋照合キー) → LanguageDetector
                 ├─ LanguageAnalysisEngine（Ja/NeAnalyzer → LanguageIR）     … §6
                 ├─ Dictionary/Terminology/TM → TermConstraintProvider       … §8
                 ├─ CandidateGenerationManager（モデル・デコード・用語・名前変種）… §9
                 ├─ CandidateAnalysis/QualityEstimation（IR 比較＝hard 検査）  … §9
                 ├─ Ranking（hard 最少 → 同点崩し）→ FinalDecision（要確認）  … §9
                 └─ ResultAssembler
Provider       ProviderRegistry / ProviderSelector / PrivacyGuard / ExecutionCoordinator … §10
Model Runtime  ModelRuntimeManager(Lease) / ModelResourceManager(Bundle) / ONNX Runtime 1.30 … §7
Speech         AudioInput / VAD / ASR(Whisper) / TTS(Android TextToSpeech)    … §11–§12
Resources      ModelStore(staging→verify→rename) / ManifestSignature / Downloader / Packs … §14
Infrastructure Room / SharedPreferences / PrivacyLog / Network                 … §15–§16
```

**依存規則**（01 §4.1）：UI はモデルを直接呼ばない／Translation Core は Provider の具体実装に依存しない／Domain（jane、jane.ir）は Android クラスに依存しない（現状：jane は純 Java、CI で単体テスト）／循環依存を作らない（**既知の例外**：`jane` ⇔ `jane.ir` が相互参照。`QualityScorer.numbers` と `KanjiNumber` を `jane.text` 等へ移して解消する＝未実装）。

**標準処理フロー**：入力検証 → 正規化 → 言語判定 → IR 解析 → 用語・固有名詞・TM の取得 → 候補生成 → IR 比較による hard 検査 → 同点崩し → 最終決定（必要なら「要確認」）→ 後処理（`finishJa`/`finishNe`）→ 結果。

---

# 4. 共通型カタログ

各部で未定義だった型（各 notes の E 節）をここで定義する。enum の値は確定、フィールドは最小限。

## 4.1 言語・方向

```kotlin
enum class LanguageCode { JA, NE, VI, AUTO }                 // C4
data class LanguagePair(val source: LanguageCode, val target: LanguageCode)
enum class LanguageDirection { JA_NE, NE_JA, JA_VI, VI_JA }  // 評価・方針は方向単位
```

## 4.2 要求・結果

```kotlin
data class TranslationRequest(
    val requestId: String, val sourceText: String,
    val sourceLanguage: LanguageCode, val targetLanguage: LanguageCode,
    val context: TranslationContext?, val domain: TranslationDomain, val style: TranslationStyle,
    val privacyMode: PrivacyMode, val allowRemote: Boolean,
    val priority: TranslationPriority, val maxLatencyMs: Long?, val createdAt: Long)

enum class TranslationStyle { AUTO, POLITE, CASUAL, FORMAL }        // 既定 AUTO（原文の丁寧さを保つ）
enum class TranslationPriority { REALTIME, INTERACTIVE, BACKGROUND } // 音声通訳＝REALTIME
enum class TranslationDomain { GENERAL, DAILY_CONVERSATION, FAMILY, RELATIONSHIP, BUSINESS, MEDICAL,
    LEGAL, GOVERNMENT, TRAVEL, EDUCATION, TECHNICAL, UNKNOWN }

data class TranslationResult(
    val requestId: String, val translatedText: String,
    val sourceLanguage: LanguageCode, val targetLanguage: LanguageCode,
    val providerId: String, val modelId: String?,
    val qualityAssessment: QualityAssessment?, val warnings: List<TranslationWarning>,
    val executionMetadata: ExecutionMetadata)

data class QualityAssessment(val overall: OverallAssessment, val hardIssueCount: Int,
    val issueTypes: List<TranslationErrorType>, val needsReview: Boolean)   // needsReview＝V10
enum class TranslationWarning { NEEDS_REVIEW, LOW_CONFIDENCE, TERM_CONSTRAINT_UNMET,
    MODEL_FALLBACK_USED, INPUT_TRUNCATED, CONTEXT_IGNORED }
data class ExecutionMetadata(val modelRevision: String?, val elapsedMs: Long, val candidateCount: Int,
    val executionProvider: ExecutionProviderType, val cancelled: Boolean)
```

## 4.3 文脈（C11）

```kotlin
data class TranslationContext(val discourse: DiscourseContext?, val speakerRole: SpeakerRole?,
    val targetAudience: AudienceType?, val preferredRegister: RegisterType?)
data class DiscourseContext(val conversationId: String?, val currentSpeakerId: String?,
    val previousUtterances: List<UtteranceReference>, val knownEntities: List<ContextEntity>,
    val topic: String?, val relationship: RelationshipType?, val confidence: Float)
data class UtteranceReference(val utteranceId: String, val language: LanguageCode,
    val text: String, val speakerId: String?, val timestampMillis: Long)   // 永続化しない（V17）
data class ContextEntity(val entityId: String, val surface: String, val type: EntityType,
    val firstSeenUtteranceId: String)
enum class SpeakerRole { SELF, PARTNER, UNKNOWN }
enum class AudienceType { INDIVIDUAL, GROUP, PUBLIC, UNKNOWN }
enum class RelationshipType { FAMILY, FRIEND, COLLEAGUE, CUSTOMER, OFFICIAL, MEDICAL_STAFF, STRANGER, UNKNOWN }
enum class RegisterType { INFORMAL, CASUAL, POLITE, FORMAL, HONORIFIC, HUMBLE,
    TECHNICAL, LEGAL, MEDICAL, ADMINISTRATIVE, UNKNOWN }
```

## 4.4 プライバシー

```kotlin
enum class PrivacyMode { STRICT_LOCAL, LOCAL_PREFERRED, ONLINE_ALLOWED }   // 既定 STRICT_LOCAL（06 §2.4）
data class TranslationPrivacyPolicy(val mode: PrivacyMode, val remoteTranslationConsent: Boolean,
    val confidential: Boolean)
// 外部実行の可否はこの式のみ（05 §4.4・06 §4.1。変更禁止）
// allowRemote = mode != STRICT_LOCAL && remoteTranslationConsent && !confidential
```

## 4.5 Language IR（第2部・実装は jane/ir Stage1）

```kotlin
enum class PartOfSpeech { NOUN, PROPER_NOUN, PRONOUN, VERB, ADJECTIVE, ADJECTIVAL_NOUN, ADVERB,
    PARTICLE, POSTPOSITION, AUXILIARY, CONJUNCTION, DETERMINER, INTERJECTION, PREFIX, SUFFIX,
    NUMERAL, PUNCTUATION, SYMBOL, FOREIGN, UNKNOWN }
enum class DependencyType { SUBJECT, OBJECT, INDIRECT_OBJECT, MODIFIER, PREDICATE, COMPLEMENT,
    CASE_MARKER, POSTPOSITION, COORDINATION, ROOT, OTHER }
enum class SemanticRole { AGENT, PATIENT, RECIPIENT, INSTRUMENT, LOCATION, TIME, MANNER, CAUSE, CONDITION }
enum class Tense { PAST, PRESENT, FUTURE, NON_PAST, UNKNOWN }          // 実装：jane/ir Tense
enum class Aspect { PERFECTIVE, PROGRESSIVE, CONTINUOUS, EXPERIENTIAL, HABITUAL, NONE, UNKNOWN }
enum class Mood { INDICATIVE, IMPERATIVE, INTERROGATIVE, CONDITIONAL, OPTATIVE, UNKNOWN }
enum class Polarity { AFFIRMATIVE, NEGATIVE, UNKNOWN }
enum class Modality { OBLIGATION, PERMISSION, PROHIBITION, ABILITY, CONJECTURE, VOLITION,
    DESIRE, REQUEST, INVITATION, HEARSAY, NONE }                       // 実装：jane/ir Modality（一部）
enum class SpeechAct { STATEMENT, QUESTION, REQUEST, COMMAND, OFFER, GREETING, APOLOGY, THANKS, UNKNOWN }
enum class EntityType { PERSON, PLACE, ORGANIZATION, PRODUCT, DATE, TIME, MONEY, QUANTITY, OTHER }
enum class InformationSource { EXPLICIT_TEXT, MORPHOLOGICAL_EVIDENCE, SYNTACTIC_INFERENCE, CONTEXT_INFERENCE, UNKNOWN }
enum class AnalysisCertainty { HIGH, INFERABLE, MULTIPLE_CANDIDATES, UNDECIDABLE, FAILED }   // 02 §10

data class LanguageAnalysisRequest(val text: String, val language: LanguageCode,
    val discourse: DiscourseContext?, val analyzerVersion: String)
data class NamedEntity(val entityId: String, val span: TextSpan, val type: EntityType,
    val surface: String, val normalized: String?, val source: InformationSource, val confidence: Float)
data class Ambiguity(val span: TextSpan, val candidates: List<String>, val confidences: List<Float>,
    val reason: String)
data class AnalysisWarning(val code: String, val span: TextSpan?)
data class PredicateAnalysis(val predicateTokenId: String, val lemma: String?, val polarity: Polarity,
    val tense: Tense?, val aspect: Aspect?, val modalities: Set<Modality>)
data class EllipsisCandidate(val text: String, val source: InformationSource, val confidence: Float)
```

Stage1 実装（`jane/ir/LanguageIR`）が持つのは：否定、疑問・疑問語（`WhConcept`）、モダリティ、時制、数（表記どおり＋漢数字変換）、通貨、地名（`Places`）、カタカナ人名候補、異常反復。**原文側は「述べていること」、候補側は「表しうる形」**として非対称に持つ（判定一致のため）。未解析の欄は空＝「未解析」であって「無い」ではない。

## 4.6 モデル基盤（第3部）

```kotlin
enum class ModelFormat { ONNX, TFLITE, CTRANSLATE2, CUSTOM }
enum class ModelPrecision { FP32, FP16, INT8, INT4, MIXED }
data class QuantizationInfo(val method: String, val tool: String, val toolVersion: String,
    val calibrationDatasetId: String?, val sourceModelId: String, val sourceRevision: String,
    val sourceChecksum: String)
enum class ExecutionProviderType { CPU, XNNPACK, NNAPI, QNN }       // 初期は CPU のみ（実装済）
typealias ExecutionProvider = ExecutionProviderType
data class RuntimeRequirements(val minRamMb: Int, val ortVersion: String, val providers: Set<ExecutionProviderType>)
data class RuntimeCompatibility(val ortMinVersion: String, val abis: Set<String>, val pageSize16k: Boolean)
data class ModelQualityProfile(val evaluationReportIds: Map<LanguageDirection, String>)   // 方向別、未評価は欠落
data class ModelInitializationResult(val ok: Boolean, val error: TranslationEngineErrorCode?, val loadMs: Long)
enum class ModelRuntimeStatus { NOT_LOADED, LOADING, READY, IN_USE, EVICTING, FAILED }
enum class ModelLifecycleState { NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, LOADING, READY,
    IN_USE, EVICTING, FAILED, CORRUPTED }
data class SpecialTokenSet(val pad: Int, val eos: Int, val unk: Int, val languageTokens: Map<LanguageCode, Int>)
enum class TokenizationWarning { TRUNCATED, UNKNOWN_TOKENS, INVALID_UNICODE, EMPTY }
data class TermConstraint(val sourceTerm: String, val preferredTargets: List<String>,
    val prohibitedTargets: List<String>, val context: String?, val priority: Int,
    val confidence: Float, val isMandatory: Boolean)                  // 03 §24.3
data class TranslationConstraints(val terms: List<TermConstraint>, val keepSpans: List<TextSpan>)
data class StructuralHint(val kind: String, val span: TextSpan?)    // 現行モデルは入力として使わない（V5）
enum class DecodingStrategy { GREEDY, BEAM, SAMPLING, DIVERSE_BEAM }
data class ModelTranslationOutput(val candidates: List<ModelCandidate>, val error: TranslationEngineErrorCode?)
data class ModelCandidate(val candidateId: String, val text: String, val modelId: String,
    val modelVersion: String, val rawScore: Float?, val normalizedScore: Float?,
    val generationTimeMs: Long, val decodingStrategy: DecodingStrategy)   // C6
data class ModelArtifact(val manifest: ModelArtifactManifest, val rootDir: String)
data class RuntimeInput(val tensors: Map<String, Any>, val runOptionsTag: String)
data class RuntimeOutput(val tensors: Map<String, Any>)
data class RuntimeLoadResult(val ok: Boolean, val error: TranslationEngineErrorCode?)
enum class InferencePriority { REALTIME, INTERACTIVE, BACKGROUND }
data class ResourceStatus(val loadedModels: List<String>, val estimatedUsedMb: Int, val availableMb: Int)
typealias RuntimeStatus = ResourceStatus
data class ModelResourceBundle(val modelId: String, val tokenizerId: String, val dictionaryPackIds: List<String>)
data class CompatibilityResult(val compatible: Boolean, val reasons: List<String>)
```

## 4.7 辞書・用語・TM（第4部）

```kotlin
data class DictionarySource(val sourceId: String, val name: String, val url: String?, val version: String,
    val licenseId: String, val retrievedAt: Long, val redistributable: Boolean)   // 04a license_registry
enum class DictionaryMatchType { EXACT, LEMMA, INFLECTION, ALIAS, PHRASE, FUZZY }
data class TextSpan(val start: Int, val endExclusive: Int, val offsetUnit: OffsetUnit = OffsetUnit.UTF16)
enum class OffsetUnit { UTF16, UNICODE_CODE_POINT }                 // 値は常に UTF16（C3）
enum class TerminologyStrength { MANDATORY, PREFERRED, ALLOWED, REFERENCE_ONLY, FORBIDDEN }
enum class TerminologyScopeType { REQUEST, CONVERSATION, DOCUMENT, USER_GLOSSARY, SESSION }
data class TerminologyScope(val type: TerminologyScopeType, val scopeId: String)
enum class TermRiskLevel { LOW, MEDIUM, HIGH }                        // HIGH＝医療・法律・行政・金額
enum class TranslationMemoryQualityStatus { UNCHECKED, PASSED_HARD_CHECKS, HAS_HARD_ISSUES, REJECTED }
enum class TranslationMemoryProvenance { USER_APPROVED, HUMAN_TRANSLATED, NATIVE_VERIFIED,
    MACHINE_TRANSLATED, MACHINE_POST_EDITED, IMPORTED_CORPUS }
enum class VerificationStatus { UNVERIFIED, MACHINE_GENERATED, MACHINE_REVIEWED, HUMAN_REVIEWED,
    NATIVE_VERIFIED, APPROVED, REJECTED, DEPRECATED }
enum class ProperNounPolicy { KEEP_SOURCE, USE_OFFICIAL_TARGET_NAME, TRANSLITERATE, TRANSLATE, SOURCE_AND_TARGET, CONTEXTUAL }
enum class ResolutionStatus { RESOLVED, MULTIPLE_CANDIDATES, LOW_CONFIDENCE, CONTEXT_MISSING, CONFLICT, NO_MATCH }
enum class ConstraintStrictness { STRICT, PREFERRED_ONLY, REFERENCE }
enum class ConstraintSource { REQUEST, DOCUMENT, CONVERSATION, USER_DICTIONARY, DOMAIN_DICTIONARY,
    PROPER_NOUN_DICTIONARY, CORE_DICTIONARY, TRANSLATION_MEMORY }
typealias ConversationContext = DiscourseContext                    // C11
```

`DictionaryEntry`・`DictionaryTranslation`・`MorphologyInfo`・`DictionaryContextRule`・`DictionaryExample`・`ProperNounEntry`・`DomainTermEntry`・`TerminologyRule`・`TerminologyContext`・`TerminologyConflict`・`TranslationMemoryEntry` は第4部本文（04 §3, §9–§12）の定義をそのまま採用する。

## 4.8 候補・品質（第5部）

```kotlin
enum class CandidateOrigin { LOCAL_MODEL, REMOTE_MODEL, PROVIDER, DICTIONARY_ASSISTED, TRANSLATION_MEMORY,
    FUSED, LEGACY_TRANSLATOR, RULE_BASED_FALLBACK, UNKNOWN }
data class GenerationConfig(val strategy: DecodingStrategy, val beamSize: Int, val variant: CandidateVariant)
enum class CandidateVariant { WHOLE, SENTENCES, TERMS_INJECTED, NAME_VARIANT, BEAM_ALTERNATIVE }   // 現行 JaNeTranslator の候補種
data class SentenceCandidate(val sentenceIndex: Int, val sourceSpan: TextSpan, val text: String)
data class DictionaryEvidence(val matches: List<String>, val constraints: List<TermConstraint>)
data class ResourceBudget(val maxMemoryMb: Int, val maxMillis: Long, val maxCandidates: Int)
enum class ProviderExecutionStatus { SUCCESS, PARTIAL, FAILED, TIMEOUT, CANCELLED, SKIPPED_BY_POLICY }
data class CandidateGenerationError(val code: TranslationEngineErrorCode, val providerId: String, val retryable: Boolean)
data class QualityEvaluationPolicy(val direction: LanguageDirection, val domain: TranslationDomain,
    val highRisk: Boolean, val allowLearnedQe: Boolean)              // allowLearnedQe は当面 false
enum class UncertaintyReason { EVALUATOR_DISAGREEMENT, SOURCE_ANALYSIS_UNCERTAIN, TARGET_ANALYZER_UNSUPPORTED,
    ALIGNMENT_UNKNOWN, CANDIDATES_DIVERGE, QE_MODEL_UNVALIDATED, INSUFFICIENT_EVAL_DATA, ALL_CANDIDATES_HARD }
enum class IndependenceAssessment { INDEPENDENT, PARTIALLY_DEPENDENT, SAME_MODEL_FAMILY, SAME_MODEL_DECODING_VARIANT, UNKNOWN }
```

`DimensionScore`・`EvaluationStatus`・`QualityDimension`・`QualityIssue`・`CandidateQualityReport`・`OverallAssessment`・`TranslationErrorType`・`IssueSeverity`・`UncertaintyReport`・`UncertaintyLevel`・`CandidateRankingVector`・`CandidateAgreement` は第5部本文（05 §3, §7.6, §8, §10–§11）の定義を採用する。現行コードの `Kind` との対応は付録B。

## 4.9 エラー（C10）

内部の正は `TranslationEngineErrorCode`（第3部 §18.1、**実装済** `voice_translation/neural_networks/translation/TranslationEngineErrorCode.java`。`fromThrowable`・`isRetryable`・`toLegacyCode` あり）。UI 向け分類 `MagiError`（第1部 §9）への写像：

| TranslationEngineErrorCode | MagiError |
|---|---|
| MODEL_NOT_INSTALLED | ModelUnavailable |
| MODEL_LOAD_FAILED, TOKENIZER_MISMATCH, RESOURCE_INCOMPATIBLE | ModelLoadFailed |
| MODEL_CHECKSUM_MISMATCH, MODEL_SIGNATURE_INVALID | ResourceIntegrityFailed |
| LANGUAGE_ID_UNSUPPORTED | UnsupportedLanguage |
| INPUT_TOO_LONG, TOKEN_LIMIT_EXCEEDED | InvalidInput |
| OUT_OF_MEMORY | OutOfMemory |
| INFERENCE_TIMEOUT | ProviderTimeout |
| INFERENCE_CANCELLED | （UI に出さない） |
| INVALID_MODEL_OUTPUT, DECODING_FAILED | TranslationFailed |
| MODEL_DOWNLOAD_FAILED, RUNTIME_UNAVAILABLE, EXECUTION_PROVIDER_UNAVAILABLE, INTERNAL_ERROR | Unknown(code) |

ネットワーク系（NetworkUnavailable, ProviderRateLimited）はオンライン Provider 実装時に追加（§10）。

---

# 5. 翻訳エンジン入口・処理フロー・エラー（第1部）

出典：`01_architecture.md`、`01_architecture.notes.md`。

- **入口の一本化**：UI・音声通訳は `Translator`（現行）を通す。将来の `TranslationEngine` は `Translator` を包む `LegacyTranslatorAdapter` から始める（第3部 §20）。**部分**：入口は `Translator` に集約済み、共通 `TranslationRequest/Result` 型は未実装。
- **入力検証・正規化**：`JaNormalizer`（NFKC・全半角）は照合キー用として位置づけ直す（C2）。**部分**。
- **言語判定**：テキストはユーザー指定。音声は ASR の2言語同時デコード（§11）。**実装済（音声）**。
- **キャンセル**：要求単位（`TranslationCancellation` のチケット、ORT `RunOptions.setTerminate`、デコーダ各ステップで確認、取り消した結果は UI に出さない）。**実装済**（CI ビルド成功、実機未確認）。
- **並列**：翻訳は公平な `ReentrantLock` で直列化。同一モデルの二重ロードは起きない。**実装済**（Lease は未実装、§7.7）。
- **ログ**：`PrivacyLog` により release で本文を出さない。**実装済**。
- **受入条件**：01 §11 の12項目。状態は §19。

---

# 6. 言語解析エンジンと Language IR（第2部）

出典：`02_language_analysis.md`、`.notes.md`。

## 6.1 段階計画（V5）

| 段階 | 内容 | 状態 |
|---|---|---|
| Stage1 | 規則ベース IR：否定（ne の否定過去形を含む）、疑問・疑問語、モダリティ（義務・依頼・推量・許可）、時制、数、通貨、地名、人名候補、反復。`JaAnalyzer`/`NeAnalyzer` を分離 | **実装済**（`jane/ir/`、旧 validator と 130,371 組で判定一致、Java⇔Python パリティを CI で検査） |
| Stage2 | 日本語形態素解析（Sudachi / Kuromoji を比較、ライセンス・APK サイズで決定） | 未実装 |
| Stage3 | 係り受け・意味役割・照応・敬語分類 | 未実装（評価データ整備後。ne はツリーバンクが乏しい） |

## 6.2 原則

- 確認できない情報は null／空（＝未解析）。推測で補完しない。主語を足さない。
- IR はまず**検証と候補選択**に使う。モデル入力の加工（主語補完・制約付与）は実測で効果を確認してから。
- 否定の作用範囲・敬語レベル・照応は Stage3 まで「有無」止まり。
- 評価は言語別・項目別（02 §12.4）。ネイティブ評価は §17.4。

---

# 7. 翻訳モデル基盤（第3部）

出典：`03_translation_model.md`、`03b_long_text.md`、`.notes.md`。

## 7.1 現行モデル

| 方向 | モデル | 形式 | 状態 |
|---|---|---|---|
| ja→ne, ne→ja | Madlad-400 3B | ONNX int4、encoder＋KV キャッシュ付き decoder（`MADLAD_CACHE`） | 実装済。**int4 の品質は FP32/INT8 と未比較**（V8 で比較予定） |
| ja⇔vi | HY-MT 1.8B | ONNX int8 | 実装済 |

モデル選択は `Translator.resolveModeForPair` の固定規則（§10.2 で方針表化）。

## 7.2 共通インターフェース
`TranslationModel` / `TranslationModelSession` / `TranslationModelMetadata` / `ModelRuntimeConfig`（03 §3）を採用。デコード設定は要求ごと（`DecodingConfig`）。**未実装**（現行は `Translator` 内の直接呼び出し）。

## 7.3 Manifest と検証
`ModelArtifactManifest`・`ModelFileEntry`（03 §4）を採用。現行は `ModelSources.java`＋`models/models.tsv`（16ファイル・SHA-256・サイズ固定、`models-v1` リリースに 5.57GB をミラー）。tokenizer 互換・ライセンス・評価レポート欄は**未実装**。有効化の条件（manifest 検証→tokenizer 検証→ロード→スモークテスト）は**部分**（スモークテストなし）。

## 7.4 モデル選定
MADLAD-400 を基準とし、候補比較は同一データ・方向別（03 §5）。NLLB-200 は CC-BY-NC のため私用以外の配布は不可、比較するなら PC で distilled-600M をシードセットに当てるのが最小。重み付き採用判定（03 §5.3）は参照訳がネイティブ確認済みになるまで実施しない。

## 7.5 推論・デコード・候補
encoder/decoder 分割、KV キャッシュ、beam N-best（`silentTranslateNBest`）、生 logits スコア。**実装済**。反復の機械的削除は禁止（03 §11.4、現行は削除しない）。生成確率は品質の正解ではない（最終順位は §9）。

## 7.6 量子化（C8：03 §13 と §15 を統合）
- 比較対象：FP32（基準）→ FP16 → INT8 → INT4（INT8 でもメモリが足りない場合のみ）。
- 各方式を別成果物・別 modelId/revision として管理し、元モデルの評価を引き継がない。
- 品質ゲート：意味保持・否定・数値日時・固有名詞・敬語・ne 文法・用語・長文一貫性・空出力/異常反復を量子化前後で比較。BLEU 等の単一指標で決めない。
- キャリブレーション：ja/ne の実入力分布を反映し、ユーザー履歴は使わない（03 §15.2）。
- 最適化の順序：ロード時間→Tokenizer→Encoder→Decoder/token→全体→メモリ→競合→EP 比較→量子化→品質回帰（03 §15.3）。
- EP：CPU を基準経路として常に確保。XNNPACK/NNAPI/QNN は実測で採否。端末名だけで決めない。
- **状態**：比較は未実施。現行 Madlad int4 は 03 §5.4「量子化後の品質を未確認のまま配布しない」に抵触しているため、V8 の比較を最優先の測定項目とする。

## 7.7 メモリ・Lease・ライフサイクル（C7・C9：03 §14 と §16 を統合）

```kotlin
interface ModelRuntimeManager {                 // 03 §14.1 と §16.1 を統合
    fun acquire(modelId: String, priority: InferencePriority): ModelLease
    fun releaseUnusedModels()
    fun getRuntimeStatus(): RuntimeStatus
    fun shutdown()
}
interface ModelLease : AutoCloseable { val modelId: String; val session: TranslationModelSession
    val isValid: Boolean; override fun close() /* 冪等 */ }
interface ModelResourceManager {                // 03 §17.7（資源の束と互換性）
    fun resolveBundle(modelId: String, direction: LanguageDirection): ModelResourceBundle
    fun validateCompatibility(bundle: ModelResourceBundle): CompatibilityResult
}
```

- Lease を取ってから推論し、`try/finally` で返す。Lease 中は解放しない。状態遷移は `ModelLifecycleState` で一元管理。
- ロード前に必要メモリを推定（重み・Session・中間テンソル・KV・Tokenizer・バッファ・候補数）。不足時は 不要モデル解放 → 同時実行削減 → 候補数削減 → 長文の小分割 → 許可済みの別 Provider。OOM 後の継続は仮定しない。
- `onTrimMemory` で未使用モデルを解放。バックグラウンドで大モデルを保持し続けない。
- **状態**：翻訳の直列化と OOM の捕捉・表示（`ERROR_OUT_OF_MEMORY`）は**実装済**。Lease・メモリ推定・`onTrimMemory`・ロード前の空き確認は**未実装**（11 §2）。

## 7.8 長文翻訳（03b）
- 現行：入力トークン上限 200/5000（`Translator` に todo 付き）、文分割→結合（`JaSegmenter`）、200 トークン超では N-best なし。**部分**。
- 分割の順序：文 → 節 → 強制分割。条件・否定・引用の途中での分割には危険フラグ。URL・電話番号等は翻訳しない区間として保護。
- 文脈の引き継ぎは人名と採用済み訳語のみ。前チャンクの訳文を次の入力に入れない。上限はモデル manifest から取る。

## 7.9 学習（V4）
Fine-tuning / BT / PEFT / 蒸留は PC で行い、学習前後を方向別に比較してから manifest で配布。テストデータの学習利用・ライセンス不明データ・同意のない個人データは禁止（03 §9）。**未着手**（ネイティブ確認済み対訳が前提）。

## 7.10 配布・更新・ロールバック → §14 に統合。

---

# 8. 辞書・用語・翻訳メモリ（第4部）

出典：`04_dictionary_terminology_tm.md`、`04a_dictionary_data_sources.md`、`04b_tm_and_rest.md`、`.notes.md`。章構成は 04a §7 の14章を採用（V12）。

## 8.1 原則
- 単純な文字列置換を翻訳の基本にしない。辞書一致は正しさの証明ではない。
- 強度（`TerminologyStrength`）と優先順位を分ける。MANDATORY・FORBIDDEN はランキングと別の制約。ja→ne は PREFERRED が既定、MANDATORY はユーザー明示時のみ（V13：用語注入が ja→ne で悪化した実測に基づく）。
- MANDATORY を満たせない候補は成功扱いにせず、未達として返す（現行：TERM_MISSING の hard で候補を落とす。未達理由の返却は**未実装**）。
- 未検証の訳語は REFERENCE_ONLY／ALLOWED 止まりで、hard 検査の根拠にしない。
- STRICT_LOCAL では外部辞書・外部 TM・外部検索を使わない。

## 8.2 データの調達と構築（04a）
- ja⇔ne の直接対訳で、ライセンスが明確かつ大規模なものは見つかっていない。主な入力：Wiktionary（Kaikki、CC BY-SA/GFDL）、PanLex（CC0 と CC BY-NC-SA の記載が矛盾＝要確認）、Wikidata（固有名詞）、CLDR（数字・通貨・日時）、GeoNames、JMdict（ja 側）、IndoWordNet（要確認）、Hunspell ne、在日ネパール大使館サイト、在ネパール日本国大使館サイト（ja/ne 併存ページ）、独自構築辞書（最優先）。
- 英語を介した導出は**辞書構築時の候補生成**であり D1（実行時に英語を経由しない）に反しない。導出物は MACHINE_GENERATED／UNVERIFIED。
- パイプライン（PC 側）：SourceImporter → LicenseValidator（`license_registry.tsv`）→ Raw 保存 → 正規化（NFC 保持＋照合キー）→ 言語ペア対応付け → 重複解決（同表記・別語義は統合しない）→ 意味検証 → ネイティブレビュー → 品質評価 → パッケージ（SQLite＋NOTICE＋pack.json）→ manifest・署名・チェックサム（§14 と同じ仕組み）。
- 大使館サイト等の公的資料は本文を転載・同梱せず、用語ペアの抽出と出典参照に限る（利用規約の確認後）。

## 8.3 検索・候補解決
検索パイプライン（Exact→Lemma→Inflection→Alias→Phrase→Fuzzy）、候補解決（品詞・意味・文脈・ドメイン・敬意・形態のフィルタ、説明可能な特徴量スコア、`ResolutionStatus`）は 04 §5–§6 を採用。Fuzzy は完全一致と同等の信頼度にしない。

## 8.4 固有名詞
`ProperNounPolicy` を採用。現行 `ProperNameResolver` の TRANSLITERATE / TRANSLITERATE_BARE / KEEP / NOUN が KEEP_SOURCE・TRANSLITERATE・TRANSLATE に相当（**部分**）。ja→ne ではカタカナ名に余分な -u を付けない表記を優先（`NAME_EXTRA_VOWEL`、**実装済**）。同定できない場合は原文保持。

## 8.5 翻訳メモリ（04 §12、04b）
- 現行 `TranslationMemory`（会話単位 LRU200）は**文ではなく用語・名前の訳し方**を覚えている。文単位の TM は**未実装**。
- 否定・数値・時制・疑問語・通貨・地名が違う過去訳は、類似度にかかわらずそのまま再利用しない。TM 候補もモデル候補と同じ hard 検査を通す。否定・数値に触れる差分の fuzzy 修復は禁止。
- 登録は品質評価済み・許可済みのものだけ。provenance を記録。ユーザー修正を自動で辞書の正解にしない。
- 永続化は第12部（§16）の方針に従う（既定 OFF）。

## 8.6 用語制約の生成
`TermConstraintProvider`（03 §24）を第4部が実装する。強度→`TermConstraint` の対応（04b §13）：MANDATORY→isMandatory=true／PREFERRED→preferredTargets／FORBIDDEN→prohibitedTargets／REFERENCE_ONLY・UNVERIFIED→制約にしない。適用方法はモデルの能力順（制約付きデコード→語彙制約→入力前処理→候補後評価→後処理）。現行は入力前処理（`Glossary` 注入・`injectReverse`）と候補後評価（TERM_MISSING）のみ（**部分**）。

---

# 9. 候補生成・品質推定・誤訳検出・最終決定（第5部）

出典：`05_candidate_quality.md`、`05b_fusion_decision.md`、`.notes.md`。

## 9.1 候補生成
- 現行 `JaNeTranslator`：whole / sentences / terms 注入 / name 変種（TRANSLITERATE_BARE 含む）/ beam N-best。単一モデル・複数デコード。**実装済**。
- 段階制御：短文会話は低遅延の少数候補、曖昧・低信頼時に追加（05 §4.5）。**部分**。
- 候補数の上限は要求単位。無制限の再翻訳は禁止。

## 9.2 正規化・重複排除
NFC 比較用文字列を別に作る。ASCII／デーヴァナーガリー数字を数値として比較（**実装済** `QualityScorer.numbers`）。否定・数値・固有名詞が異なる候補は文字列が似ていても統合しない（現行は文字列一致の重複除去のみ＝**部分**）。

## 9.3 重大誤訳の検出（hard 検査）
- 現行 `TranslationValidator`（IR 比較）：否定、数値、疑問（文・疑問語）、義務・依頼、過去、通貨、地名（欠落・追加）、用語、未翻訳文字、異常反復、名前の余分な母音。**実装済**。対応表は付録B。
- 流暢さで重大誤訳を相殺しない。確信度の段階（REJECT / NEEDS_REVIEW / WARNING / INSUFFICIENT_EVIDENCE）は、現行 validator に確信度が無いため**未実装**（hard/soft の2値）。
- 「未評価」と「問題なし」を区別する（`EvaluationStatus`）。現行は検出できない＝問題なし扱い（**未実装**）。

## 9.4 品質推定
- Rule-Based（A）＝validator（実装済）。Reference-Free（D）の簡易版＝逆翻訳 chrF＋MBR（実装済、生 logits）。
- MBR と逆翻訳 chrF は同一モデルの beam 間合意で**独立した根拠ではない**（V14）。hard 検査を通った候補間の同点崩しにのみ使う。
- Learned QE（COMETKiwi 等）は ne への適合が未検証のため PC 評価に限定。端末では使わない。
- Reference-Based（C）は `tools/eval`（chrF++・validator 指標・Java/Python パリティ）で PC のみ。

## 9.5 ランキング
辞書式：hard 問題の数が最少 → 同点崩し（MBR・逆翻訳 chrF）。ne→ja の beam 代替は全主候補が hard のときだけ使う。**実装済**。Pareto・Risk-aware・方向別方針は未実装。固定閾値を根拠なく使わない。

## 9.6 合意・融合・最終決定
- 複数モデルの合意は正解の証明ではない。同一モデルの別デコードは独立投票ではない（`IndependenceAssessment.SAME_MODEL_DECODING_VARIANT`）。
- 融合は既定 OFF。許すのは文単位の選択と単一用語の置換のみで、融合後に全 hard 検査をやり直す（V15）。
- **全候補が hard のときは結果に NEEDS_REVIEW を付けて表示**（V10）。**未実装**（UI 変更を含む）。
- 監査記録は種別とラベルだけを残し、原文・訳文は残さない。

---

# 10. Provider 管理・プライバシー（第6部）

出典：`06_provider_management.md`。

## 10.1 現状
ローカルのみ。オンライン Provider は**未実装**。外部送信経路は存在しない＝常に STRICT_LOCAL 相当。

## 10.2 方向別の既定方針（現行規則の仕様化）

| 方向 | 第一候補 | 未導入時 |
|---|---|---|
| ja→ne / ne→ja | Madlad（`MADLAD_CACHE`） | `ERROR_MISSING_MADLAD`＋ダウンロード案内ダイアログ（`showMissingMadladDialog`、**実装済**）。他モデルへ黙って切り替えない |
| ja⇔vi | HY-MT | 既存の案内に従う |

## 10.3 PrivacyGuard
外部送信の可否を判断するのは PrivacyGuard だけで、式は §4.4 の1つだけ（変更禁止）。送ってはならないもの：STRICT_LOCAL の原文、機密指定の原文、送信未許可の履歴、ローカル専用辞書、認証情報。ローカル品質が低いことを理由に黙ってオンラインへ再翻訳しない。**未実装**（オンライン経路が無いため不要だったもの）。

## 10.4 fallback・再試行
順序：同一モデル別 EP → 同一モデル CPU → 軽量ローカル → 既存互換経路 → 許可がある場合のみオンライン。再試行は `isRetryable` のエラーだけ。OOM・キャンセル・署名不正・checksum 不一致の後は再試行しない。**部分**（`isRetryable` は実装済、連鎖は未実装）。

## 10.5 オンライン Provider を追加する前提条件（6項目）
API キーを Android Keystore に置きソースに書かない／同意 UI と `ConsentRecord`／プライバシーポリシーの運営者情報（§20）／Rate limit・指数バックオフ＋jitter／送信内容の最小化／STRICT_LOCAL の回帰テスト。

---

# 11. 音声認識 ASR（第7部）

出典：`07_asr.md`、`tools/whisper_ne/RESULTS.md`。

## 11.1 現行
16kHz 録音、Silero VAD と手動モード、2言語同時デコードによる言語判定、Whisper-small の6分割 ONNX、無音ゲート（no_speech > 0.6 かつ平均 logprob < −1）、ne の keep-best（制約あり／なしの2仮説から hard → スコアで選択）、調整済み ne モデル（`files/Whisper_ne/`、任意）。**実装済**。

## 11.2 実測（FLEURS ne 15発話・277語、CPU greedy、fp32、参考値）

| 構成 | WER | CER |
|---|---:|---:|
| openai/whisper-small（基本） | 139.0% | 65.2% |
| ilprl/Whisper-Small-Nepali-ASR-OSLR54（調整済み） | 50.9% | 34.9% |
| 調整済み＋文字マスク＋ne プロンプト（旧アプリ設定） | 76.5% | 54.7% |

これを受け、調整済みモデル使用時はマスクとプロンプトを外した（**実装済**、CI ビルド成功、実機未確認）。

## 11.3 既知の課題（コード読解、未測定）
1. デーヴァナーガリーのバイト単位 BPE 片をマスクが止める欠陥は、基本モデルでも文字化け（U+FFFD）を起こしうる。
2. 調整済みモデル使用時、2仮説が同じ設定で読まれ片方が無駄。
3. U+FFFD を含む結果が hard 判定にならない。
4. OpenSLR54（学習データ）とテスト音声の重複未確認、雑音・会話音声で未評価、INT8 の精度未測定、ライセンス未確認（§20）。

## 11.4 翻訳への受け渡し
ASR 結果は確定文として翻訳へ渡すが、ASR 由来の誤りを翻訳側で「修正」しない。信頼度が低い場合は表示で知らせる（未実装）。

---

# 12. 音声合成 TTS（第8部）

出典：`08_tts.md`。

- 現行：Android 標準 `TextToSpeech`。言語ごとの音声有無の判定と、無い場合の案内は**未実装**。ne 音声が端末にあるかは実機未確認。
- 音声が無い場合は訳文の表示で会話を続け、端末の音声データ設定へ案内する。**別言語の音声で ne を読ませることは禁止**。
- STRICT_LOCAL ではネットワークが必要な音声を使わない。
- 数字・金額は実機で読み方を確認し、読めない場合だけ読み上げ用の文字列を作る（表示する訳文は変えない）。
- 自分の読み上げをマイクが拾って再認識する自己ループを防ぐ（§13）。

---

# 13. リアルタイム音声通訳（第9部）

出典：`09_realtime_interpretation.md`。

- モード：WalkieTalkie（1台・対面、初回既定）、Conversation（2台・Bluetooth）。**実装済**（既存）。
- 発話を落とさない（V16）。新しい発話で古い翻訳を取り消さない。滞留の上限と表示は**未実装**。
- 翻訳は直列化（`ReentrantLock`）。モード単位の取り消しは画面終了・サービス破棄時のみ（**実装済**）。
- TTS 再生中の録音衝突を防ぐ（**部分**：既存の調停あり、詳細は 09 §5.4）。
- 遅延の内訳（録音終了→ASR→翻訳→TTS 開始）を計測項目とする。数値は未測定。
- 触覚（`VibrationEffect.BasicEnvelopeBuilder`）・通知（`Notification.ProgressStyle`）は Android 16 機能として**実装済**。

---

# 14. 資源の導入管理（第10部、第3部 §17 を含む）

出典：`10_resource_install.md`、`03_translation_model.md` §17、`docs/MODEL_SIGNING.md`。

## 14.1 パックと初回起動
- `models.tsv` の16エントリ（約 5.57GB、`models-v1`）を機能単位のパックに分ける。初回既定＝音声認識＋ne 翻訳＋基本辞書。
- 初回：パックと必要容量を表示 → ダウンロード → 検証 → インストール → 短いスモークテスト。容量見積りは当面「ダウンロードサイズ×2」の暫定上限（展開後サイズは未測定）。
- NLLB・TranslationDictionaries・Tatoeba が ja/ne/vi の経路で使われていないなら初回から外す（要確認）。

## 14.2 ダウンロード・検証・原子的置換
- 手順：manifest 取得 → 署名検証 → 容量確認 → `.staging/` へダウンロード → サイズ・SHA-256 検証 → rename で正式名へ。途中のファイルは正式名に現れない。**実装済**（`ModelStore`、`Downloader`）。
- zip の展開を staging 内で行う処理、revision ディレクトリ（`revisions/`・`active.json`）は**未実装**。`ZipSafety`（Zip Slip 対策）は実装済。

## 14.3 署名（V9）
Ed25519 で manifest を署名、公開鍵はアプリに埋め込み、秘密鍵は CI secret `MAGI_MANIFEST_SIGNING_KEY` のみ。現在は鍵が空のプレースホルダで **UNSIGNED＝従来どおりハッシュのみ**。INVALID のときだけダウンロードを止める。`tools/ManifestSigner.java`（genkey/sign）あり。実機で Ed25519 が使えるかの確認が有効化の前提。**部分**。

## 14.4 ロールバック
空きが 2GB を超えるときだけ旧版を `.previous/` に残し、失敗した版は `.failed.tsv` に記録して自動再有効化しない。**部分**（UI・自動トリガは未実装）。

## 14.5 辞書パック・ライセンス表示
辞書は SQLite＋`NOTICE.txt`＋`pack.json`、`license_registry.tsv` の同梱可否で通過させる。ライセンス・出典表示画面を設ける（**未実装**）。調整済み ne Whisper パックはライセンス確認後に任意パックとして配布。

---

# 15. Android 性能・安定性・セキュリティ（第11部）

出典：`11_android_perf_security.md`。

- 前提：Pixel 10 Pro XL・Android 16・arm64・16KB ページ（`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`、CI で ELF LOAD 整列を報告）。**実装済**。
- `largeHeap` 有効。メモリ逼迫時の解放（`onTrimMemory`）、発熱監視、ロード前の空き確認は**未実装**（§7.7）。
- スレッド：推論スレッド数で UI・音声を妨げない。数値は未測定。
- 予測型「戻る」（`OnBackPressedCallback`）は**実装済**。
- セキュリティ：release ログに本文を出さない（実装済）、ダウンロード資源の検証（§14）、署名鍵は**公開中のサイドロード鍵**（差し替えると一度アンインストールが必要、§20）、権限は V11 のとおり維持。
- プライバシーポリシー：同意チェックのリンクが上流 RTranslator v2.00 を指したまま、日本語版の運営者名・連絡先が空欄（§20）。

---

# 16. 保存・履歴・設定・キャッシュ（第12部）

出典：`12_storage.md`。

- 現状：Room は `RecentPeerEntity`（version 1）のみ。設定は `default` SharedPreferences。会話は `VoiceTranslationService` のメモリ上リストだけで**保存されない**。
- 会話履歴の保存はオプトイン・暗号化・期限付き・既定 OFF（V17）。**未実装**。
- 追加予定テーブル：ユーザー辞書、用語規則、TM（第4部）、評価キャッシュ（第5部）。いずれも原文を含む場合は履歴と同じ扱い。
- キャッシュキーには正規化入力・言語・解析器版・辞書版・設定を含める（02 §11.2）。
- 削除・初期化：ユーザー操作で全履歴・辞書・キャッシュを消せること。

---

# 17. 統合・テスト・品質評価（第13部）

出典：`13_integration_testing.md`、`docs/eval/`。

## 17.1 テストの層
| 層 | 内容 | 状態 |
|---|---|---|
| 純 Java 単体 | `JaNeLibraryTest`、`ZipSafetyTest`、`ModelSourcesTest`、`ModelStoreTest`、validator パリティ（Java⇔Python） | **実装済**（CI で毎回） |
| PC 評価 | `tools/eval`（chrF++、validator 指標、`onnx_runner.py` で Madlad を PC 再現、`JaneEval.java`） | 実装済 |
| ASR 評価 | `tools/whisper_ne/eval_wer_cer.py` | 実装済（15発話） |
| Android ビルド | GitHub Actions `assembleRelease` | 実装済 |
| 実機試験 | ロード・メモリ・遅延・キャンセル・TTS | **未実施** |

## 17.2 評価データの限界
シードセット40行は**参照訳が AI 作成・未検証**。退行の確認にだけ使い、絶対的な品質や「市販以上」の根拠にしない。

## 17.3 これまでの実測（シードセット、PC、参照未検証）

| 方向 | 指標 | raw Madlad | v1 | v2 | v3 |
|---|---|---:|---:|---:|---:|
| ne→ja | chrF++ | 40.23 | 44.32 | 40.17 | 44.59 |
| ne→ja | 数字保持 | 66.7% | 66.7% | 100% | 100% |
| ne→ja | 用語 | 42.9% | 57.1% | 71.4% | 57.1% |
| ja→ne | chrF++ | 63.59 | 60.46 | 60.78 | 60.78 |
| ja→ne | 用語 | 90% | 90% | 80% | 80% |

v4（IR hard 検査）は**机上検査のみ**：参照訳 0/20 で hard、v3 出力で jn14・jn19・nj04・nj12・nj17 のみ検出。**モデルを使った再測定は未実施**（最優先）。

## 17.4 ネイティブ評価プロトコル
評価者は ne 母語話者かつ日本語の十分な理解者を2人以上。どの方式の訳か伏せて採点（ブラインド）。項目は 03 §19.4 の10項目。意見が割れた場合は平均せず記録し、基準の曖昧さを確認。

## 17.5 測定の規則
CPU 負荷の大きい測定は事前に机上検証する（V18）。

---

# 18. 移行・最終受入基準（第14部）

出典：`14_migration_acceptance.md`、`01` §10、`03` §20。

## 18.1 移行の原則
一括置換しない。`LegacyTranslatorAdapter` で既存経路を新インターフェースに載せ、新旧を同一データで比較し、受入条件を満たした機能から切り替える。旧経路へ戻せる状態を保つ。

## 18.2 次に行う作業（推奨順）
1. v4 のモデル再測定（シードセット、方向別）。jane が raw Madlad を上回らない方向では jane を切る判断を記録。
2. Madlad int4 vs INT8 vs FP32 の品質比較（V8、机上見積り先行）。
3. 「要確認」表示（V10）と `EvaluationStatus`（未評価≠問題なし）。
4. ASR 課題（§11.3）の修正と再測定。
5. 独自構築辞書の雛形と `license_registry.tsv`（§8.2）。現行の `Glossary.withDefaults()` と `jane/ir/Places` をデータへ移す。
6. `ModelRuntimeManager`/Lease、`onTrimMemory`、ロード前の空き確認（§7.7）。
7. 共通型（§4）の Java 実装と `TranslationEngine` 入口。
8. `jane`⇔`jane.ir` の循環依存の解消。

## 18.3 最終受入基準（リリース判定）
- **機能**：ja⇔ne・ja⇔vi のテキスト・音声通訳がオフラインで動く。キャンセルが効く。資源を検証してから使う。
- **品質**：方向別の再測定とネイティブ評価が完了し、否定・数値・固有名詞の回帰がない。jane の有効/無効を方向別に実測で決めている。
- **安定性・性能**：実機でロード成功、メモリ・p50/p95 遅延を記録、長時間連続でクラッシュしない。
- **安全・プライバシー**：STRICT_LOCAL で外部送信なし、release ログに本文なし、署名鍵差し替え済み、プライバシーポリシー記入済み。
- **文書**：本書の状態表記（§19）がコードと一致。

---

# 19. 実装状態の一覧

| 部 | 主な項目 | 状態 | 根拠（コード） |
|---|---|---|---|
| 1 | 入口集約・直列化・キャンセル・エラー分類・PrivacyLog | 部分（共通型・TranslationEngine は未実装） | `Translator`、`TranslationCancellation`、`TranslationEngineErrorCode`、`PrivacyLog` |
| 2 | Stage1 IR（ja/ne 分離） | 実装済（Stage2/3 未実装） | `jane/ir/*` |
| 3 | Madlad/HY-MT 推論・KV・beam N-best | 実装済 | `Translator` |
| 3 | manifest（ハッシュ固定）・原子的置換・署名の枠 | 部分 | `ModelSources`、`ModelStore`、`ManifestSignature` |
| 3 | 量子化比較・Lease・メモリ推定・長文の高度化 | 未実装 | — |
| 4 | 用語注入・逆注入・固有名詞方針・会話 TM（用語） | 部分 | `Glossary`、`ProperNameResolver`、`TranslationMemory` |
| 4 | 辞書データ・SQLite・文単位 TM・強度別制約 | 未実装 | — |
| 5 | 候補生成・IR hard 検査・辞書式選択・同点崩し | 実装済 | `JaNeTranslator`、`TranslationValidator`、`QualityScorer` |
| 5 | 要確認表示・EvaluationStatus・確信度段階・融合 | 未実装 | — |
| 6 | ローカル方針・Madlad 未導入案内 | 実装済 | `resolveModeForPair`、`showMissingMadladDialog` |
| 6 | オンライン Provider・PrivacyGuard・同意 UI | 未実装 | — |
| 7 | Whisper・VAD・無音ゲート・ne keep-best・調整済み ne モデル | 実装済（課題4件） | `Recognizer` |
| 8 | 標準 TTS | 部分（音声有無の判定・案内なし） | — |
| 9 | WalkieTalkie/Conversation・発話を落とさない・触覚/通知 | 実装済（滞留表示は未実装） | `WalkieTalkieService` ほか |
| 10 | staging/検証/rename・ZipSafety・missing-model ダイアログ | 実装済 | `Downloader`、`ZipSafety` |
| 10 | パック選択 UI・容量見積り・ライセンス画面・zip の staging 展開 | 未実装 | — |
| 11 | 16KB ページ・予測型戻る・release ログ保護 | 実装済 | `app/build.gradle`、`GeneralActivity` |
| 11 | onTrimMemory・発熱監視・鍵差し替え | 未実装 | — |
| 12 | 設定・RecentPeer | 実装済 | Room v1、SharedPreferences |
| 12 | 履歴保存（オプトイン）・辞書/TM テーブル | 未実装 | — |
| 13 | 純 Java テスト・パリティ・PC 評価・ASR ミニ評価 | 実装済 | `.github/workflows/release-build.yml`、`tools/eval`、`tools/whisper_ne` |
| 13 | 実機試験・ネイティブ評価・v4 再測定 | 未実施 | — |

---

# 20. オーナー（ユーザー）にしかできない作業

1. プライバシーポリシーの運営者名・連絡先を記入し、同意チェックのリンクを自分のポリシーへ切り替える。
2. 署名鍵の差し替え（keystore と CI secret の登録）。差し替え後は一度アンインストールが必要。
3. manifest 署名鍵（Ed25519）の生成・保管・CI secret `MAGI_MANIFEST_SIGNING_KEY` への登録（`docs/MODEL_SIGNING.md`）。
4. 実機で `models-v1` からの初回ダウンロード、Ed25519 の可否、ne の TTS 音声の有無、キャンセル動作を確認。
5. ライセンス・利用規約の確認：調整済み ne Whisper（ilprl/Whisper-Small-Nepali-ASR-OSLR54）、PanLex（CC0 と CC BY-NC-SA の矛盾）、IndoWordNet、在日ネパール大使館サイト、在ネパール日本国大使館サイト、`license_registry.tsv` の「要確認」欄。
6. ネイティブ評価者の手配（ne 母語話者2人以上）と、シードセット参照訳の確認。

---

# 付録A. 重複の解消記録

| 重複 | 出典 | 統合先 | 扱い |
|---|---|---|---|
| 量子化 | 03 §13、§15.1–§15.3 | 本書 §7.6 | §15 の詳細（適用順序・品質ゲート・成果物識別・キャリブレーション・EP 選択）を正とし、§13 はその要約として吸収 |
| メモリ・Lease | 03 §14、§16 | 本書 §7.7 | §16 の `ModelRuntimeManager`（priority 付き acquire、AutoCloseable Lease、状態 enum）を正とする |
| ModelResourceManager | 03 §14.1、§17.7 | 本書 §7.7 | §14.1 の acquire/release は `ModelRuntimeManager` へ、§17.7 の bundle/互換性は `ModelResourceManager` へ分離 |
| 配布・更新・ロールバック | 03 §17、10 全体 | 本書 §14 | 10 の具体化（パック・初回起動・現状）を正とし、03 §17 は要件として参照 |
| TranslationCandidate | 03 §11.5、05 §3.2 | 本書 §4.6/§4.8 | 03 版を `ModelCandidate` に改名 |
| 文脈型 | 01 §5.4、02 §7.1、04 §6.2 | 本書 §4.3 | `DiscourseContext` に一本化 |
| エラー型 | 01 §9、03 §10.5、§18.1 | 本書 §4.9 | `TranslationEngineErrorCode` を正、`MagiError` は写像 |
| 前書きと本文の重複 | 03 前書き §0.1–§1.2 | 03 本文 | 本文に統合済み（03 ファイル冒頭注記） |

# 付録B. validator 種別の対応表（05b §15 を採用）

| 現行 `Kind` | hard/soft | `TranslationErrorType` | `IssueSeverity` |
|---|---|---|---|
| EMPTY | hard | OTHER（空出力） | CRITICAL |
| NUMBER_LOST | hard | NUMBER_MISMATCH | CRITICAL |
| NUMBER_ADDED | soft | NUMBER_MISMATCH | MODERATE |
| NEGATION_LOST | hard | NEGATION_REVERSAL | CRITICAL |
| NEGATION_ADDED | soft | NEGATION_REVERSAL | MODERATE |
| TERM_MISSING | hard | MANDATORY_TERM_MISSING | MAJOR |
| UNTRANSLATED_SOURCE_SCRIPT | hard | IMPORTANT_OMISSION | MAJOR |
| QUESTION_LOST | hard | MODALITY_ERROR | MAJOR |
| REPETITION | hard | GRAMMAR_ERROR | MAJOR |
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
| NAME_EXTRA_VOWEL | hard | NAMED_ENTITY_ERROR | MODERATE（重大度付き選択へ移る際に hard から外す検討） |

当面、選択の順序（hard を先に数える）は変えない。重大度は `Kind` に付ける既定値の案。

# 付録C. 出典対応表

| 本書 | 出典ファイル |
|---|---|
| §1 | `docs/SPEC.md` §4、`00_README.md`（採用済みの判断） |
| §2 | 各 `.notes.md` の A・C 節 |
| §3 | `01_architecture.md` §3–§4、03 §2、04 §2、05 §1、06 §1 |
| §4 | 01 §5、02 §3、03 §3–§4・§11・§16–§18・§24、04 §3・§9–§12、05 §3・§7–§11、各 notes E 節 |
| §5 | `01_architecture.md`、`.notes.md` |
| §6 | `02_language_analysis.md`、`.notes.md` |
| §7 | `03_translation_model.md`、`03b_long_text.md`、`.notes.md` |
| §8 | `04_dictionary_terminology_tm.md`、`04a_dictionary_data_sources.md`、`04b_tm_and_rest.md`、`.notes.md` |
| §9 | `05_candidate_quality.md`、`05b_fusion_decision.md`、`.notes.md` |
| §10 | `06_provider_management.md` |
| §11 | `07_asr.md`、`tools/whisper_ne/RESULTS.md` |
| §12 | `08_tts.md` |
| §13 | `09_realtime_interpretation.md` |
| §14 | `10_resource_install.md`、03 §17、`docs/MODEL_SIGNING.md` |
| §15 | `11_android_perf_security.md` |
| §16 | `12_storage.md` |
| §17 | `13_integration_testing.md`、`docs/eval/` |
| §18 | `14_migration_acceptance.md`、01 §10、03 §20 |


> 追記（2026-11-25）：ユーザー辞書の取り込み（`UserDictionary` / `PhraseBook`、設定から端末内に読み込み、用語は PREFERRED、フレーズは検査付き候補）を **実装済**。詳細は `04a_dictionary_data_sources.md` §9。
