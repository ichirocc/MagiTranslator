# MagiTranslator 総合詳細機能実装仕様書
## 第7部：ASR（音声認識）エンジン 詳細実装仕様書

**文書バージョン：** 2.0（Claude 起草。ユーザー原稿の欠落部を補うもの）
**対象言語：** 日本語・ネパール語（主）、ベトナム語（採用済み判断で維持）
**対象端末：** Pixel 10 Pro XL（Android 16 / minSdk 36、arm64）— D5
**関連：** 第1部（共通データ）、第2部（言語解析）、第5部（候補品質）、第9部（リアルタイム通訳）、第10部（資源導入）
**記法：** 型は仕様記述のため Kotlin 風に書くが、実装は Java（採用済み判断）。状態欄は **実装済／部分／未実装**。数値は実測したものだけを書き、条件と注意を併記する。

---

# 0. 目的と範囲

## 0.1 目的
対面会話（1台）で話された日本語・ネパール語を、翻訳に渡せる品質のテキストへ変換する。ネパール語の認識精度を最重要課題とする（汎用 Whisper-small はネパール語で反復崩壊する：§10.2）。

## 0.2 範囲
音声取得、前処理、発話区間検出（VAD）、言語判定、モデル選択、デコード、無音・幻聴（hallucination）抑制、信頼度、翻訳への受け渡し、評価。

## 0.3 非目標
話者分離（diarization）、複数人同時発話の分離、端末上でのモデル学習、ASR 誤りの完全修正（翻訳側は第5部で ASR 由来の誤りに頑健であることを目指すが、修正は保証しない）。

## 0.4 最重要原則
1. ネパール語の改善は**実測（WER/CER）で採否**を決める。推論だけで採用しない（§10 の mask/prompt の反転事例）。
2. 無音・雑音から文章を作らない（幻聴を出すより何も出さない）。
3. 認識テキスト・音声をリリースログ・外部へ出さない（PrivacyLog、STRICT_LOCAL）。
4. 言語判定の失敗で翻訳方向を誤らない（ja⇔ne の取り違えは重大誤訳と同等）。
5. 小規模サンプルの結果を一般化しない。サンプル数・条件を必ず併記する。

---

# 1. 全体構成

```text
SpeechRecognitionEngine
├── AudioInputManager        … Recorder（AudioRecord, 16 kHz mono PCM16）
├── VoiceActivityDetector    … Silero VAD（com.konovalov.vad.silero）, 振幅しきい値, 手動モード
├── AudioPreprocessor        … log-mel 80（Whisper 前処理, initializer ONNX）
├── LanguageIdentifier       … Whisper 言語トークン確率（2言語の同時デコード）
├── ModelSelector            … base Whisper-small / ネパール語微調整 Whisper（任意）
├── Decoder                  … 6分割 ONNX（encoder / cache_init / cache_init_batch / decoder / …）, KV cache, greedy
├── DecodingConstraints      … ne 許可トークン mask, 反復罰, no-repeat-3gram, ne プロンプト（base 時のみ）
├── HallucinationGuard       … no_speech 確率＋平均 logprob, 反復ループ検出, 最大長
├── HypothesisSelector       … ne keep-best（constrained vs free の辞書式選択）
├── SpeechPostProcessor      … correctText, 句読点, 数字
└── RecognitionResultEmitter … RecognizerListener / RecognizerMultiListener
```

| コンポーネント | 既存実装 | 状態 |
|---|---|---|
| AudioInputManager | `Recorder.java`（16 kHz 固定, `SAMPLE_RATE_CANDIDATES={16000}`） | 実装済 |
| VAD | Silero VAD（フレーム 512）＋ `global.getAmplitudeThreshold()`、`setManualMode`（押して話す） | 実装済 |
| 言語判定 | 2言語を batch=2 で同時デコードしスコア比較 | 実装済 |
| ne 微調整モデル | `files/Whisper_ne/` があれば `ensureNeModel()` で遅延ロード | 部分（自動配布なし） |
| ne mask/prompt | `constrainedLogits`, `buildPromptedPrefix`（base 時のみ有効） | 実装済（7741ba4 で微調整時は無効化） |
| 無音ゲート | `NO_SPEECH_THRESHOLD=0.6` かつ `LOGPROB_THRESHOLD=-1.0` | 実装済 |
| keep-best | `nepaliHardScore`（ループ・空・非デーヴァナーガリー）→ スコア | 実装済 |
| 信頼度の校正 | なし（raw 平均 logprob のみ） | 未実装 |
| キャンセル | 翻訳は対応（2a9700f）。ASR デコードの中断は未対応 | 未実装 |

