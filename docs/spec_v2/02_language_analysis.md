# MagiTranslator 総合詳細機能実装仕様書
## 第2部：日本語・ネパール語専用高度言語解析エンジン

> 本文はユーザー提示の原稿。現状コード・確定済み判断との差分は `02_language_analysis.notes.md` を参照（実装前に必読）。

**対象：** 日本語 ⇔ ネパール語
**対象プラットフォーム：** Android
**設計方式：** 言語別解析器＋共通言語中間表現（Language Intermediate Representation）
**目的：** 翻訳モデルに入力する前の言語理解と、翻訳結果の言語的検証を高度化する。

---

# 1. 開発目的

MagiTranslatorの翻訳精度を改善するため、日本語とネパール語それぞれの文法・意味・文脈を解析する専用言語解析エンジンを新設する。

一般的な多言語翻訳モデルは、両言語のすべての文法的特徴や会話上の省略表現を常に正確に理解できるとは限らない。そのため、翻訳モデルにすべてを任せる設計を避け、翻訳前の解析、翻訳中の制約、翻訳後の検証を組み合わせる。

本エンジンは翻訳モデルを置き換えるものではない。翻訳モデルが理解しにくい情報を構造化して提供し、翻訳結果が原文の意味を保持しているかを検証する補助基盤とする。

## 1.1 最重要目標

以下の問題を重点的に改善する。

1. 日本語の主語・目的語の省略による誤訳
2. 日本語の助詞の解釈間違い
3. 日本語の敬語・丁寧語の誤変換
4. 日本語の時制・相・否定表現の誤認識
5. 文脈に依存する多義語の誤訳
6. ネパール語の格標識・後置詞の誤解析
7. ネパール語の動詞活用と敬意レベルの誤解
8. ネパール語の人称・数・性に関係する表現の不整合
9. デーヴァナーガリー文字の正規化問題
10. 日常会話における省略・反復・言い換えの誤処理
11. 長文における文間の意味関係の喪失
12. 翻訳後の意味・文法・表現の不一致

---

# 2. 基本アーキテクチャ

```text
LanguageAnalysisEngine
│
├── InputPreprocessor
│   ├── UnicodeNormalizer
│   ├── ScriptDetector
│   ├── LanguageDetector
│   ├── NoiseAnalyzer
│   └── TextSegmenter
│
├── LanguageAnalyzer
│   ├── JapaneseAnalyzer
│   │   ├── JapaneseTokenizer
│   │   ├── MorphologicalAnalyzer
│   │   ├── DependencyAnalyzer
│   │   ├── ParticleAnalyzer
│   │   ├── HonorificAnalyzer
│   │   ├── TenseAspectAnalyzer
│   │   └── EllipsisAnalyzer
│   │
│   └── NepaliAnalyzer
│       ├── NepaliTokenizer
│       ├── MorphologicalAnalyzer
│       ├── SuffixAnalyzer
│       ├── CaseMarkerAnalyzer
│       ├── VerbAnalyzer
│       ├── HonorificAnalyzer
│       └── SentenceStructureAnalyzer
│
├── SemanticAnalysisEngine
│   ├── SemanticRoleAnalyzer
│   ├── PredicateAnalyzer
│   ├── NegationAnalyzer
│   ├── ModalityAnalyzer
│   ├── EntityAnalyzer
│   ├── CoreferenceAnalyzer
│   └── AmbiguityAnalyzer
│
├── DiscourseAnalysisEngine
│   ├── ContextResolver
│   ├── ConversationAnalyzer
│   ├── SpeakerRelationAnalyzer
│   └── DiscourseLinkAnalyzer
│
├── LanguageIRBuilder
├── CrossLanguageMapper
└── AnalysisValidator
```

## 2.1 設計原則

言語別の解析処理と、言語共通の意味解析処理を分離する。日本語とネパール語の文法構造は異なるため、同一の形態素解析アルゴリズムを無理に共用してはならない。一方、意味役割、否定、時制、数量、固有名詞などの情報は共通の中間表現で管理する。

---

