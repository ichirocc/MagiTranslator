# MagiTranslator 総合詳細機能実装仕様書
## 日本語 ⇔ ネパール語 特化型・高精度ハイブリッド翻訳システム

| 項目 | 内容 |
|---|---|
| 文書種別 | 総合開発・機能実装仕様書（他社 AI／開発者がそのまま設計・解析・実装・テストに着手するための単一の正本） |
| 対象 | MagiTranslator（Android、RTranslator 3.00 の派生） |
| リポジトリ | `ichirocc/MagiTranslator`（既定ブランチ `main`） |
| 主対象言語 | 日本語 ⇔ ネパール語（副対象：日本語 ⇔ ベトナム語。§2.3） |
| 最優先目標 | 翻訳精度・音声認識精度・翻訳結果の信頼性 |
| 処理方式 | ローカル処理を基本とし、オンライン処理を任意で併用するハイブリッド |
| 基準コミット | 版 `3.0.0-magi.4`（versionCode 31）。§19 の状況列を参照 |
| 最終更新 | 2026-11-24 |

---

## 0. この文書の使い方（最初に読む）

1. **§1–§2 が方針の正本**。他の節と矛盾したら §1–§2 を優先する。
2. **§3「現状（As-Is）」は実在するコードの事実**。存在しないクラス・API を前提に実装してはならない。新設する物は §9 以降で「新設」と明記する。
3. **§4「確定済みの設計判断」**は過去の検討で決着した事項。再提案・再議論しない（覆すには §20 の実測が必要）。
4. **§19「既知の不具合」**は着手時にまず直す対象。ファイルと行の目安を付けてある（行は変動するので grep で再確認すること）。
5. 「改善した」と報告できるのは §20 の手順で**実測した**ものだけ。机上の推論・PoC だけで改善済みと書かない。

---

## 1. 目的と基本方針

### 1.1 目的
MagiTranslator を、日本語⇔ネパール語の相互翻訳に特化した、高精度で実用的な Android 翻訳アプリへ発展させる。主用途は **1 台のスマホを挟んだ対面会話（WalkieTalkie モード）**、次いでテキスト翻訳。

重点的に改善する能力：
1. 日→ネ／ネ→日の翻訳精度（長文・複文・会話文を含む）
2. 音声認識（ASR）精度、および ASR 結果を経由した翻訳精度
3. 固有名詞・人名・地名・専門用語・数字・日時・金額の正確さ
4. ネパール語の文法・語順・敬語（तपाईं/तिमी/तँ）・動詞活用の正確さ
5. 日本語の省略・助詞・敬語・文脈依存表現への対応
6. 翻訳結果の一貫性、オフラインでの安定動作

### 1.2 最重要方針：精度のための大規模再設計を許可する
- 既存コードの温存を目的にしない。翻訳精度・ASR 精度を本質的に上げるために必要なら、クラス・アルゴリズム・データ構造・処理順序・モジュール構成・モデル連携方式を**大規模に変更してよい**。
- 軽微な修正やパラメータ調整だけで済ませない。必要ならパイプラインの再構築、言語解析・文脈解析・候補生成/評価の新設、辞書・ASR パイプラインの再設計、モデルの追加・差し替えを行う。
- ただし**大規模改修そのものは目的ではない**。すべての変更は、§20 で測定可能な品質改善、または安定性・保守性・効率の改善に結び付けること。改善が測れない／負の変更は入れない（「安全」と「有益」は別物）。

### 1.3 品質と速度
速度・軽量化のために精度を犠牲にしない（ユーザー決定）。ただし処理時間・メモリ・電池は計測し、品質を落とさない範囲で改善する。

---

## 2. 優先順位とスコープ

### 2.1 優先順位

| 優先 | 項目 | 方針 |
|---|---|---|
| P0 | 翻訳精度／意味的正確性 | 最優先 |
| P0 | 音声認識精度 | 最優先 |
| P0 | 既存の重大不具合（§19） | 最優先 |
| P0 | データ保護・セキュリティ | 必須 |
| P1 | 言語解析・文脈解析、辞書・言語資源 | 大幅強化 |
| P1 | 翻訳候補生成・品質評価 | 強化（既存 jane を土台） |
| P1 | ローカル翻訳・ローカル ASR | 基本機能として維持 |
| P1 | オンライン AI 連携 | 精度向上が実測できる場合のみ。既定 OFF |
| P1 | 長文・会話翻訳 | 強化 |
| P2 | TTS 読み上げ品質 | 正確性・明瞭性優先 |
| P2 | UI・操作性 | 片手・最小操作で改善 |
| P2 | メモリ・CPU・電池 | 品質を損なわない範囲 |

### 2.2 対象端末
- **Android 16（API 36）以降のみ**（minSdk 36 / targetSdk 36 / compileSdk 36）。
- 主ターゲット端末：**Pixel 10 Pro XL**（RAM 16GB）。arm64-v8a のみ。
- 16KB ページサイズ端末に対応すること（§17.4）。

### 2.3 言語スコープ
- 主：日本語 ⇔ ネパール語（本仕様の品質要件はこの方向に適用）。
- 副：日本語 ⇔ ベトナム語（既に実装済みのため維持。品質要件は「現状を劣化させない」まで）。
- 言語選択肢は ja / ne / vi の 3 つに限定（実装済み）。

---

## 3. 現状（As-Is）— 実在するコードの事実

### 3.1 ビルド・配布
| 項目 | 現状 |
|---|---|
| 言語 | Java（一部 C++/NDK）。Kotlin ではない |
| ビルド | Gradle 8.12 / AGP 8.2.2 / NDK 28.0.12674087 / CMake 3.22.1 / JDK 17 |
| applicationId | `com.magi.translator`（本家 `nie.translator.rtranslator` と並行インストール可）。namespace は `nie.translator.rtranslator` のまま |
| ネイティブ依存 | git submodule 4 本（sentencepiece, bergamot-translator, protobuf, fastText。本家 v3.00 と同一コミット） |
| 推論 | ONNX Runtime Android 1.30.0、onnxruntime-extensions 0.13.0 |
| CI | `.github/workflows/release-build.yml`：assembleRelease → 16KB ELF 整列レポート → `MagiTranslator-<versionName>.apk` を Artifact に保存 → **main への push で GitHub Release を自動作成**（tag `v<versionName>`） |
| 署名 | `app/magi-sideload.jks` 固定鍵（個人サイドロード用、§19.8） |
| ローカル検証 | Android SDK 無しの環境では Java 純ロジック（`jane` パッケージ）のみ `javac` でテスト可能。それ以外は CI |

