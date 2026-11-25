# MagiTranslator 総合詳細機能実装仕様書
## 第6部：ローカル・オンライン統合 Provider 管理エンジン 詳細実装仕様書

**文書バージョン：** 2.0（Claude 起草。ユーザー原稿なし）
**対象：** 日本語 ⇔ ネパール語（主）、日本語 ⇔ ベトナム語（維持）/ Android 16+（minSdk 36, arm64）
**関連仕様：** 第1部 全体設計（Provider Layer）、第3部 翻訳モデル基盤、第5部 候補統合・品質推定、第10部 資源導入、第11部 性能・セキュリティ
**採用済み判断（00_README）：** オンライン経路は既定 OFF・同意制（実質 STRICT_LOCAL）／実装言語は Java（本書の Kotlin 風型定義は仕様記法であり、実装は不変クラス＋Executor/Future）／VI 維持。

> 状態表記：**実装済**＝コードに存在しテスト/CI で確認、**部分**＝一部のみ、**未実装**＝コードなし。品質・速度は実測値がある場合のみ数値で書く。

---

# 0. 位置づけ

## 0.1 目的
翻訳要求を「どの実行先（Provider）で処理するか」を、言語方向・導入済みモデル・端末状態・プライバシー設定・同意状態から決定し、失敗時の切替（fallback）と外部送信の可否を一元管理する。

翻訳品質の判定（第5部）とモデル内部の実行（第3部）は担当しない。本部はそれらに「誰に頼むか」「頼んでよいか」を提供する。

## 0.2 最重要原則
1. **ローカルが基本。** オンラインは追加経路であり、無くてもアプリは完全に動作しなければならない。
2. **外部送信の可否を決めるのは PrivacyGuard だけ。** Provider・候補生成・品質評価が独自に送信判断をしてはならない。
3. **同意のない外部送信は絶対にしない。** ローカル品質が低いことは同意の代わりにならない。
4. **認証情報をソースに置かない。** APIキーは端末の安全な保存領域かユーザー入力のみ。
5. **Provider の自己申告品質を採用根拠にしない**（第5部 §2.1）。
6. **選択理由を記録する**（原文は記録しない）。

## 0.3 現状（2026-11-25 時点の事実）

| 項目 | 現状 | 状態 |
|---|---|---|
| Provider の種類 | ローカルのみ：Madlad 3B int4（`MADLAD_CACHE`）、HY-MT 1.8B int8、Mozilla/Bergamot（ja・vi）、NLLB（RTranslator 由来） | 実装済 |
| 選択ロジック | `Translator.resolveModeForPair`：ne を含む→Madlad（未導入なら現モード）、音声モードで低遅延希望→Mozilla、HY-MT 導入済→HY-MT | 実装済（固定規則） |
| ネパール語で Madlad 未導入 | `ERROR_MISSING_MADLAD`（37）を返し、`GeneralActivity.showMissingMadladDialog` / `warnIfNepaliWithoutMadlad` で導入を案内 | 実装済 |
| 同時に RAM に載る大モデル | 1つ（`switchModelSync`。Mozilla は併存可） | 実装済 |
| オンライン Provider | なし | 未実装（D8：既定 OFF） |
| PrivacyGuard / 同意 UI | なし（オンラインが無いため実質 STRICT_LOCAL） | 未実装 |
| fallback（別 Provider への切替） | なし。失敗はエラー表示 | 未実装 |
| キャンセル | 要求単位のキャンセル（ORT `RunOptions.setTerminate`） | 実装済（CI ビルド成功、実機未確認） |
| エラー分類 | `TranslationEngineErrorCode`（第3部 §18.1） | 実装済（同上） |

---

# 1. 全体構成

```text
ProviderManagementEngine
├── ProviderRegistry          … 登録・能力・導入状態
├── ProviderCapabilityProbe   … 導入済みファイル・RAM・ORT 実行可否
├── ProviderSelector          … 方向・方針・端末状態から順位付き計画を作る
├── PrivacyGuard              … 外部送信の唯一の判定者
├── ConsentManager            … 同意の取得・撤回・記録
├── ExecutionCoordinator      … 計画どおり実行・タイムアウト・キャンセル・fallback
├── RateLimiter / RetryPolicy … オンライン用の制限と再試行
├── CredentialStore           … 認証情報（Android Keystore）
└── ProviderTelemetry         … 本文を含まない選択・失敗の記録
```

依存方向：`TranslationEngine（第1部）→ ProviderSelector → ExecutionCoordinator → Provider 実装（第3部 TranslationModel を包む）`。UI は Provider を直接呼ばない。

---

# 2. データモデル

## 2.1 ProviderDescriptor

