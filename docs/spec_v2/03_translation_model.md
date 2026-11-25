# MagiTranslator 総合詳細機能実装仕様書
## 第3部：翻訳モデル・機械翻訳基盤・学習・推論最適化【全面改訂版】

> 本文はユーザー提示の原稿（**受領原稿は §15.1 の途中で途切れている。§15 以降は未着**）。見出し・型定義・表は原文どおり、箇条書きの一部は一文に要約して収録。冒頭の前書き（§0.1〜§1.2 の重複版）は改訂版本文と同内容のため本文側に統合。現状との差分は `03_translation_model.notes.md`。

**対象アプリ：** MagiTranslator
**対象OS：** Android（minSdk 36を設計上の想定値とする。実際の採用は既存アプリの対応端末・配布方針を確認して決定）
**対象言語：** 日本語 ⇔ ネパール語
**実行方式：** ローカル翻訳モデル＋オンラインProvider
**設計方針：** モデル非依存・双方向独立評価・交換可能な推論基盤
**優先順位：** 翻訳品質 ＞ 意味保持・安全性 ＞ 安定性 ＞ 実行速度 ＞ モデル容量

前書きの要旨：モデルの知名度やパラメータ数で採用を決めない。日→ネとネ→日を別々に評価し、翻訳品質、Android端末での動作、メモリ使用量、ライセンス、運用コストを総合して採用を決定する。第2部の言語解析結果を利用し、第1部のProvider管理エンジンから独立して動作する構造にする。

---

# 0. 本仕様の位置づけ

既存のMADLAD-400を単純に呼び出す構造から脱却し、モデルの選定、管理、ロード、推論、最適化、評価、更新までを統合的に管理する。

## 0.1 上位仕様との接続

| 仕様 | 責務 |
|---|---|
| 第1部：Provider管理 | ローカル・オンラインの実行先選択 |
| 第2部：言語解析 | 日本語・ネパール語の構文・意味解析 |
| 第3部：本仕様 | ローカル翻訳モデルの管理・推論・最適化 |
| 第4部：候補統合・品質評価 | 翻訳候補の比較・品質推定・誤訳検出 |
| 第5部：辞書・用語管理 | 用語・固有名詞・翻訳メモリ |
| 後続仕様 | ASR・TTS・音声通訳など |

本仕様は、第1部のProvider選択エンジンがローカル翻訳を選択した後に実行される下位システムである。第2部のLanguage IRを入力情報として利用し、第4部へ翻訳候補と推論情報を返す。

## 0.2 責務の境界

担当：ローカル翻訳モデルの登録・管理／モデル・Tokenizer・辞書の互換性管理／モデル選択／ロード・解放／翻訳推論／デコード方式の管理／複数候補生成／長文分割と再構成支援／量子化・推論最適化／ダウンロード・検証・更新／学習データセットの管理／学習済みモデルの評価・登録／Android実機の性能・メモリ管理。

非担当（連携インターフェースは提供）：オンラインProviderのAPI通信／Provider全体の自動切替／最終候補の品質ランキング／最終的な文法修正／ASR・TTSの音声処理。

## 0.3 実装上の基本原則

1. 特定の翻訳モデルにアプリ全体を依存させない。
2. モデルとTokenizerは一体の互換性単位として管理する。
3. 日本語→ネパール語とネパール語→日本語を独立して評価する。
4. モデルの知名度・サイズ・対応言語数だけで採用を決定しない。
5. Android実機で実行できることを確認する。
6. 量子化・高速化による翻訳品質の変化を測定する。
7. 未検証モデルを本番モデルとして自動採用しない。
8. オフラインでも基本翻訳を利用可能にする。
9. モデルのロード・解放・更新を安全に管理する。
10. 実測していない性能や品質を保証値として記載しない。

---

# 1. 開発目的と最重要目標

## 1.1 開発目的
既存の翻訳モデル呼び出し処理を、拡張可能な機械翻訳基盤へ置き換える。モデルを変更しても上位の翻訳処理やUIを大幅に変更する必要がない構造を実現する。現在のモデルが十分な品質を提供できる場合は再利用する。

