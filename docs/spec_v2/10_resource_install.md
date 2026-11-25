# MagiTranslator 総合詳細機能実装仕様書
## 第10部：モデル・辞書・言語資源の導入管理 詳細実装仕様書

**文書バージョン：** 2.0（Claude 起草。ユーザー原稿なし）
**対象：** Android 16+（minSdk 36, arm64）、Pixel 10 Pro XL を主対象
**関連仕様：** 第3部 §4・§17（Manifest・配布・ロールバック）、第4部 追補A（辞書データ）、第6部（Provider）、第7部（ASR）、第11部（性能・セキュリティ）、第12部（DB）
**採用済み判断：** 署名は Ed25519（公開鍵をアプリに埋め込み、秘密鍵は CI secret）／実装は Java。

> 状態表記：**実装済**／**部分**／**未実装**。容量・時間は実測値のみ数値で書く。

---

# 0. 位置づけ

## 0.1 目的
初期化された（ゼロからの）端末にアプリを入れた直後から、必要なモデル・辞書・言語資源を**安全に・途中で壊れずに・取り消し可能に**導入し、更新・削除・復旧まで管理する。

## 0.2 原則
1. 検証前のファイルを使わない（staging → 検証 → 原子的置換）。
2. ハッシュ＝破損検出、署名＝配布元の真正性。ハッシュだけを真正性の根拠にしない。
3. 失敗した版は記録し、自動で再有効化しない。
4. ライセンス表示を同梱物と一緒に管理する。
5. 容量不足を事前に検知し、途中まで書いて止まる状態を作らない。
6. ネットワークなしでも、導入済みの資源だけで翻訳できる。

## 0.3 現状（事実）

| 項目 | 現状 | 状態 |
|---|---|---|
| 資源一覧 | `models/models.tsv`：16ファイル（Whisper 6、NLLB 4、TranslationDictionaries.zip、Tatoeba.zip、Mozilla_ja/vi.zip、Madlad.zip、HY-MT.zip）。名前・SHA-256・サイズ・上流URL | 実装済 |
| 配布元 | GitHub Release `models-v1`（16ファイル、計 約5.57GB、上流からミラー済み） | 実装済 |
| ハッシュ固定 | `ModelSources.java`（models.tsv と CI テスト `ModelSourcesTest` で一致確認） | 実装済 |
| 導入手順 | `Downloader` → `.staging/` に保存 → サイズ＋SHA-256 照合 → rename で正式名へ（`ModelStore.install`） | 実装済（CI ビルド成功、実機未確認） |
| 旧版保持 | 空きが 2GB 超なら `.previous/` に移動（`PREVIOUS_KEEP_MARGIN_BYTES`） | 実装済（同上） |
| 失敗記録 | `.failed.tsv`（名前・SHA-256） | 実装済（同上） |
| rollback | `ModelStore.rollback()` は存在するが UI・自動起動なし | 部分 |
| 署名 | `ManifestSignature`（Ed25519）。公開鍵・署名は空＝UNSIGNED でハッシュのみ。INVALID ならダウンロード拒否（`SIGNATURE_INVALID=5`） | 部分（鍵未設定） |
| 署名ツール | `tools/ManifestSigner.java`（genkey / sign）、手順 `docs/MODEL_SIGNING.md` | 実装済（sign は未実行） |
| zip 展開 | `ZipSafety`（Zip Slip 防止） | 実装済 |
| 空き容量確認 | `Global` で空きMBを取得 | 部分（導入計画全体の事前見積りは無し） |
| ネパール語 Whisper（調整済み） | `files/Whisper_ne/` に手動配置した場合のみ使用。配布なし | 未実装（配布） |
| 辞書パッケージ | 無し（用語はコード内の少数語） | 未実装 |
| ライセンス表示 UI | 無し | 未実装 |

---

# 1. 資源パックの定義

## 1.1 パック単位

