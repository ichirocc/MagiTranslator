# MagiTranslator 総合詳細機能実装仕様書
## 第1部：システム全体設計・基本アーキテクチャ

> 本文はユーザー提示の原稿。現状コード・確定済み判断との差分は `01_architecture.notes.md` を参照（実装前に必読）。

**プロジェクト名：** MagiTranslator
**対象プラットフォーム：** Android
**対象言語：** 日本語 ⇔ ネパール語
**設計方針：** 既存翻訳システムの段階的な全面再設計
**基本方式：** ローカル処理を基本とするハイブリッド型システム

---

# 1. プロジェクトの目的

MagiTranslatorを、日本語とネパール語の双方向翻訳に特化した、高精度・高安定性の翻訳および音声通訳システムへ進化させる。

既存システムの単純な機能追加や個別クラスの修正だけではなく、翻訳エンジンの内部構造、データ構造、処理フロー、モデル管理、言語解析、品質評価を必要に応じて根本から再設計する。

本プロジェクトでは、既存コードの再利用を目的化しない。

既存コードについては、実際の動作、品質、保守性、性能、安全性を調査し、再利用する価値がある部分だけを残す。

設計上の問題が大きい部分については、新しいインターフェースと処理基盤に置き換える。

## 1.1 最終目標

以下の能力を備えた翻訳システムを構築する。

1. 日本語からネパール語への高精度翻訳
2. ネパール語から日本語への高精度翻訳
3. 日本語とネパール語の文法・意味・文脈を考慮した翻訳
4. 日常会話、家族、仕事、医療、行政、旅行などへの対応
5. 敬語、丁寧語、尊敬表現、親しい相手への表現の適切な変換
6. 主語・目的語の省略や文脈依存表現の解釈
7. 複数翻訳候補の生成と品質評価
8. 誤訳、意味欠落、否定の反転、数字の誤変換の検出
9. ローカル翻訳とオンライン翻訳の自動統合
10. オフライン環境での基本翻訳機能の維持
11. 日本語・ネパール語の高精度音声認識
12. 認識結果を翻訳するリアルタイム音声通訳
13. 聞き取りやすく安定した音声読み上げ
14. 翻訳モデル・辞書・言語資源の事前導入と更新
15. 低性能Android端末でも可能な限り安定して動作すること

---

# 2. 基本設計原則

## 2.1 翻訳品質を最優先する

処理速度だけを優先して翻訳品質を犠牲にしてはならない。

ただし、処理時間やメモリ使用量が過大にならないよう、品質と性能を両立する。

翻訳品質は単一の数値だけで判断しない。次の項目を独立して評価する。

- 意味の正確性
- 文法的正確性
- 自然な文章表現
- 文脈との整合性
- 敬語・人称の適切性
- 専門用語の正確性
- 固有名詞の保持
- 数字・日時・数量の正確性
- 原文の意味の欠落
- 原文にない情報の追加

## 2.2 ローカル処理を基本とする

ローカル翻訳をアプリの基本機能とする。

オンライン接続がない場合でも、導入済みの翻訳モデル、辞書、言語資源を利用して翻訳できる構成とする。

オンライン翻訳は、品質改善が期待できる場合に利用する追加経路として扱う。

オンラインサービスが利用できなくても、アプリ全体が使用不能になってはならない。

## 2.3 モデルと処理ロジックを分離する

特定の翻訳モデルに依存した設計を禁止する。

翻訳モデル、Tokenizer、言語解析器、辞書、品質評価器、Providerは独立したコンポーネントとして管理する。

モデルを交換しても、アプリケーション全体を書き直す必要がない構造にする。

## 2.4 日本語・ネパール語専用設計

多言語翻訳向けの一般的な処理をそのまま適用するだけでは不十分である。以下を両言語に特化して処理する。

- 日本語の形態素・係り受け・助詞
- 日本語の主語省略・目的語省略
- 日本語の敬語・丁寧語・文末表現
- ネパール語の形態・格標識・後置詞
- ネパール語の動詞活用・人称・数・敬意
- デーヴァナーガリー文字のUnicode正規化
- 両言語の語順と文構造の違い
- 会話における省略・言い換え・曖昧表現

## 2.5 既存機能との互換性

