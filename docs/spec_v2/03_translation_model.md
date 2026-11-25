# MagiTranslator 総合詳細機能実装仕様書
## 第3部：翻訳モデル・機械翻訳基盤・学習・推論最適化【全面改訂版】

> 本文はユーザー提示の原稿（前半＋続編 §15.1〜§24 を受領。**続編の §15 は量子化で、前半で始まりかけた「§15 長文翻訳」は本文が無い**＝notes A6）。見出し・型定義・表は原文どおり、箇条書きの一部は一文に要約して収録。冒頭の前書き（§0.1〜§1.2 の重複版）は改訂版本文と同内容のため本文側に統合。現状との差分は `03_translation_model.notes.md`。

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

# 15. 量子化・推論最適化の詳細（続編）

## 15.1 量子化方式の選択基準
サイズ縮小だけを目的に選ばない。翻訳品質、推論速度、ピークメモリ、端末互換性を総合評価する。

| 方式 | 主な用途 | 注意事項 |
|---|---|---|
| FP32 | 基準モデル・品質比較 | メモリ消費が大きい |
| FP16 | 対応ハードウェアでの高速化 | CPU・端末によっては利点が小さい |
| INT8 | モバイル向け軽量化 | 量子化による翻訳品質の変化を検証する |
| INT4 | 大型モデルのメモリ削減 | 演算子対応と品質劣化の検証が必須 |

### 15.1.1 適用順序
1 FP32/元モデルの基準品質を測定 2 FP16版の品質・速度・メモリを測定 3 INT8版を同一評価データで比較 4 INT4はINT8でもメモリ要件を満たせない場合などに限定 5 方式ごとに独立した成果物として管理 6 量子化後は元モデルの評価を引き継がず必ず再評価。

### 15.1.2 量子化品質ゲート
量子化前後で、意味保持率、否定の保持率、数値・日付・時刻の一致率、固有名詞の保持率、敬語・丁寧さの保持率、ネパール語の文法的正確性、用語辞書への適合率、長文の一貫性、翻訳不能・空出力・異常反復の発生率を比較する。BLEU等の単一指標だけで採否を決めない。

### 15.1.3 量子化成果物の識別
元モデルID・revision・checksum、量子化方式、量子化ツールとバージョン、量子化設定、Calibration Dataset ID、評価データセットID、量子化前後の評価結果、対応Runtimeバージョン、対応Execution Provider、既知の制限事項を記録する。設定が異なる成果物を同一モデルIDで上書きしない。

## 15.2 量子化キャリブレーション
日本語・ネパール語の実入力分布を反映したデータを使う。最低限：日常会話、短文・長文、敬語・丁寧語、否定・疑問・命令、数字・日時・金額、固有名詞、英語混在文、デーヴァナーガリー文字、日本語の省略表現、ネパール語の格標識・後置詞、文脈を要する文。学習データとの重複を検査する。個人情報を含む会話ログを無断使用しない。ユーザーの翻訳履歴を自動転用しない。出所・許諾・匿名化を記録し、バージョン管理する。

## 15.3 推論最適化の段階的適用
1 モデルロード時間 2 Tokenizer時間 3 Encoder時間 4 Decoderの1tokenあたり時間 5 全体時間 6 メモリ 7 同時実行時の競合 8 EP比較 9 量子化比較 10 最適化後の品質回帰試験、の順に測定する。測定結果を残さずに最適化を採用しない。

### 15.3.1 ONNX Runtime Execution Provider
候補：CPU、XNNPACK、NNAPI、条件が整えばQualcomm QNN等。初期はCPUを基準経路として確保し、他は実機で演算子対応・モデル分割・メモリ・速度を比較して採否を決める。特定EPが使えなくても翻訳機能全体が停止してはならない。

### 15.3.2 Execution Provider選択
参照情報：OSバージョン、CPU ABI、SoC、利用可能アクセラレータ、ORTバージョン、モデルの演算子構成、量子化形式、過去の実測、メモリ制限、省電力設定。端末名だけの固定判定は禁止。

```kotlin
interface ExecutionProviderSelector {
    suspend fun select(model: ModelArtifactManifest, device: DeviceRuntimeProfile, policy: InferencePolicy): ExecutionProviderDecision
}

data class ExecutionProviderDecision(
    val provider: ExecutionProviderType,
    val fallbackProviders: List<ExecutionProviderType>,
    val reason: String,
    val requiresBenchmark: Boolean
)
```