### 3.2 処理パイプライン（現状）
```text
[音声] Recorder → Recognizer(Whisper-small ONNX, 6 分割モデル)
          ├ 2 言語同時デコード(batch=2)で話者言語を自動判定（WalkieTalkie）
          └ ネパール語: 制約付き＋制約なしの 2 仮説 keep-best（§11.2）
     ↓
[翻訳] Translator.performTextTranslation
          ├ 言語ペアでモデル自動選択: ne を含む→Madlad(MADLAD_CACHE) / ja⇔vi→HY-MT（DL 済みなら）
          ├ ja⇔ne かつ Madlad → jane ライブラリ（候補生成＋逆翻訳リランキング）
          └ それ以外 → 本家の分割・NN 翻訳
     ↓
[出力] 画面表示 / TTS(システム TTS) / 振動通知（Android 16 envelope）
```

### 3.3 主要ファイル
| ファイル | 役割（現状） |
|---|---|
| `voice_translation/neural_networks/translation/Translator.java` | 翻訳の中核。モデルロード、ペア別モデル切替（`resolveModeForPair`/`switchModelSync`）、jane 呼び出し（`translateJaNeWithLibrary`/`silentTranslate`）、beam search |
| `voice_translation/neural_networks/voice/Recognizer.java` | Whisper デコード。ネパール語制約（`constrainedLogits`）、プロンプト（`NE_PROMPT_IDS`）、無音判定、keep-best（`nepaliHardScore`） |
| `jane/*.java` | 日⇔ネ特化ライブラリ（純 Java、Android 非依存）。§3.4 |
| `Global.java` | 言語既定値（ja / ne）、言語一覧（ja/ne/vi 固定）、設定 |
| `downloader/Downloader.java`, `DownloaderService.java` | モデル DL・ZIP 展開・通知（Android 16 ProgressStyle） |
| `LoadingActivity.java` | 起動時の DL 状態と翻訳モデル設定の整合 |
| `res/raw/whisper_ne_allowed_tokens.bin` | ネパール語で許可する Whisper トークン（873/51865）のビットセット |

### 3.4 jane ライブラリ（`app/src/main/java/nie/translator/rtranslator/jane/`）
| クラス | 現状の機能 |
|---|---|
| `Backend` | NMT 呼び出しの抽象（実体は Translator の Madlad を UI 通知なしで呼ぶ） |
| `JaNormalizer` | NFKC、話し言葉フィラー除去（区切り後のみ）、漢数字→数字（`KanjiNumber`）、文末補完 |
| `JaSegmenter` | 句点＋丁寧体文末（ます/です…＋空白）で分割 |
| `KanaDevanagari` | カタカナ→デーヴァナーガリー規則音訳（拗音・促音・撥音・長音） |
| `Glossary` | 既定 6 語（日本/ネパール/東京/大阪/カトマンズ/ポカラ）＋ユーザー TSV `files/jane_glossary.tsv`。訳語を原文へ埋め込む（目標語注釈）。カタカナ＋敬称は音訳 |
| `QualityScorer` | 逆翻訳 chrF(n=4, β=2)、数字保持、必須用語、文字種純度、敬語整合（です/ます→तिमी/तँ 減点）、長さ比 |
| `NePostEditor` | NFC、`.`→`।`、句読点前空白除去、1–3 語の連続反復除去（**§19.6 の不具合あり**） |
| `JaNeTranslator` | 候補（terms / whole / sentences[+terms]、最大 4）→翻訳→後処理→候補 2 以上なら逆翻訳採点＋MBR(候補 3 以上, 重み 0.3) → 最良を採用 |
| テスト | `app/src/test/java/.../jane/JaNeLibraryTest.java`（main 実行、28 項目） |

**注意：QualityScorer の重み（-0.15 / -0.10 / -0.5 / -0.2 / MBR 0.3）は実データで未校正の暫定値**（§7.5）。

### 3.5 モデル
| 用途 | モデル | 備考 |
|---|---|---|
| ASR | Whisper-small 244M（ONNX, int8 部分量子化, KV キャッシュ, 6 分割） | ネパール語は語彙上の弱点あり（完全なデーヴァナーガリー字トークンが 21 個のみ） |
| 翻訳(ne) | Madlad 3B int4（`MADLAD_CACHE`） | ne を扱える唯一の同梱候補 |
| 翻訳(vi) | HY-MT 1.5 1.8B int8 | ja⇔vi 用。ne 非対応 |
| 翻訳(軽量) | Mozilla Bergamot | ne 非対応。音声モード高速化オプション用 |
| TTS | Android システム TTS | 端末の TTS 対応に依存 |

---

## 4. 確定済みの設計判断（再議論しない）

| # | 判断 | 根拠 |
|---|---|---|
| D1 | **英語ピボット翻訳はしない**（日⇔ネは直接翻訳） | ユーザー決定 |
| D2 | 精度優先・速度は二の次（日⇔ネは beam≥4、候補＋逆翻訳を許容） | ユーザー決定 |
| D3 | ネパール語を含むペアは Madlad、ja⇔vi は HY-MT（DL 済みなら）。モデルは 1 つだけ RAM に常駐し、ペア切替時に再ロード | Madlad 以外に ne 対応なし |
| D4 | 言語は ja/ne/vi の 3 つに限定、初期値 ja ⇔ ne、初回起動は WalkieTalkie 画面 | ユーザー決定（主用途＝対面会話） |
| D5 | Android 16+ 専用（minSdk 36） | ユーザー決定 |
| D6 | ネパール語 ASR は「制約付き＋制約なし」2 仮説 keep-best。採否は辞書式（HARD→スコア） | MAGI 最適化器の受理規則を移植 |
| D7 | 言語判定（ja vs ne の 2 言語同時デコード）のスコアは**制約前の生 logits** で計算する | 制約後で正規化すると ne が水増しされ日本語が ne と誤判定される（修正済みの不具合） |
| D8 | ローカル処理が基本。オンラインは任意・既定 OFF・プライバシー同意必須 | §14 |
| D9 | 精度の主張（「市販ソフト以上」等）は §20 の比較実測が済むまでしない | 事実に基づく報告 |
| D10 | MAGI 勤務最適化エンジン（magi7ichiro-fork）の探索アルゴリズム（SA/ALNS/CP-SAT 等）は翻訳・ASR に流用しない。流用するのは「複数候補＋統一採否規則」「実測で採否」という考え方のみ | 問題構造が異なる |

---

## 5. 目標アーキテクチャ（To-Be）

