# MagiTranslator 総合詳細機能実装仕様書
## 第11部：Android 性能・メモリ・安定性・セキュリティ 詳細実装仕様書

**文書バージョン：** 2.0（Claude 作成）
**対象端末：** Pixel 10 Pro XL（D5：Android 16+ 特化）。他端末は動作保証外（第3部 §21.3 の「特定端末での動作を全端末の保証としない」）。
**型表記：** Kotlin 風、実装は Java。
**状態表記：** 【実装済】【部分】【未実装】（e4823e1 時点）。

---

# 0. 目的
大規模モデル（Madlad 3B int4、HY-MT 1.8B int8、Whisper-small、任意で Whisper_ne）を1台の端末で安定して動かし、利用者のデータと端末を守る。

# 1. 前提と確定事項

| 項目 | 値 | 根拠 | 状態 |
|---|---|---|---|
| minSdk / target / compile | 36 / 36 / 36 | D5（ユーザー指示: Android 16+） | 【実装済】 |
| ABI | arm64-v8a のみ | 対象端末 | 【実装済】 |
| 16KB ページ | `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`、jniLibs は `useLegacyPackaging true`（圧縮）、CI で ELF LOAD 整列を報告 | Android 15+ の 16KB ページ端末 | 【実装済】 |
| largeHeap | `android:largeHeap="true"` | 大規模モデル | 【実装済】 |
| allowBackup | `false` | 会話・モデルのバックアップ防止 | 【実装済】 |
| minify / shrink | 無効（`minifyEnabled false`） | ORT/JNI の反射・ネイティブ参照の破損回避 | 【実装済（意図的）】 |
| 署名 | 固定のサイドロード鍵 `app/magi-sideload.jks`（パスワード公開）。環境変数・CI secret で上書き可 | 初回インストールの簡便さ | 【部分：他者へ配布する前に鍵の差し替え必須】 |

# 2. メモリ

## 2.1 構成要素
| 要素 | 概算 | 状態 |
|---|---|---|
| Madlad 3B int4（encoder＋cached decoder） | 数 GB 級 | 実測値なし【未実装：計測】 |
| HY-MT 1.8B int8 | 約 2GB 級 | 実測値なし |
| Whisper-small（6分割 ONNX） | 数百 MB | 実測値なし |
| Whisper_ne fp32 | 約 1GB（ファイル合計） | 書き出し時の実測（tools/whisper_ne/RESULTS.md） |

**ピークメモリの端末実測は未取得。** 第3部 §14.4 の項目（重み・セッション・KV キャッシュ・テンソル・複数候補）で計測し、`docs/eval/` に記録する。

## 2.2 方針
1. 翻訳モデルは同時に1つだけ常駐させる（言語ペア切替時に解放）【部分：切替時の解放ポリシーは明文化されていない】。
2. 翻訳は直列化（公平ロック）で同時実行による倍増を防ぐ【実装済】。
3. `OutOfMemoryError` を捕捉して表示し、処理スレッドを落とさない【実装済（2a9700f）】。OOM 後に同じ設定で即再実行しない（第3部 §18.5）。
4. 【未実装】`ComponentCallbacks2.onTrimMemory` で、使用中でないモデルを解放する（第3部 §16.7）。
5. 【未実装】ロード前の空きメモリ確認（`ActivityManager.MemoryInfo`）と、不足時の警告。
6. ne の候補生成（whole/sentences/terms/名前変種/beam N-best）は候補数×デコード回数でメモリと時間が増える。候補数の上限を設定で持つ【部分：コード内定数】。

# 3. 性能

## 3.1 計測項目（第3部 §19.6 と共通）
モデルロード時間、初回翻訳時間、2回目以降の時間、デコーダ 1 token あたりの時間、p50/p95、ピークメモリ、CPU 使用率、端末温度、電池消費、30 分連続使用の安定性。
**いずれも実機で未計測。** 計測手順（adb で `dumpsys meminfo`、`dumpsys batterystats`、アプリ内タイマーの集計）を `docs/eval/device/` に置く。

## 3.2 スレッド
- ORT の intra-op スレッド数は端末のコア数に合わせる。UI・録音・TTS を妨げないよう上限を設ける【部分：既定値のまま、比較未実施】。
- Execution Provider は CPU 基準。XNNPACK / NNAPI / QNN は実測比較後に採否（第3部 §15.3）【未実装】。

## 3.3 発熱（サーマル）
- 【未実装】`PowerManager.getThermalHeadroom()` / `addThermalStatusListener` で温度状態を監視し、`THERMAL_STATUS_SEVERE` 以上では候補数を減らす・スレッド数を下げる。
- 品質を落とす切替を行った場合はログ（本文なし）に記録し、ユーザーに表示する。