### 15.3.3 初回実行と継続実行
初回起動時に大規模ベンチマークを強制しない。必要なら短時間の安全な動作確認を行い結果をキャッシュする。モデルrevision・Runtime・EPの変更、OS更新等の大きな環境変化、異常なベンチ結果でキャッシュを無効化する。

---

# 16. メモリ管理・同時実行・キャンセル・ライフサイクル

## 16.1 ModelRuntimeManager

```kotlin
interface ModelRuntimeManager {
    suspend fun acquire(modelId: String, priority: InferencePriority): ModelLease
    suspend fun releaseUnusedModels()
    suspend fun getRuntimeStatus(): RuntimeStatus
    suspend fun shutdown()
}
```

## 16.2 ModelLease

```kotlin
interface ModelLease : AutoCloseable {
    val modelId: String
    val session: TranslationModelSession
    val isValid: Boolean
    override fun close()
}
```

モデル利用は必ずLeaseを取得してから行う。Lease有効中は他処理がセッションを解放しない。`close()` は複数回呼んでも安全にする。

## 16.3 モデルロード状態

```kotlin
enum class ModelLifecycleState {
    NOT_INSTALLED, DOWNLOADING, VERIFYING, INSTALLED, LOADING, READY, IN_USE, EVICTING, FAILED, CORRUPTED
}
```

状態遷移は一元管理し、不正遷移を検知したらエラーを記録して安全な状態へ復帰する。

## 16.4 同時実行制御
同じセッションを無条件に共有しない。スレッドセーフと確認できない場合はセッション単位で排他。推奨：モデル単位のロード管理、セッション単位の実行制御、リクエスト単位のキャンセル、優先度付き実行キュー、メモリ圧迫時の実行数制限。音声通訳などのリアルタイム処理は長文翻訳より高い優先度を設定できるようにする。

## 16.5 キャンセル処理
Kotlin Coroutineのキャンセルに対応。1 新規推論の投入停止 2 中断可能なら中断 3 中断できないネイティブ推論は安全な境界で停止 4 中間テンソル解放 5 Lease解放 6 キャンセル状態を返す。キャンセルされたリクエストの結果を後からUIへ反映しない。

## 16.6 メモリ予測
ロード前に、モデルファイル、Runtime内部、Encoder/Decoder中間テンソル、KV Cache、Tokenizer、入出力バッファ、同時実行、アプリ本体を推定し、余裕を加えて利用可能メモリと比較する。不足予測時は 1 不要モデル解放 2 同時実行数削減 3 軽量モデル選択 4 長文を小チャンクに分割 5 許可済みの別Providerへ切替。OOM後もプロセスが継続できるとは仮定しない。

## 16.7 Android Lifecycle
Activity/Serviceの破棄とセッション寿命を直接結び付けず、アプリケーションスコープのRuntime管理下に置く。ただしバックグラウンドで大きなモデルを保持し続けない。メモリ圧迫通知を受けたら利用中でないモデルを優先的に解放する。

---

# 17. モデル配布・検証・更新・ロールバック

## 17.1 ModelArtifactRepository

```kotlin
interface ModelArtifactRepository {
    suspend fun getInstalledModels(): List<InstalledModelInfo>
    suspend fun download(manifest: ModelArtifactManifest, progress: (DownloadProgress) -> Unit): DownloadResult
    suspend fun verify(modelId: String): ArtifactVerificationResult
    suspend fun activate(modelId: String, revision: String): ActivationResult
    suspend fun rollback(modelId: String): RollbackResult
    suspend fun deleteUnused(modelId: String): DeleteResult
}
```

## 17.2 配布パッケージ
Manifest、モデル本体、Tokenizer、Tokenizer設定、言語ID定義、Runtime設定、ライセンス文書、checksum、署名情報、制限事項、評価レポート。Tokenizerとモデルの組み合わせが一致しないパッケージを有効化しない。

## 17.3 ダウンロード処理
1 Manifest取得 2 署名検証 3 容量確認 4 一時ディレクトリ作成 5 ダウンロード 6 サイズ検証 7 checksum検証 8 パッケージ構成検証 9 Tokenizer互換性検証 10 Runtime互換性検証 11 インストール領域へ移動 12 有効化。途中のファイルを実行可能なモデルとして扱わない。