# 3. 共通言語中間表現（Language IR）

Language IRは、日本語とネパール語の解析結果を共通形式で表現するためのデータ構造である。翻訳モデル、辞書エンジン、品質評価エンジンは、この構造を参照できるようにする。

## 3.1 LanguageAnalysisResult

```kotlin
data class LanguageAnalysisResult(
    val analysisId: String,
    val language: LanguageCode,
    val originalText: String,
    val normalizedText: String,
    val sentences: List<SentenceAnalysis>,
    val entities: List<NamedEntity>,
    val semanticFrames: List<SemanticFrame>,
    val discourseContext: DiscourseContext?,
    val ambiguities: List<Ambiguity>,
    val overallConfidence: Float,
    val warnings: List<AnalysisWarning>
)
```

## 3.2 SentenceAnalysis

```kotlin
data class SentenceAnalysis(
    val sentenceId: String,
    val text: String,
    val tokens: List<LanguageToken>,
    val dependencies: List<DependencyRelation>,
    val predicates: List<PredicateAnalysis>,
    val semanticFrameIds: List<String>,
    val tense: Tense?,
    val aspect: Aspect?,
    val mood: Mood?,
    val polarity: Polarity,
    val speechAct: SpeechAct?,
    val register: RegisterType?,
    val confidence: Float
)
```

## 3.3 LanguageToken

```kotlin
data class LanguageToken(
    val tokenId: String,
    val surface: String,
    val lemma: String?,
    val startOffset: Int,
    val endOffset: Int,
    val partOfSpeech: PartOfSpeech,
    val features: Map<String, String>,
    val normalizedForm: String?,
    val confidence: Float
)
```

文字位置はUTF-16オフセットなど、Android/Kotlinで扱う基準を明確に統一する。外部解析器がUnicodeコードポイント単位の位置を返す場合は、専用アダプターで変換する。

## 3.4 DependencyRelation

```kotlin
data class DependencyRelation(
    val headTokenId: String?,
    val dependentTokenId: String,
    val relation: DependencyType,
    val confidence: Float
)
```

依存関係には、主語、目的語、修飾語、述語、補語、助詞、後置詞などの関係を保持する。

## 3.5 SemanticFrame

```kotlin
data class SemanticFrame(
    val frameId: String,
    val predicate: String,
    val agent: SemanticArgument?,
    val patient: SemanticArgument?,
    val recipient: SemanticArgument?,
    val instrument: SemanticArgument?,
    val location: SemanticArgument?,
    val time: SemanticArgument?,
    val manner: SemanticArgument?,
    val cause: SemanticArgument?,
    val condition: SemanticArgument?,
    val polarity: Polarity,
    val modality: Modality?,
    val confidence: Float
)
```

すべての項目を必ず埋める必要はない。原文から確認できない情報はnullとし、推測で補完しない。

## 3.6 SemanticArgument

```kotlin
data class SemanticArgument(
    val text: String,
    val normalizedValue: String?,
    val entityId: String?,
    val source: InformationSource,
    val confidence: Float
)

enum class InformationSource {
    EXPLICIT_TEXT,
    MORPHOLOGICAL_EVIDENCE,
    SYNTACTIC_INFERENCE,
    CONTEXT_INFERENCE,
    UNKNOWN
}
```

明示された情報と推定された情報を必ず区別する。

---

# 4. 日本語解析エンジン

## 4.1 基本構成

```text
JapaneseAnalyzer
├── JapaneseNormalizer
├── JapaneseTokenizer
├── MorphologicalAnalyzer
├── ParticleAnalyzer
├── DependencyParser
├── PredicateAnalyzer
├── HonorificAnalyzer
├── TenseAspectAnalyzer
├── EllipsisAnalyzer
├── ModalityAnalyzer
└── JapaneseIRBuilder
```

## 4.2 形態素解析

解析対象：名詞、動詞、形容詞、形容動詞、副詞、助詞、助動詞、接続詞、連体詞、感動詞、接頭辞・接尾辞。

