# MagiTranslator 総合詳細機能実装仕様書
## 第4部：辞書・用語・翻訳メモリ統合エンジン 詳細実装仕様書

> 本文はユーザー提示の原稿（**§12.6 の途中で途切れている。続き未着**）。見出し・型定義・表は原文どおり、箇条書きの一部は一文に要約。現状との差分は `04_dictionary_terminology_tm.notes.md`。

**文書バージョン：** 2.0　**対象：** 日本語 ⇔ ネパール語 / Android
**関連仕様：** 第1部 Provider管理、第2部 高度言語解析エンジン、第3部 翻訳モデル・機械翻訳基盤、第5部 翻訳候補生成・品質推定・候補統合エンジン
**設計方針：** ローカル優先のハイブリッド構成。主要辞書・用語集・翻訳メモリは端末内で利用可能にする。

---

# 0. 文書の目的と適用範囲

## 0.1 目的
日本語・ネパール語専用の辞書、用語管理、翻訳メモリ、文脈依存訳語選択、形態情報連携を統合した言語資源エンジンを定義する。単語を検索して置き換えるだけの辞書ではない。同じ語でも文脈、品詞、意味役割、敬意、話者と聞き手の関係、時制、相、否定、格標識、後置詞、語形変化で適切な訳語が変わり、専門用語も会話・文書・分野・読者で変わる。

統合して提供する情報：一般対訳辞書、基本語彙辞書、文脈依存辞書、品詞・基本形・形態情報、意味カテゴリ・意味役割、専門用語辞書、固有名詞辞書、表記ゆれ・別名・同義語辞書、敬意・丁寧さ・文体情報、活用・語形変化情報、会話・文書内の用語状態、翻訳メモリ、ユーザー個人辞書、ユーザー指定の必須訳語・禁止訳語、用語の出典・検証状態・信頼性、翻訳後の用語適合性検査。

第2部の `LanguageAnalysisResult` を参照し、第3部の `TermConstraintProvider` と接続する。第5部には辞書一致情報、用語適合情報、翻訳メモリ候補、採用・不採用の理由を提供する。

## 0.2 最終目標
1. 多義語を文脈に応じて選択する。2. 固有名詞を一般語として誤翻訳しない。3. 同一文書・会話内の用語を統一する。4. ユーザー指定の訳語・禁止訳語を尊重する。5. 両言語の形態変化を考慮する。6. ネパール語の格標識、後置詞、敬意表現、動詞活用を考慮する。7. 翻訳メモリから適切な過去の翻訳を再利用する。8. 誤った辞書一致による品質低下を防ぐ。9. オフラインでも導入済み資源を利用できる。10. 辞書・用語データをアプリ更新なしに追加・更新できる構造を目指す。11. 用語制約をモデルに適用できない場合も安全な代替処理を提供する。12. 辞書一致率だけで翻訳を正しいと判定しない。13. 出典、ライセンス、検証状態、変更履歴を追跡できる。14. ユーザーの修正を誤った訳語の無条件な学習に利用しない。

## 0.3 非目標
翻訳モデルの学習・推論、完全な形態素解析、文全体の品質の最終判定、ASR誤りの修正、TTS、外部辞書サービスへの無条件アクセス、ユーザー修正の自動正解採用。

---

# 1. 基本設計原則

## 1.1 単純文字列置換を基本にしない
`text.replace("大丈夫", "ठीक छ")` のような方式を基本実装にしない（多義語、品詞、意味役割、否定・疑問・条件、固有名詞と一般語、敬意、活用、複合語・慣用句、文脈切替、ネパール語の語形変化、モデルが生成した語順を扱えない）。文字列一致は候補検索に使ってよいが、結果をそのまま最終翻訳へ挿入しない。

## 1.2 辞書一致と翻訳の正しさを区別する
例「かける」：電話をかける／眼鏡をかける／鍵をかける／時間をかける／声をかける／迷惑をかける。複数の意味候補を保持し、Language IRで絞り込む。解析情報が不十分なら一つに確定しない。

## 1.3 責務の分離