## 17.4 Atomic Update

```text
models/
  model-id/
    revisions/
      revision-a/
      revision-b/
    active.json
    previous.json
    staging/
```

利用中のモデルを直接上書きしない。新revisionをstagingに保存し、検証完了後に有効revisionを切り替える。失敗時は旧revisionを維持する。

## 17.5 署名・整合性検証
checksum＝破損検出、署名＝配布元の真正性。署名対象の定義を固定し、公開鍵の信頼起点をアプリ側で管理し、署名鍵の更新手順を定義する。署名不正時はインストールを拒否する。checksumだけを真正性保証として扱わない。

## 17.6 ロールバック
新revisionのロード失敗、Tokenizer不整合、Runtimeクラッシュ、異常な空出力、品質回帰の検出、重大な不具合報告で可能にする。ロールバック後は失敗状態を記録し、自動再有効化を防ぐ。

## 17.7 ModelResourceManager連携

```kotlin
interface ModelResourceManager {
    suspend fun resolveBundle(modelId: String, languageDirection: LanguageDirection): ModelResourceBundle
    suspend fun validateCompatibility(bundle: ModelResourceBundle): CompatibilityResult
}
```

モデル・Tokenizer・辞書・言語資源は互換性情報を共有し、モデルだけを更新して整合性を失う状態を防ぐ。

---

# 18. エラー分類・Fallback・ログ・プライバシー

## 18.1 エラー分類

```kotlin
enum class TranslationEngineErrorCode {
    MODEL_NOT_INSTALLED, MODEL_DOWNLOAD_FAILED, MODEL_CHECKSUM_MISMATCH, MODEL_SIGNATURE_INVALID,
    MODEL_LOAD_FAILED, TOKENIZER_MISMATCH, LANGUAGE_ID_UNSUPPORTED, RUNTIME_UNAVAILABLE,
    EXECUTION_PROVIDER_UNAVAILABLE, INPUT_TOO_LONG, TOKEN_LIMIT_EXCEEDED, INFERENCE_TIMEOUT,
    INFERENCE_CANCELLED, OUT_OF_MEMORY, INVALID_MODEL_OUTPUT, DECODING_FAILED,
    RESOURCE_INCOMPATIBLE, INTERNAL_ERROR
}
```

すべてを INTERNAL_ERROR へ変換しない。原因、復旧可能性、利用者への表示内容を分離する。

## 18.2 Fallback方針
1 同一モデルの別EP 2 同一モデルのCPU実行 3 軽量ローカルモデル 4 既存Translator互換経路 5 ユーザーが許可した場合に限りオンラインProvider。STRICT_LOCALではオンラインProviderを候補に含めない。

## 18.3 プライバシー制約
モデル基盤は外部送信の可否を独自判断しない（上位のPrivacyGuard・Provider管理層が決定）。STRICT_LOCAL指定の原文、機密指定の原文、送信未許可の翻訳履歴、ローカル専用の辞書・個人用語、認証情報・APIキーを外部へ送信しない。ローカル品質が低いことを理由に許可なくオンライン再翻訳しない。

## 18.4 ログ
本番ログに原文・翻訳文・音声認識結果を出力しない。記録可能：requestId、modelId、modelRevision、languageDirection、推論時間、token数、選択EP、エラーコード、fallback理由、メモリ推定、結果ステータス。入力内容の診断はユーザーが明示的に許可した場合に限り、別の安全な手順で扱う。

## 18.5 Retry制御
ロード・ダウンロード・推論の再試行を区別する。推論エラーを無制限に再試行しない。OOM後に同じ設定で即時再実行しない。ダウンロード再試行は回数制限。一時的通信エラーは指数バックオフ＋jitter。署名不正・checksum不一致は再試行で解決したと扱わない。

---

# 19. 品質評価・ベンチマーク・回帰試験

## 19.1 基本方針
日→ネとネ→日を独立した評価対象とし、片方向から逆方向を推定しない。候補比較は同一テストセット・同一条件・同一の出力後処理で行う。

## 19.2 評価データセット