```kotlin
data class ProviderDescriptor(
    val providerId: String,              // "local.madlad3b.int4", "local.hymt1.8b.int8", "local.mozilla.ja", "remote.<name>"
    val kind: ProviderKind,              // LOCAL_MODEL, REMOTE_API, LEGACY
    val supportedPairs: Set<LanguagePair>,
    val requiredArtifacts: List<String>, // models.tsv の name（例 "Madlad.zip"）
    val modelFamily: String?,            // 第5部 §11.3 の独立性判定用（"madlad", "hy-mt", "bergamot"）
    val estimatedPeakMemoryMb: Int?,     // 実測値のみ。未測定は null
    val sendsDataOffDevice: Boolean,
    val licenseId: String?,
    val version: String
)

enum class ProviderKind { LOCAL_MODEL, REMOTE_API, LEGACY }
```

## 2.2 ProviderAvailability

```kotlin
data class ProviderAvailability(
    val providerId: String,
    val installed: Boolean,              // 全 requiredArtifacts が検証済みで配置されている（第10部）
    val loadable: Boolean?,              // 直近のロード結果。未試行は null
    val lastFailure: TranslationEngineErrorCode?,
    val disabledReason: DisabledReason?  // NOT_INSTALLED, FAILED_REVISION, USER_DISABLED, NO_CONSENT, OFFLINE, RATE_LIMITED
)
```

## 2.3 ProviderPlan

```kotlin
data class ProviderPlan(
    val requestId: String,
    val primary: String,                 // providerId
    val fallbacks: List<String>,         // 試す順。STRICT_LOCAL では REMOTE を含めない
    val reasonCode: String,              // "pair-ne→madlad", "voice-low-latency→mozilla" 等（本文なし）
    val privacyMode: PrivacyMode
)
```

## 2.4 PrivacyMode / 同意

```kotlin
enum class PrivacyMode { STRICT_LOCAL, LOCAL_PREFERRED, ONLINE_ALLOWED }

data class ConsentRecord(
    val providerId: String,
    val granted: Boolean,
    val scope: ConsentScope,             // PER_REQUEST, PER_SESSION, PERSISTENT
    val grantedAtMillis: Long,
    val policyVersion: String            // 同意時に表示したプライバシーポリシーの版
)
```

**既定値は STRICT_LOCAL。** ONLINE_ALLOWED への変更はユーザーの明示操作のみで行い、自動で上げない。

---

# 3. Provider 選択

## 3.1 選択の入力
言語方向、翻訳モード（テキスト／対面会話／トランシーバー）、低遅延希望（`preferMozilla`）、導入状態、直近の失敗、空き RAM、PrivacyMode、同意、ネットワーク状態。**端末名による固定判定は禁止**（第3部 §15.3.2）。

## 3.2 既定の方向別方針（現行規則を仕様化）

| 方向 | primary | fallback（ローカル） | 根拠 | 状態 |
|---|---|---|---|---|
| ja→ne / ne→ja | Madlad 3B int4（MADLAD_CACHE） | なし（Mozilla・HY-MT はネ語非対応） | D3。ネ語を扱える導入済みモデルが Madlad のみ | 実装済 |
| ja⇔vi（テキスト） | HY-MT 1.8B | Mozilla（ja・vi） | D3 | 実装済（fallback は未実装） |
| ja⇔vi（音声・低遅延希望） | Mozilla | HY-MT | 遅延優先の設定 | 実装済（fallback は未実装） |
| その他 | 現モード | — | RTranslator 互換 | 実装済 |

**禁止：** ネパール語を、ネ語を扱えないモデルへ流すこと（現行は `ERROR_MISSING_MADLAD` で明示的に失敗させる。この挙動を維持する）。

## 3.3 選択アルゴリズム（未実装部分を含む）
1. 方向に対応し `installed=true` の Provider を集める。
2. `FAILED_REVISION`（第10部 §8 の失敗記録）・ユーザー無効化を除外する。
3. PrivacyGuard で `sendsDataOffDevice=true` のものを判定し、不可なら除外する。
4. 3.2 の方針表で順位付けする。
5. primary が空なら、方向別の不足資源を `MissingResource` として返し、UI が導入を案内する（現行の Madlad ダイアログを一般化）。

---

# 4. PrivacyGuard

## 4.1 判定式（第5部 §4.4 と同一。変更禁止）

```kotlin
val allowRemoteExecution =
    privacyPolicy.mode != PrivacyMode.STRICT_LOCAL &&
    privacyPolicy.remoteTranslationConsent
```

## 4.2 送ってはならないもの
STRICT_LOCAL の原文、機密指定の原文、送信未許可の履歴・文脈（第2部 §7.3）、ローカル専用の辞書・個人用語（第4部 §1.6）、認証情報。

## 4.3 文脈の扱い
オンラインへ送るときは、翻訳対象の原文だけを送る。会話文脈・翻訳メモリ・用語集は**既定で送らない**（送る場合は別の同意項目）。