---

# 2. 共通データモデル

```kotlin
data class AudioSegment(
    val segmentId: String,
    val pcm: FloatArray,            // 16 kHz mono, [-1, 1]
    val sampleRate: Int = 16000,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val captureMode: CaptureMode     // VAD / MANUAL
)

data class RecognitionRequest(
    val requestId: String,
    val segment: AudioSegment,
    val candidateLanguages: List<LanguageCode>,  // 1 または 2（例: [JA, NE]）
    val beamSize: Int,                           // 現行は greedy（1）
    val privacy: PrivacyMode                     // STRICT_LOCAL 固定（D8）
)

data class RecognitionHypothesis(
    val text: String,
    val language: LanguageCode,
    val avgLogProb: Double,          // raw logits から計算（mask 適用前, D7）
    val noSpeechProb: Double,
    val hitMaxLength: Boolean,
    val hardScore: Long,             // nepaliHardScore 相当（ne のみ）
    val modelId: String,             // "whisper-small" / "whisper-small-ne-oslr54"
    val decodingProfile: DecodingProfile
)

data class RecognitionResult(
    val requestId: String,
    val text: String?,               // null = 発話なし（UNDEFINED_TEXT）
    val language: LanguageCode?,
    val confidence: AsrConfidence,
    val alternatives: List<RecognitionHypothesis>,
    val warnings: List<AsrWarning>
)

enum class AsrConfidence { HIGH, MEDIUM, LOW, UNKNOWN }   // 校正前は常に UNKNOWN 扱い（§8）
enum class AsrWarning { SILENCE_DROPPED, LOOP_DETECTED, MAX_LENGTH, FOREIGN_SCRIPT, LANGUAGE_UNCERTAIN, REPLACEMENT_CHAR }
```

---

# 3. 音声取得・VAD

## 3.1 取得
16 kHz mono PCM16 を `AudioRecord` で取得する（実装済）。サンプルレート変換はしない（端末側で 16 kHz を要求）。

## 3.2 VAD
- Silero VAD で発話区間を検出し、振幅しきい値（ユーザー設定）を併用する（実装済）。
- 押して話す手動モード（`setManualMode`）を用意する（実装済）。対面会話で相手の声と自分の声が交互になる場面では、手動モードの方が誤起動が少ない可能性があるが**未測定**。
- TTS 再生中はマイクを開けない（Recorder 内のディレイ処理、実装済）。自分の TTS を認識する自己ループを防ぐ（第8部 §7）。

## 3.3 禁止事項
- 無音区間を連結して1発話にしない。
- VAD しきい値を端末名で固定しない（ユーザー設定と実測で決める）。

---

# 4. 前処理

Whisper 標準の 80 次元 log-mel（30 秒窓）を initializer ONNX で計算する（実装済）。ノイズ抑制・AGC は Android の `AudioRecord` 既定に任せ、独自処理は**入れない**（WER への効果が未測定のため。導入時は §10 の手順で A/B する）。

---

# 5. 言語判定

## 5.1 方式
WalkieTalkie／会話モードでは2言語（例: ja と ne）を batch=2 で同時にデコードし、平均 logprob（raw）で勝者を選ぶ（実装済）。ne が勝った場合、微調整モデルがあれば ne で**再認識**して置き換える（`reportAsMulti`、実装済）。

## 5.2 原則
- mask 後の logits で言語スコアを比較しない（Nepali 部分集合で再正規化するとスコアが膨らみ自動判定が壊れる：`constrainedLogits` のコメント、実装済の設計）。
- 判定が僅差のときは `LANGUAGE_UNCERTAIN` を付け、第9部で翻訳方向の確認 UI を出せるようにする（**未実装**）。僅差の閾値は固定値を埋め込まず、評価データで校正する（第5部 §10.6）。

---

# 6. モデル選択

| モデル | 用途 | 入手 | 状態 |
|---|---|---|---|
| openai/whisper-small（6分割 ONNX） | ja / vi / 言語判定 / ne（微調整なし時） | models-v1（SHA-256 固定） | 実装済 |
| ilprl/Whisper-Small-Nepali-ASR-OSLR54（6分割 ONNX, fp32 約1GB） | ne の認識 | 手動配置 `files/Whisper_ne/`（エクスポートは `tools/whisper_ne/`） | 部分 |