| コンポーネント | 主な責務 |
|---|---|
| Dictionary Engine | 訳語・意味候補の検索 |
| Morphology Adapter | 基本形・活用形・語形の対応 |
| Context Resolver | 文脈に合う候補の絞り込み |
| Terminology Manager | 用語の優先度・適用範囲・統一 |
| Translation Memory | 過去の翻訳例の検索 |
| Term Constraint Builder | モデルへ渡す用語制約の生成 |
| Translation Model | 文全体の翻訳 |
| Post-Translation Validator | 翻訳後の用語適合性検査 |
| Quality Evaluator | 翻訳全体の品質評価 |

辞書エンジンから翻訳モデルの内部状態を直接操作しない。

## 1.4 強制制約と参考情報を分離する
`MANDATORY`（ユーザーの明示的な必須指定）／`PREFERRED`／`ALLOWED`／`FORBIDDEN`／`REFERENCE_ONLY`／`UNVERIFIED` を区別する。MANDATORY は独立した制約として管理し、重み付きランキングで上書きしない。ただし文字列挿入で文法を壊さず、活用・助詞・格標識を調整し、調整不能なら制約未達として返す。

## 1.5 文脈に合わない強制適用を防止する
例：「銀行」を特定の金融機関名として登録していても、原文の「岸」（川岸）に部分一致で適用しない。適用対象を同定できない場合は 1 適用可能な候補として保持 2 曖昧であることを通知 3 ユーザー指定との競合として記録 4 強制適用せず確認対象にする、のいずれか。

## 1.6 ローカル優先とプライバシー
基本・ユーザー・導入済み専門辞書は端末内で利用可能にする。STRICT_LOCALでは外部辞書・外部TM・外部検索を使わない。機密指定の内容を外部検索へ送らない。同期・バックアップ・外部共有はユーザー許可が必要。会話中の一時的な用語を保存許可なしに永続化しない。自動機密検出だけを唯一の保護にしない。

## 1.7 証拠と出典を保持する
出典名、出典URL/参照ID、ライセンス、データセットのバージョン、登録者/検証者、検証日、検証方法、使用例、適用ドメイン、信頼性状態。出典不明のデータを検証済みと同等に扱わない。

---

# 2. 全体アーキテクチャ

## 2.1 コンポーネント構成

```text
DictionaryTerminologyEngine
├── DictionaryRepository
│   ├── CoreDictionaryRepository / GeneralDictionaryRepository / DomainDictionaryRepository
│   ├── ProperNounRepository / UserDictionaryRepository / ExternalDictionaryRepository
├── DictionarySearchEngine
│   ├── UnicodeNormalizer / ExactMatcher / LemmaMatcher / InflectionMatcher
│   ├── VariantMatcher / PhraseMatcher / FuzzyMatcher
├── MorphologyAdapter
│   ├── JapaneseMorphologyAdapter / NepaliMorphologyAdapter
├── DictionaryCandidateResolver
│   ├── POSFilter / SemanticFilter / ContextFilter / DomainFilter
│   ├── RegisterFilter / MorphologyFilter / CandidateRanker
├── TerminologyManager
│   ├── TerminologyRepository / TerminologyPolicyEngine / TerminologyScopeManager
│   ├── TerminologyConflictResolver / ConsistencyChecker / TerminologyConstraintBuilder
├── TranslationMemoryEngine
│   ├── TranslationMemoryRepository / ExactMatchSearch / StructuralMatchSearch
│   ├── FuzzyMatchSearch / SemanticMatchSearch / ContextualMatchSearch / MemoryCandidateRanker
├── TranslationMemoryIntegrator
│   ├── SegmentAlignment / FuzzyRepair / SpanReuse / ContextConsistencyChecker
├── DictionaryLearningManager
│   ├── UserCorrectionImporter / CandidateReviewManager / DictionaryUpdateManager
├── DictionaryValidator
│   ├── SchemaValidator / LinguisticValidator / LicenseValidator / CompatibilityValidator / SecurityValidator
└── DictionaryTelemetry
    ├── LocalPerformanceMetrics / ErrorRecorder / PrivacySafeDiagnostics
```