既存アプリの画面、音声通訳、翻訳履歴、設定、モデルダウンロード機能などを不用意に破壊しない。

新エンジンへの移行中は、必要に応じて互換アダプターを用意する。

ただし、互換性維持のために新しいアーキテクチャを不必要に複雑化してはならない。

---

# 3. システム全体アーキテクチャ

新しいMagiTranslatorは、責務を明確に分離した多層構造とする。

## 3.1 全体構成

```text
MagiTranslator
│
├── Presentation Layer
│   ├── Translation UI
│   ├── Voice Translation UI
│   ├── Conversation UI
│   ├── Dictionary UI
│   └── Settings UI
│
├── Application Layer
│   ├── TranslationUseCase
│   ├── VoiceTranslationUseCase
│   ├── SpeechRecognitionUseCase
│   ├── SpeechSynthesisUseCase
│   └── ResourceManagementUseCase
│
├── Translation Core
│   ├── TranslationEngine
│   ├── TranslationOrchestrator
│   ├── LanguageDetector
│   ├── InputNormalizer
│   ├── LanguageAnalyzer
│   ├── ContextAnalyzer
│   ├── SemanticAnalyzer
│   ├── TranslationMemory
│   ├── TerminologyEngine
│   ├── CandidateGenerator
│   ├── QualityEvaluator
│   ├── CandidateRanker
│   └── OutputValidator
│
├── Provider Layer
│   ├── ProviderRegistry
│   ├── ProviderSelector
│   ├── LocalProviderManager
│   ├── RemoteProviderManager
│   ├── PrivacyGuard
│   └── ExecutionCoordinator
│
├── Model Runtime Layer
│   ├── ONNX Runtime
│   ├── ModelLoader
│   ├── TokenizerEngine
│   ├── InferenceEngine
│   ├── BeamSearchEngine
│   └── MemoryManager
│
├── Speech Layer
│   ├── AudioInputManager
│   ├── AudioPreprocessor
│   ├── VoiceActivityDetector
│   ├── ASREngine
│   ├── SpeechPostProcessor
│   ├── TTSManager
│   └── AudioOutputManager
│
├── Language Resource Layer
│   ├── BilingualDictionary
│   ├── TerminologyDatabase
│   ├── ProperNounDatabase
│   ├── TranslationMemoryDatabase
│   └── LanguageRuleDatabase
│
├── Resource Management Layer
│   ├── ModelRepository
│   ├── ResourceInstaller
│   ├── DownloadManager
│   ├── IntegrityValidator
│   ├── VersionManager
│   └── UpdateManager
│
└── Infrastructure Layer
    ├── Database
    ├── Network
    ├── Android Storage
    ├── Logging
    ├── Security
    └── Monitoring
```

## 3.2 各レイヤーの責務

- **Presentation Layer**：画面表示とユーザー操作を担当する。翻訳処理やモデル推論の内部ロジックを直接実装してはならない。
- **Application Layer**：ユーザー操作を具体的な処理へ変換する。翻訳、音声認識、音声通訳、リソース管理などのユースケースを管理する。
- **Translation Core**：翻訳品質に関わる中心的な処理を担当する。言語解析、文脈解析、候補生成、品質評価、最終候補選択を統括する。
- **Provider Layer**：ローカルとオンラインの翻訳処理を統一する。Providerの選択、実行、切替、障害復旧、プライバシー制御を担当する。
- **Model Runtime Layer**：翻訳モデルを実行する。Tokenizer、ONNX Runtime、推論パラメータ、メモリ管理などを担当する。
- **Speech Layer**：音声入力、音声認識、音声合成、音声出力を担当する。翻訳Coreとは独立した構造とし、共通インターフェースを通じて連携する。
- **Language Resource Layer**：辞書、専門用語、固有名詞、翻訳メモリなどを管理する。単純な文字列置換ではなく、翻訳処理に必要な言語情報を提供する。
- **Resource Management Layer**：モデル、Tokenizer、辞書、言語資源の導入・更新・検証・削除を担当する。
- **Infrastructure Layer**：Android固有機能、データベース、ネットワーク、セキュリティなどの基盤機能を提供する。

---

# 4. 新しいモジュール設計