## 4.4 状態
**未実装**（オンライン経路が無いため不要だった）。オンライン Provider を追加する前に必ず実装し、テスト（§9.3）を通すこと。

---

# 5. 実行・fallback・タイムアウト

## 5.1 ExecutionCoordinator
- 計画の primary を実行し、`TranslationEngineErrorCode` が **再試行可能**（`isRetryable`）な場合だけ fallback へ進む。
- `OUT_OF_MEMORY` 後は同じ Provider・同じ設定で即時再実行しない（第3部 §18.5）。
- `INFERENCE_CANCELLED` は fallback しない（ユーザーが止めた）。
- fallback で得た結果には `providerId` と `fallbackReason` を付ける。

## 5.2 fallback の順序（第3部 §18.2）
同一モデルの別 EP → 同一モデルの CPU → 軽量ローカルモデル → 旧互換経路 → **同意がある場合に限り**オンライン。

## 5.3 タイムアウト
Provider 選択・モデルロード・推論・オンライン応答・全体を別々に持つ。**数値は実機測定後に決める**（Madlad 3B のロード・推論時間は Pixel 10 Pro XL で未測定）。

## 5.4 状態
キャンセルは実装済。fallback・個別タイムアウトは未実装。

---

# 6. オンライン Provider（将来）

## 6.1 追加条件
1. 第4節 PrivacyGuard と同意 UI が実装済み。
2. 送信先・保存期間・学習利用の有無をプライバシーポリシーに明記（**運営者名・連絡先はユーザーが記入**。推測で埋めない）。
3. APIキーは `CredentialStore`（Android Keystore 暗号化）に保存。**ソース・リソース・ログ・CI 成果物に置かない。**
4. HTTPS のみ。証明書検証を無効化しない。
5. RateLimiter：429 は指数バックオフ＋jitter、回数上限あり。
6. 料金が発生する場合は使用量の表示と上限設定。

## 6.2 品質の扱い
オンライン結果も第5部の hard 検査（否定・数値・疑問語・地名等）を通す。**オンラインだから正しいと扱わない。** 同一系列モデルとの合意は独立投票として数えない（第5部 §11.3）。

---

# 7. ログ・テレメトリ
記録可：requestId、providerId、方向、選択理由コード、fallback 理由、エラーコード、所要時間、PrivacyMode。
記録不可：原文・訳文・音声認識結果・APIキー。現行の `PrivacyLog`（release で本文を出さない）を継続使用する。**実装済（本文マスク）／構造化記録は未実装。**

---

# 8. 禁止事項
- PrivacyGuard 以外の場所で外部送信を判断する。
- STRICT_LOCAL を設定変更なしに緩める。品質低下を理由にオンラインへ自動再送する。
- 認証情報のハードコード・ログ出力。
- ネパール語をネ語非対応モデルへ流す。
- 同一モデル系列の一致を独立した合意として扱う。
- 実測していない速度・品質を Provider 選択の固定根拠にする。

---

# 9. テスト

## 9.1 Unit（純 Java、CI の javac/java ステップに追加）
- 方向別方針表（3.2）：ne を含む方向で Madlad 以外が primary にならない。
- 未導入時に `MissingResource` が返る。
- 失敗記録のある revision が除外される。
- STRICT_LOCAL で REMOTE が計画に入らない（全組み合わせ）。
- `isRetryable=false` のエラーで fallback しない。CANCELLED で fallback しない。

## 9.2 Integration
- Madlad 未導入でネ語を選んだときのダイアログ表示（既存動作の回帰）。
- 大モデル切替時に 1 つしか RAM に載らない。

## 9.3 Privacy
- STRICT_LOCAL・同意なし・機密指定のそれぞれで、ネットワーク呼び出しが 0 回であることをモックで検証。
- 同意撤回後に送信されない。

---

# 10. 受入条件
1. Provider 選択が一箇所に集約され、理由コードが記録される。
2. ネパール語は Madlad のみで処理され、未導入時は導入案内が出る（現行維持）。
3. STRICT_LOCAL が既定で、テストで外部送信 0 回が確認されている。
4. fallback は再試行可能なエラーに限られ、OOM・キャンセルで暴走しない。
5. 認証情報がリポジトリ・ログに存在しない（シークレットスキャンで確認）。
6. オンライン Provider を追加する場合、§6.1 をすべて満たしている。

# 11. 実装 AI への指示
- まず `Translator.resolveModeForPair`・`switchModelSync`・`ErrorCodes`・`TranslationEngineErrorCode` を読む。仕様のクラス名が既存コードにあると決めつけない。
- 現行の選択規則を変えずに `ProviderSelector`（純 Java）へ移し、テストで同一の選択結果を確認してから拡張する。
- オンライン Provider の実装は、ユーザーの明示指示とプライバシーポリシーの記入が済むまで行わない。
- 報告では実装済／未実装／未検証を区別する。