解析器の候補としてSudachi、MeCab系辞書、UniDic等を比較する。Androidへの組み込み方法、ライセンス、辞書サイズ、実行速度、メモリ消費量を検証して採用する。解析器の選択を特定ライブラリに固定せず、JapaneseMorphologyProviderインターフェースを設ける。

## 4.3 助詞解析

| 助詞 | 主な解析対象 |
|---|---|
| は | 話題・対比 |
| が | 主語・焦点 |
| を | 目的語・経路 |
| に | 時間・場所・対象・到達点 |
| で | 手段・場所・原因 |
| と | 引用・共同・並列 |
| から | 起点・原因 |
| まで | 終点・範囲 |
| へ | 方向 |
| の | 所有・修飾 |
| も | 追加・強調 |
| だけ | 限定 |

助詞を単語単位で機械的に置換してはならない。周辺語、述語、係り受け、文脈と合わせて意味を判断する。

## 4.4 主語・目的語の省略解析

例：「もう食べました。」— 誰が・何を食べたのかを確定できない場合がある。

```kotlin
data class EllipsisAnalysis(
    val omittedRole: SemanticRole,
    val candidates: List<EllipsisCandidate>,
    val selectedCandidate: String?,
    val confidence: Float,
    val isResolved: Boolean
)
```

候補の情報源：1. 同一文内の文法情報 2. 直前の会話 3. 話者情報 4. 会話上の対象 5. 文脈推定。

信頼度が低い場合は補完しない。ネパール語側で主語を明示する必要があっても、根拠のない主語を追加してはならない。

## 4.5 敬語解析

尊敬語、謙譲語、丁寧語、普通体、親しい相手への口語、命令・依頼の丁寧さ、相手への敬意を示す表現を区別する。敬語の変換は単語の置換だけでなく、文全体の意味と話者・聞き手の関係を考慮する。

## 4.6 時制・相・モダリティ解析

過去、現在、未来、完了、継続、経験、進行、推量、意志、義務、可能、許可、禁止、希望、条件、伝聞を独立して解析する。

「行きました」「行っています」「行ったことがあります」「行くつもりです」「行かなければなりません」を単なる時制の違いとしてまとめてはならない。

## 4.7 否定表現解析

否定の作用範囲を解析する。例：行きません。／行かないと思います。／行かなくてもいいです。／行かなければなりません。／行ったわけではありません。

否定表現の誤変換は重大誤訳として扱う。

---

# 5. ネパール語解析エンジン

## 5.1 基本構成

```text
NepaliAnalyzer
├── NepaliNormalizer
├── DevanagariProcessor
├── NepaliTokenizer
├── MorphologicalAnalyzer
├── StemAnalyzer
├── SuffixAnalyzer
├── CaseMarkerAnalyzer
├── PostpositionAnalyzer
├── VerbAnalyzer
├── HonorificAnalyzer
├── SentenceStructureParser
└── NepaliIRBuilder
```

## 5.2 Unicode・デーヴァナーガリー処理

基本的にNFCを採用する。子音、母音、母音記号、結合文字、半子音・結合形、ヴィラーマ、アヌスヴァーラ、チャンドラビンドゥ、ヌクター、デーヴァナーガリー数字、ASCII数字との混在を正しく処理する。文字の結合や分解を誤って処理しない。原文の表記を不必要に変更しない。

## 5.3 形態素・語幹解析

対象：名詞、動詞、形容詞、副詞、代名詞、後置詞、接尾辞、活用語尾、派生語。語幹と表層形を別々に保持する。語幹を抽出しただけで意味が確定したと判断してはならない。

## 5.4 格標識・後置詞解析

ले、लाई、को、का、की、मा、बाट、देखि、सँग、लागि、सम्म を重点的に解析する。単語置換による翻訳は禁止し、述語との関係、意味役割、名詞句の構造を合わせて解析する。

## 5.5 動詞解析

語幹、活用形、時制、相、否定、人称、数、敬意レベル、可能・義務・希望などのモダリティを解析する。性・数・人称による形態変化は特徴量として保持する。すべての動詞に同じ活用規則を適用してはならない。不規則形や例外形は辞書・解析モデルで管理する。