| パック | 内容（models.tsv の name） | 必須性 | 用途 |
|---|---|---|---|
| CORE_ASR | Whisper_*.onnx（6） | 音声を使うなら必須 | 音声認識 |
| CORE_NE | Madlad.zip | ネ語に必須 | ja⇔ne 翻訳 |
| CORE_VI | HY-MT.zip、Mozilla_ja.zip、Mozilla_vi.zip | ベトナム語を使うなら | ja⇔vi 翻訳 |
| LEGACY_NLLB | NLLB_*.onnx（4） | 任意 | RTranslator 互換（ja/ne/vi では未使用の可能性。要調査） |
| AUX_DATA | TranslationDictionaries.zip、Tatoeba.zip | RTranslator 互換 | 既存機能用（用途の要調査） |
| OPT_ASR_NE | 調整済みネパール語 Whisper（ilprl/Whisper-Small-Nepali-ASR-OSLR54 由来、FP32 約1GB） | 任意 | ネ語音声の改善（小規模測定で WER 139%→50.9%、15発話のみ） |
| DICT_BASE / DICT_DOMAIN | 第4部追補Aの辞書パッケージ（SQLite＋NOTICE） | DICT_BASE は推奨 | 用語・固有名詞・数字表現 |

> LEGACY_NLLB・AUX_DATA が ja/ne/vi の経路で実際に使われるかは**未調査**。使われないと確認できれば初回導入から外し、容量を節約できる（要コード調査・要承認）。

## 1.2 PackManifest

```kotlin
data class PackManifest(
    val packId: String,                 // "CORE_NE"
    val packVersion: String,            // "models-v1"
    val files: List<ModelFileEntry>,    // 第3部 §4.2（relativePath, sizeBytes, sha256, required）
    val unpackedSizeBytes: Long?,       // 実測値のみ。未測定は null
    val licenseIds: List<String>,
    val noticeFile: String?,            // ライセンス表示文の相対パス
    val dependsOn: List<String>,        // 例: OPT_ASR_NE → CORE_ASR
    val minAppVersionCode: Int
)
```

現行の `models.tsv` は全ファイルを1表で持つ。パック分けは models.tsv に `pack` 列を追加して表現する（後方互換：列が無ければ従来どおり）。**未実装。**

---

# 2. 初回起動（ゼロからの端末）

## 2.1 流れ

```text
インストール → 起動 → プライバシーポリシー確認（既存のチェック） → 用途の選択
  （ネパール語／ベトナム語／音声を使うか）→ 必要パックと合計サイズの提示
  → 空き容量の事前確認 → 通信方法の確認（Wi-Fi 推奨の表示）
  → ダウンロード（進捗は Notification.ProgressStyle：実装済）
  → 検証 → 導入 → 初回の短い動作確認（スモークテスト）→ 使用開始
```

## 2.2 既定の選択
D4（ja・ne・vi の3言語、既定 ja⇔ne、初回は WalkieTalkie）に合わせ、**既定は CORE_ASR＋CORE_NE＋DICT_BASE**。ベトナム語は選択式。**現行は全ファイルを一括ダウンロード**（部分）。

## 2.3 容量見積り
- 必要容量 = Σ(ダウンロードサイズ) + Σ(展開後サイズ) + staging の一時領域 + 旧版保持分（任意）+ 安全余白。
- **zip の展開後サイズは未測定。** 測定してから `unpackedSizeBytes` に記入する。未測定のパックは「ダウンロードサイズ×2」を暫定上限として確認し、表示では「目安」と明記する。
- 不足時は開始しない。どのパックを外せば足りるかを示す。

## 2.4 オフラインでの初回起動
資源が無い状態では翻訳できないことを明示し、何が必要かを示す（現行の Madlad 案内ダイアログを全パックに一般化）。

---

# 3. ダウンロード

## 3.1 手順（第3部 §17.3 の具体化）
1. Manifest（models.tsv 由来）の署名検証（UNSIGNED は現行どおり許可、INVALID は拒否）。
2. 容量確認（§2.3）。
3. `.staging/` へ保存。
4. サイズ照合 → SHA-256 照合。
5. zip は `ZipSafety` で展開先を検証して staging 内で展開。
6. rename で正式名へ。旧版は条件付きで `.previous/` へ。
7. 失敗時は staging を削除し `.failed.tsv` に記録。

