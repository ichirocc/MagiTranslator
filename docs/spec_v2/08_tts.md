# MagiTranslator 総合詳細機能実装仕様書
## 第8部：TTS（音声合成）エンジン 詳細実装仕様書

**文書バージョン：** 2.0（Claude 起草。ユーザー原稿の欠落部を補うもの）
**対象言語：** 日本語・ネパール語（主）、ベトナム語
**対象端末：** Pixel 10 Pro XL（Android 16）
**関連：** 第7部（ASR）、第9部（リアルタイム通訳）、第11部（安定性・セキュリティ）
**記法：** 型は Kotlin 風、実装は Java。状態欄は **実装済／部分／未実装**。

---

# 0. 目的と範囲

## 0.1 目的
翻訳結果を、相手が聞き取りやすい音声で読み上げる。対面会話（1台）で、翻訳文を相手の言語で再生することが主用途。

## 0.2 現状の要点
- 読み上げは Android 標準の `TextToSpeech`（端末の既定エンジン）に任せている（`tools/TTS.java`、`voice_translation/TTSEngine.java`、テキスト画面は `TranslationFragment`）。独自の TTS モデルは持たない。
- **ネパール語の音声が端末に無いことがある。** 既定エンジン（Pixel では通常 Google の音声合成）が ne の音声を提供するかは、エンジンの版と音声データのダウンロード状況による。本アプリは**実機で確認していない**。
- 音声が無い言語では読み上げできない。現在の扱い（無音で失敗するのか、通知するのか）は §5 で定義し直す。

## 0.3 非目標
独自 TTS モデルの学習、声のクローン、話者の声質の再現。

## 0.4 原則
1. 読み上げは翻訳の正しさを変えない（読み上げのために訳文を書き換えない）。
2. 音声が無いことを黙って失敗しない。ユーザーに分かる形で伝える。
3. 音声・テキストを外部へ送らない（オフラインで動くエンジン・音声を優先）。
4. 自分の TTS をマイクが拾って再認識する自己ループを起こさない。

---

# 1. 全体構成

```text
SpeechSynthesisEngine
├── TtsEngineManager      … TextToSpeech 初期化・エンジン検出（tools/TTS）
├── VoiceSelector         … 言語→Voice 選択（オフライン音声優先）
├── TextPreparer          … 読み上げ用テキスト整形（数字・記号・括弧）
├── PlaybackQueue         … 発話キュー（TTSEngine: messageQueue, QUEUE_ADD）
├── AudioFocusController  … 再生中のマイク停止・他アプリとの調停
└── TtsAvailabilityReporter … 言語ごとの可否を UI・第9部へ通知
```

| コンポーネント | 既存実装 | 状態 |
|---|---|---|
| TtsEngineManager | `tools/TTS.java`：既定エンジンで初期化、失敗時 `GOOGLE_TTS_ERROR`（エンジン限定チェックはコメントアウト済み） | 実装済 |
| VoiceSelector | `TTSEngine.speak`：現在の Voice の Locale と一致するかを確認 | 部分 |
| TextPreparer | なし | 未実装 |
| PlaybackQueue | `TTSEngine`（`ArrayDeque`, `QUEUE_ADD`） | 実装済 |
| マイクとの調停 | `Recorder` が TTS 中断後のディレイでマイク起動を遅らせる | 部分 |
| 可否通知 | 言語ごとの可否を UI に出す仕組みなし | 未実装 |

---

# 2. データモデル

```kotlin
data class SpeechRequest(
    val requestId: String,
    val text: String,
    val language: LanguageCode,
    val purpose: SpeechPurpose,          // TRANSLATION_OUTPUT / UI_PROMPT
    val rate: Float = 1.0f,              // ユーザー設定
    val pitch: Float = 1.0f
)

data class VoiceAvailability(
    val language: LanguageCode,
    val status: VoiceStatus,
    val voiceName: String?,
    val requiresNetwork: Boolean?,       // Voice.isNetworkConnectionRequired()
    val engine: String?                  // 例: com.google.android.tts
)

enum class VoiceStatus { AVAILABLE_OFFLINE, AVAILABLE_NETWORK_ONLY, NOT_INSTALLED, NOT_SUPPORTED, ENGINE_ERROR }
```

---

# 3. エンジン・音声の検出

## 3.1 初期化
アプリ起動時ではなく、最初の読み上げが必要になった時点で初期化してよい（初回遅延は許容）。初期化失敗は `ENGINE_ERROR` として扱う（実装済：`GOOGLE_TTS_ERROR`）。

## 3.2 言語ごとの可否判定（未実装）
`TextToSpeech.getVoices()` と `isLanguageAvailable(Locale)` で ja / ne / vi の可否を判定し、`VoiceAvailability` を作る。
- `Voice.isNetworkConnectionRequired()==true` の音声は `AVAILABLE_NETWORK_ONLY` とし、STRICT_LOCAL（D8）では**使わない**（音声エンジンが訳文をサーバへ送る可能性があるため）。
- ne の音声が無い場合は `NOT_INSTALLED` か `NOT_SUPPORTED` を区別する（`LANG_MISSING_DATA` と `LANG_NOT_SUPPORTED`）。

