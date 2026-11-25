# MagiTranslator 総合詳細機能実装仕様書
## 第9部：リアルタイム音声通訳エンジン 詳細実装仕様書

**文書バージョン：** 2.0（Claude 作成。ユーザー原稿ではない）
**対象：** 日本語 ⇔ ネパール語（主）、日本語 ⇔ ベトナム語（維持）／ Android 16+（Pixel 10 Pro XL 特化, D5）
**関連仕様：** 第1部（全体）、第3部（翻訳モデル）、第5部（候補・品質）、第7部（ASR）、第8部（TTS）、第11部（性能）、第12部（保存）
**型表記：** Kotlin 風に記すが、実装は Java（採用済み判断）。`suspend` は Java の `Executor`/`Future` とコールバックで読み替える。
**状態表記：** 【実装済】【部分】【未実装】は 2026-11-25 時点の `claude/keen-planck-05zahs`（e4823e1）での事実。

---

# 0. 目的と適用範囲

## 0.1 目的
音声入力 → 音声認識（ASR）→ 翻訳 → 音声合成（TTS）を連続実行し、対面の会話を通訳する。対象は D4 の「対面会話（1台）」が主、Bluetooth による2台間会話（Conversation モード）を従とする。

## 0.2 最重要方針
1. **正確さを速さより優先**（D2）。ただし会話が成立しない遅延は不可。
2. **発話を取りこぼさない**：新しい発話が来ても前の発話の翻訳を破棄しない（現行の設計判断、§5.3）。
3. **キャンセルした結果を画面・音声に出さない**。
4. **原文・訳文・認識結果を release ログに出さない**（PrivacyLog）。
5. オフラインで完結する（D8：オンライン既定 OFF＝実質 STRICT_LOCAL）。

## 0.3 非目標
同時通訳（話者の発話中に訳を出し始める逐次出力）、3者以上の会話、話者分離（同時に話す2人の分離）、電話音声の通訳。

---

# 1. 通訳モード

| モード | 用途 | 入力 | 既存クラス | 状態 |
|---|---|---|---|---|
| WalkieTalkie（対面1台） | 2人が1台を挟んで交互に話す。**既定・初回起動モード（D4）** | マイク1系統、言語2つ（自動判定または左右ボタン） | `_walkie_talkie_mode/_walkie_talkie/WalkieTalkieService`, `VoiceTranslationService` | 【実装済】 |
| Conversation（2台） | 各自の端末で話し、相手端末へ訳を送る | Bluetooth（BLE） | `_conversation_mode/_conversation/ConversationService`, `bluetooth/` | 【実装済】（RTranslator 由来。ja/ne/vi 特化の検証は未） |
| Text | 文字入力の翻訳 | キーボード | `_text_translation/TranslationFragment` | 【実装済】 |

---

# 2. 全体パイプライン

```text
AudioInput (mic / BT headset)
  → VAD・発話区間切り出し（Recorder）
  → ASR（Recognizer: Whisper-small、ne は keep-best 2仮説、任意で Whisper_ne）
  → 言語判定（2言語のスコア比較 / 手動指定）
  → ASR 後処理（無音幻聴の棄却、Devanagari 以外の混入検査）
  → 翻訳（Translator → JaNeTranslator / HY-MT / Madlad、公平ロックで直列化）
  → 候補選択（validator hard 優先 → スコア）
  → 表示（原文・訳文）
  → TTS（TTSEngine / Android TextToSpeech）
```

## 2.1 遅延の内訳（計測項目）
| 区間 | 計測 | 状態 |
|---|---|---|
| 発話終了 → ASR 完了 | ms | 【未実装】（ログのみ。p50/p95 の集計なし） |
| ASR 完了 → 翻訳完了 | ms | 【部分】（Translator のログに時間はあるが集計なし） |
| 翻訳完了 → TTS 開始 | ms | 【未実装】 |
| 端到端（発話終了 → 訳の音声開始） | ms | 【未実装】 |

**実測値は未取得。** 目標値は実機計測後に設定する（固定値を根拠なく仕様化しない、第5部 §10.6 と同方針）。

---

# 3. データモデル