## 1.2 最重要目標（優先順）
1. 日本語→ネパール語の翻訳品質向上
2. ネパール語→日本語の翻訳品質向上
3. 双方向の品質差の把握と改善
4. 意味の欠落・追加・反転の防止
5. 敬語・時制・否定・人称の保持
6. 固有名詞・数字・日時・専門用語の正確性
7. 長文翻訳での文脈保持
8. オフライン翻訳の安定性
9. Android実機でのメモリ・速度・クラッシュ耐性
10. モデルの追加・交換・更新・ロールバック

## 1.3 品質と性能の優先関係
翻訳品質を速度より優先する。ただし、実機で安定動作しないモデルは採用しない。採用は次の3条件を満たすこと：翻訳品質が評価基準を満たす／対象端末で安定して推論できる／モデル・Tokenizer・学習データのライセンス条件を満たす。

## 1.4 双方向独立評価
評価結果は必ず「日本語 → ネパール語」「ネパール語 → 日本語」の単位で記録する。片方向のスコアから逆方向の品質を推定してはならない。一方向のみ本番採用し、逆方向には別モデルを使用する構成も許可する。

---

# 2. 全体アーキテクチャ

## 2.1 システム構成

```text
TranslationModelSystem
│
├── ModelRegistry
│   ├── ModelCatalog
│   ├── ModelMetadataRepository
│   ├── ModelArtifactRepository
│   └── ModelCompatibilityChecker
│
├── ModelSelectionEngine
│   ├── LanguagePairSelector
│   ├── DeviceCapabilityAnalyzer
│   ├── QualityProfileSelector
│   └── ModelFallbackSelector
│
├── ModelResourceManager
│   ├── ModelDownloader
│   ├── ModelInstaller
│   ├── ModelVerifier
│   ├── ModelLoader
│   ├── ModelUnloader
│   └── ModelVersionManager
│
├── TokenizationEngine
│   ├── TokenizerRegistry
│   ├── SentencePieceAdapter
│   ├── SubwordTokenizerAdapter
│   └── TokenizerValidator
│
├── TranslationInferenceEngine
│   ├── ModelInputAdapter
│   ├── EncoderRunner
│   ├── DecoderRunner
│   ├── SearchStrategy
│   ├── CandidateGenerator
│   └── OutputAdapter
│
├── ModelOptimizationEngine
│   ├── QuantizationManager
│   ├── MemoryOptimizer
│   └── RuntimeSelector
│
├── LongTextTranslationManager
│   ├── DocumentAnalyzer
│   ├── ChunkPlanner
│   ├── ContextPreserver
│   └── DocumentReconstructor
│
└── TranslationModelEvaluator
    ├── QualityEvaluator
    ├── PerformanceEvaluator
    ├── RegressionTester
    └── ModelBenchmarkManager
```

## 2.2 レイヤー分離
- **Model Domain Layer**：モデルのID、バージョン、言語ペア、Tokenizer、ライセンス、対応ランタイム等を管理。
- **Model Runtime Layer**：ONNX Runtime等の実行環境を管理。
- **Inference Layer**：トークン化、モデル実行、デコード。
- **Optimization Layer**：量子化、実行プロバイダー、スレッド数、メモリ制御。
- **Evaluation Layer**：モデル品質、推論速度、メモリ、回帰テスト。
- **Resource Layer**：ダウンロード、検証、インストール、更新、削除。

## 2.3 依存関係の禁止事項
- UIからONNX Sessionを直接操作しない。
- Translatorからモデル固有クラスを直接生成しない。
- ModelRegistryから推論処理を直接実行しない。
- Tokenizerをモデル情報と無関係に選択しない。
- ModelResourceManagerを通さずモデルファイルを削除しない。
- ProviderSelectorにモデル内部の実行詳細を持たせない。

---

# 3. モデル共通インターフェース

## 3.1 TranslationModel

```kotlin
interface TranslationModel {
    val metadata: TranslationModelMetadata
    suspend fun initialize(config: ModelRuntimeConfig): ModelInitializationResult
    suspend fun createSession(): TranslationModelSession
    suspend fun warmUp()
    suspend fun release()
    fun getRuntimeStatus(): ModelRuntimeStatus
}
```

