# 第4部 追補A：辞書データソース・構築・導入基盤（改訂案）

> ユーザー指摘（2026-11-25）：第4部は検索・評価・統合を定義しているが、**辞書の元データの調達・構築・検証・導入**が欠けている。本追補でそれを独立章として定義し、第4部の再構成案を示す。
> ライセンス欄の「確認状態」は、本セッションでの調査結果。**「要確認」は配布元の正式な規約を人が読んで確定するまで同梱しない**（第3部 §23.3、第4部 §1.7）。

---

## 1. 前提（調査で分かった事実）

- **ja⇔ne の直接対訳辞書で、ライセンスが明確かつ大規模なものは見つかっていない。** 主な公開資源は「ja⇔英」「ne⇔英」「多言語の語彙対応」で、ja⇔ne は**多言語資源の言語間リンクから導出**するか、**独自構築**になる。
- 英語を経由した導出は「英語経由翻訳しない」（D1）とは別物である（実行時の翻訳ではなく、辞書構築時の**候補生成**）。ただし語義ずれ（英語の多義語を介した誤対応）が起きやすいので、導出した対訳は必ず `MACHINE_GENERATED`／`UNVERIFIED` とし、ネイティブ確認前は `PREFERRED` 以上にしない。
- OPUS では ja–ne の対訳コーパスを今回の調査で確認できなかった。対訳は**辞書の正解としない**（ユーザー指示）うえ、量も期待できない。

## 2. データソース候補

| # | データソース | 用途 | 言語 | 形式 / 取得方法 | ライセンス（確認状態） | 採否案 |
|---|---|---|---|---|---|---|
| 1 | **Wiktionary（Kaikki.org / wiktextract 抽出）** | 一般語彙・品詞・語義・活用、翻訳表（translations）からの ja⇔ne リンク | ja, ne（英語版 Wiktionary の各語の翻訳表） | JSONL（kaikki.org の一括ダウンロード） | CC BY-SA 3.0/4.0＋GFDL（Wiktionary 継承。**同梱時は表示義務・継承義務あり**）— 確認済（二次情報） | **採用候補（主力）**。ne の収録量は要実測 |
| 2 | **PanLex** | 多言語の語彙対応（ja–ne の直接/間接対応） | 多数 | CSV / JSON / SQL のスナップショット、PanLex Lite（SQLite） | スナップショットは **CC0** と記載、一方ライセンスページは **CC BY-NC-SA 4.0** と記載 — **矛盾あり・要確認** | 候補。**同梱は PanLex への確認後**。それまでは PC 側の候補生成にのみ使用 |
| 3 | **Wikidata** | 固有名詞（人名・地名・組織名）の ja / ne ラベル・別名 | 多数 | JSON ダンプ / SPARQL | CC0 — 一般知識（要再確認） | **採用候補（固有名詞辞書の主力）** |
| 4 | **Unicode CLDR** | 数字・日時・単位・通貨・国名・言語名の ja / ne 表記 | ja, ne | XML/JSON（cldr-json） | Unicode License — 一般知識（要再確認） | **採用候補（数字・日時・通貨辞書）** |
| 5 | **GeoNames** | 地名の別名（ne 表記を含む場合あり） | 多数 | TSV ダンプ | CC BY 4.0 — 一般知識（要再確認） | 候補（地名の補完） |
| 6 | **JMdict（EDRDG）** | 日本語の見出し・読み・品詞・用例（訳は英語等） | ja（＋英など） | XML | CC BY-SA 4.0（EDRDG ライセンス）— 一般知識（要再確認） | 候補（日本語側の品詞・表記ゆれ。ne 訳は含まない） |
| 7 | **UniDic / IPADIC** | 日本語の形態情報（第2部の形態素解析器用） | ja | 辞書ファイル | UniDic は BSD/GPL/LGPL の三重、IPADIC は独自ライセンス — 要確認 | 第2部（形態素）側で採否 |
| 8 | **IndoWordNet（Nepali WordNet）** | ne の語義（synset）・品詞 | ne（＋印欧諸語、英 WordNet リンク） | 一括配布（CFILT） | GNU ライセンスとの記載のみ、Nepali 単体の条件は未確認 — **要確認** | 候補（ne 語義・品詞） |
| 9 | **Hunspell ne_NP 等の綴り辞書** | ne の語形・異形・表記ゆれ | ne | .dic/.aff | GPL/LGPL 系が多い — 要確認 | 候補（表記ゆれ辞書） |
| 10 | **Apertium** | 形態辞書・規則 | ja–ne ペアは無し（調査範囲） | XML | GPL | **不採用**（対象ペアなし。ne 単言語辞書があれば再検討） |
| 11 | **OPUS（Tatoeba / WikiMatrix / CCMatrix 等）** | 対訳例・用語候補（**辞書の正解にしない**） | ja–ne は今回確認できず | Moses/TMX | コーパスごとに異なる（Tatoeba は CC BY 2.0 FR 等） | 候補（翻訳メモリの参考例・評価用。ja–ne があれば） |
| 12 | **FLORES-200** | 評価専用（ja, ne の同一文対訳） | ja, ne | テキスト | CC BY-SA 4.0 — 一般知識（要再確認） | **評価専用**。辞書・学習に混ぜない（第3部 §6.4） |
| 13 | **Nepali Brihat Sabdakosh（ネパール・アカデミー）の JSON 化** | ne 国語辞典（語義・品詞） | ne | JSON（GitHub の変換物） | 変換コードは MIT、**辞書本文のライセンス不明** | **不採用（権利不明）**。アカデミーの許諾があれば再検討 |
| 14 | 公的機関の多言語資料（在留支援・医療・防災の ne 版） | 高リスク分野の用語 | ja, ne | PDF/HTML | 著作物。**個別に利用条件を確認** | 候補（専門用語。許諾確認後） |
| 15 | **独自構築辞書** | ja⇔ne 特有の表現・会話表現・敬語 | ja, ne | 本リポジトリの TSV | 自前（ネイティブ確認必須） | **採用（最優先で小規模から）** |