## 5.6 敬意レベル解析

तँ、तिमी、तपाईं などの人称表現を区別する。ただし単純な上下関係として固定せず、地域、関係性、会話場面、相手への距離感を考慮する。日本語への翻訳では、ネパール語の敬意レベルを日本語の敬語・丁寧さに適切に対応させる。

---

# 6. 共通意味解析エンジン

## 6.1 SemanticRoleAnalyzer
Agent、Patient、Recipient、Instrument、Location、Time、Cause、Manner、Condition を推定する。文法上の主語と意味上の動作主体は必ずしも一致しない。受動・使役・授受表現を考慮する。

## 6.2 PredicateAnalyzer
動詞・形容詞・名詞述語を対象に、述語の意味、必須項、任意項、否定、時制、相、モダリティ、依存する修飾表現を保持する。

## 6.3 NegationAnalyzer
否定語と否定の作用範囲（文全体／特定の節・語句）を特定する。

## 6.4 ModalityAnalyzer
断定、推測、可能性、義務、許可、希望、意志、命令、依頼、禁止、条件を解析する。

## 6.5 AmbiguityAnalyzer
多義語・曖昧表現を検出し、候補と信頼度を保持する。確定できない意味を勝手に決定しない。

---

# 7. 会話・文脈解析

## 7.1 DiscourseContext

```kotlin
data class DiscourseContext(
    val conversationId: String?,
    val currentSpeakerId: String?,
    val previousUtterances: List<UtteranceReference>,
    val knownEntities: List<ContextEntity>,
    val topic: String?,
    val relationship: RelationshipType?,
    val confidence: Float
)
```

## 7.2 照応解析
例：「それを持ってきてください。」の「それ」を直前の会話から推定する。複数候補がある場合は曖昧性を保持する。

## 7.3 会話状態管理
会話単位の文脈管理、文脈の有効期限、会話終了時の破棄、ユーザーによる履歴削除、機密会話の保存禁止、文脈情報の最大サイズ制御を実装する。古い文脈を無制限に蓄積しない。文脈情報を外部Providerへ送信する場合は、原文と同様にプライバシー制御を適用する。

---

# 8. 言語間意味マッピング

```text
Japanese Input → Japanese Analysis → Language IR → CrossLanguageMapper → Target Language Constraints → Translation Model
```

逆方向も同様。

## 8.1 マッピング対象
主語・目的語、動作主体・対象、時制・相、否定、人称、敬意レベル、数量、時間、場所、条件、原因、依頼・命令、希望・意志、話者の確実性。

## 8.2 変換時の注意
日本語で省略された主語を無条件に追加しない。ネパール語の敬意レベルを日本語へ変換する際、機械的に最上位の敬語を適用しない。意味と会話上の関係を保持することを優先する。

---

# 9. 解析器の共通インターフェース

```kotlin
interface LanguageAnalyzer {
    suspend fun analyze(request: LanguageAnalysisRequest): LanguageAnalysisResult
    suspend fun initialize()
    suspend fun release()
    fun supportedLanguages(): Set<LanguageCode>
}

interface JapaneseLanguageAnalyzer : LanguageAnalyzer
interface NepaliLanguageAnalyzer : LanguageAnalyzer
```

形態素解析器、構文解析器、辞書、機械学習モデルを変更しても、Language IRの基本仕様を維持できるようにする。

---

# 10. 解析信頼度と不確実性管理

異なる解析器の信頼度をそのまま同じ尺度で比較しない。評価データで校正する。状態：確定度が高い／推定可能／複数候補が存在／判断不能／解析失敗。

信頼度が低い場合は次を制限する：1. 主語の自動補完 2. 固有名詞の自動確定 3. 敬意レベルの強制変換 4. 多義語の単一候補への固定 5. 文脈情報による意味の追加。必要に応じて翻訳結果に警告を付与する。

---

# 11. Android実装要件