### 5.1 レイヤ構成
```text
Presentation : TranslationUI / VoiceTranslationUI(WalkieTalkie) / ConversationUI / DictionaryUI / SettingsUI / ModelManagerUI
Application  : TranslationOrchestrator / SpeechOrchestrator / ConversationManager / LanguageDirectionManager / ResourceManager
Language     : JapaneseLinguisticAnalyzer / NepaliLinguisticAnalyzer / ContextAnalyzer / SemanticAnalyzer
               TerminologyManager / ProperNameResolver / GrammarRuleEngine
Translation  : ProviderManager(Local/Online) / CandidateGenerator / CandidateRanker / TranslationValidator
               ConsistencyChecker / PostEditor(Ja/Ne) / LongTextProcessor / TranslationMemory / TranslationCache
Speech       : AudioCapture / VAD / SpeechPreprocessor / AsrProviderManager(Local/Online) / AsrResultAnalyzer
               SpeechCorrection / TtsProviderManager
Data         : BilingualDictionary / TerminologyDB / ProperNameDB / GrammarRules / EvaluationDataset
Model        : Translation/ASR/TTS models + metadata(version, sha256, license)
Infra        : NetworkManager / ModelDownloader / IntegrityValidator / CacheManager / Logging(PII-safe) / PrivacyManager
```

### 5.2 既存コードとの対応（移行方針）
| To-Be | 既存の出発点 | 方針 |
|---|---|---|
| TranslationOrchestrator | `Translator.performTextTranslation` ＋ `JaNeTranslator` | Translator から「モデル実行」以外の責務を剥がして Orchestrator へ |
| Local Translation Provider | `Translator` の ONNX 実行部 | `Backend` を正式な Provider インターフェースへ昇格 |
| CandidateGenerator / Ranker | `JaNeTranslator` / `QualityScorer` | 拡張（§7） |
| Ja 正規化・分割 | `JaNormalizer` / `JaSegmenter` | 拡張・形態素解析導入（§4.4 相当→§6.1） |
| PostEditor(Ne) | `NePostEditor` | 不具合修正後に文法検証を追加 |
| ProperNameResolver | `Glossary` ＋ `KanaDevanagari` | 辞書優先・候補比較化 |
| Local ASR Provider | `Recognizer` | Provider 化、デコード改善（§11） |
| ModelDownloader | `Downloader` / `DownloaderService` | セキュリティ修正（§19.1）と整合性検証 |

### 5.3 共通インターフェース（Java。既存は Java のため Kotlin 化は任意）
```java
public interface TranslationProvider {
    String id();
    boolean supports(String srcLang, String tgtLang);
    TranslationResult translate(TranslationRequest request) throws TranslationException; // 呼出しはバックグラウンドスレッド
}
public final class TranslationRequest {   // 不変
    String sourceText, sourceLanguage, targetLanguage;
    List<String> context;                 // 直近の発話（必要分のみ）
    String conversationId;
    Map<String,String> terminology;       // ja → ne
    Mode mode;                            // TEXT / VOICE
    int beamSize; long timeoutMs; CancellationSignal cancel;
}
public final class TranslationResult {
    String translatedText, providerId, modelId;
    List<Candidate> candidates;           // 採点内訳つき（ログはデバッグビルドのみ）
    List<ValidationIssue> issues;         // §7.3
    long processingTimeMs;
    Double calibratedConfidence;          // 校正済みのときだけ非 null（§7.5）
}
```

**ASR も同様**に `AsrProvider`（`recognize(AudioChunk, langHints) → AsrResult{text, nBest, perTokenLogProb, noSpeechProb, langScores}`）を定義する。

---

## 6. 言語解析

### 6.1 日本語（`JapaneseLinguisticAnalyzer`、新設）
- 形態素解析を導入する（候補：Kuromoji/Sudachi の Java 実装。APK サイズ・ライセンス・速度を比較して選定）。
- 解析対象：助詞・助動詞、活用、敬語（丁寧/尊敬/謙譲）、主語・目的語の省略、時制、否定、疑問、条件、推量、依頼・命令、受身・使役、複文・従属節、口語、ASR 誤認識。
- 省略された主語は**会話文脈から確定できる場合のみ**補う。確定できない情報は補完しない。
- 既存 `JaNormalizer`・`JaSegmenter` はこの解析結果を使う形へ置き換える（単純な正規表現分割に依存しない）。

### 6.2 ネパール語（`NepaliLinguisticAnalyzer`、新設）
- 対象：デーヴァナーガリー、NFC 正規化、音節（書記素クラスタ）、語境界、語幹・接尾辞、格・後置詞（-ले, -लाई, -मा, -बाट, -को）、動詞活用（人称・数・敬意・時制・アスペクト）、否定、疑問、敬意レベル、語順（SOV）、口語・表記揺れ、外来語。
- 文字数・長さは `char` 数ではなく**書記素クラスタ**（`BreakIterator.getCharacterInstance`）で数える。
- 自然さのために原文の意味・情報を変えてはならない。

### 6.3 文脈・意味解析
- `ContextAnalyzer`：多義語（かける/取る/出す/つける/大丈夫/よろしく/結構です/お願いします 等）の訳語を、現在文・直近の会話・話題・話者・方向・用語辞書から選ぶ。辞書の第一訳を機械的に採用しない。
- `SemanticAnalyzer`：主体・対象・動作・時制・肯否・条件・因果・比較・発話行為（疑問/依頼/命令）・数量/日時/単位を抽出し、翻訳後の欠落・追加検出（§7.3）に使う。
- 会話履歴は翻訳に必要な範囲だけ保持（既定：直近 N=5 発話、アプリ終了で破棄）。文脈が不十分なら推定の確度を下げる。

---

## 7. 翻訳候補生成・品質評価

### 7.1 処理フロー（目標）
```text
入力検証 → 方向判定 → Unicode 正規化 → 言語解析 → 文脈解析 → 固有名詞・用語検出 → 辞書照合
→ 翻訳単位の決定（LongTextProcessor） → 候補生成 → 候補正規化 → 品質評価 → 検証 → 最良選択
→ 後処理 → 最終検証 → 結果
```
この順序は初期案。実装・評価結果に応じて変更してよい。

### 7.2 候補生成（`CandidateGenerator`）
- 生成源：ローカル NMT（入力バリエーション：全文／文単位／用語注入／固有名詞音訳注入）、beam の N-best（**現状は 1-best のみ返す。N-best を返すよう Translator の beam 実装を拡張するのが最優先の改善候補**）、オンライン NMT（任意）、辞書・文法ルール。
- すべての文に多候補は不要。曖昧・低信頼・長文・専門性の高い文を優先して追加候補を作る。

### 7.3 検証（`TranslationValidator`）
検出項目：数字・日付・時刻・金額・単位の変化、人名・地名の変化、否定の消失／肯否反転、主語・目的語の欠落、時制変化、条件関係の消失、文の欠落、意味の追加、不自然な文字混在、Unicode 異常、文字化け。
検出結果は `TranslationResult.issues` に入れ、重大なものは UI で警告できるようにする。

