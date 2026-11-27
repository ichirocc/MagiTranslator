# 残件一覧（2026-11-26 時点）

## 進行中（結果待ち）
| # | 項目 | 状態 |
|---|---|---|
| 1 | 高速化の測定（40文） | 実行中。自動重大誤り率が上がれば取り消す |
| 2 | magi.13 の main マージとリリース | CI 結果待ち |
| 3 | 「ダウンロードしたデータをすべて削除」ボタン | push 済み・CI 結果待ち。main には magi.14 で入れる予定 |

## Claude が行う（未着手・中断中）
| # | 項目 | 状態・メモ |
|---|---|---|
| 4 | モデルの確からしさ（対数確率）を候補選びに使う | 実装済み・`docs/wip/logprob-signal.patch` に保存。取り込みと40文測定が残り |
| 5 | 日→ネで否定の一致が下がった文の特定と修正 | 198文で 96.0%→90.9%。未調査 |
| 6 | nj12「価格」→「賞金」の語義ずれ | 通貨・数字は修正済み。今の検査では検出できない |
| 7 | nj17「ネパールから」の作り話 | 「要確認」で警告は出る。正しい訳はまだ作れない |
| 8 | ~~モデルを個別に選んで消す機能~~ | 済み：「モデル管理」画面の各モデルの削除ボタンは元からある。調べたら消し残しが2つあったので直した：①ロールバック用の旧版（`.previous/`、Madlad では zip 1.7 GB）と途中のダウンロード（`.staging/`）が残っていた →`ModelStore.deleteAll`（テストあり）、②PLaMo を消してもエンジンが削除済みファイルを mmap したままで、アプリ終了まで容量とメモリが空かなかった → 削除直後（翻訳中なら次の翻訳時）に閉じる |
| 9 | 会話集に無い文での評価 | 198文は会話集由来で辞書ありが有利 |
| 10 | ~~Jetifier の撤去~~ | 済み：RadioGroupPlus を自前の `widgets/NestedRadioGroup` に置き換え、`android.enableJetifier=false` |
| 11 | ~~`libsqlite4java.so` の 16KB ページ対応~~ | 済み：SQLite を同梱ビルドに置き換え（9d534f8）。残る 4KB 整列は `libdjl_tokenizer.so`・`libortextensions.so`・`libonnxruntime_extensions4j_jni.so`（配布済みバイナリ、新版なし） |
| 12 | 多義語の文脈選択、主語・目的語の入れ替え検出 | 仕様書の未実装項目 |
| 20 | Madlad-v2（4ビット埋め込み・出力層分割） | **使わない**（2026-11-27 決定）：より良い PLaMo がある（80文 chrF 日→ネ/ネ→日：PLaMo 2B 52.7/38.0、0.8B 45.3/34.1、個人アダプタ付き 53.1/44.4、Madlad 24.9/15.1。v2 は品質同じで小さく速いだけ）。ツールは `tools/models` に残す |
| 22 | Madlad-v2 の改善（4ビット埋め込み・日ネ専用出力層）を他のモデルへ | **使わない**（2026-11-27 測定）：PLaMo の埋め込みは元から4ビット。Whisper の日ネ専用出力層は速さ約8%、FLEURS 日本語150文で英語の語が壊れた（Amazon→Amazonger、3文）。PLaMo は生成約17%速いが、削るのは英語の語で同じ壊れ方になる。詳細は `tools/models/README.md` |
| 21 | PLaMo を Madlad なしで使えるようにする | 今はネパール語に「Madlad」選択が必須（`Translator.java` の Nepali チェック）で、PLaMo はその中で呼ばれる。そのため PLaMo だけ使いたくても Madlad（約1.5 GB）の取得が要り、PLaMo で訳している間も Madlad（約1.7 GB）がメモリに載る。案：日⇄ネは PLaMo を先に使い、Madlad は PLaMo が使えない時だけ読み込む |
| 19 | 非推奨 API（javac `-Xlint:deprecation` で 212 件、2026-11-27） | 置き換えが同じ動作になるもの 44 件は直した（`setColorFilter`→`PorterDuffColorFilter`、`Handler()`→`Handler(Looper.myLooper())`、`getColor/getDrawable(id, null)`、`stopForeground(STOP_FOREGROUND_REMOVE)`、型付きの `getSerializable(Extra)/getParcelable(Extra)`）。残りは構造の書き換えで、実機の会話モード（Bluetooth）で確かめられるまで触らない：Bluetooth GATT（`setValue`/`writeCharacteristic`/`notifyCharacteristicChanged`/`getDefaultAdapter`/`enable`）、`Parcel.readParcelable/readSerializable`（Bluetooth の受け渡し）、`AsyncTask`、`onActivityCreated`、`startActivityForResult/onActivityResult`、`setSystemUiVisibility` とウィンドウインセット、`overridePendingTransition`、`Locale(String)` |

## ユーザーにお願いすること
| # | 項目 | メモ |
|---|---|---|
| 13 | 実機（Pixel 10 Pro XL）での確認 | 上書きインストールで起動するか（Room 2.1→2.8）、アプリ終了、データ削除、辞書の読み込み、「要確認」表示、ネパール語音声認識、応答時間 |
| 14 | ネイティブ評価（保留中） | 198文の参照訳シートは `tools/eval/testsets/test_v1_ref_sheet.tsv`。ne 母語話者2名以上＋日本語母語話者 |
| 15 | プライバシーポリシーの運営者名・連絡先 | 同意画面のリンクも元の RTranslator を指したまま |
| 16 | 署名鍵の差し替え | 今の鍵とパスワードは公開。差し替え時は一度アンインストールが必要 |
| 17 | 両大使館サイトの利用規約の確認 | 確認後に用語抽出ツールを作成 |
| 18 | ネパール語用 Whisper のライセンス確認 | 確認後に配布 |

## 制約
- サブエージェントは週の利用上限で停止中（11月30日 22時 UTC に回復）。それまでは1件ずつ直接進める。