採用条件（第3部 §5.4 / §19.7 準拠）：
1. ライセンスと帰属表示をモデルカードで人手確認する（**未確認**）。
2. 学習データ（OpenSLR54）と評価データの重複を確認する（**未確認**）。FLEURS で評価しているので直接の重複は低いと見込むが、確認前は「参考値」とする。
3. int8 版を作る場合は fp32 と同一評価で比較する（**未測定**）。
4. 確認後に models-v1 へ追加し、第10部の任意パック（Nepali ASR pack）として配布する。

---

# 7. デコード

## 7.1 現行
greedy・KV cache・最大 445 トークン（`MAX_TOKENS`）・秒あたり 30 トークン上限（`MAX_TOKENS_PER_SECOND`）。強制接頭辞 `<|startoftranscript|><|lang|><|transcribe|><|notimestamps|>`（実装済）。

## 7.2 ネパール語プロファイル

```kotlin
enum class DecodingProfile {
    BASE_FREE,           // 制約なし
    BASE_NE_CONSTRAINED, // 許可トークン mask＋反復罰1.2＋no-repeat-3gram＋ne プロンプト
    NE_FINETUNED_FREE    // 微調整モデル＋制約なし（7741ba4 以降の既定）
}
```

| 条件 | 仮説1 | 仮説2 | 選択 |
|---|---|---|---|
| base モデルのみ | BASE_NE_CONSTRAINED | BASE_FREE | keep-best（hard→スコア） |
| 微調整モデルあり | NE_FINETUNED_FREE | NE_FINETUNED_FREE | 実質同一（2本目は冗長） |

- 微調整モデル時に2本とも制約なしになるのは 7741ba4 の副作用で、**batch=2 の計算が無駄**。改善案：微調整時は batch=1 に落とす、または2本目を「mask にバイト片 BPE を追加した版」にして A/B する（**未実装**）。
- mask の既知の欠陥：許可ビット集合がデーヴァナーガリーの**部分バイト BPE トークン**を禁じ、不正 UTF-8（U+FFFD）を生む（§10.2、15件中7件）。base 時にも同じ欠陥があるはずで、**バイト片トークンを許可集合に加える修正**が必要（未実装、`whisper_ne_allowed_tokens.bin` の再生成）。

## 7.3 禁止事項
- 反復罰・no-repeat を強くしすぎない（ネパール語の正当な反復 धेरै धेरै を壊す。第3部 §11.4）。
- 言語トークンを ne に固定したまま ja 音声を認識しない（言語判定の結果を尊重する）。

---

# 8. 幻聴抑制・信頼度

## 8.1 無音ゲート（実装済）
Whisper の規則に倣い `no_speech > 0.6 かつ 平均 logprob < -1.0` なら発話なし（UNDEFINED_TEXT）とする。値は Whisper 既定値の流用で、**本アプリの ne では未校正**。

## 8.2 hard 判定（実装済, ne のみ）
`nepaliHardScore`：反復ループ、空出力、デーヴァナーガリー以外の文字（英数字の混在は扱いを要検討）、最大長到達。追加すべき項目：U+FFFD を hard にする（**未実装**、§7.2 の欠陥の安全網）。

## 8.3 信頼度
`AsrConfidence` は評価データ（§10）で avgLogProb・noSpeechProb を校正してから HIGH/MEDIUM/LOW を割り当てる。校正前は UNKNOWN とし、UI で「確信度が高い」と表示しない（**未実装**）。

---

# 9. 翻訳への受け渡し

- 認識結果は第1部 `TranslationRequest` の `sourceText` とし、`context` に ASR 由来であることと `AsrWarning` を載せる（**未実装**：現在はテキストのみ）。
- 第5部の評価で「ASR 誤りを含む入力」カテゴリ（第3部 §6.1-19）を用意し、ASR 由来の誤字に対する翻訳の頑健性を測る（**未実装**）。
- 翻訳側で ASR テキストを勝手に書き換えて意味を補完しない（第2部 §4.4）。

---

# 10. 評価

## 10.1 プロトコル
- 指標：WER と CER を**言語別**に報告。正規化＝NFC、デーヴァナーガリー数字→ASCII、句読点除去（`tools/whisper_ne/eval_wer_cer.py`）。
- データ：FLEURS（ne_np / ja_jp の test）、加えて**雑音下・会話音声**（対面会話の実利用に近いもの）を別カテゴリで用意する（未）。
- 条件記録：モデル、精度（fp32/int8）、デコードプロファイル、端末、サンプル数。
- CPU 負荷の大きい測定は事前に机上見積りを行う（ユーザー規則）。