可能な限り各機能を独立したモジュールに分割する。推奨構成は以下とする。

```text
:app
:core:common
:core:domain
:core:data

:translation:api
:translation:engine
:translation:language
:translation:context
:translation:semantic
:translation:candidate
:translation:quality
:translation:postprocess

:provider:api
:provider:local
:provider:remote
:provider:manager

:model:runtime
:model:tokenizer
:model:manager

:language:dictionary
:language:terminology
:language:propernoun
:language:memory

:speech:api
:speech:audio
:speech:asr
:speech:tts
:speech:voice-translation

:resource:manager
:resource:downloader
:resource:installer
:resource:validator

:storage:database
:storage:preferences

:security
:monitoring
```

これは推奨分割案であり、既存Gradle構成、依存関係、ビルド時間、保守性を調査したうえで確定する。

最初からすべてを独立Androidモジュールにする必要はない。責務を先に分離し、ビルドや保守上のメリットがある単位で物理的なモジュールに分割する。

## 4.1 依存関係の原則

以下を厳守する。

1. PresentationはApplicationを利用する。
2. ApplicationはDomainのインターフェースを利用する。
3. DomainはAndroid固有クラスに依存しない。
4. Translation CoreはProviderの具体的実装に直接依存しない。
5. Provider実装は共通APIを実装する。
6. Model Runtimeは特定の画面に依存しない。
7. Language Resourceは翻訳処理から独立して管理できる。
8. データベースやネットワークの具体的実装はInfrastructure側に置く。

循環依存を禁止する。

---

# 5. 共通データモデル

翻訳エンジン全体で共通のデータ構造を使用する。個々のクラスが独自の翻訳要求や結果オブジェクトを作成する設計を禁止する。

## 5.1 LanguageCode

```kotlin
enum class LanguageCode {
    JA,
    NE,
    AUTO
}
```

言語コードは内部で統一し、Providerごとの異なる言語コードはアダプターで変換する。

## 5.2 LanguagePair

```kotlin
data class LanguagePair(
    val source: LanguageCode,
    val target: LanguageCode
)
```

対応方向は JA → NE、NE → JA の2種類を基本とする。

## 5.3 TranslationRequest

```kotlin
data class TranslationRequest(
    val requestId: String,
    val sourceText: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,

    val context: TranslationContext?,
    val domain: TranslationDomain,
    val style: TranslationStyle,

    val privacyMode: PrivacyMode,
    val allowRemote: Boolean,

    val priority: TranslationPriority,
    val maxLatencyMs: Long?,

    val createdAt: Long
)
```

要求データには、一意な要求ID、原文、入力言語、出力言語、文脈、翻訳分野、表現スタイル、プライバシー設定、オンライン利用許可、優先度、任意の処理時間制限を保持する。

原文や文脈を不要なログへ記録してはならない。

## 5.4 TranslationContext

```kotlin
data class TranslationContext(
    val conversationId: String?,
    val previousUtterances: List<String>,
    val speakerRole: SpeakerRole?,
    val targetAudience: AudienceType?,
    val relationship: RelationshipType?,
    val preferredRegister: RegisterType?
)
```

文脈情報は翻訳精度向上のために利用する。文脈が存在しない場合も正常に翻訳できなければならない。

## 5.5 TranslationResult

```kotlin
data class TranslationResult(
    val requestId: String,
    val translatedText: String,

    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,

    val providerId: String,
    val modelId: String?,

    val qualityAssessment: QualityAssessment?,
    val warnings: List<TranslationWarning>,

    val executionMetadata: ExecutionMetadata
)
```

翻訳結果には、翻訳文だけでなく、使用Provider、モデル、品質情報、警告情報を保持する。内部情報を画面にすべて表示する必要はない。

## 5.6 TranslationDomain

```kotlin
enum class TranslationDomain {
    GENERAL, DAILY_CONVERSATION, FAMILY, RELATIONSHIP, BUSINESS, MEDICAL,
    LEGAL, GOVERNMENT, TRAVEL, EDUCATION, TECHNICAL, UNKNOWN
}
```

分野に応じて辞書、用語、翻訳スタイル、評価条件を切り替える。

---

# 6. 翻訳エンジンの共通インターフェース