### 7.4 評価（`CandidateRanker`）
- 評価項目：意味的一致、文法、語彙、文脈整合、固有名詞、用語、ネパール語の語順、日本語の自然さ、情報の欠落・追加、表記、方向の整合。
- **文字列類似度だけで意味的正確性を判定しない**。逆翻訳 chrF は「重大な意味変化・欠落の検出」の補助指標であり、逆翻訳が原文に近いことだけを根拠に正しいと判定しない。
- 採否は辞書式を基本とする：①§7.3 の重大違反（数字消失・否定反転・用語欠落）が少ない → ②総合スコア。
- MBR（候補間合意）は維持。将来は COMET 系の参照なし品質推定（QE）モデルの端末実行を検討（サイズ・速度を実測して判断）。

### 7.5 スコアの校正
- 現行の重みは暫定。§20 の評価データ（ネイティブ評価付き）で校正してから確定する。
- 根拠のない信頼度（「95%」等）を UI に出さない。校正前は `calibratedConfidence = null`。

### 7.6 長文・会話
- `LongTextProcessor`：文境界・節・意味のまとまりで分割（文字数だけで分割しない）。順序・文脈・用語・固有名詞・分割位置を保持し、元の順序で再構成。Madlad の入力窓（約 200 トークン）を超えないこと。
- `ConversationManager`：発話者・時刻・原文・ASR 原文・補正後テキスト・訳文・方向・会話 ID・用語を保持。用語は会話内で訳語を固定（`TranslationMemory`）。

---

## 8. ネパール語後処理（`NePostEditor`）

- 対象：語順、文法不整合、後置詞、動詞活用、敬意表現、不自然な連結、Unicode 異常、空白、句読点（`।`）、英語混在。
- **反復の扱い（必須）**：ネパール語には意味を持つ反復（धेरै धेरै「とても」、बिस्तारै बिस्तारै「ゆっくり」、घर घर「家々」等）がある。**機械的な重複除去は禁止**。除去は「同一 n-gram が 3 回以上連続」など明確な生成ループに限定し、2 回の反復は保持する（§19.6）。
- 敬語の整合：原文が丁寧体なら तपाईं 系の活用（-नुहुन्छ 等）を優先。代名詞だけの置換は動詞活用と不整合になるため禁止（候補選択で解決する）。
- NFC 正規化。デーヴァナーガリー数字とアラビア数字は意味上同一として扱う（表示はアラビア数字を既定）。

---

## 9. 既存クラスの改修方針

| クラス | 必須改修 |
|---|---|
| `JaNeTranslator` | Orchestrator へ責務移管、`TranslationRequest/Result` 化、検証（§7.3）統合、タイムアウト・キャンセル、N-best 対応 |
| `JaNormalizer` | 形態素解析ベースに移行。フィラー誤削除防止（現状「あの人」は保持できているがテスト拡充）、漢数字変換の誤変換防止（一緒・一般 等） |
| `JaSegmenter` | 形態素解析ベースの文境界。句読点なし ASR 出力、複文、敬語の誤分割防止 |
| `NePostEditor` | §8・§19.6 |
| `QualityScorer` | §19.3、校正（§7.5） |
| `KanaDevanagari` | 対応表拡充（ヴァ行・ティ/ディ等の外来音）、辞書優先、音訳/原文維持/意味訳の候補比較（`ProperNameResolver`） |
| `Glossary` | 構造化辞書（§10）へ移行。TSV 読み込みは互換維持 |
| `Translator` | モデル実行専任化。§19.2 の不具合修正 |
| `Recognizer` | Provider 化、§11 |
| `Downloader` | §19.1 |

既存に同等機能がある場合は重複実装せず、統合する。

---

## 10. 辞書・言語資源

### 10.1 双方向辞書エントリ
```text
DictionaryEntry { entryId, sourceTerm, targetTerm, sourceLanguage, targetLanguage, partOfSpeech,
                  meanings[], examples[], contextTags[], grammarTags[], formalityLevel, domain,
                  alternativeTranslations[], sourceReference, license, version }
```
- 形式：SQLite（端末内、読み取り専用の配布 DB ＋ ユーザー DB の 2 層）。
- 既存 `jane_glossary.tsv` はユーザー DB への取り込みで互換維持。

### 10.2 用語・固有名詞
- `TerminologyManager`：ドメイン（医療・行政・法律・教育・仕事・日常 等）別。会話内の訳語固定。
- `ProperNameResolver`：既知は辞書優先。未知は「音訳／原文維持／意味訳」の候補を作り §7.4 で比較。音訳だけで最終訳を決めない。

### 10.3 言語資源の調査と品質管理
- 調査対象：ネパール語文法・形態論・構文論・敬語・口語・地域差、日ネ対訳コーパス、日ネ MT 研究、ネパール語 ASR 研究（例：FLORES-200 の npi_Deva、OpenSLR SLR54 等）。
- 成果は辞書・文法ルール・評価データ・前後処理・モデル選定に**実装として反映**する（文書保存だけで終わらせない）。
- 取り込み前に必ず確認：ライセンス・利用条件・出典・更新日・正確性・重複・誤訳・表記揺れ・**評価データとの混入（データ汚染）**。ライセンス不明のデータは組み込まない。

---

## 11. 音声認識（ASR）

### 11.1 方針
ASR 誤りをそのまま翻訳に渡さない。認識・補正・翻訳を連携させる。元の認識結果は必ず保持する。

### 11.2 実装済みのネパール語対策（維持）
| 対策 | 内容 | 根拠 |
|---|---|---|
| 文字制約 | 許可トークン（`whisper_ne_allowed_tokens.bin`）以外を argmax 候補から除外。スコアは生 logits | — |
| 反復抑制 | 繰り返しペナルティ 1.2、3-gram 再出現禁止 | — |
| プロンプト | `<\|startofprev\|>` ＋「म नेपाली बोल्छु।」（20 トークン）でヒンディー語化を抑制 | Peng et al. 2023 |
| 無音判定 | no_speech>0.6 かつ 平均 logprob<-1 → 棄却 | Whisper 論文 / Careless Whisper 2024 |
| keep-best | 制約付き／制約なし 2 仮説、HARD（ループ・空・外字数）→ logprob | D6 |

注意：プロンプトは「धन्यवाद」等の無音時ハルシネーション頻出語を含めない。

### 11.3 次の改善（優先順）
1. **ネパール語ファインチューン Whisper への差し替え**（最大の精度レバー）。候補例：`ilprl/Whisper-Small-Nepali-ASR-OSLR54`、`Dragneel/whisper-small-nepali` 等。本家と同じ 6 分割 ONNX（initializer / encoder / decoder / cache_initializer / cache_initializer_batch / detokenizer）へ変換するスクリプトを作成し、WER/CER を現行と比較して採否。ライセンス確認必須。
2. ビームサーチ（現状 greedy）。
3. 温度フォールバック（ループ・低 logprob 時に再デコード）。
4. VAD 改善・音声前処理（ゲイン・ノイズ）。
5. 補正（`SpeechCorrection`）：N-best・辞書・固有名詞・文脈で補正。**発話されていない内容を追加しない**。
6. 日本語 ASR も同様に評価（固有名詞・数字・文末欠落）。