**状態：** 1・3・4・6・7 は実装済（実機未確認）。zip の staging 内展開と 2 の事前見積りは未実装。

## 3.2 再開（レジューム）
- 中断（通信断・アプリ終了・再起動）後、staging の部分ファイルから再開できること。HTTP Range を使う。
- 再開後も最終的な SHA-256 照合は必須。部分ハッシュを完了扱いにしない。
- **注意：** staging 化の変更により、旧ビルドで途中だったダウンロードは最初からやり直しになる（既知）。
- 状態：PRDownloader の再開機能に依存（部分）。再開の実機試験は未実施。

## 3.3 通信
- HTTPS のみ。リダイレクト先も HTTPS。
- 再試行は回数上限＋指数バックオフ＋jitter。checksum 不一致・署名不正は再試行で解決したと扱わない（第3部 §18.5）。
- モバイル回線では開始前に合計サイズを示して確認する。

---

# 4. 署名

## 4.1 方式
- Ed25519。署名対象は models.tsv から作る正規化 Manifest（名前・SHA-256・サイズを固定順で連結）。
- 公開鍵はアプリに埋め込む（`ManifestSignature`）。秘密鍵は CI secret `MAGI_MANIFEST_SIGNING_KEY` のみ。リポジトリに置かない。
- 鍵更新：新旧2つの公開鍵を一定期間併記できる構造にする（**未実装**）。

## 4.2 有効化までに必要なこと（ユーザー作業を含む）
1. `ManifestSigner genkey` で鍵を作る（ユーザー）。
2. 秘密鍵を CI secret に登録（ユーザー）。
3. 公開鍵と署名をアプリに入れる。
4. **実機で Ed25519 が使えることを確認**（Android 13+ の Conscrypt。未確認）。UNSUPPORTED の端末では拒否せずハッシュのみ。

---

# 5. 更新とロールバック

## 5.1 更新
- 新しい版は別 revision として staging に置き、検証後に切替える。利用中のモデルを直接上書きしない。
- 翻訳中（ModelLease 保持中、第3部 §16）は切替を待つ。
- 切替後にスモークテスト（短文の翻訳・認識）を行い、失敗なら自動で旧版へ戻す。

## 5.2 ロールバック
- 条件：ロード失敗、Tokenizer 不整合、ランタイムクラッシュ、空出力、品質回帰の報告。
- 旧版が `.previous/` に無い場合（空き不足で上書きした場合）は再ダウンロードで復旧する。
- 戻した版は `.failed.tsv` に記録し、自動で再有効化しない。
- **状態：** `rollback()` はあるが、トリガ（自動・UI）は未実装。

## 5.3 版管理の限界（現状）
`.previous/` はファイル単位の1世代のみ。第3部 §17.4 の revisions/ ディレクトリ・active.json・previous.json（パック単位の複数世代）は未実装。5.57GB 規模では2世代保持に追加で同程度の容量が要るため、**大モデルは1世代＋再ダウンロード復旧を既定**とする。

---

# 6. 辞書・言語資源パッケージ（第4部 追補A）

## 6.1 形式
- SQLite 1ファイル＋`NOTICE.txt`＋`pack.json`（PackManifest）。
- 各エントリーに出典・ライセンス・検証状態（VerificationStatus）を持たせる。
- 同梱可否は `license_registry.tsv` で判定し、「要確認」「不許可」のソースは入れない（ビルド時に検査）。

## 6.2 導入
- モデルと同じ staging → 検証 → 置換の手順（`ModelStore` を流用）。
- ユーザー辞書は別ファイル（第12部 DB）で、パック更新で上書きしない。

## 6.3 状態
すべて未実装。最初に作るのは独自構築辞書（高リスク用語・数字/通貨・地名・会話定型句 100〜300語）と、CLDR・Wikidata 由来の数字・通貨・地名（追補A §8）。

---

# 7. ライセンス表示（NOTICE）