## 3.4 起動
- 初回は大容量ダウンロード（models-v1：16 ファイル・5.57GB）が必要。ダウンロード中も UI は応答する【実装済】。
- 起動時に大規模ベンチマークを強制しない（第3部 §15.3.3）。

# 4. 安定性

| 項目 | 状態 |
|---|---|
| ANR：UI スレッドでのロック待ち禁止 | 【実装済】（`resetConversationMemory` をロック不要化、キャンセルは推論を待たない） |
| 前景サービス（マイク・データ同期） | 【実装済】 |
| プロセス終了後の再起動 | モデルは再ロード。会話は失われる（保存しない設計） |
| 予測型「戻る」（Android 16） | 【実装済】（`OnBackPressedCallback`） |
| ダウンロード進捗通知 `Notification.ProgressStyle` | 【実装済】 |
| クラッシュ収集 | 【未実装】（外部送信なしの方針のため、端末内ログ＋ユーザーが手動で共有する方式を推奨） |

# 5. セキュリティ

## 5.1 データ
1. 会話（原文・訳文・音声）は永続化しない（第12部）【実装済】。
2. release ログに本文を出さない（`PrivacyLog`）【実装済】。debug ビルドでは出るため、**配布は release のみ**。
3. `allowBackup=false`【実装済】。
4. 外部送信なし（D8）。INTERNET 権限はモデル・資源のダウンロードにのみ使う【実装済】。

## 5.2 ダウンロード資源
1. 各ファイルの SHA-256 固定（`ModelSources.java`, `models/models.tsv`）【実装済】。
2. staging → 検証 → 置換、途中のファイルを使わない（`ModelStore`）【実装済（f2c88a9）】。
3. Zip 展開のパストラバーサル防止（`ZipSafety`）【実装済】。
4. マニフェストの Ed25519 署名検証【部分：仕組みのみ。鍵が空＝ハッシュのみで動作】。
5. HTTPS のみ。cleartext を許可しない【要確認：network security config の明示】。

## 5.3 署名鍵
- 現行のサイドロード鍵とパスワードは**公開**されている。本人の端末で使う限りは許容。**他者へ配布する前に**新しい鍵を生成し、CI secret（`MAGI_KEYSTORE_*`）に登録する【未実装：ユーザー作業】。
- 鍵を差し替えると既存インストールの上書き更新ができない（Android の署名一致要件）。差し替え時は一度アンインストールが必要であることを告知する。

## 5.4 権限
RECORD_AUDIO、POST_NOTIFICATIONS、Bluetooth 系（Conversation モード用）、位置情報（BLE スキャンの旧 API 要件、`neverForLocation` 指定あり）。**使わない権限は削除する**：Conversation モードを使わない運用なら Bluetooth・位置情報を外せる【要判断】。

## 5.5 プライバシーポリシー
- `privacy/Privacy_Policy_MagiTranslator_ja.md` は運営者名・連絡先がプレースホルダ【未実装：ユーザー作業。推測で埋めない】。
- アプリ内の同意チェックボックス（`privacy_check_box`）のリンクは RTranslator 本家（niedev/RTranslator v2.00）を指したまま【未実装：差し替え要】。

# 6. テスト

| 種別 | 内容 | 状態 |
|---|---|---|
| CI | 純 Java テスト（jane, ZipSafety, ModelSources, ModelStore, validator パリティ）、assembleRelease、16KB ELF 整列レポート | 【実装済】 |
| 実機 | §3.1 の計測、30 分連続、低メモリ時（他アプリ多数起動）、発熱時 | 【未実装】 |
| セキュリティ | 改ざんファイル・途中ファイル・不正 zip・不正署名 | 【部分：単体テストあり、実機未】 |
| ログ検査 | release ビルドの logcat に本文が出ないこと | 【未実装：手順化】 |

# 7. 受入条件
1. §3.1 の全項目が実機で記録されている。
2. 30 分連続使用で異常終了なし。
3. 低メモリ・発熱時にクラッシュせず、品質を落とす切替を行った場合は表示される。
4. release の logcat に原文・訳文・認識結果が出ない。
5. 他者配布前に署名鍵が差し替えられ、プライバシーポリシーの運営者情報とリンクが確定している。

# 8. 実装AIへの指示
- minSdk・ABI・16KB 設定を根拠なく変えない（D5）。
- minify を有効にする場合は、ORT・JNI・反射の keep ルールを用意し、release で全モデル経路を実機確認してから。
- 性能改善は実測の前後比較なしに主張しない。
- 署名鍵・パスワード・API キーをリポジトリに追加しない（既存のサイドロード鍵は例外として明示されている）。
- プライバシーポリシーの運営者名・連絡先を推測で埋めない。