### 11.4 途中結果と確定結果
途中の認識結果を確定翻訳として扱わない。確定時のみ翻訳・振動・TTS を実行（実装済み）。

---

## 12. TTS
- 優先：読み上げ内容の正確性 > 聞き取りやすさ > 安定性 > 低コスト > オフライン > 速度。ネイティブ同等の自然さは必須としない。
- 検証順：Android 標準 TTS（ne-NP 音声の有無を確認）→ ローカル TTS（例：MMS-TTS npi 等、ライセンス確認）→ 低コストオンライン TTS（任意）。
- **ネパール語 TTS 非対応端末でも、言語一覧からネパール語を消さない**こと（翻訳・表示は可能なため）。TTS 失敗で翻訳を止めない。

---

## 13. モデル・辞書の事前導入（ResourceManager / Downloader）
- 管理対象：翻訳・ASR・TTS モデル、辞書、用語、固有名詞、文法ルール、設定、バージョン。
- Downloader 要件：進捗（Android 16 `Notification.ProgressStyle`、実装済み）、一時停止・再開・再試行、通信断からの復旧、サイズ確認、**SHA-256 検証**、**Zip Slip 対策**（§19.1）、完了状態と「利用可能」状態の区別、不完全ファイル削除、空き容量確認、使用中モデルを壊さない更新（別ディレクトリに展開→原子的に切替）。
- モデル管理画面：名称・用途・対応言語・版・サイズ・DL/インストール/更新/利用可能状態・容量。
- 現状、ne 翻訳には **Madlad の手動 DL が必要**。初回起動の DL 対象に Madlad を含めるか、未 DL 時に DL を促す導線を設ける（UX 必須改善）。

---

## 14. ハイブリッド（ローカル＋オンライン）
- `ProviderManager` が、ローカル可否・通信状態・設定・文の特性（長さ・曖昧さ・専門性・ローカル信頼度）から Provider を選ぶ。
- オンライン利用は**既定 OFF**。ON にする場合は送信対象（テキスト／音声）・送信先・目的を明示し同意を得る。特定サービスに依存しない（Provider は交換可能）。
- 障害時（タイムアウト・再試行上限・API 制限・停止・不正応答・認証エラー）はローカルへ自動フォールバック。アプリ全体を止めない。
- API キーはソースに埋め込まない（ユーザー入力→Android Keystore で暗号化保存）。

---

## 15. 音声通訳（WalkieTalkie）
```text
話者の発話 → VAD → ASR(2 言語同時判定) → 補正 → 言語解析 → 翻訳(jane) → 検証 → 表示 → 振動 → TTS
```
- 逆方向も同等。リアルタイム性と精度のバランスは設定で調整可能にする（既定＝精度優先）。
- 日⇔ネの jane は候補＋逆翻訳で数倍の時間がかかる。待ち時間を UI で明示（処理中表示）し、確定まで次の録音を妨げないこと。

---

## 16. UI・操作性（P2）
- 片手・最小操作。主要操作（話す／言語入替／再読み上げ／コピー）は 1 タップ。
- Madlad 未 DL 時はネパール語選択時に DL 導線を表示。
- 翻訳警告（§7.3 の重大検出）は控えめなアイコンで表示。
- UI 文言は日本語化（`values-ja/strings.xml` 未作成。211 文字列）。

---

## 17. Android 実装要件

### 17.1 着手前の調査
Gradle 設定、SDK、AndroidX、依存、推論環境、モデル保存場所、翻訳・音声の呼び出し経路、権限、バックグラウンド処理を確認してから実装する。

### 17.2 スレッド・状態
- 翻訳・ASR・モデルロード・DL をメインスレッドで実行しない。
- 共有状態（モデル切替、無音フラグ、キャッシュ）はロックまたは単一スレッド Executor で直列化する（§19.2）。キャンセルとメモリ解放を管理する。

### 17.3 メモリ
遅延ロード、必要時解放、キャッシュ上限、同時推論数制御、長文のストリーミング。Pixel 10 Pro XL（16GB）では Whisper RAM 削減モードは既定 OFF。

### 17.4 Android 16 対応（実装済み・維持）
- 予測型「戻る」有効。`GeneralActivity` で `OnBackPressedCallback` から既存 `onBackPressed()` へ委譲（targetSdk 36 では override が呼ばれないため）。
- Live Update 通知（`Notification.ProgressStyle`、promoted ongoing を要求）。
- envelope 触覚（`VibrationEffect.BasicEnvelopeBuilder`、非対応端末は TICK）。
- 16KB ページ：`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`、jniLibs 圧縮格納（`useLegacyPackaging true`）、CI で ELF LOAD 整列をレポート。将来 AGP ≥ 8.5.1 に更新したら非圧縮＋zip 16KB 整列へ移行を検討。
- edge-to-edge：既存レイアウトは `fitsSystemWindows` 対応。新規画面も insets を処理すること。

---

## 18. プライバシー・セキュリティ
- 会話内容の不要な外部送信・保存をしない。音声データは既定で保存しない。
- **リリースビルドのログに会話内容・ASR 結果・翻訳候補を出さない**（§19.4）。
- API キー・署名パスワードをソースに置かない（§19.8）。
- DL ファイルの整合性検証、ZIP 展開のパストラバーサル防止。
- 履歴の削除手段を提供し、オンライン利用状況を明示する。

---

## 19. 既知の不具合（着手時に最初に直す）

各項目は基準コミット `fdfe9d3` で確認済み。修正には回帰テストを付けること。