```kotlin
data class Utterance(
    val utteranceId: Long,            // 既存: speakingUtteranceId に相当
    val conversationId: String,
    val mode: InterpretationMode,     // WALKIE_TALKIE / CONVERSATION / TEXT
    val speakerSide: SpeakerSide,     // LEFT / RIGHT / REMOTE
    val sourceLanguage: LanguageCode, // ASR 判定結果（確信度つき）
    val languageConfidence: Float?,
    val recognizedText: String,       // 永続化しない（§8）
    val asrScore: Float?,
    val noSpeechProb: Float?,
    val startedAtMillis: Long,
    val endedAtMillis: Long
)

data class InterpretationResult(
    val utteranceId: Long,
    val translatedText: String,
    val targetLanguage: LanguageCode,
    val hardIssueCount: Int,          // validator の hard 数（第5部）
    val warnings: List<String>,       // 表示用コード（本文は含めない）
    val cancelled: Boolean,
    val timings: InterpretationTimings
)

data class InterpretationTimings(
    val asrMs: Long?, val translateMs: Long?, val ttsStartMs: Long?, val endToEndMs: Long?
)
```

---

# 4. 入力・発話区間

## 4.1 VAD・切り出し
- 【実装済】RTranslator の Recorder による音量しきい値ベースの区間検出。
- 【実装済】Whisper の no-speech 確率 ＋ 平均 logprob による無音幻聴の棄却（ne の keep-best 経路、`NO_SPEECH_THRESHOLD`/`LOGPROB_THRESHOLD`）。
- 【未実装】ja / vi 経路での同等の無音棄却。
- 【未実装】学習型 VAD（Silero 等）。採用する場合はライセンス・端末速度・誤棄却率を実測してから。

## 4.2 マイク・オーディオルーティング
- Bluetooth ヘッドセット接続時はヘッドセットマイクを使う【実装済（RTranslator 由来）】。
- 他アプリの音声出力中・通話中はマイクを奪わない。録音は前景サービス（`FOREGROUND_SERVICE_MICROPHONE`）でのみ行う【実装済】。

---

# 5. 並行性・順序・キャンセル

## 5.1 直列化
- 翻訳は `Translator` の公平な `ReentrantLock(true)` で直列化する【実装済】。到着順に処理され、後着が先着を追い越さない。
- ASR は `Recognizer` の内部キュー（`dataToRecognize`）で直列化する【実装済】。ne の言語判定後の再認識は `addFirst` で先頭に入れる。

## 5.2 キャンセル（第3部 §16.5）
- 【実装済】要求ごとのチケットと ORT `RunOptions.setTerminate(true)`、デコーダの各ステップでの確認。キャンセルされた要求は結果・途中経過・エラーのいずれも UI へ届けない。
- 発動条件：テキストモードで新しい翻訳を開始したとき／テキスト画面の Activity が終了するとき／WalkieTalkie サービス破棄時【実装済】。
- 【実装済・意図的】音声モードでは**新しい発話で前の発話をキャンセルしない**（全発話を訳す）。Conversation モードはキュー進行がコールバック依存のためキャンセルしない。

## 5.3 取りこぼしと滞留
- 話者が連続して話すと翻訳待ちが滞留する。**滞留の上限と表示は【未実装】**。
- 推奨：滞留件数を UI に表示し、上限（実測で決める）を超えたら新規録音を一時停止して通知する。発話の破棄はしない。

## 5.4 TTS と録音の衝突
- 訳の読み上げ中に自分の TTS 音声を録音しない（エコー対策）【実装済（RTranslator 由来：読み上げ中はマイク停止）】。
- 【未実装】読み上げ中の割り込み（相手が話し始めたら読み上げを止める）。

---

# 6. 言語判定

| 項目 | 内容 | 状態 |
|---|---|---|
| 2言語スコア比較 | Whisper の言語トークン確率で2言語を比較 | 【実装済】 |
| ne 判定後の再認識 | 基本モデルが ne と判定したら Whisper_ne（存在時）で再認識し、スコアは保持 | 【実装済】 |
| 言語判定の確信度表示 | 低確信度時に「どちらの言語か」をユーザーに確認 | 【未実装】 |
| 手動固定 | 左右ボタンで話者の言語を固定 | 【実装済（RTranslator 由来）】 |

ne と hi（ヒンディー語）の取り違えは Devanagari 限定マスクと keep-best で抑制する（第7部）。Whisper_ne 使用時はマスク・プロンプトを外す（実測で悪化したため、7741ba4）。

---

# 7. ASR 誤りに強い翻訳への受け渡し

- ASR 結果に U+FFFD（壊れた文字）、Devanagari 以外の文字、同一語の反復ループがある場合は、翻訳前に hard として扱い再認識候補を選ぶ【部分：ne の keep-best のみ】。
- 【未実装】ASR の N-best（複数仮説）を翻訳候補生成に渡す。
- 【未実装】ASR 確信度が低い発話の訳に「聞き取り不確か」警告を付ける。