## 11.1 処理性能
UIスレッドで解析しない。長文を適切に分割する。不要なオブジェクト生成を抑制する。モデルや辞書を遅延ロードする。同時実行数を制御する。キャンセル可能にする。

## 11.2 キャッシュ
キャッシュキー：正規化入力、言語コード、解析器バージョン、辞書バージョン、解析設定（文脈依存解析は文脈識別情報も）。

## 11.3 ライブラリ選定
日本語対応品質、ネパール語対応品質、Android対応、オフライン動作、ライセンス、モデル容量、メモリ使用量、推論速度、保守状況で比較する。ライブラリ名だけで採用を決めない。日本語向け解析器をネパール語にそのまま適用することは禁止する。

---

# 12. テスト・品質評価

## 12.1 日本語解析テスト
助詞の違い、主語省略、目的語省略、敬語、丁寧語、否定、時制、相、推量、条件、依頼、命令、数字・日時、固有名詞、会話表現、長文。

## 12.2 ネパール語解析テスト
Unicode正規化、文字結合、母音記号、格標識、後置詞、動詞活用、人称、数、敬意レベル、否定、時制、条件、疑問文、依頼表現、口語表現、数字・日時、長文。

## 12.3 共通意味解析テスト
意味役割の抽出、主語と目的語の識別、否定範囲、時間関係、因果関係、条件関係、照応関係、多義語、曖昧表現、文脈依存表現。

## 12.4 評価指標

| 評価項目 | 指標 |
|---|---|
| 形態素解析 | Precision / Recall / F1 |
| 品詞判定 | Accuracy / F1 |
| 係り受け解析 | UAS / LAS |
| 意味役割 | Precision / Recall / F1 |
| 否定解析 | 正解率・意味反転検出率 |
| 敬語解析 | 分類精度 |
| 時制・相解析 | 分類精度 |
| 照応解析 | Accuracy / F1 |
| 曖昧性検出 | Precision / Recall |
| 実行性能 | p50 / p95処理時間 |
| メモリ | 最大メモリ使用量 |

日本語とネパール語の評価結果を混ぜず、言語別・解析項目別に報告する。

---

# 13. 必須受入条件

1. 日本語専用解析器とネパール語専用解析器が分離されている。
2. Language IRが共通データ形式として実装されている。
3. 解析結果に信頼度と不確実性が保持されている。
4. 主語・目的語の省略を扱える。
5. 日本語の助詞を文脈依存で解析できる。
6. 日本語の敬語・時制・否定を区別できる。
7. ネパール語の格標識・後置詞・動詞活用を解析できる。
8. デーヴァナーガリー文字の正規化が正しく行われる。
9. 意味役割と述語構造を共通形式で保持できる。
10. 曖昧な情報を無理に確定しない。
11. 翻訳モデルから独立して解析器を交換できる。
12. オフライン環境で基本解析が動作する。
13. 解析結果の再現性をテストできる。
14. 実機でメモリ・速度・安定性を測定している。
15. 日本語・ネパール語のネイティブ評価を実施している。

---

# 14. 実装AIへの具体的指示

- 最初に既存の言語解析・正規化・分割処理を調査する。
- 既存のJaNormalizer、JaSegmenter、KanaDevanagariなどは、実装内容と有効性を確認してから再利用する。
- 既存処理をそのまま新エンジンへ移植するだけの実装は禁止する。
- Language IRを中心にデータ構造を統一する。
- 日本語とネパール語の解析器を別々に設計する。
- 解析できない情報を推測で確定しない。
- ネパール語の文法規則を日本語の規則から流用しない。
- ライブラリやモデルのライセンスを確認する。
- Androidでの実行可能性を実測する。
- テストデータと評価結果を保存する。
- 未実装・未検証の機能を完成済みと報告しない。

## 第2部の完成条件

日本語とネパール語の文章をそれぞれ解析し、共通のLanguage IRへ変換できること。さらに、その解析結果を翻訳モデル、辞書エンジン、品質評価エンジンへ提供できること。単なる形態素解析器ではなく、MagiTranslator全体の翻訳品質を支える言語理解基盤として完成させること。