モデルの初期化と個別翻訳セッションを分離する。重いパラメータや共有可能な実行リソースはモデル単位で管理し、リクエストごとの状態はTranslationModelSessionに保持する。

## 3.2 TranslationModelSession

```kotlin
interface TranslationModelSession {
    suspend fun translate(input: ModelTranslationInput): ModelTranslationOutput
    suspend fun cancel()
    suspend fun close()
}
```

セッションはリクエストのライフサイクルに対応する。並行翻訳時に入力文、言語方向、会話文脈、デコード設定、キャンセル状態、翻訳候補、一時テンソルが混在しないようにする。共有モデルの同時利用可否は、Runtimeとモデルのスレッド安全性を確認して決定する。

## 3.3 TranslationModelMetadata

```kotlin
data class TranslationModelMetadata(
    val modelId: String,
    val modelName: String,
    val modelVersion: String,
    val supportedLanguagePairs: Set<LanguagePair>,
    val modelFormat: ModelFormat,
    val modelSizeBytes: Long,
    val tokenizerId: String,
    val tokenizerVersion: String,
    val runtimeRequirements: RuntimeRequirements,
    val qualityProfile: ModelQualityProfile,
    val checksum: String,
    val license: String,
    val source: String
)
```

## 3.4 ModelFormat

```kotlin
enum class ModelFormat { ONNX, TFLITE, CTRANSLATE2, CUSTOM }
```

形式名だけでAndroid対応と判断してはならない。実際の演算子、モデル構造、メモリ、ランタイム互換性を検証する。

## 3.5 ModelRuntimeConfig

```kotlin
data class ModelRuntimeConfig(
    val executionProvider: ExecutionProvider,
    val intraOpThreads: Int,
    val interOpThreads: Int,
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val memoryLimitMb: Int,
    val enableProfiling: Boolean
)
```

Beam SizeやRepetition Penaltyなどのデコード設定はRuntime設定から分離し、TranslationRequestごとに指定できる構造とする。

---

# 4. モデル成果物・Manifest管理

## 4.1 ModelArtifactManifest

```kotlin
data class ModelArtifactManifest(
    val manifestVersion: Int,
    val modelId: String,
    val modelVersion: String,
    val revision: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val modelFiles: List<ModelFileEntry>,
    val tokenizerId: String,
    val tokenizerVersion: String,
    val tokenizerFiles: List<ModelFileEntry>,
    val runtimeFormat: ModelFormat,
    val runtimeCompatibility: RuntimeCompatibility,
    val precision: ModelPrecision,
    val quantization: QuantizationInfo?,
    val maxInputTokens: Int,
    val maxOutputTokens: Int,
    val estimatedPeakMemoryMb: Int,
    val licenseId: String,
    val licenseUrl: String,
    val datasetProvenance: String,
    val evaluationReportId: String,
    val artifactHash: String,
    val signature: String
)
```

## 4.2 ModelFileEntry

```kotlin
data class ModelFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val required: Boolean
)
```

## 4.3 Manifest検証
ロード前に、1 Manifestのバージョン 2 モデルID 3 モデルバージョン 4 ファイル一覧 5 ファイルサイズ 6 SHA-256 7 TokenizerのID・バージョン 8 言語ペア 9 ランタイム互換性 10 量子化形式 11 ライセンス情報 12 署名 を確認する。

ハッシュ＝ファイルが期待した内容と一致するか、署名＝信頼できる配布元が成果物を承認したか、と目的を分ける。署名鍵はアプリ内に秘密鍵として保存しない。

## 4.4 モデルの有効化条件
Manifest検証、Tokenizer検証、モデルロード、短いスモークテストが成功するまで、本番モデルとして有効化してはならない。

---

# 5. モデル候補の選定