| # | 重要度 | 場所 | 問題 | 修正方針 |
|---|---|---|---|---|
| 19.1 | **高** | `downloader/Downloader.java`（ZIP 展開ループ、`new File(path + filename)`） | Zip Slip（`../` を含むエントリで保存先外へ書き込める） | `destFile.getCanonicalPath()` が展開先の canonical path で始まるか検証、違反エントリは中止。SHA-256 検証を追加 |
| 19.2 | 中 | `Translator.java`：`janeTranslator` 生成時の lambda が最初の `beamSize` を捕捉 | 以後 beam 設定変更が反映されない | Backend 呼び出しごとに現在値を渡す（Request に beamSize を持たせる） |
| 19.2b | 中 | `Translator.java`：`silentNN`（volatile 共有フラグ） | 並行翻訳時に UI 通知の抑止が他リクエストへ波及／漏れ | リクエスト単位のリスナー引数で抑止。フラグ廃止 |
| 19.2c | 中 | `Translator.java`：`switchModelSync` | 翻訳中の別スレッドがセッションを使用中に close される可能性 | 翻訳実行とモデル切替を同一の直列 Executor に載せる |
| 19.3 | 中 | `jane/QualityScorer.java`：`foreignLetterRatio` | 英字の固有名詞・略語（Wi-Fi, ID 等）を不当に減点 | 原文に存在するラテン文字列は除外して計算 |
| 19.4 | 中 | `Translator.java`（`Log.i("result","Translation input: "…)`、`Log.i("jane", …)`）、`Recognizer.java`（`Log.i("result","result: "…)`） | 会話内容が Logcat に出る | `BuildConfig.DEBUG` ガード、またはログ出力ラッパで本文をマスク |
| 19.5 | 低 | `jane/JaNormalizer.java` 経由の `KanjiNumber` | 「一日中」「一人で」は正しく数値化するが、慣用表現（一人前 等）も数字化される | 形態素解析導入時に慣用表現辞書で除外 |
| 19.6 | **高（精度）** | `jane/NePostEditor.java`：`collapseRepeats` | 1 語の 2 連続を削除するため धेरै धेरै・बिस्तारै बिस्तारै 等の意味ある反復を壊す | 3 回以上の連続のみ除去、2 回は保持。テスト追加 |
| 19.7 | 低 | `jane/KanaDevanagari.java` | ヴァ/ヴィ等・ツァ行・クァ等の外来音が未対応（null を返し音訳されない） | 対応表拡充、辞書優先化 |
| 19.8 | 中 | `app/build.gradle` の `signingConfigs.sideload`、`app/magi-sideload.jks` | 公開リポジトリに鍵とパスワードがある（個人サイドロード専用と割り切った暫定措置） | ストア配布や第三者配布を行う前に、鍵を GitHub Secrets／環境変数へ移し、鍵を再発行。既存インストールは署名変更で上書き不可になる点を利用者へ告知 |
| 19.9 | 低 | `LoadingActivity`／初回 DL | ne に必要な Madlad が初回 DL 対象でない | §13 の導線追加 |
| 19.10 | 低 | `res/values-ja` なし | UI が英語 | 日本語リソース追加 |


### 19.A 対応状況（3.0.0-magi.4）
| 項目 | 状況 | 実装 | テスト |
|---|---|---|---|
| 19.1 Zip Slip | **修正済** | `downloader/ZipSafety.resolve`（canonical path 検証、絶対パス拒否）を展開ループで使用。.tmp も同じ解決先 | `ZipSafetyTest`（受理3／拒否5）CI 実行 |
| Downloader 配列範囲（startIndex） | **修正済** | 範囲チェック後に配列アクセス、負値は 0 | — |
| 19.2 beam の固定 | **修正済** | `janeBeamSize` を呼び出しごとに更新、backend lambda はフィールドを参照 | 要実機 |
| 19.2b silentNN 競合 | **修正済** | `ThreadLocal<Boolean> SILENT`（呼び出しスレッド単位、入れ子も復元） | 要実機 |
| 19.2c モデル切替と並行翻訳 | **修正済** | `performTextTranslation` を公平 `ReentrantLock` で直列化（翻訳とモデル切替が同一ロック下） | 要実機。並行2翻訳が直列になるため待ち時間は増える（精度・安全優先） |
| 19.3 英字固有名詞の減点 | **修正済** | 原文にあるラテン語は外字に数えない。chrF 前に NFC | JaNeLibraryTest |
| 数字の正規化 | **修正済** | NFKC・デーヴァナーガリー数字・桁区切り・小数・先頭0 | JaNeLibraryTest |
| 19.4 会話内容のログ | **修正済** | `tools/PrivacyLog`（debuggable 以外は長さのみ）。Translator/Recognizer/Bluetooth/WalkieTalkie | 要実機 |
| 19.5 漢数字の誤変換 | **修正済** | 「度」「番」を対象外、慣用句（一人前・一日中 等）を保護 | JaNeLibraryTest |
| 正規化後の空文字 | **修正済** | フィラーのみ→空。Translator は空訳を返す（フィラーを訳さない） | JaNeLibraryTest |
| JaSegmenter の誤分割 | **修正済** | 走査型に再実装：括弧内・「ますます」・接続助詞（が/けど/から/ので…）では分割しない | JaNeLibraryTest |
| 19.6 反復の誤削除 | **修正済** | 2回の反復は保持。1語の3回以上→2回、2〜3語句の3回以上→1回 | JaNeLibraryTest（धेरै धेरै 等） |
| 19.7 カタカナ外来音 | **修正済** | ヴァ行・ツァ行・クァ行・ティ/ディ/デュ/テュ・イェ・小書き仮名 | JaNeLibraryTest |
| 意味検証（§7.3 の一部） | **新設** | `jane/TranslationValidator`：数字の消失/追加、否定の消失/追加（日・ネ双方）、必須用語欠落、原文文字の残存、疑問の消失。候補選択を「HARD 数→スコア」の辞書式に変更 | JaNeLibraryTest |
| 19.8 署名情報 | **一部対応** | 環境変数／Gradle プロパティ／CI Secrets（`MAGI_KEYSTORE_BASE64`, `MAGI_KEYSTORE_PASSWORD`, `MAGI_KEY_ALIAS`, `MAGI_KEY_PASSWORD`）を優先。未設定時のみ暫定公開鍵 | 鍵の再発行と Secrets 登録は所有者作業 |
| Release Lint | **対応** | `checkReleaseBuilds true`（報告のみ、`abortOnError false`） | CI |
| minify / shrinkResources | **見送り** | ONNX Runtime・JNI・Gson の反射利用があり、keep ルール検証なしの有効化はクラッシュの危険 | — |
| 19.10 UI 日本語化 | **対応** | `res/values-ja/strings.xml`（209 件） | 要実機 |
| 19.9 Madlad 導線 | 未対応 | — | — |
| §7.2 beam N-best 候補（3.0.0-magi.5） | **新設** | `executeCacheDecoder` が要求時（`ThreadLocal NBEST`）に完了文を確率順で返す。`Backend.translateNBest` → jane が単一区間入力で上位3仮説を候補化し、辞書式選択（HARD→スコア＋MBR） | JaNeLibraryTest（否定を保つ第2仮説が選ばれる） |

---

## 20. 品質評価・ベンチマーク