| カテゴリ | 評価対象 |
|---|---|
| 日常会話 | 自然さ・意味保持 |
| 家族・人間関係 | 敬意・親密さ・呼称 |
| 仕事 | 丁寧さ・専門表現 |
| 医療 | 症状・否定・数量 |
| 行政 | 固有名詞・制度用語 |
| 旅行 | 場所・日時・依頼 |
| 数字・金額 | 数値保持 |
| 長文 | 文脈・照応・一貫性 |
| 音声認識誤り | 誤認識への頑健性 |
| 混在言語 | 英語などの混在 |
| 敬語 | 丁寧さ・上下関係 |
| 曖昧表現 | 文脈依存の意味選択 |

利用許諾、作成者、作成日、修正履歴を付与する。

## 19.3 自動評価指標
BLEU、chrF++、COMET、COMETKiwi、必要に応じ他の品質推定モデル。日本語・ネパール語の全領域で適切とは限らず、特にCOMET系は適合性を事前検証する。人間評価の代替として無条件に使わない。

## 19.4 人間による評価
ネパール語母語話者かつ日本語の十分な理解者が評価する。項目：1 意味が正しく伝わるか 2 否定・肯定の逆転 3 数字・日時 4 固有名詞 5 敬意・丁寧さ 6 文法的自然さ 7 不要な情報の追加 8 情報の欠落 9 文脈との整合性 10 実会話で使えるか。評価者間で異なる場合は単純平均で済ませず評価基準の曖昧さを確認する。

## 19.5 エラー分類
意味の取り違え、主語・目的語の誤り、否定の反転・欠落、時制・相の誤り、敬語・丁寧さの誤り、数値の誤り、固有名詞の誤り、用語の不一致、語順の不自然さ、文法誤り、情報の欠落、原文にない情報の追加、文脈の不整合、Unicode・Tokenizer由来の破損、長文分割による意味の断絶。

## 19.6 性能評価
ダウンロード時間、ロード時間、初回翻訳時間、2回目以降の時間、1tokenあたりDecoder時間、p50/p95 latency、最大メモリ、CPU使用率、消費電力の参考値、長時間安定性、同時実行時の性能、モデル解放時間。端末機種、OS、RAM、SoC、Runtime、モデルrevision、量子化方式を記録する。

## 19.7 品質ゲート
両方向の評価完了／重大な意味反転が許容範囲内／数値・否定・固有名詞の回帰確認／母語話者評価完了／メモリ要件充足／対象端末で安定動作／ライセンス確認／モデル・Tokenizer整合性確認／旧モデルとの比較結果保存、をすべて満たす場合に限り採用する。数値基準は初期評価の実測を基準に設定し、根拠のない固定スコアを埋め込まない。

---

# 20. 既存Translatorからの移行計画

## 20.1 移行方針
既存の翻訳機能を一度に削除しない。段階的に導入し、品質・安定性・性能の比較を経て移行する。

## 20.2 LegacyTranslatorAdapter

```kotlin
class LegacyTranslatorAdapter(
    private val legacyTranslator: LegacyTranslator
) : TranslationModel {
    override suspend fun translate(request: ModelTranslationRequest): ModelTranslationResult {
        // 既存Translatorへの変換と呼び出し
        TODO("Implement legacy compatibility mapping")
    }
}
```

インターフェース例であり、実際の既存クラス・メソッド名に合わせて実装する。

## 20.3 移行フェーズ
- **Phase A 現状把握**：呼び出し元、入出力形式、モデル・Tokenizer・辞書の依存関係を調査し、既存テストと実測性能を保存。
- **Phase B 新基盤の追加**：TranslationModel、LegacyTranslatorAdapter、ModelRuntimeManager、ModelArtifactRepositoryを追加。既存UIから新基盤を直接参照させない。
- **Phase C 並行評価**：新旧を同一テストセットで評価、実機性能、エラー率、ネイティブ評価。
- **Phase D 段階的切り替え**：開発用設定で有効化→内部テスト→限定環境で検証。旧経路へ戻せる状態を維持。
- **Phase E 旧経路の整理**：基準を満たした後に限り削除を検討。

## 20.4 互換性維持
翻訳要求・結果の基本形式、エラー通知、キャンセル動作、設定値の読み込み、既存UIとの接続を維持する。内部実装の変更を理由に既存機能を無断で削除しない。

---

# 21. テスト要件