## 2.2 依存関係
第2部 `LanguageAnalysisResult` を参照。第3部 `TermConstraintProvider` を実装し、`TranslationModel` に用語制約情報を提供。第5部に候補評価・用語適合性検査の情報を提供。第10部（言語資源の配布・署名・更新・ロールバック）、第11部（ユーザー辞書・用語集・履歴保存・プライバシー設定）と連携。辞書エンジンが翻訳モデルやUIに直接依存しない。

## 2.3 依存方向

```text
UI → DictionaryTerminologyFacade → Dictionary / Terminology / TM Services → Repositories / Indexes
```

Language IRは入力として受け取り、モデルへの制約は第3部のインターフェースを通じて提供する。

---

# 3. 共通データモデル

## 3.1 LanguageCode

```kotlin
enum class LanguageCode { JA, NE }
```

内部保存形式にはBCP-47等との変換レイヤーを設ける。

## 3.2 DictionaryEntry

```kotlin
data class DictionaryEntry(
    val entryId: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val sourceTerm: String,
    val normalizedSourceTerm: String,
    val lemma: String?,
    val sourceForms: List<String>,
    val aliases: List<String>,
    val partOfSpeech: String?,
    val semanticCategory: String?,
    val semanticFrameIds: List<String>,
    val targetCandidates: List<DictionaryTranslation>,
    val domain: String?,
    val register: RegisterType?,
    val morphology: MorphologyInfo?,
    val contextRules: List<DictionaryContextRule>,
    val examples: List<DictionaryExample>,
    val priority: Int,
    val confidence: Float,
    val verificationStatus: VerificationStatus,
    val source: DictionarySource,
    val licenseId: String?,
    val version: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isEnabled: Boolean
)
```

`confidence` は辞書エントリーの確からしさであり、翻訳品質スコアやモデルの確信度と混同しない。

## 3.3 DictionaryTranslation

```kotlin
data class DictionaryTranslation(
    val translationId: String,
    val targetText: String,
    val normalizedTargetText: String,
    val targetLemma: String?,
    val targetPartOfSpeech: String?,
    val semanticCategory: String?,
    val register: RegisterType?,
    val morphology: MorphologyInfo?,
    val usageConditions: List<String>,
    val priority: Int,
    val confidence: Float,
    val isPreferred: Boolean,
    val isAllowed: Boolean,
    val isForbidden: Boolean,
    val verificationStatus: VerificationStatus
)
```

一つの原語に複数の訳語候補を保持し、原則として一つに固定しない。

## 3.4 MorphologyInfo

```kotlin
data class MorphologyInfo(
    val lemma: String?,
    val inflectionType: String?,
    val conjugationClass: String?,
    val caseMarkers: List<String>,
    val suffixes: List<String>,
    val prefixes: List<String>,
    val honorificForms: List<String>,
    val irregularForms: List<String>,
    val person: String?,
    val number: String?,
    val tense: String?,
    val aspect: String?,
    val modality: String?,
    val notes: String?
)
```

言語ごとの形態情報を無理に共通化せず、言語固有の詳細は拡張フィールドまたは専用構造で保持する。

## 3.5 DictionaryContextRule

```kotlin
data class DictionaryContextRule(
    val ruleId: String,
    val requiredPOS: List<String>,
    val excludedPOS: List<String>,
    val requiredSemanticCategories: List<String>,
    val excludedSemanticCategories: List<String>,
    val requiredDomains: List<String>,
    val excludedDomains: List<String>,
    val requiredRegister: List<RegisterType>,
    val requiredDependencyRelations: List<String>,
    val requiredSemanticRoles: List<String>,
    val requiredContextTerms: List<String>,
    val forbiddenContextTerms: List<String>,
    val minimumAnalysisConfidence: Float,
    val ruleWeight: Float,
    val isHardConstraint: Boolean
)
```

`isHardConstraint=true` は意味上明確な矛盾の排除に限定する。曖昧な解析結果だけで候補を完全排除しない。