## 5.1 比較対象
- **MADLAD-400**：既存の基準モデル。日→ネ／ネ→日の品質、モデルサイズ、Tokenizer、ONNX変換可否、実機速度、メモリ、量子化後の品質、ライセンスと配布条件を確認。モデルカードの記載はMagiTranslatorでの双方向品質を保証するものではない。
- **NLLB-200**：低リソース言語を含む比較対象。公開モデルカードには研究用途が主目的で本番運用・医療・法律・長文を想定しない旨の記載があるモデルがあり、代表的チェックポイントはCC-BY-NC-4.0。商用前提なら採用前にライセンスを確認。
- **mBART系**：追加学習・多言語転移の候補。言語トークン、Tokenizer、学習済み言語、ライセンス、推論構造を確認。
- **その他**：日本語・ネパール語に対応しAndroidで実行可能なモデルを追加候補にできる。

## 5.2 モデル比較表

| 評価項目 | 評価内容 |
|---|---|
| 対応言語 | 日本語・ネパール語が実際に対応しているか |
| 双方向品質 | 方向別の実測結果 |
| 意味保持 | 意味の欠落・追加・反転 |
| 文法 | ネパール語・日本語の自然さ |
| Tokenizer | 語彙カバレッジ・分割品質 |
| 容量 | ダウンロードサイズ・展開サイズ |
| 推論速度 | 実機でのp50・p95 |
| メモリ | 最大使用量 |
| 量子化 | 品質と速度の変化 |
| ライセンス | 配布・改変・商用利用条件 |
| 更新性 | モデルの入手・更新可能性 |

## 5.3 採用判定

| 評価項目 | 初期重み |
|---|---:|
| 日本語→ネパール語品質 | 20% |
| ネパール語→日本語品質 | 20% |
| 意味保持 | 15% |
| 文法・自然さ | 10% |
| 用語・固有名詞 | 10% |
| 長文性能 | 5% |
| 推論速度 | 8% |
| メモリ | 7% |
| 容量・配布容易性 | 5% |

重みは初期評価用であり、評価結果と利用目的に基づいて変更する。重大誤訳の発生率は平均スコアとは別の採用ゲートとする。

## 5.4 モデル採用の禁止事項
モデル名の知名度だけ／パラメータ数だけ／FLORES等の公開ベンチマークだけ／一方向の評価結果だけ／ONNX変換できたことだけで採用しない。量子化後の品質を未確認のまま配布しない。ライセンスを確認せず同梱しない。

---

# 6. 双方向専用評価データセット

## 6.1 評価データの構成
方向別に作成し、各方向に次の分野を含める：1 日常会話 2 家族・親族 3 人間関係 4 買い物 5 食事 6 旅行 7 仕事 8 学校・教育 9 医療 10 行政・手続き 11 交通 12 住居 13 感情・相談 14 丁寧な依頼 15 命令・禁止 16 数字・金額・日時 17 長文 18 複数話者の会話 19 音声認識由来の誤字を含む文 20 固有名詞・地名・人名。

## 6.2 評価データ形式

```json
{
  "id": "ja_ne_000001",
  "source_language": "ja",
  "target_language": "ne",
  "source_text": "明日、駅で会いましょう。",
  "reference_text": "भोलि स्टेशनमा भेटौँ।",
  "domain": "daily_conversation",
  "register": "polite",
  "difficulty": "normal",
  "critical_facts": ["time:tomorrow", "location:station", "intent:meeting"],
  "review_status": "native_reviewed"
}
```

## 6.3 データ品質管理
原文と参照訳の意味対応、訳抜け、意味の追加、数字・日時の不一致、固有名詞の誤り、文法誤り、不自然な表現、重複、言語ラベルの誤り、Unicode異常、文体・敬意レベルの不一致を検査する。ネパール語の参照訳は可能な限りネイティブ話者が確認する。

## 6.4 学習・検証・テスト分割
三者を分離する。同一文章の言い換え、同じ会話の別断片、同じ翻訳元からの合成データが異なる分割へ混入しないようにする。最終テストデータはモデル選択やハイパーパラメータ調整に使用しない。

## 6.5 個人情報・権利管理
個人情報を含む会話データは同意を得て利用する。不要な個人情報は匿名化する。データの出所を記録する。利用許諾が不明なデータを学習に使わない。データセットごとにライセンス情報を管理する。