翻訳処理の入口を一つに統一する。

```kotlin
interface TranslationEngine {
    suspend fun translate(request: TranslationRequest): TranslationResult
    suspend fun translateBatch(requests: List<TranslationRequest>): List<TranslationResult>
    suspend fun cancel(requestId: String)
    suspend fun initialize()
    suspend fun release()
}
```

UIや音声通訳機能から、翻訳モデルを直接呼び出してはならない。すべてTranslationEngineを経由する。

## 6.1 TranslationEngineの内部構造

```text
TranslationEngine
│
├── InputValidator
├── InputNormalizer
├── LanguageDetector
├── ContextAnalyzer
├── LanguageAnalyzer
├── TerminologyEngine
├── TranslationOrchestrator
│   ├── ProviderSelector
│   ├── CandidateGenerator
│   └── ExecutionCoordinator
├── QualityEvaluator
├── CandidateRanker
├── OutputValidator
└── ResultAssembler
```

各コンポーネントは明確な入出力を持つ。内部処理を巨大な一つのクラスに集約してはならない。

---

# 7. 標準翻訳処理フロー

```text
TranslationRequest → Input Validation → Unicode Normalization → Language Detection → Text Quality Analysis
→ Context Analysis → Japanese / Nepali Analysis → Terminology & Dictionary Retrieval → Provider Selection
→ Candidate Generation → Candidate Quality Evaluation → Candidate Ranking → Output Validation
→ Post Processing → TranslationResult
```

## 7.1 入力検証
空文字、不正なUnicode、対応言語、入力サイズ、制御文字、不正な要求パラメータ、処理上限を超える長文を確認する。不正な入力は適切なエラーとして返す。

## 7.2 Unicode正規化
日本語とネパール語の文字列を正規化する。基本方針はNFCを採用する。NFKCによる正規化は文字の互換性変換が必要な箇所に限定し、意味や表記を壊さないようにする。原文を完全に失わないよう、必要に応じて原文と正規化文字列を別々に保持する。

## 7.3 言語判定
AUTO指定の場合は入力言語を判定する。日本語、ネパール語、英数字、混在文章を考慮する。短文や固有名詞だけの入力では判定が不確実になるため、推定信頼度を保持する。信頼度が低い場合は、ユーザーが指定した言語設定を優先できる構造にする。

## 7.4 文脈解析
過去の会話、話者、相手との関係、翻訳分野などを利用する。ただし、文脈によって原文にない意味を勝手に追加してはならない。推定された情報と原文に明示された情報を区別して保持する。

## 7.5 言語解析
日本語とネパール語それぞれの解析器を呼び出す。解析結果は共通の中間表現へ変換する。

## 7.6 言語資源検索
辞書、専門用語、固有名詞、翻訳メモリから関連情報を取得する。単純な置換ではなく、文脈と品詞と意味を考慮して候補を提供する。

## 7.7 候補生成
ローカルまたはオンラインProviderを使用して翻訳候補を生成する。必要に応じて複数候補を作成する。

## 7.8 品質評価
候補ごとに意味、文法、自然さ、用語、数字、否定などを評価する。品質評価モデルが利用できない場合は、ルールベースの検証へフォールバックする。

## 7.9 最終候補選択
品質評価結果とユーザーの優先設定を利用して候補を選択する。単純にスコアの合計が最も高い候補を選ぶのではなく、意味反転や重大な情報欠落などの致命的な問題を優先して除外する。

## 7.10 出力検証
空の翻訳結果、原文の重要情報の欠落、否定の反転、数字・日時・数量の変化、固有名詞の不自然な変換、文字化け、不自然な反復、明らかな文法上の異常を検証する。検証に失敗した場合は、修正または再翻訳を検討する。

---

# 8. 非同期処理・並列実行・キャンセル

翻訳処理はCoroutineを基本とする。UIスレッドでモデル推論やファイル処理を実行してはならない。

## 8.1 並列処理
複数の翻訳要求を同時に受け付けられる構造とする。ただし、端末のメモリやモデル実行環境に応じて並列数を制限する。同一モデルのロード処理が同時に複数回実行されないよう、排他制御を実装する。