## 3.6 DictionaryExample

```kotlin
data class DictionaryExample(
    val exampleId: String,
    val sourceText: String,
    val targetText: String,
    val domain: String?,
    val register: RegisterType?,
    val contextDescription: String?,
    val verificationStatus: VerificationStatus,
    val verifierId: String?,
    val verifiedAt: Long?
)
```

自動生成例文、機械翻訳例文、母語話者検証済み例文を区別する。

## 3.7 RegisterType

```kotlin
enum class RegisterType {
    INFORMAL, CASUAL, POLITE, FORMAL, HONORIFIC, HUMBLE,
    TECHNICAL, LEGAL, MEDICAL, ADMINISTRATIVE, UNKNOWN
}
```

日本語とネパール語の敬意体系は一対一で対応しないため、enum値の変換だけで敬語を決定しない。話者・聞き手・対象人物の関係をLanguage IRと合わせて評価する。

## 3.8 VerificationStatus

```kotlin
enum class VerificationStatus {
    UNVERIFIED, MACHINE_GENERATED, MACHINE_REVIEWED, HUMAN_REVIEWED,
    NATIVE_VERIFIED, APPROVED, REJECTED, DEPRECATED
}
```

`APPROVED` と `NATIVE_VERIFIED` は同一ではない。承認プロセスと母語話者確認を分離して記録する。

---

# 4. 辞書の分類・出典・優先順位

## 4.1 辞書の種類

| 辞書 | 用途 |
|---|---|
| Core Dictionary | 基本語彙 |
| General Dictionary | 一般語彙 |
| Domain Dictionary | 医療・仕事・行政など |
| Proper Noun Dictionary | 人名・地名・組織名 |
| User Dictionary | ユーザー独自の訳語 |
| Conversation Dictionary | 会話中の一時的な用語 |
| Document Glossary | 文書単位の用語集 |
| Alias Dictionary | 表記ゆれ・別名 |
| Translation Memory | 過去の翻訳例 |

種類はデータの出所・管理方法であり、正しさの順位ではない。

## 4.2 優先順位
1 現在の翻訳要求で明示された用語指定 2 現在の文書の必須用語指定 3 現在の会話で明示された用語指定 4 ユーザー個人辞書 5 適用範囲が一致する承認済み専門用語 6 適切な固有名詞辞書 7 検証済み基本辞書 8 一般辞書 9 文脈が一致する翻訳メモリ 10 未検証の外部辞書候補。

順位だけで決定しない。原文の意味と一致する／品詞・意味役割が矛盾しない／ドメインが一致する／対象言語として文法的に使える／禁止条件に該当しない／必要な検証状態を満たす、を満たさない候補は自動採用しない。

## 4.3 優先順位と強制力を分離する

```kotlin
enum class TerminologyStrength { MANDATORY, PREFERRED, ALLOWED, REFERENCE_ONLY, FORBIDDEN }
```

MANDATORY と FORBIDDEN は通常のランキングとは別の制約として処理する。

## 4.4 用語競合
同じ適用範囲で両立しない Mandatory 指定は自動的に片方を選ばない。

```kotlin
data class TerminologyConflict(
    val conflictId: String,
    val termKey: String,
    val conflictingRuleIds: List<String>,
    val scope: TerminologyScope,
    val reason: String,
    val resolutionRequired: Boolean
)
```

競合が解決するまで、該当箇所の強制適用を保留できること。

---

# 5. 辞書検索エンジン

## 5.1 検索パイプライン

```text
Input → Original Text Preservation → Unicode Normalization → Token / Lemma Extraction → Exact Match
→ Lemma Match → Inflection Match → Alias / Variant Match → Phrase Match → Fuzzy Match
→ Candidate Merge → Context Resolution → Ranked Dictionary Candidates
```

原文を破壊的に正規化しない。検索用表現と表示用原文を分離する。

