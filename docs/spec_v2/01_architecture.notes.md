# 第1部 現状コードとの整合性メモ（2026-11-25, 基準 `8a234f2` / 3.0.0-magi.7）

第1部の本文（原稿）はユーザー提示のものを正とし、ここでは**実装に着手する AI が誤解しやすい差分だけ**を挙げる。
「要決定」はユーザーの判断が必要な項目。

## A. 前提の食い違い（要決定）

| # | 原稿の記述 | 現状・確定済み判断 | 推奨 |
|---|---|---|---|
| A1 | コード例が Kotlin（data class / suspend / sealed interface / Coroutine） | アプリは **Java**（Kotlin プラグインなし、AGP 8.2.2）。SPEC v1 付録A で「Java を基本」と決定済み | **Java の不変クラス＋`ExecutorService`/`Future`（キャンセル可）で同じ契約を実装**。Kotlin 導入は別判断（ビルド設定・依存追加が必要） |
| A2 | `LanguageCode { JA, NE, AUTO }`、対応方向は JA→NE / NE→JA のみ | 現行は **ja / ne / vi の3言語**（ja⇔vi は HY-MT、D3・D4） | `VI` を残す（品質要件は ja⇔ne のみに適用）。外すならユーザー判断 |
| A3 | 「低性能 Android 端末でも可能な限り安定」 | **Android 16（minSdk 36）・Pixel 10 Pro XL 特化**（D5）。Madlad 3B は RAM 6GB 未満では実質動かない | 「対象は Android 16 以上の端末。主ターゲットは RAM 16GB 級」と明記し、低性能端末は対象外とする |
| A4 | オンライン翻訳を「自動統合」 | オンライン経路は**未実装**。v1 で「既定 OFF・同意必須」（D8）。現在の外部通信はモデル DL（`models-v1`）のみ | 既定 OFF・明示同意・送信内容の表示を第6部の必須要件にする |
| A5 | 「学習」（第3部の題） | 端末内学習の基盤はない。モデルは配布済み ONNX（Madlad int4 / HY-MT int8 / Whisper） | 第3部は「モデル選定・変換・量子化・推論最適化」とし、学習は PC 側の別工程として扱う |

## B. 現行コードとの対応（Phase 1「既存コード調査」の出発点）

| 原稿の部品 | 現行の実体 | 備考 |
|---|---|---|
| TranslationEngine / Orchestrator | `Translator.performTextTranslation`（公平ロックで直列化）＋ `jane.JaNeTranslator` | 入口は既に Translator に一本化。UI は Translator を直接呼ぶ |
| CandidateGenerator | `JaNeTranslator.jaToNe/neToJa`（whole / sentences / terms / 人名3案 / beam N-best） | N-best は `Translator.silentTranslateNBest`（ThreadLocal sink） |
| QualityEvaluator / CandidateRanker | `QualityScorer`（逆翻訳 chrF・MBR 等）＋ 辞書式選択（HARD 数 → スコア） | 重みは未校正（実測は §D） |
| OutputValidator | `TranslationValidator`（数字・否定・用語・原文文字残存・疑問・反復） | Python 版 `tools/eval/metrics.py` と同期必須 |
| TerminologyEngine / ProperNounDatabase | `Glossary`（既定語＋`jane_glossary.tsv`、逆方向注記あり）、`ProperNameResolver`、`KanaDevanagari` | 構造化辞書（SQLite）は未実装 |
| TranslationMemory | `jane.TranslationMemory`（会話単位、LRU 200、端末保存なし） | 会話終了・言語変更でクリア |
| LanguageAnalyzer（日本語） | `JaNormalizer` / `JaSegmenter` / `KanjiNumber`（規則ベース） | 形態素解析は未導入 |
| LanguageAnalyzer（ネパール語） | `NePostEditor`（NFC・danda・反復）、`TranslationValidator` の否定判定 | 形態解析は未導入 |
| ProviderRegistry / Selector | `Translator.resolveModeForPair`（ne→Madlad、ja⇔vi→HY-MT） | Provider 抽象は未導入 |
| Model Runtime | ONNX Runtime 1.30（Java API）、Madlad=`MADLAD_CACHE`、HY-MT、Bergamot（Mozilla） | 1モデルのみ常駐・ペア切替時に再ロード |
| Speech / ASR | `Recognizer`（Whisper-small 6分割、ne 制約・プロンプト・無音判定・2仮説 keep-best、任意の `Whisper_ne/`） | |
| TTS | Android 標準 TTS（`TTS.java`）。ne 音声なし端末は1回だけ通知 | |
| Resource Management | `downloader/`（`ModelSources` で `models-v1`＋SHA-256、`ZipSafety`）、`mirror-models.yml` | |
| Logging / Security | `PrivacyLog`（release で本文を出さない）、固定サイドロード鍵（要差し替え） | |

## C. 原稿どおりで良いが、既存の決定と表現を合わせる点

- §2.1「品質最優先」＝ D2（速度より精度）と一致。§2.3「モデル非依存」は現行では未達（Translator が Madlad/HY-MT の内部を直接扱う）。
- §7.2 正規化：現行は日本語入力に **NFKC**（全角英数の統一のため）、ネパール語に **NFC**。原稿の「基本 NFC、NFKC は限定」と整合させるなら、日本語の NFKC 適用範囲（英数字・記号のみ）を第2部で定義する。
- §7.9 最終選択：「致命的問題を優先除外」＝ 現行の辞書式選択（HARD→スコア）と同じ思想。
- §9 エラー分類：現行は `ErrorCodes`（int 定数、`ERROR_MISSING_MADLAD` 等）。新分類への写像表を第14部に置く。
- §10 移行：「一括削除禁止・段階移行」は現行の進め方と一致。

## D. 実測で分かっている事実（第5部・第13部に反映すべき）

`docs/eval/2026-11-25_madlad-vs-jane-v1-v3-seed.md`（参照訳は未検証の seed 40 文、PC 実行）:

- ne→ja：jane v3 で chrF++ 40.2→44.6、数字保持 66.7%→100%。
- ja→ne：jane は素の Madlad を**下回る**（63.6→60.8）。原因は逆翻訳採点が意味のずれた候補を選ぶこと（例：先生はもう帰られました→「あなたの先生は家にいます」）。
  → 第5部では「逆翻訳 chrF を主評価にしない」（v1 §7.4）を再確認し、参照なし品質推定（QE）か意味要素の照合を主にする設計が必要。
- 評価は **ネイティブ検証済みの参照訳** がない限り参考値（seed は AI 作成・未検証）。

## E. 未定義のまま残っている項目（後続の部で定義が必要）

`TranslationStyle`, `PrivacyMode`, `TranslationPriority`, `SpeakerRole`, `AudienceType`, `RelationshipType`, `RegisterType`,
`QualityAssessment`, `TranslationWarning`, `ExecutionMetadata` の各定義と、`translateBatch` の並列度・順序保証、
`cancel` の対象範囲（推論途中の ONNX セッションを止められるか：ORT Java では `RunOptions.setTerminate` が必要）。