---

# 7. Tokenizer・語彙管理

## 7.1 TranslationTokenizer

```kotlin
interface TranslationTokenizer {
    suspend fun encode(text: String, language: LanguageCode): TokenizationResult
    suspend fun decode(tokens: IntArray, language: LanguageCode): String
    fun getVocabularySize(): Int
    fun getTokenizerVersion(): String
    fun getSpecialTokens(): SpecialTokenSet
}
```

## 7.2 TokenizationResult

```kotlin
data class TokenizationResult(
    val tokenIds: IntArray,
    val originalText: String,
    val normalizedText: String,
    val tokenCount: Int,
    val warnings: List<TokenizationWarning>
)
```

## 7.3 必須検証項目
- 日本語：漢字、ひらがな、カタカナ、複合語、助詞、敬語、句読点、長音記号、英語混在、固有名詞。
- ネパール語：デーヴァナーガリー文字、Unicode結合文字、母音記号、ヴィラーマ、複合文字、数字、句読点、英語混在、固有名詞。
- 共通：空文字、改行、タブ、絵文字、記号、不正Unicode、長文、未知語。

## 7.4 Unicode正規化
正規化はTokenizerの仕様に合わせる。評価・辞書検索・文字列比較にはNFCを基本候補とする。モデルが特定の正規化方式を前提とする場合はモデル専用の正規化アダプターを使う。NFKC等の互換正規化を無条件に適用してはならない（文字の意味や表記を変える可能性があるため）。

## 7.5 Tokenizer互換性
tokenizerId、tokenizerVersion、vocabularyHash、specialTokenMapping、modelVocabularySize、languageTokenMapping のいずれかが不一致の場合、モデルをロードしない。Tokenizerを別モデルから流用してはならない。

## 7.6 語彙カバレッジ測定
方向別・言語別に、未知語率、1単語あたりの平均トークン数、1文あたりのトークン数、固有名詞の分割数、ネパール語の形態素に対する分割品質、長文のトークン増加率、数字・記号の保持率を測定する。Tokenizer変更後は回帰テストを必須とする。

---

# 8. モデル入力アダプターとLanguage IR連携

## 8.1 ModelInputAdapter

```kotlin
interface ModelInputAdapter {
    suspend fun adapt(
        request: TranslationRequest,
        analysis: LanguageAnalysisResult,
        constraints: TranslationConstraints
    ): ModelTranslationInput
}
```

## 8.2 ModelTranslationInput

```kotlin
data class ModelTranslationInput(
    val requestId: String,
    val sourceText: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val context: TranslationContext?,
    val terminology: List<TermConstraint>,
    val structuralHints: List<StructuralHint>,
    val decodingConfig: DecodingConfig,
    val maxOutputTokens: Int
)
```

## 8.3 Language IRの利用
モデルが意味構造を直接受け取れない場合、無理にJSONや独自記号を原文へ混ぜてはならない。モデルの学習方式・Tokenizer・入力形式に合わせ、必要な情報（否定、時制、敬意レベル、人物・地名、固有名詞、用語制約、文脈情報）だけをモデル対応形式に変換する。モデルが対応しない構造情報は、推論後の検証や候補評価に利用する。

## 8.4 原文の保護
モデル入力用に加工しても元の原文は保持し、翻訳結果との比較、誤訳検出、再翻訳、ログの追跡に利用する。

---

# 9. 学習データ構築・追加学習

## 9.1 学習方式
Fine-tuning、Continued Pretraining、Domain Adaptation、Back Translation、Multilingual Transfer Learning、Parameter-Efficient Fine-Tuning、Knowledge Distillation を候補として評価する。すべてを実施する必要はなく、モデル構造、学習データ、計算資源、ライセンスに応じて決定する。

## 9.2 Back Translation
1 原文を用意 2 逆方向モデルで合成訳を作成 3 品質検査 4 不自然な訳・意味欠落を除外 5 ネイティブ確認が必要なデータを抽出 6 学習用データへ登録。合成データには必ず生成元モデルとバージョンを記録する。