---

# 8. プライバシー

1. 認識結果・訳文は**端末内のメモリ（サービスの `messages` リスト）にのみ保持**し、サービス終了で消える【実装済】。永続履歴は持たない（第12部）。
2. release ログでは `PrivacyLog.text()` により本文を伏せる【実装済】。
3. 翻訳メモリ（`TranslationMemory`）は会話単位・LRU 200・メモリ内のみ【実装済】。会話リセット時に破棄（`resetConversationMemory`、ロック不要）。
4. Conversation モードで相手端末へ送るのは訳文と原文のみ。音声は送らない【実装済（RTranslator 由来）】。
5. 外部送信は行わない（D8）。

---

# 9. エラー・劣化時の動作

| 事象 | 動作 | 状態 |
|---|---|---|
| ne モデル（Madlad）未導入で ne を選択 | 警告ダイアログで導入を促す | 【実装済】（`warnIfNepaliWithoutMadlad`） |
| 翻訳中のメモリ不足 | 処理を落とさず「メモリ不足」を表示 | 【実装済】（2a9700f） |
| TTS に ne 音声が無い | 文字表示のみで続行、TTS エラー表示 | 【部分】（isTTSError 表示はある。ne 音声の案内は第8部） |
| マイク権限なし | 権限要求 | 【実装済】 |
| 無音の幻聴 | 結果を出さない | 【部分】（ne 経路のみ） |

---

# 10. 触覚・通知（Android 16）

- 録音開始・終了の触覚フィードバック（`VibrationEffect.BasicEnvelopeBuilder`）【実装済】。
- 長時間の前景サービス通知【実装済】。

---

# 11. テスト

## 11.1 単体（純 Java で CI 実行可能なもの）
- キャンセルチケットの状態遷移（発行・取消・取消後の配信抑止）【未実装：テスト無し】。
- 発話の到着順＝翻訳完了順（公平ロック）【未実装】。

## 11.2 実機テスト（Pixel 10 Pro XL）
| 試験 | 合格条件 |
|---|---|
| 交互会話 20 往復（ja⇔ne） | クラッシュ・ANR なし、全発話に訳が出る |
| 連続発話 5 回（相手の応答なし） | 取りこぼし 0、順序保持 |
| 読み上げ中に画面を閉じる | 以後の結果が表示・読み上げされない |
| 30 分連続使用 | 異常終了なし、端末温度と電池消費を記録（第11部） |
| 無音・環境雑音 30 秒 | 幻聴の訳が出ない |
| BT ヘッドセット切替 | 入出力経路が追従 |

## 11.3 品質評価
- 端到端の品質は「ASR 誤り＋翻訳誤り」の合成になる。第7部の WER/CER と第13部の翻訳評価を**別々に**記録し、合成結果だけで改善を主張しない。

---

# 12. 受入条件

1. WalkieTalkie で ja⇔ne の交互会話が実機で成立する（§11.2 の全試験に合格）。
2. 端到端遅延の p50 / p95 が実機で記録されている（数値目標は記録後に設定）。
3. キャンセルされた要求の結果が一切表示・読み上げされない。
4. 発話の取りこぼしが無い（滞留上限を超える場合は録音停止と通知）。
5. release ログに本文が出ない（PrivacyLog の検査）。
6. ネイティブ話者による会話シナリオ評価を実施している（第13部 §6）。

---

# 13. 実装AIへの指示

- 既存の WalkieTalkie / Conversation / Text の3経路を壊さない。UI の変更は最小限にし、変更する場合は理由を書く。
- 発話の破棄による遅延短縮をしない（§0.2-2）。
- 遅延の改善を主張する場合は、§2.1 の計測を実機で取り、前後比較を `docs/eval/` に保存する。
- 録音・TTS・翻訳のスレッドを UI スレッドで待たせない（ANR）。ロック待ちを UI スレッドで行わない（過去に `resetConversationMemory` で発生し修正済）。
- 優先度付きキュー（音声通訳＞長文テキスト、第3部 §16.4）を導入する場合は、公平ロックとの関係（順序保証）を明記する。

---

# 14. 優先実装順（推奨）

1. 端到端遅延の計測と集計（§2.1）— 品質改善の前提。
2. 滞留件数の表示と上限（§5.3）。
3. ja / vi 経路の無音幻聴棄却（§4.1）。
4. ASR 低確信度警告（§7）。
5. 読み上げ中の割り込み（§5.4）。
