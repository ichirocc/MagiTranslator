# MagiTranslator 総合詳細機能実装仕様書
## 第12部：データベース・履歴・設定・キャッシュ 詳細実装仕様書

**文書バージョン：** 2.0（Claude 作成）
**型表記：** Kotlin 風、実装は Java（Room は Java から利用）。
**状態表記：** 【実装済】【部分】【未実装】（e4823e1 時点）。

---

# 0. 目的と原則
端末内に保存するデータの種類・保存先・寿命・削除方法を定める。

1. **会話内容は既定で保存しない**（現行設計と一致）。保存する機能を追加する場合は明示的なオプトイン。
2. 保存するものは目的・寿命・削除方法を持つ。
3. 外部へ同期・バックアップしない（`allowBackup=false`、D8）。
4. 個人辞書・用語集は保存してよいが、会話から自動的に作らない（第4部 §1.6）。

# 1. 現状の保存物

| データ | 保存先 | 寿命 | 状態 |
|---|---|---|---|
| 設定（言語、モード、TTS 等） | `SharedPreferences("default")`（`Global` ほか） | 永続 | 【実装済】 |
| 最近の接続相手（Conversation） | Room `AppDatabase` v1（`RecentPeerEntity`） | 永続 | 【実装済（RTranslator 由来）】 |
| Tatoeba 例文 DB | `databases/tatoeba`（ダウンロード資源） | 資源と同じ | 【実装済】 |
| 会話メッセージ | `VoiceTranslationService.messages`（メモリ内 `ArrayList<GuiMessage>`） | サービス終了まで | 【実装済：非永続】 |
| 翻訳メモリ | `jane/TranslationMemory`（会話単位 LRU 200、メモリ内） | 会話リセット／プロセス終了まで | 【実装済：非永続】 |
| モデル・辞書資源 | `files/`（staging・`.previous/`・`.failed.tsv`） | 資源管理（第10部） | 【実装済】 |
| ログ | logcat（release は本文を伏せる） | OS 管理 | 【実装済】 |

# 2. データ分類

| 区分 | 例 | 保存 | 暗号化 | 削除 |
|---|---|---|---|---|
| A 設定 | 言語、モード | する | 不要 | アプリデータ消去 |
| B 個人辞書・用語集 | ユーザー指定の訳語・禁止訳語 | する（第4部） | 推奨（Android Keystore 由来の鍵） | 画面から個別／全削除 |
| C 会話内容 | 原文・訳文・認識結果 | **既定しない** | 保存時は必須 | 会話終了時／ユーザー操作 |
| D 一時キャッシュ | 解析結果・翻訳キャッシュ | メモリ内のみ | — | プロセス終了で消える |
| E 資源 | モデル・辞書パッケージ | する | 不要（署名・ハッシュで改ざん検出） | 資源管理画面 |
| F 診断情報 | 時間・エラーコード・モデル版（本文なし） | 任意 | 不要 | ローテーション |

# 3. 設定

```kotlin
data class AppSettings(
    val defaultMode: InterpretationMode,        // 既定 WALKIE_TALKIE（D4）
    val languagePair: Pair<LanguageCode, LanguageCode>, // 既定 JA / NE
    val onlineEnabled: Boolean,                 // 既定 false（D8）
    val privacyMode: PrivacyMode,               // 既定 STRICT_LOCAL
    val saveConversationHistory: Boolean,       // 既定 false（§5）
    val ttsEnabled: Boolean,
    val beamSize: Int,                          // 品質設定
    val maxCandidates: Int
)
```

- 【実装済】SharedPreferences のキーは RTranslator 由来のものと MagiTranslator 追加分が混在。
- 【未実装】設定キー一覧とスキーマ版の管理（移行時に既存設定を初期化しない：第3部 §23.5）。

# 4. Room データベース

## 4.1 現行
`AppDatabase`（version = 1, entities = `RecentPeerEntity`）。

## 4.2 追加予定のテーブル（第4部・第5部）

```kotlin
@Entity data class UserTermEntity(
    @PrimaryKey val ruleId: String,
    val sourceLanguage: String, val targetLanguage: String,
    val sourceTerm: String, val normalizedSourceTerm: String,
    val targetCandidates: String,   // JSON 配列
    val strength: String,           // MANDATORY / PREFERRED / ALLOWED / FORBIDDEN / REFERENCE_ONLY
    val scope: String,              // USER_GLOSSARY / DOCUMENT / ...
    val domain: String?,
    val createdAt: Long, val updatedAt: Long,
    val isEnabled: Boolean
)

@Entity data class SavedConversationEntity(   // saveConversationHistory=true のときのみ
    @PrimaryKey val conversationId: String,
    val createdAt: Long, val languagePair: String,
    val encryptedPayload: ByteArray,           // 原文・訳文は暗号化して保存
    val expiresAt: Long?
)
```

- 辞書パッケージ（基本・拡張・専門）は Room ではなく、**読み取り専用の SQLite ファイル**として資源管理で配布・置換する（第4部 04a §6）。ユーザー辞書（書き込み可）とは別ファイル。
- スキーマ変更は Room の Migration で行い、**破壊的移行（fallbackToDestructiveMigration）を使わない**。

# 5. 会話履歴（オプトイン）

- 既定 OFF。ON にする画面で「端末内に保存」「暗号化」「削除方法」を表示する。
- 保存単位は会話。保存期間の上限（例：ユーザー選択の 1日/7日/30日）を持つ。期限切れは起動時に削除。
- 機密指定の会話は保存しない。
- エクスポート（共有）はユーザー操作のみ。
- 【未実装】全体。

# 6. キャッシュ

| キャッシュ | キー（第2部 §11.2） | 寿命 | 状態 |
|---|---|---|---|
| 翻訳メモリ（会話内の訳語統一） | 原語＋言語方向 | 会話 | 【実装済】 |
| 言語解析 IR | 正規化入力＋言語＋解析器版＋辞書版 | メモリ内 LRU | 【未実装】 |
| 翻訳結果 | 正規化入力＋言語方向＋モデル版＋設定 | メモリ内 LRU | 【未実装】（同一入力の再翻訳を省く） |
| EP ベンチ結果 | モデル revision＋Runtime 版＋EP | 永続（小） | 【未実装】（第3部 §15.3.3） |

キャッシュに原文を含めるものはメモリ内に限る。ディスクに書かない。

# 7. 削除・初期化

1. 「会話を消去」：会話メッセージ・翻訳メモリ・文脈を破棄【部分：会話リセットはあるが UI 文言の整理は未】。
2. 「個人辞書を消去」：ユーザー辞書テーブルの全削除【未実装】。
3. 「資源を削除」：モデル・辞書の個別削除（第10部）【部分】。
4. アプリ更新で設定・個人辞書を消さない。

# 8. テスト
- Room Migration テスト（v1 → v2 で `RecentPeerEntity` が残る）。
- 会話履歴 OFF のときディスクに会話内容が書かれないこと（`files/` と DB を走査）。
- 期限切れ会話の削除。
- 暗号化した履歴を別アプリ・バックアップから読めないこと（`allowBackup=false` の確認）。

# 9. 受入条件
1. 会話内容が既定で永続化されない。
2. 保存する場合はオプトイン・暗号化・期限・削除が揃う。
3. 設定・個人辞書がアプリ更新で失われない。
4. Room の移行が非破壊。

# 10. 実装AIへの指示
- 既存の SharedPreferences キーを改名・削除しない（移行コードを書く）。
- 会話内容を SharedPreferences やログに書かない。
- 新テーブルは Migration と同時に追加し、テストを付ける。