## 9.3 Fine-tuning
学習前後の比較を必須とする。評価対象：日→ネ、ネ→日、一般会話、専門ドメイン、数字・固有名詞、否定・時制、長文、未学習ドメイン。特定分野の向上と引き換えに一般会話の品質が大きく低下していないか確認する。

## 9.4 LoRA・PEFT
学習コストを抑える候補として検討する。ONNX形式の推論モデルへ直接LoRA学習できると仮定してはならない。基本フロー：1 学習可能なベースモデルを準備 2 学習データを検証 3 PEFT設定を作成 4 学習実行 5 検証データで評価 6 ベースモデルと比較 7 必要に応じて重みを統合 8 推論用形式へ変換 9 ONNX等へエクスポート 10 Android実機で検証。

## 9.5 Knowledge Distillation
教師モデルと生徒モデルの品質を方向別に比較する。サイズが小さくなっても意味保持や文法が大きく低下する場合は採用しない。

## 9.6 学習データの禁止事項
テストデータの学習利用／品質未確認の合成データの大量投入／ライセンス不明データの使用／個人情報を含むデータの無断利用／一方向だけの評価で学習結果を採用／学習前後の比較を行わないモデル更新。

---

# 10. 推論エンジン

## 10.1 TranslationInferenceEngine

```kotlin
interface TranslationInferenceEngine {
    suspend fun infer(
        session: TranslationModelSession,
        input: ModelTranslationInput
    ): ModelTranslationOutput
}
```

## 10.2 処理フロー

```text
ModelTranslationInput → InputValidation → InputNormalization → Tokenizer.encode → EncoderRunner
→ DecoderRunner → SearchStrategy → CandidateGenerator → Tokenizer.decode → OutputValidation → ModelTranslationOutput
```

Encoder-Decoder型でないモデルには、その構造に対応した推論アダプターを実装する。

## 10.3 EncoderRunner
入力テンソルの形状、データ型、マスク、位置情報などはモデルの仕様に従う。

## 10.4 DecoderRunner
自己回帰型モデルでは過去のKey/Valueキャッシュを利用できるか確認する（モデル構造とONNXエクスポート内容に依存）。

## 10.5 推論エラー
MODEL_NOT_FOUND、MODEL_NOT_INITIALIZED、TOKENIZER_MISMATCH、UNSUPPORTED_LANGUAGE_PAIR、INPUT_TOO_LONG、TOKENIZATION_FAILED、RUNTIME_EXECUTION_FAILED、OUT_OF_MEMORY、INFERENCE_TIMEOUT、REQUEST_CANCELLED、OUTPUT_INVALID、MODEL_CORRUPTED を区別する。単一の一般例外に変換してはならない。

---

# 11. デコード方式・候補生成

## 11.1 対応方式
Greedy Search、Beam Search、Sampling、Diverse Beam Search（対応する場合）。初期段階ではGreedyとBeamを優先する。

## 11.2 DecodingConfig

```kotlin
data class DecodingConfig(
    val strategy: DecodingStrategy,
    val beamSize: Int,
    val lengthPenalty: Float,
    val repetitionPenalty: Float,
    val noRepeatNgramSize: Int,
    val maxOutputTokens: Int,
    val earlyStopping: Boolean
)
```

## 11.3 設定の初期値
Beam Sizeは2～5程度を比較候補とする。最適値はモデル・言語方向・文章の種類・端末性能に応じて決定する。

## 11.4 反復表現の保護
日本語・ネパール語には反復で意味や強調を表す表現があるため、機械的な反復削除を禁止する。noRepeatNgramSizeやRepetition Penaltyを強く設定しすぎて自然な反復表現を破壊しないようにする。

## 11.5 TranslationCandidate

```kotlin
data class TranslationCandidate(
    val candidateId: String,
    val text: String,
    val modelId: String,
    val modelVersion: String,
    val rawScore: Float?,
    val normalizedScore: Float?,
    val generationTimeMs: Long,
    val decodingStrategy: DecodingStrategy
)
```

モデルの生成確率は翻訳品質の正解スコアではない。最終順位付けは第4部の品質評価エンジンへ委譲する。