## 5.2 検索方式
- **Exact Match**：正規化済みの完全一致。
- **Lemma Match**：第2部の形態解析の基本形と照合。
- **Inflection Match**：活用形・格標識・語尾変化を考慮。
- **Alias Match**：表記ゆれ、略称、別名、旧表記。
- **Phrase Match**：複合語、慣用句、複数トークン表現。
- **Fuzzy Match**：入力誤り・表記揺れの補助。完全一致と同等の信頼度を与えない。

## 5.3 検索結果データ

```kotlin
data class DictionaryMatch(
    val entryId: String,
    val matchedText: String,
    val matchType: DictionaryMatchType,
    val sourceSpan: TextSpan,
    val matchedLemma: String?,
    val matchedPOS: String?,
    val analysisConfidence: Float,
    val dictionaryConfidence: Float,
    val candidateTranslations: List<DictionaryTranslation>,
    val appliedRules: List<String>,
    val rejectedRules: List<String>
)
```

`TextSpan` はUnicodeコードポイントまたは一貫したUTF-16オフセット規約で定義し、Androidの文字列インデックスと混同しない。

## 5.4 重複排除
同一エントリーが複数の方式で検出された場合は統合する。表記が同じだけの別エントリー（別の意味）は統合しない。

---

# 6. 文脈依存候補解決エンジン

## 6.1 目的
辞書候補から、原文の意味と文脈に適合する候補を絞り込む。

## 6.2 入力

```kotlin
data class DictionaryResolutionRequest(
    val sourceText: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val analysisResult: LanguageAnalysisResult?,
    val domain: String?,
    val requestedRegister: RegisterType?,
    val terminologyContext: TerminologyContext,
    val conversationContext: ConversationContext?,
    val strictLocal: Boolean
)
```

`LanguageAnalysisResult` がなくても検索できる。ただし文脈情報不足を明示する。

## 6.3 候補評価要素
表層形一致、基本形一致、品詞一致、意味カテゴリ一致、意味役割一致、依存関係一致、ドメイン一致、敬意・文体一致、活用可能性、会話文脈一致、文書内用語状態、検証状態、出典の信頼性、ユーザー指定との整合性。

## 6.4 スコアリング
初期実装は説明可能な特徴量ベースとする。

```kotlin
data class CandidateScoreBreakdown(
    val lexicalMatch: Float,
    val lemmaMatch: Float,
    val posCompatibility: Float,
    val semanticCompatibility: Float,
    val contextCompatibility: Float,
    val domainCompatibility: Float,
    val registerCompatibility: Float,
    val morphologyCompatibility: Float,
    val verificationScore: Float,
    val sourceReliability: Float
)
```

```text
score = w1*lexicalMatch + w2*lemmaMatch + w3*posCompatibility + w4*semanticCompatibility
      + w5*contextCompatibility + w6*domainCompatibility + w7*registerCompatibility
      + w8*morphologyCompatibility + w9*verificationScore + w10*sourceReliability
```

重みは実データで調整し、初期値を製品品質の保証値として扱わない。Mandatory・Forbidden・明確な意味矛盾は通常のスコア計算とは別に評価する。

## 6.5 不確実性の扱い
解析信頼度が低い／複数候補のスコアが近い／品詞を確定できない／ドメイン不明／照応先不明／固有名詞か一般語か判断できない／検証状態が不十分、の場合は単一候補へ自動確定しない。

```kotlin
enum class ResolutionStatus { RESOLVED, MULTIPLE_CANDIDATES, LOW_CONFIDENCE, CONTEXT_MISSING, CONFLICT, NO_MATCH }
```

曖昧な候補を無理に確定するより、複数候補を第3部または第5部へ渡すことを優先する。

---

# 7. 日本語専用処理

## 7.1 対象
名詞、動詞、形容詞、形容動詞、副詞、助詞、助動詞、接続詞、敬語、慣用句、複合語、省略表現、擬音語・擬態語。

## 7.2 助詞・格関係
「が」「を」「に」「で」「へ」「から」「まで」「と」「は」を独立した単語辞書だけで翻訳しない。意味役割、述語、依存関係と合わせて扱う（同じ「に」でも到達点・存在場所・時間・対象・受益者などで意味が異なる）。