### 20.1 評価データ
- 両方向（ja→ne / ne→ja）。分類：日常会話、挨拶、質問、依頼、感情、敬語、家族、仕事、医療、行政、教育、買い物、旅行、数字・金額、日時、固有名詞、長文、複文、否定、条件、口語、ASR 由来文。
- 規模目安：各方向 500 文以上（うち 100 文はネイティブ参照訳つきの開発用、残りは凍結テスト用）。公開データ（FLORES-200 jpn_Jpan↔npi_Deva 等）を基盤にし、会話・介護/仕事場面の独自文を追加。
- ASR：日本語・ネパール語の音声（静音／雑音、早口、数字・固有名詞を含む）各 1 時間以上。

### 20.2 指標
- 翻訳：chrF++、BLEU（参考）、COMET 系、用語・固有名詞・数字・日時の正確率、情報欠落率・追加率、ネイティブ評価（意味・文法・自然さ・敬意・誤解・欠落・追加の 7 項目、5 段階）。
- ASR：WER、CER、固有名詞・数字認識率、雑音条件別、処理時間、メモリ。
- 単一指標で品質を決めない。

### 20.3 手順
1. **改修前のベースラインを測定・記録**（現行 `3.0.0-magi.3-a16`）。
2. 変更ごとに同じテストセットで A/B。採否は「最終品質（final）」を主指標とする。
3. 比較対象として市販翻訳（Google 翻訳等）の出力も同じセットで記録する。「市販以上」はこの比較で優位が示された場合にのみ主張できる。
4. 結果は `docs/eval/` に日付・コミット・モデル・指標で記録する。

### 20.4 測定の道具
- 純 Java 部分：`javac` で `JaNeLibraryTest` を実行（Android 不要）。
- モデル込みの評価：端末または Python（onnxruntime）で同一 ONNX を回す評価スクリプトを `tools/eval/` に置く（新設）。

---

## 21. テスト要件
- 単体：正規化、分割、辞書検索、固有名詞、文法ルール、Unicode、数字変換、後処理（反復保持を含む）、品質評価、Provider 選択、モデル管理、Zip Slip。
- 統合：ja→ne、ne→ja、ローカル／オンライン／ハイブリッド、ローカル／オンライン ASR、ASR→翻訳連続、TTS、長文、会話、オフライン切替、モデル切替（ne⇔vi の連続切替）。
- 回帰：代表翻訳ケースを毎回実行（CI に組み込む）。
- 異常系：ネットワーク断、タイムアウト、モデル破損、DL 中断、ストレージ不足、メモリ不足、空文字、超長文、不正 Unicode、無音、ASR 失敗、同時リクエスト、連続要求、バックグラウンド移行。
- 実機：Pixel 10 Pro XL（Android 16）で、戻るジェスチャー（終了確認が出る）、Live Update 通知、振動、16KB ページ（開発者オプション）を確認。

---

## 22. 開発工程

| Phase | 内容 | 成果物 |
|---|---|---|
| 1 | 既存コード調査（本書 §3 の再確認）＋ベースライン測定 | 依存図、問題一覧、ベースライン |
| 2 | §19 の不具合修正（特に 19.1, 19.6, 19.2 系, 19.4） | 修正＋回帰テスト |
| 3 | 翻訳の再設計：Orchestrator / Provider / Request・Result / Validator / N-best | 新アーキテクチャ |
| 4 | 言語解析（日本語形態素、ネパール語形態）、文脈・意味解析 | Analyzer 群 |
| 5 | 辞書・言語資源（SQLite 辞書、用語、固有名詞）、評価データ整備 | 辞書 DB、評価セット |
| 6 | ASR 改良：ネパール語 FT Whisper 変換・比較、ビーム、補正 | 変換スクリプト、比較結果 |
| 7 | ハイブリッド（任意）、TTS 検証 | ProviderManager、TTS 調査結果 |
| 8 | ResourceManager・Downloader 強化、モデル管理 UI、Madlad 導線 | — |
| 9 | 総合評価（自動指標＋ネイティブ評価＋市販比較）、実機・性能測定 | 評価レポート |
| 10 | リリース準備：鍵の移行（19.8）、lint、ログ確認、互換性確認 | リリース |

各 Phase の終了条件は「§20 の測定で劣化がないこと（改善を主張する場合は改善が測定されていること）」。

---

## 23. 完成判定（受け入れ条件）

### 翻訳
- ja→ne／ne→ja がローカルで動作し、オンライン不能時も動作する。オンライン翻訳は設定に応じて利用できる（既定 OFF）。
- 辞書・文脈・用語が翻訳に反映され、会話内で訳語が一貫する。
- 長文が順序を保って翻訳される。固有名詞が辞書優先で処理される。
- 意味の欠落・追加（数字・否定・用語を含む）を検出できる（§7.3）。
- 品質評価の重みがテストデータで校正されている（§7.5）。

### ASR
- 日本語・ネパール語の ASR がローカルで動作する。補正が動作し、元の認識結果が保持される。
- ASR→翻訳が連続して動作する。WER/CER が記録されている。

### TTS
- 日本語読み上げが動作する。ネパール語の対応状況が確認・記録されている。TTS 失敗で翻訳が止まらない。

### モデル・資源管理
- モデル・辞書を事前導入できる。ダウンロードが安全（Zip Slip 対策・SHA-256 検証）で、再開できる。
- バージョン管理ができ、不完全なモデルを利用しない。ne 利用時に Madlad 未 DL なら導線が出る。

### 安定性
- メインスレッドを長時間ブロックしない。並列処理の競合を防止している（§19.2 系の解消）。
- メモリ不足・通信障害に対応する。既存機能の回帰テストが通過する。
- セキュリティ上の重大な問題（§19 の「高」）が残っていない。リリースビルドのログに会話内容が出ない。
- Android 16（Pixel 10 Pro XL）実機で主要フローがクラッシュせず動作する。

### 品質改善
- 改修前後の比較結果がある（翻訳方向別・文章分類別・モデル別）。ASR の比較結果がある。
- ネイティブ話者による評価を実施している。
- 品質改善が確認できない変更は再検討する（入れない／戻す）。

**品質目標値**は Phase 1 のベースラインと評価データの規模を踏まえて設定する。根拠のない精度目標を設定し、それを達成したことにしてはならない。

---

## 24. 実装時の禁止事項
1. 既存コードを十分に調査せずに大規模な置き換えを行わない。
2. 逆に、既存コードの温存を理由に必要な再設計を避けない。
3. 精度向上目的の変更を、軽微なリファクタリングで済ませない。
4. 辞書の単語置換だけで文全体の翻訳精度が上がると考えない。
5. 文字列類似度だけで翻訳品質を判断しない。
6. 逆翻訳だけで翻訳の正しさを保証しない。
7. 文脈を理由に、原文に無い情報を追加しない。
8. ネパール語の意味ある反復表現を機械的に削除しない（§8, §19.6）。
9. 固有名詞を無条件に音訳しない。
10. ASR の誤認識をそのまま確定情報として扱わない。
11. オンラインサービスを必須依存にしない。高額な AI サービスを必須条件にしない。
12. 翻訳精度を測定せずに改善済みと報告しない。
13. テストを実行せずにビルド成功・テスト成功と報告しない。実装していない機能を実装済みと報告しない。
14. ライセンスを確認せずに外部モデル・コーパスを組み込まない。
15. 秘密情報や会話内容をログに出力しない。
16. §4 の確定済み判断（英語ピボット不採用など）を、実測の根拠なく覆さない。