## 3.3 禁止事項
- 特定エンジン名だけで可否を決めない（エンジン限定チェックを復活させない）。
- ne の音声が無いときに、ヒンディー語など別言語の音声で ne を読ませない（発音が別言語になり、聞き手に誤解を与える）。

---

# 4. 音声選択

1. オフライン音声を優先する。
2. 同一言語に複数音声がある場合は、ユーザーが選んだもの → エンジン既定 → 品質（`Voice.getQuality()`）の高いもの、の順。
3. 選択結果はセッション中キャッシュし、エンジン更新や音声データの追加で無効化する。

---

# 5. 音声が無い場合のフォールバック（未実装）

| 状況 | 動作 |
|---|---|
| ne 音声が未インストール | 読み上げずに、翻訳文を大きく表示し「ネパール語の音声がこの端末にありません」と1回だけ通知。システムの音声データ設定画面（`ACTION_INSTALL_TTS_DATA`）へ誘導するボタンを出す |
| ne 音声がネット必須のみ | STRICT_LOCAL では読み上げない（同上の通知）。オンライン許可の扱いは第6部の同意に従う |
| エンジン初期化失敗 | 読み上げ機能を無効化し、テキスト表示のみで会話を継続 |

会話（第9部）は TTS が無くても止めない。翻訳文の表示を主、読み上げを従とする。

---

# 6. 読み上げ用テキスト整形（未実装）

訳文そのものは変えず、**読み上げ用の別文字列**を作る。

## 6.1 数字
- ネパール語：ASCII 数字とデーヴァナーガリー数字（०–९）のどちらでも、エンジンが正しく読むかを実機で確認する（**未確認**）。読まない場合のみ、数詞への展開を TextPreparer で行う。展開規則（ネパール語の数詞は100未満が不規則）は辞書化し（第4部 追補A の数字・通貨辞書）、規則の自作推測で埋めない。
- 金額：`रु १,०००` のような通貨記号・桁区切りを読み上げで落とさない（数値の誤読は重大：第5部 §8.4）。
- 日本語：エンジンの読みに任せる。時刻「10:30」等の読みを実機で確認する。

## 6.2 記号・括弧
括弧内の注記（固有名詞の原綴りなど）は読み上げから外すか、間を置いて読む。読み上げ対象から外したことで意味が欠けないかを第5部の OmissionDetector と同じ観点で確認する。

## 6.3 禁止事項
- 読み上げ整形のために表示用の訳文を変えない。
- 否定・数字・固有名詞を整形で消さない。

---

# 7. マイクとの調停（自己ループ防止）

- 再生中はマイク（VAD）を止め、再生終了後に短い待ちを入れてから再開する（部分実装：`Recorder` のディレイ）。
- 待ち時間は端末の出力遅延に依存するため、固定値を実機で確認して決める（**未測定**）。
- 第7部の ASR が TTS の音声を認識した場合（直前の読み上げと高一致）、その結果を翻訳に回さない安全網を設ける（**未実装**）。

---

# 8. 性能・安定性

- 初回の読み上げ遅延、1文あたりの合成開始までの時間を実機で記録する（未測定）。
- 長い訳文は文単位でキューに分けて再生し、途中停止（新しい発話・画面離脱）に対応する（`QUEUE_ADD` は実装済、停止の扱いは第9部と合わせて定義）。
- `TextToSpeech` はサービス破棄時に `shutdown()` する（既存の解放経路を確認：要確認）。

---

# 9. プライバシー

- STRICT_LOCAL ではネット必須の音声を使わない（§3.2）。
- 読み上げテキストをリリースログに出さない（PrivacyLog）。
- 合成音声を保存しない。

---

# 10. テスト

| 種別 | 内容 | 状態 |
|---|---|---|
| Unit | VoiceAvailability 判定（LANG_MISSING_DATA / NOT_SUPPORTED / network-only）、フォールバック分岐、読み上げ整形（数字・通貨・括弧） | 未 |
| Device | Pixel 10 Pro XL で ja / ne / vi の音声の有無、オフライン可否、数字の読み、自己ループ有無、初回遅延 | 未 |
| Regression | ne 音声なし端末で会話が止まらないこと | 未 |

---

# 11. 受入条件

1. ja / ne / vi それぞれの音声の可否を検出し、無い場合にユーザーへ通知できる。
2. STRICT_LOCAL でネット必須音声を使わない。
3. 音声が無くても会話（表示）が継続する。
4. 数字・金額が正しく読まれることを実機で確認、または正しく読めない場合は整形で対処している。
5. 自分の読み上げを再認識しない。
6. 読み上げテキストがリリースログに出ない。

---

# 12. 実装 AI への指示

- まず実機（Pixel 10 Pro XL）で ne 音声の有無とオフライン可否を確認し、結果を記録してから実装する。端末で ne 音声が使えるかを推測で書かない。
- 別言語の音声で ne を代読させない。
- TTS 関連は Android ビルドでしか検証できない。CI でコンパイル確認する。

## 次の作業（推奨順）
1. 言語ごとの `VoiceAvailability` 判定と「音声がありません」通知（§3.2, §5）。
2. STRICT_LOCAL でのネット必須音声の除外。
3. 実機で数字・金額の読みを確認し、必要な場合のみ数字整形。
4. 自己ループの安全網（直前読み上げとの一致チェック）。