- **21.1 Unit**：Manifest解析・検証、言語コード変換、Tokenizer設定、ModelSelectionEngine、ExecutionProviderSelector、Decoding設定、入力長制御、チャンク分割、エラー分類、Fallback判定、Lease管理、モデル状態遷移。
- **21.2 Integration**：Repository↔Runtime、Tokenizer↔モデル互換性、ONNXロード、Encoder/Decoder接続、KV Cache再利用、モデル更新、ロールバック、Legacy Adapter、第2部Language IR接続、第4部用語制約API接続。
- **21.3 Device**：ローエンド／ミドル／ハイエンド、RAM容量違い、CPUのみ、NNAPI等アクセラレータあり、OSバージョン違い。特定端末での動作を全端末の保証として扱わない。
- **21.4 Stress**：連続翻訳、長文連続、高頻度キャンセル、同時投入、モデル更新中の要求、メモリ圧迫中のロード、バックグラウンド移行、画面回転・Activity再生成、ネットワーク切断中のモデル管理。
- **21.5 Security**：不正Manifest、不正署名、checksum不一致、不完全ダウンロード、パストラバーサル、異常に大きなファイル、不正Tokenizer設定、不正言語ID、外部送信禁止状態でのProvider切替。
- **21.6 Regression**：既存テストセットを保存し導入前後で比較。否定の消失、数値の変化、ネパール語の反復表現の削除、固有名詞の変化、敬語の不自然な変化、文末表現の欠落、文の途中切断、Unicode文字化けを検出する。

---

# 22. 受入条件

コードが追加されたことだけでは完了と判定しない。

- **22.1 アーキテクチャ**：モデル実装と呼び出し元の分離、モデル固有処理のAdapter閉じ込め、Tokenizer互換性検証、Manifestベースの追加・更新、既存Translator互換経路の維持。
- **22.2 翻訳品質**：日→ネ・ネ→日の評価完了、母語話者評価、否定・数値・固有名詞の回帰試験通過、長文・文脈依存文の評価完了。
- **22.3 実行性能**：対象端末でロード成功、推論中メモリの測定、p50/p95記録、キャンセル・同時実行・解放の安全動作、EP失敗時にCPU等へ安全に切替。
- **22.4 モデル管理**：途中成果物を使わない、署名・checksum検証、Atomic Update、更新失敗時の旧モデル復帰、モデル・Tokenizer・辞書の互換性確認。
- **22.5 プライバシー**：STRICT_LOCALで外部Providerを使わない、許可なく原文を外部送信しない、本番ログに原文・訳文を出さない、認証情報をログに出さない。
- **22.6 リリース判定**：ライセンス確認、実機テスト、品質評価、署名検証、ロールバック試験、プライバシー試験、重大な回帰の修正、のいずれかが未完了なら完成としない。

---

# 23. 実装AIへの厳守指示

## 23.1 既存コードの調査
default branch、HEAD commit、Gradle構成、minSdk/targetSdk、Kotlin/Javaバージョン、既存Translator、モデルファイル形式、Tokenizer、ONNX Runtime依存、ダウンロード処理、Room等の永続化構成、既存テストを確認する。仕様書のクラス名が既存コードに存在すると決めつけない。

## 23.2 minSdkの扱い
minSdk 36などを根拠なく強制設定しない。既存値・対応端末・ライブラリ・必要APIを調査し、変更が必要なら理由と影響を明記する。

## 23.3 モデルの採用
MADLAD-400、NLLB-200、mBART系等を名前やパラメータ数だけで採用しない。対象言語と方向、実際の品質、revision、Tokenizer、言語ID、モデルカード、ライセンス、商用利用条件、Runtime互換性、メモリ要件、実機性能を確認する。NLLB-200の公開チェックポイントにはCC-BY-NC-4.0等の非商用制限のあるものがあり、商用の可能性があるアプリに利用条件の確認なしで組み込まない。

## 23.4 未検証の主張を禁止
「精度が向上した」「高速化した」「すべてのAndroid端末で動作する」「INT8でも品質が変わらない」「NNAPIで必ず高速化する」「ネパール語に完全対応」「オフライン翻訳が完全に保証される」を実測なしで記載しない。実測済み・実装済み・テスト済み・未検証を区別する。