## 7.3 敬語
尊敬語、謙譲語、丁寧語、敬語を含む定型表現、対象人物への敬意、聞き手への丁寧さを区別する。訳語は辞書だけで決めず、話者・聞き手・対象人物の関係を第2部の解析結果と合わせて選ぶ。

## 7.4 省略表現
解析結果に根拠がない人物・対象を補完しない。照応先が不明な場合は人物を特定しない表現を優先するか、不確実性を保持する。

## 7.5 複合語・慣用句
意味を持つ複合表現の一致を優先的に検討する。複合語の一部だけが一致した場合に全体の訳語を適用しない。

---

# 8. ネパール語専用処理

## 8.1 Unicode・正規化
NFC正規化を基本とする。原文表示用の文字列を保持し、検索用の正規化文字列を別に保持する。結合文字を無条件に削除しない。コードポイント単位の比較だけで形態を判断しない。UTF-16インデックスとコードポイント位置の混同を防ぐ。

## 8.2 形態情報
名詞の語形、性・数、格標識、後置詞、人称、動詞の活用、時制、相、法、否定、敬意レベル、不規則形、接辞を必要に応じて保持する。解析器が提供しない情報は不明として扱う。

## 8.3 反復表現
धेरै धेरै、बिस्तारै बिस्तारै 等を機械的に削除しない（程度・頻度・継続・強調の意味がある）。重複検出は単純な連続文字列比較でなく、形態・意味・用法を考慮する。

## 8.4 英語混在・借用語
外国文字が含まれることだけで誤りと判定しない。英語由来の技術語、組織名、商品名、略語の保持・音訳・翻訳の方針はエントリーごとに定義する。

---

# 9. 固有名詞辞書

## 9.1 基本方針
固有名詞をすべて翻訳または音訳しない。

```kotlin
enum class ProperNounPolicy { KEEP_SOURCE, USE_OFFICIAL_TARGET_NAME, TRANSLITERATE, TRANSLATE, SOURCE_AND_TARGET, CONTEXTUAL }
```

## 9.2 ProperNounEntry

```kotlin
data class ProperNounEntry(
    val entryId: String,
    val sourceForms: List<String>,
    val officialName: String?,
    val targetForms: List<String>,
    val entityType: String,
    val entityDescription: String?,
    val policy: ProperNounPolicy,
    val language: LanguageCode,
    val aliases: List<String>,
    val domain: String?,
    val confidence: Float,
    val verificationStatus: VerificationStatus,
    val source: DictionarySource
)
```

## 9.3 誤同定防止
一般名詞と同じ表記の人名・地名・組織名は文字列一致だけで固有名詞と判定しない。Language IRの固有表現情報、周辺語、ドメイン、文脈を利用する。同定できない場合は原文保持を優先する。

---

# 10. 専門用語管理

## 10.1 対象分野
医療、法律、行政、教育、仕事、金融、交通、旅行、技術、IT、日常生活。

## 10.2 DomainTermEntry

```kotlin
data class DomainTermEntry(
    val termId: String,
    val sourceTerm: String,
    val targetCandidates: List<DictionaryTranslation>,
    val domain: String,
    val subDomain: String?,
    val definition: String?,
    val usageConditions: List<String>,
    val riskLevel: TermRiskLevel,
    val requiredVerification: VerificationStatus,
    val sourceReferences: List<String>,
    val licenseId: String?,
    val validFrom: Long?,
    val validUntil: Long?,
    val version: String
)
```

## 10.3 高リスク用語
医療・法律・行政などは一般辞書と異なる検証ポリシーを設ける：出典・適用範囲・検証日を記録する、可能なら専門家または母語話者の確認を行う、未検証訳語を確定訳として扱わない、曖昧な用語は警告を第5部へ渡す、原文にない医学的・法的情報を補足しない。

---

# 11. 用語統一エンジン

## 11.1 適用範囲

```kotlin
enum class TerminologyScopeType { REQUEST, CONVERSATION, DOCUMENT, USER_GLOSSARY, SESSION }
```