## 10.2 実測（PC・CPU・greedy・fp32、FLEURS ne_np test 先頭15発話＝277語。**参考値**）

| 構成 | WER | CER |
|---|---:|---:|
| openai/whisper-small（現行 base 相当） | 139.0% | 65.2% |
| ilprl/Whisper-Small-Nepali-ASR-OSLR54 | 50.9% | 34.9% |
| 同モデルの6分割 ONNX・アプリ同等ループ・制約なし | 50.9% | 34.9% |
| ＋mask・反復罰・no-repeat-3gram | 62.1% | 38.3% |
| ＋ne プロンプトのみ | 65.7% | 50.6% |
| mask＋prompt（7741ba4 以前のアプリ設定） | 76.5% | 54.7% |

注意：サンプルが小さい、雑音・会話音声なし、int8・実機速度/メモリ未測定、学習データ重複未確認。

## 10.3 未測定（受入前に必須）
ja の WER/CER、vi の WER/CER、実機の RTF（実時間比）・ピークメモリ・初回ロード時間、int8 劣化、言語判定の正解率（ja/ne 混在）。

---

# 11. Android 実装要件

- 認識は専用スレッドで行い UI スレッドを塞がない（実装済：`recognizer` スレッド＋`lock`）。
- 微調整モデルは遅延ロードし、失敗時は base に戻る（実装済：`ensureNeModel` が false を返す）。
- メモリ：base と微調整を同時に保持すると数 GB 規模になりうる。バックグラウンド時・メモリ圧迫時に微調整モデルを解放する（**未実装**、第3部 §16.7）。
- ASR デコードのキャンセル（新しい発話・画面離脱）：ORT `RunOptions.setTerminate` を翻訳と同じ方式で導入する（**未実装**）。

---

# 12. プライバシー

- 音声データは端末外に出さない（STRICT_LOCAL、D8）。録音ファイルを保存しない。
- リリースログに認識テキストを出さない（`PrivacyLog.text`、実装済）。スコアや no_speech 等の数値は出してよい。
- 評価用音声は公開データセットのみを使い、ユーザー音声を評価・学習に転用しない（第3部 §15.2.2）。

---

# 13. テスト

| 種別 | 内容 | 状態 |
|---|---|---|
| Unit | `nepaliHardScore`、`constrainedLogits`（言語別の適用有無）、プロンプト接頭辞長、U+FFFD 検出 | 未（純 Java 化が必要） |
| Integration（PC） | 6分割 ONNX と HF の出力一致（実施済：一致）、プロファイル別 WER/CER | 部分 |
| Device | RTF、ピークメモリ、長時間連続、TTS 自己ループなし、手動モード | 未 |
| Regression | §10.2 の15発話を固定セットとして保存し、変更ごとに WER/CER の悪化を検出 | 部分（結果 JSON あり） |

---

# 14. 受入条件

1. ne の WER/CER が、固定評価セットで base より改善していることを実測で示す（現状 15 発話の参考値のみ）。
2. 微調整モデル使用時に U+FFFD を含む出力が出ない。
3. 無音・雑音のみの区間で文章が出ない（無音ゲートの実機確認）。
4. ja/ne の言語判定の正解率を測定し記録している。
5. 実機の RTF・ピークメモリ・初回ロード時間を記録している。
6. 認識テキストがリリースログに出ない。
7. 微調整モデルのライセンス・帰属・学習データ重複を確認済み。

---

# 15. 実装 AI への指示

- `Recognizer.java` のデコードループは大きく、Android ビルドでしか検証できない。変更は小さく分け、CI（Release Build）で必ずコンパイル確認する。
- mask・プロンプト・閾値の変更は、`tools/whisper_ne/eval_wer_cer.py` で変更前後を測ってから採用する。改善を推測で報告しない。
- ネパール語の規則を日本語の規則から流用しない（第2部 §14）。
- 未実装・未測定の項目を完成と報告しない。

## 次の作業（推奨順）
1. 許可トークン集合にバイト片 BPE を加えて再生成し、base 時の U+FFFD を解消（測定つき）。
2. U+FFFD を `nepaliHardScore` の hard に追加。
3. 微調整モデル時は batch=1（冗長な2本目を止める）。
4. 微調整モデルのライセンス確認後、models-v1 に追加し第10部の任意パックとして配布。
5. ja の WER/CER と言語判定正解率の測定。