## 3. 必要な辞書の種類と主な入力元

| 辞書 | 主な入力元（上表 #） | 初期の検証状態 |
|---|---|---|
| 基本語彙辞書 | 1, 2, 15 | UNVERIFIED（導出）／NATIVE_VERIFIED（独自） |
| ja⇔ne 対訳辞書 | 1（翻訳表）, 2, 15 | 同上 |
| 品詞・活用辞書 | 6, 7（ja）, 1, 8, 9（ne） | MACHINE_GENERATED |
| 慣用句・複合語辞書 | 1, 6, 15 | UNVERIFIED |
| 固有名詞辞書（人名・地名・組織名） | 3, 5, 15 | MACHINE_REVIEWED（Wikidata ラベル） |
| 敬語・丁寧表現辞書 | 15 | NATIVE_VERIFIED 必須 |
| 会話表現・口語辞書 | 15, 11（参考） | NATIVE_VERIFIED 必須 |
| 専門用語辞書（医療・行政・技術） | 14, 15 | 高リスク：専門家または母語話者の確認必須 |
| 数字・日時・単位・通貨表現辞書 | 4 | MACHINE_REVIEWED（CLDR は規格データ） |
| ネパール語の表記揺れ・異形辞書 | 9, 1 | MACHINE_GENERATED |
| ユーザー独自辞書 | 端末内入力 | ユーザー指定（`MANDATORY` はユーザー明示時のみ） |

## 4. 構築パイプライン（PC 側。端末では行わない）

```text
公開辞書・対訳データ・専門用語集
  → SourceImporter（ソースごとのパーサ。取得日時・版・URL・ハッシュを記録）
  → LicenseValidator（ソース単位の許諾表 license_registry.tsv と照合。要確認/不許可は同梱パッケージに入れない）
  → RawDictionaryStorage（取得物は改変せず保存。再現性のため）
  → DataNormalizer（原文保持＋照合キー。ja: NFKC キー、ne: NFC 保持・ँ/ं 同一視キー）
  → LanguagePairMapper（ja–ne の直接リンク優先。英語等を介した導出は MACHINE_GENERATED とし pivot 語を記録）
  → DuplicateResolver（同表記・別語義は統合しない。訳語の重複のみ統合し出典は全て保持）
  → SemanticValidator（品詞の一致、ne 活用形の妥当性、数字・否定を含む見出しの検査、古語・方言・誤記タグ）
  → NativeSpeakerReview（レビュー用 TSV を出力→結果を取り込み、NATIVE_VERIFIED / REJECTED を付与）
  → DictionaryQualityEvaluator（シードセットの用語再現率・誤適用率、検証済み比率を出力）
  → DictionaryPackageBuilder（SQLite＋エントリーごとの出典/ライセンス/検証状態、NOTICE ファイル）
  → Manifest・署名・チェックサム（第3部 §17：既存の ModelStore / ManifestSignature を流用）
  → Android DictionaryRepository
```

- **出典表示**：CC BY-SA 等のデータを同梱する場合、アプリ内に出典・ライセンス表示画面（NOTICE）を設け、派生データの継承条件に従う。
- **再現性**：同じ取得物と設定から同じパッケージが生成されること（ハッシュ一致）をテストする。