## 7.1 必要な表示
| 対象 | 表示が必要な理由 |
|---|---|
| RTranslator（本アプリの元） | 元プロジェクトのライセンス条件 |
| ONNX Runtime、各ネイティブライブラリ | 各ライセンス |
| Madlad-400、HY-MT、Bergamot、NLLB、Whisper の各モデル | モデルライセンス（**NLLB は CC-BY-NC 系の可能性**、第3部 §23.3） |
| 調整済みネパール語 Whisper | **ライセンス・作者表示は未確認（ユーザー確認待ち）** |
| 辞書データ（Wiktionary 由来は CC BY-SA 等） | 表示・継承義務 |

## 7.2 UI
設定画面に「ライセンスと出典」を設け、導入済みパックの NOTICE を一覧表示する。パックを削除したら表示も消す。**未実装。**

## 7.3 禁止
ライセンス未確認の資源を `models-v1` やアプリに同梱しない。調整済みネパール語 Whisper は、ライセンス確認後に OPT_ASR_NE として配布する。

---

# 8. 失敗の記録と復旧

```kotlin
data class InstallFailure(
    val name: String,
    val sha256: String,
    val reason: InstallFailureReason,   // SIZE_MISMATCH, HASH_MISMATCH, SIGNATURE_INVALID, UNZIP_UNSAFE, NO_SPACE, IO_ERROR, SMOKE_TEST_FAILED
    val atMillis: Long
)
```

- 現行の `.failed.tsv` は name・sha256 のみ。reason と時刻の追加は未実装。
- ユーザーが「再試行」した場合だけ記録を消してやり直す。

---

# 9. 禁止事項
- 検証前のファイル、途中まで書いたファイルを使う。
- 利用中のモデルを直接上書きする。
- ハッシュだけで真正性を保証したと書く。
- 秘密鍵をリポジトリ・アプリ・ログに置く。
- ライセンス未確認の資源を同梱・配布する。
- 容量を確認せずに大きなダウンロードを始める。
- 失敗した版を自動で再有効化する。

---

# 10. テスト

## 10.1 Unit（純 Java、CI）
- 既存：`ModelStoreTest`（内容違い・サイズ違い・未登録名・更新拒否時に旧版維持・rollback・署名の正常/改ざん/半設定）、`ModelSourcesTest`、`ZipSafetyTest`。**実装済。**
- 追加：パック依存関係の解決、容量見積り、`InstallFailure` の記録/解除、models.tsv の pack 列の後方互換。

## 10.2 Device（未実施）
- ゼロからの端末で、Wi-Fi・モバイル回線・途中切断・アプリ強制終了・再起動の各条件で全パックが導入できる。
- 空き容量不足で開始されない。
- 導入後、機内モードで ja⇔ne のテキスト翻訳と音声認識が動く。
- rollback で旧版に戻り、翻訳できる。

## 10.3 Security
- 改ざんファイル、署名不正、Zip Slip を含む zip、異常に大きいファイル、パス区切りを含む名前を拒否する。

---

# 11. 受入条件
1. 初回起動で必要パックと合計容量が示され、容量不足なら開始しない。
2. 途中切断から再開でき、最終的に SHA-256 照合を通ったファイルだけが使われる。
3. 署名鍵設定後、INVALID は導入されず、UNSIGNED/UNSUPPORTED の扱いが仕様どおり。
4. 更新失敗時に旧版または再ダウンロードで復旧できる。
5. 導入済み資源のライセンス表示が UI で確認できる。
6. 機内モードで導入済み資源による翻訳が動く（実機で確認）。

# 12. 実装 AI への指示
- まず `ModelSources`・`ModelStore`・`ManifestSignature`・`Downloader`・`ZipSafety`・`models/models.tsv`・`.github/workflows/mirror-models.yml` を読む。
- 変更前後で `ModelStoreTest`・`ModelSourcesTest` が通ることを確認する（CI の javac/java ステップ）。
- Android 側はここでコンパイルできない前提で、API・import・ラムダ捕捉を目視確認し、CI で確かめる。
- 容量・時間・WER などの数値は実測したものだけ書く。調整済みネパール語 Whisper の配布はライセンス確認後。