---

## 25. 他社 AI・開発 AI への実装指示

あなたは Android、自然言語処理、機械翻訳、音声認識、音声合成、ML モデル統合に精通したシニアエンジニアとして作業する。対象は既存 Android アプリ MagiTranslator（Java、§3）であり、最終目標は日⇔ネの双方向翻訳と音声通訳の品質を本質的に上げることである。

### 25.1 最初にすること
1. リポジトリ・ブランチ・構成・依存を調査し、本書 §3 の記述が現状と一致するか確認する（不一致は本書を更新する）。
2. 翻訳と ASR の実際の呼び出し経路を追跡する（入口：`Translator.performTextTranslation`、`Recognizer.recognize`、`WalkieTalkieService`）。
3. 想像でクラスや API を作らない。
4. §20.3 のベースライン測定を行う。

### 25.2 改修方針
- 大規模改修・拡張を積極的に検討する。既存の修正だけで十分な場合は、その理由を説明する。
- クラスの分割・統合・削除・新設を認める。ただし変更前後の責務と影響範囲を記録する。
- 着手順は §22（まず §19 の不具合修正）に従う。

### 25.3 精度を最優先する観点
日本語・ネパール語の文法と意味解析、文脈依存の訳語、固有名詞、専門用語、長文、会話文、否定・肯定、時制、敬語、情報欠落の防止、意味追加の防止。

### 25.4 ASR を最優先する観点
ASR を単なるマイク入力として扱わない。音声処理・モデル（ネパール語 FT Whisper を含む）・N-best・文脈補正・辞書連携・評価データを含めて再設計する。

### 25.5 学術資料・言語資源
ネパール語の学術資料・辞書・文法研究・コーパス・対訳データを調査し、アルゴリズム・辞書・文法ルール・評価に反映する。参照した資料は出典とライセンスを `docs/references.md` に記録する。

### 25.6 ハイブリッドと TTS
ローカルを基本とし、オンラインは精度向上が実測できる場合のみ任意で使う（§14）。TTS は翻訳・ASR より優先度を下げ、Android 標準 TTS から検証する（§12）。

### 25.7 実装・テスト・報告
変更したファイル・内容・理由を記録する。可能な限り単体・統合・回帰テストとビルド（CI）を実行し、実行できないものはその理由を明記する。最終報告には次を含める：

調査したブランチ／調査したファイル／改修・新規・削除・統合したファイル／翻訳エンジン・ASR・モデル・辞書と言語資源の変更内容／実行したテストと結果／ビルド結果／翻訳品質と ASR 品質の比較結果／未実装項目／既知の問題／今後の課題。

実測していない品質改善を断言しない。

### 25.8 渡し方（依頼者向け）
本書と一緒に MagiTranslator の Git リポジトリを渡し、最初に既存コードの全体調査とベースライン測定をさせること。新機能を足すだけでなく、既存翻訳エンジンの内部処理そのものを改善させることが目的である。

---

## 26. 最終目標
MagiTranslator を、翻訳モデルを呼び出すだけのアプリから、日本語とネパール語の言語特性を考慮した統合型の翻訳・音声通訳システムへ発展させる。そのために、翻訳エンジン、ASR、辞書、文法解析、文脈解析、候補評価、モデル管理、オンライン連携、ローカル処理を一体的に改善する。

目指すシステムの条件：
- 日本語⇔ネパール語の双方向翻訳に特化し、意味を正確に伝える。
- ネパール語の文法と表現（敬語・反復・後置詞）を適切に扱い、日本語の省略・敬語・文脈を考慮する。
- 音声認識の誤りを減らし、ASR→翻訳→表示→読み上げが安定して連携する。
- ローカルで基本機能を維持し、オンライン AI を必要に応じて活用できる。
- モデル・辞書・言語資源を事前導入できる。長文・会話・専門用語に対応する。
- Android 16 端末で安定して動作し、継続的に評価・改善できる。

**既存実装の維持を最優先にせず、日本語⇔ネパール語の翻訳精度と音声認識精度の実質的な向上を最優先とする。必要な大規模改良・拡張・再設計を実施し、成果は測定可能な品質改善によって確認する。大規模改修をしたという事実だけを成果として扱わない。**

---

## 付録 A. 統合時に解消した矛盾・重複

| 論点 | 草案・過去の記述 | 本書の決定 |
|---|---|---|
| 実装言語 | 「Kotlin の場合は…」 | 既存は Java。新規も Java を基本（Kotlin 化は任意・別判断） |
| 対象言語 | 日⇔ネのみ | 日⇔ネ主、日⇔越は副として維持（§2.3） |
| 英語ピボット | 草案に記載なし／過去に一度検討 | 採用しない（D1） |
| 逆翻訳 | 現行実装は逆翻訳 chrF を主評価に使用 | 補助指標に格下げ。重大違反を先に判定（§7.4） |
| 重複除去 | 現行は 1 語反復も削除 | 3 回以上のみ（§8, §19.6） |
| 信頼度 | 草案 TranslationResult に confidence | 校正済みのときのみ（§5.3, §7.5） |
| 署名 | 草案「秘密情報をソースに置かない」／現行は鍵をリポジトリに配置 | 個人サイドロード期間の暫定措置として明記し、配布前に移行（§19.8） |
| TTS とネパール語一覧 | 本家は TTS 非対応言語を一覧から外す挙動あり | ネパール語は TTS 非対応でも一覧に残す（§12） |
| 言語判定 | — | 生 logits でスコアリング（D7） |
| 精度主張 | 「市販ソフト以上」 | 比較実測後にのみ主張（D9, §20.3） |
| MAGI アルゴリズム流用 | 流用を検討 | 考え方のみ流用（D10） |

## 付録 B. 主要な根拠文献
- chrF：Popović, 2015 / 品質考慮の選択：Fernandes et al., 2022 / MBR：Freitag et al., 2022・2023
- 目標語注釈による用語指定：Exel et al., 2020；Bergmanis & Pinnis, 2021
- Whisper：Radford et al., 2022 / プロンプト：Peng et al., 2023 / 無音時ハルシネーション：Koenecke et al., 2024 (Careless Whisper)
- MADLAD-400：Kudugunta et al., 2023