## 5. 品質管理

- 語義の正しさ、ja と ne の意味一致、品詞、文脈依存の訳語分岐、ne 活用形、敬意レベル、古語・方言・誤記の混入、重複訳語の整理、出典・ライセンスの追跡を検査する。
- 機械生成した対訳は `UNVERIFIED` / `MACHINE_GENERATED` とし、ネイティブ確認済みデータと区別する。**未検証の訳語は REFERENCE_ONLY／ALLOWED 止まり**で、hard 検査（TERM_MISSING 等）の根拠にしない。
- ネイティブ確認は**高リスク用語と頻出会話表現から優先**する（全量確認は非現実的）。

## 6. アプリへの導入

| 区分 | 導入方法 | オフライン |
|---|---|---|
| 基本辞書 | 初回セットアップでモデルと同時にダウンロード（models-v1 と同じ仕組み） | 必須 |
| 拡張辞書 | 追加ダウンロード | 導入後は可 |
| 専門辞書 | 用途別（医療・行政・技術）に選択導入 | 導入後は可 |
| ユーザー辞書 | 端末内保存（第11部の DB） | 可 |
| 更新辞書 | バージョン管理・ロールバック（第3部 §17、`ModelStore` の staging→検証→置換を流用） | 可 |

## 7. 第4部の再構成案（ユーザー提案を採用）

| 章 | 内容 | 現行第4部の対応箇所 |
|---|---|---|
| 1 | 辞書エンジン全体アーキテクチャ | §1–§2 |
| 2 | 公開辞書・対訳コーパス・専門用語集の調達 | **本追補 §1–§2（新規）** |
| 3 | 辞書データのライセンス・出典・再配布管理 | §1.7＋本追補 §4 LicenseValidator（新規） |
| 4 | 日本語・ネパール語辞書データの構築 | **本追補 §3–§4（新規）** |
| 5 | 品詞・語義・活用・文脈情報の構造化 | §3（データモデル） |
| 6 | 重複排除・意味検証・ネイティブレビュー | §5.4＋本追補 §5（新規） |
| 7 | 固有名詞・専門用語・会話表現辞書 | §9–§10 |
| 8 | 翻訳メモリの構築と管理 | §12（途中まで受領） |
| 9 | DictionaryRepository・検索・候補解決 | §5–§8 |
| 10 | Mandatory・Preferred・Forbidden 制約 | §1.4, §4.3, §11 |
| 11 | 辞書パッケージの生成・配布・更新 | 本追補 §4 後段・§6（新規） |
| 12 | Android への導入・Room・キャッシュ | 本追補 §6（第11部と接続） |
| 13 | 翻訳モデル・第5部との統合 | §2.2, 第3部 §24 |
| 14 | 品質評価・テスト・受入条件 | 未受領 |

## 8. 最初の実装ステップ（推奨順）

1. `license_registry.tsv`（ソース・版・URL・ライセンス・確認者・確認日・同梱可否）を作り、上表の「要確認」を人が埋める。
2. **独自構築辞書（#15）**の雛形 TSV：高リスク用語・数字/通貨・地名・会話定型句から 100〜300 語。現行 `Glossary.withDefaults()` と `jane/ir/Places` をここへ移す（コードに直書きしない）。
3. CLDR（#4）と Wikidata（#3）から、数字・通貨・国名・主要地名を自動生成（規格/CC0 系で権利が比較的明確）。
4. Kaikki（#1）の ja / ne 翻訳表から ja–ne リンクを抽出し、収録量を実測（PC、机上見積り後）。
5. ネイティブレビュー用 TSV の出力と取込ツール。
6. 端末側 DictionaryRepository（SQLite）と、validator の TERM_MISSING を「検証状態つき用語」に切り替え。

## 調査出典
- PanLex スナップショット（CC0 と記載）: https://panlex.org/snapshot ／ db.panlex.org README: https://db.panlex.org/README.txt
- PanLex ライセンスページ（CC BY-NC-SA 4.0 と記載＝矛盾）: https://panlex.org/license/
- wiktextract / kaikki.org: https://github.com/tatuylonen/wiktextract
- Kaikki 由来データのライセンス継承の例: https://huggingface.co/datasets/QubitPi/wiktionary-data/blob/main/README.md
- IndoWordNet: https://en.wikipedia.org/wiki/IndoWordNet ／ https://arxiv.org/pdf/2201.02977.pdf
- Nepali Brihat Sabdakosh JSON（本文の権利不明）: https://github.com/bikashpadhikari/nepali-brihat-sabdakosh-json
- OPUS: https://github.com/Helsinki-NLP/OPUS