## 8.2 キャンセル
翻訳要求単位でキャンセル可能にする。キャンセルされた要求は、可能な範囲で推論処理、ネットワーク要求、後続処理を停止する。別の翻訳要求へ影響させてはならない。

## 8.3 タイムアウト
Provider選択時間、モデルロード時間、ローカル推論時間、オンライン応答時間、品質評価時間、全体処理時間を個別に管理する。全体タイムアウトを超過した場合でも、すでに利用可能なローカル結果があれば返却できる設計にする。

---

# 9. エラー管理の共通化

エラー処理を各クラスで独自に実装しない。共通のエラー分類を用意する。

```kotlin
sealed interface MagiError {
    data object InvalidInput : MagiError
    data object UnsupportedLanguage : MagiError
    data object ModelUnavailable : MagiError
    data object ModelLoadFailed : MagiError
    data object OutOfMemory : MagiError
    data object NetworkUnavailable : MagiError
    data object ProviderTimeout : MagiError
    data object ProviderRateLimited : MagiError
    data object TranslationFailed : MagiError
    data object QualityValidationFailed : MagiError
    data object ResourceIntegrityFailed : MagiError
    data class Unknown(val internalCode: String) : MagiError
}
```

内部例外とユーザー向けメッセージは分離する。機密情報や原文をエラーログへ含めてはならない。

---

# 10. 既存システムからの移行方針

既存コードを一括削除して新システムに置き換える方法は禁止する。段階的な移行を基本とする。

- **Phase 1：既存コード調査** — 現在の翻訳処理フロー、モデルとTokenizerの依存関係、辞書・後処理の動作、音声通訳との接続、データ保存形式、既存テストと不具合を整理する。
- **Phase 2：新しい共通APIの導入** — TranslationRequest、TranslationResult、TranslationEngine、TranslationProvider、共通エラー管理を新規実装する。
- **Phase 3：既存処理のアダプター化** — 再利用可能な既存処理を新しいインターフェースに接続し、既存実装を直接呼び出す箇所を減らす。
- **Phase 4：新しい処理基盤の実装** — 新しい言語解析、候補生成、品質評価、Provider管理を順番に導入する。
- **Phase 5：新旧エンジンの比較** — 同じ入力データで新旧の翻訳結果を比較する。日本語→ネパール語とネパール語→日本語を別々に評価する。
- **Phase 6：段階的な切り替え** — 新エンジンの品質・安定性・性能が受入条件を満たした機能から切り替える。問題が発生した場合は旧エンジンへ戻せるようにする。
- **Phase 7：旧実装の整理** — 移行完了後、不要になった旧クラス、互換コード、重複処理を削除する。

---

# 11. 第1部の受入条件

1. 翻訳要求と翻訳結果の共通データ構造が定義されている。
2. 翻訳処理の入口が統一されている。
3. UIと翻訳モデルの直接依存がない。
4. ローカル・オンラインProviderを交換できる。
5. 言語解析と翻訳モデルが独立している。
6. 辞書と翻訳メモリを独立して管理できる。
7. 翻訳要求単位でキャンセルできる。
8. 並列実行時にモデルロード競合が発生しない。
9. エラー分類と復旧方針が統一されている。
10. 既存システムから段階的に移行できる。
11. オフラインで基本翻訳機能を維持できる。
12. 原文・翻訳文・認証情報が不要なログへ出力されない。

---

# 12. 他社AIへの実装指示

- 既存コードを十分に調査してから変更する。
- 既存クラスの修正だけで解決しようとしない。
- 必要に応じて新しいクラス、API、データ構造、モジュールを設計する。
- 既存機能を無断で削除しない。
- 特定の翻訳モデルに依存した構造にしない。
- Androidのメモリ制約を無視しない。
- オフライン動作を維持する。
- 原文や翻訳文を不用意に外部送信しない。
- テストを実施せずに実装完了と報告しない。
- 翻訳精度の改善を実測せずに断言しない。
- 仕様と異なる実装が必要になった場合は、理由と影響を報告する。

実装前に必ず既存コードの構造と依存関係を調査し、変更対象、再利用対象、新規作成対象を明示すること。

**第1部の完了条件は、全体アーキテクチャと共通基盤の設計が実装可能な状態になっていることである。**