## 23.5 既存機能の保護
旧Translatorを品質ゲート通過前に削除しない。既存UIを理由なく変更しない。既存設定を無断で初期化しない。翻訳履歴を無断で削除しない。ローカル翻訳をオンライン必須に変更しない。STRICT_LOCALを弱めない。

## 23.6 実装手順
1 既存構成の調査 2 インターフェース定義 3 Manifest・モデル管理 4 Tokenizer統合 5 LegacyTranslatorAdapter 6 ModelRuntimeManager 7 ONNX Runtime統合 8 1つ目のモデルAdapter 9 翻訳品質評価 10 メモリ・性能最適化 11 モデル更新・ロールバック 12 第2部Language IRとの接続 13 第4部用語制約APIとの接続 14 回帰試験 15 リリース判定。一度に全機能を実装して原因追跡を困難にしない。

## 23.7 実装報告
変更したファイル、追加したクラス・インターフェース、変更した依存関係、採用モデルとrevision、ライセンス確認結果、実装済み機能、未実装機能、実行したテスト、テスト結果、実機検証端末、翻訳品質評価結果、性能測定結果、既知の問題、次の作業。テストを実行していない場合は成功と報告しない。

---

# 24. 第4部との接続仕様

第4部では辞書・用語・翻訳メモリを統合する。モデル内部に辞書機能を無理に埋め込まず、外部の用語制約システムと接続できる構造を提供する。

## 24.1 TermConstraintProvider

```kotlin
interface TermConstraintProvider {
    suspend fun resolveConstraints(request: TermConstraintRequest): TermConstraintResult
}
```

## 24.2 TermConstraintRequest

```kotlin
data class TermConstraintRequest(
    val sourceText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val domain: String?,
    val languageContext: LanguageAnalysisResult?,
    val conversationContextId: String?,
    val strictness: ConstraintStrictness
)
```

## 24.3 TermConstraintResult

```kotlin
data class TermConstraintResult(
    val constraints: List<TermConstraint>,
    val warnings: List<String>,
    val source: ConstraintSource
)

data class TermConstraint(
    val sourceTerm: String,
    val preferredTargets: List<String>,
    val prohibitedTargets: List<String>,
    val context: String?,
    val priority: Int,
    val confidence: Float,
    val isMandatory: Boolean
)
```

## 24.4 制約の適用方法
モデルの能力に応じて選ぶ：1 モデルが正式に対応するConstrained Decoding 2 Adapterが対応する語彙・トークン制約 3 入力前処理による用語情報の整理 4 候補生成後の用語適合性評価 5 必要な場合の後処理。対応していない制約をプロンプトに足すだけで守られると扱わない。`isMandatory = true` を満たせなかった場合は成功扱いにせず、検証警告または失敗情報を返す。

## 24.5 第2部Language IRとの接続
モデルは `LanguageAnalysisResult` を入力として受け取れるようにする。構造化IRを直接入力できないモデルでは、Adapterが必要な情報を抽出して入力形式へ変換する。変換時に意味情報を勝手に追加・削除しない。

## 24.6 第4部への引き渡し条件
第4部は、文脈依存辞書、固有名詞辞書、専門用語辞書、表記ゆれ統合、翻訳メモリ、用語の敬意・丁寧さ、品詞・活用情報、用語制約の優先順位、用語候補の信頼度、翻訳後の用語適合性評価を提供する。第3部のTranslationModelインターフェースを変更せず、TermConstraintProviderを追加接続できることを基本方針とする。

---

# 第3部 完了条件

1. モデル非依存の翻訳インターフェース
2. モデル・Tokenizer・Runtimeの互換性管理
3. ONNX Runtime Android実行基盤
4. モデルロード・解放・同時実行管理
5. 量子化と性能評価の仕組み
6. モデル配布・署名検証・更新・ロールバック
7. 日本語→ネパール語の品質評価
8. ネパール語→日本語の品質評価
9. 既存Translatorとの互換性
10. STRICT_LOCALと外部送信制御の維持
11. 第2部Language IRとの接続
12. 第4部TermConstraintProviderとの接続
13. 自動テスト・実機テスト・品質評価レポート

**最終原則：** 翻訳モデルを導入しただけで高精度化したと判断してはならない。日本語とネパール語の両方向で、実際の利用文を用いた品質評価を行い、既存機能より有効であることを確認してから標準モデルとして採用する。