---

# 12. ONNX Runtime・Android推論基盤

## 12.1 Runtime抽象化

```kotlin
interface ModelRuntime {
    suspend fun load(artifact: ModelArtifact, config: ModelRuntimeConfig): RuntimeLoadResult
    suspend fun execute(input: RuntimeInput): RuntimeOutput
    suspend fun close()
}
```

## 12.2 ONNX Runtime
主要な実行候補として評価する。確認事項：Android対応、CPU Execution Provider、XNNPACK、NNAPI、演算子の対応状況、動的形状、セッション初期化時間、メモリ使用量、推論速度、スレッド設定、量子化モデルの互換性。

## 12.3 Execution Provider
初期評価はCPUを基準にし、次にXNNPACKやNNAPIなどを比較する。アクセラレーションが利用可能でも必ず高速化するとは限らない（演算子が複数EPに分割されると遅くなり得る）。端末・モデル・入力長ごとに実測する。

## 12.4 Runtime設定
IntraOp/InterOp Thread数、Execution Provider、メモリ上限、プロファイリング、セッション再利用、最適化レベルを設定可能にする。スレッド数を過剰に増やしてUIや音声処理を妨げない。

## 12.5 Androidビルド
ONNX RuntimeのAndroidパッケージ採用時は、ABI、NDK、Gradle、ProGuard/R8設定との互換性を確認する。不要なExecution Providerを同梱しない。必要なネイティブライブラリがReleaseビルドで欠落しないことを検証する。

---

# 13. 量子化・推論最適化

## 13.1 比較対象
FP32、FP16、INT8、Dynamic Quantization、Static Quantization。INT4などは対象モデルと実行ランタイムが対応する場合に限り追加評価する。

## 13.2 量子化の評価
量子化前後で、日→ネ品質、ネ→日品質、意味保持、否定、数字・日時、固有名詞、文法、長文、推論速度、モデル容量、最大メモリ使用量を比較する。

## 13.3 採用基準
容量や速度が改善しても重大誤訳が増加する場合は採用しない。量子化モデルは元モデルとは別のモデルバージョンとして管理する。

## 13.4 最適化の優先順位
1 不要なモデルロードを防止 2 セッション再利用 3 Tokenizer処理の最適化 4 不要なテンソルコピー削減 5 適切なスレッド設定 6 実測に基づくExecution Provider選択 7 量子化 8 モデル構造に適したキャッシュ利用。

---

# 14. モデルリソース・メモリ管理

## 14.1 ModelResourceManager

```kotlin
interface ModelResourceManager {
    suspend fun acquire(modelId: String): ModelLease
    suspend fun release(lease: ModelLease)
    suspend fun unloadUnusedModels()
    suspend fun getResourceStatus(): ResourceStatus
}
```

## 14.2 ModelLease
使用中であることを示す管理オブジェクト。使用中のモデルを誤って解放しない。Leaseは必ず `try/finally` などの確実な解放経路で管理する。

## 14.3 同時ロード制御
同一モデルの二重ロードを防ぐ。ロード中の要求を適切に待機させる。解放中のモデルを新規利用させない。翻訳中のセッションを保持する。キャンセル時に一時リソースを解放する。メモリ圧力時は安全にモデルを解放する。

## 14.4 メモリ予算
ファイルサイズだけで必要メモリを推定しない。モデル重み、ONNX Session、Encoder出力、Decoderキャッシュ、入出力テンソル、Tokenizer、一時バッファ、複数候補生成、長文処理を考慮する。

## 14.5 OOM対策
1 ロード前にメモリ状況を確認 2 同時実行数を制限 3 不要なモデルを解放 4 最大入力長を制限 5 候補数を制限 6 必要に応じて軽量モデルへ切替 7 推論失敗を記録 8 上位のProvider管理エンジンへエラーを返す。AndroidのプロセスがOSに終了される可能性を前提とし、アプリ内の例外処理だけで必ず復旧できると考えてはならない。

---

# 15. 長文翻訳

## 15.1 基本方針

長文を単純な文字 ……（**受領原稿はここで途切れている。続きを待つ**）