## 11.2 TerminologyRule

```kotlin
data class TerminologyRule(
    val ruleId: String,
    val sourceTerm: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val targetCandidates: List<String>,
    val strength: TerminologyStrength,
    val scope: TerminologyScopeType,
    val domain: String?,
    val semanticCategory: String?,
    val requiredPOS: List<String>,
    val morphologyPolicy: String?,
    val properNounPolicy: ProperNounPolicy?,
    val createdAt: Long,
    val expiresAt: Long?,
    val source: String,
    val isEnabled: Boolean
)
```

## 11.3 用語状態

```kotlin
data class TerminologyContext(
    val scopeId: String,
    val scopeType: TerminologyScopeType,
    val activeRules: List<TerminologyRule>,
    val conflicts: List<TerminologyConflict>,
    val glossaryVersion: String
)
```

## 11.4 文脈による変化を許容する
用語統一はすべての表記を無条件に同一にすることではない。品詞や意味が異なれば訳語が異なってよく、ネパール語は活用で表層形が変わる。語彙的な基本形、意味カテゴリ、文脈上の用法、形態的な変化、文書内での用語指定の単位で判定する。活用形の違いだけで不一致と判定しない。

## 11.5 競合解決
1 スコープの具体性 2 ユーザー指定の明示性 3 意味カテゴリ 4 品詞 5 ドメイン 6 有効期限 7 検証状態 で解決を試み、できなければ `CONFLICT` を返す。

---

# 12. 翻訳メモリ（Translation Memory）

## 12.1 目的
過去に使用した翻訳を保存し、類似する原文に参考候補を提供する。翻訳モデルを単純に置き換えるものではない。類似度が高くても文脈・意味・否定・数値・固有名詞・敬意・時制が一致するとは限らない。

## 12.2 TranslationMemoryEntry

```kotlin
data class TranslationMemoryEntry(
    val memoryId: String,
    val sourceText: String,
    val targetText: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val normalizedSource: String,
    val normalizedTarget: String,
    val sourceLemmaSequence: List<String>,
    val targetLemmaSequence: List<String>,
    val domain: String?,
    val register: RegisterType?,
    val semanticFingerprint: String?,
    val terminologyFingerprint: String?,
    val contextBefore: String?,
    val contextAfter: String?,
    val sourceHash: String,
    val qualityStatus: TranslationMemoryQualityStatus,
    val verificationStatus: VerificationStatus,
    val provenance: TranslationMemoryProvenance,
    val createdAt: Long,
    val updatedAt: Long,
    val usageCount: Int,
    val isEnabled: Boolean
)
```

## 12.3 登録条件
品質評価が未完了の翻訳、明確な誤訳が検出された翻訳、保存未許可の機密情報、一時的な会話情報、原文・訳文の対応が不明なデータ、出典・ライセンス不明の外部データを無条件に登録しない。

## 12.4 登録元

```kotlin
enum class TranslationMemoryProvenance {
    USER_APPROVED, HUMAN_TRANSLATED, NATIVE_VERIFIED, MACHINE_TRANSLATED, MACHINE_POST_EDITED, IMPORTED_CORPUS
}
```

母語話者確認済みデータと機械翻訳データを区別する。

## 12.5 検索方式
- **Exact Match**：正規化済み原文の完全一致。
- **Structural Match**：基本形・品詞・構文構造が近いもの。
- **Fuzzy Match**：編集距離、トークン類似度、形態素類似度。
- **Semantic Match**：意味表現が近いもの。意味類似度が高いだけでは置換可能と判定しない。
- **Contextual Match**：ドメイン、敬意、前後文脈、用語状態を考慮。

## 12.6 類似度評価

```kotlin
data class TranslationMemoryMatchScore(
    val lexicalSimilarity: Float,
    val lemmaSimilarity: Float,
    val structuralSimilarity: Float,
    val semanticSimilarity: Float,
    val domainSimilarity: Float,
    val registerSimilarity: Float,
    val terminolog……（受領原稿はここで途切れている。続きを待つ）
```
