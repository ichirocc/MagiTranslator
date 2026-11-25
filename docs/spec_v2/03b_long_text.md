# 第3部 補遺B：§15 長文翻訳（LongTextTranslationManager）

> 第3部前半で「§15 長文翻訳」の本文が欠落していたため（03 notes A6）、現状コードに基づいて補う。型は Kotlin 風に記すが、実装言語は Java（採用済み判断）。状態欄：**実装済／部分／未実装**。品質の改善は実測なしに主張しない。

---

## 15.0 位置づけ

長文翻訳は「入力を分割してそれぞれ翻訳し、つなぐ」だけでは成立しない。分割点で否定・条件・主語・照応・用語が断ち切られると、各文は流暢でも全体の意味が変わる（第3部 §19.5「長文分割による意味の断絶」、第5部 §9.4 SemanticDrift）。

本節は、モデルの入力上限を守りつつ、文脈と用語を保ったまま長文を翻訳・再構成する仕組みを定義する。

## 15.1 現状（実装済の事実）

| 項目 | 現状コード | 状態 |
|---|---|---|
| 入力上限 | `Translator.performTextTranslation`：`maxLength`（入力トークン）＝ Madlad 200、NLLB 200、HY-MT 5000（いずれも `todo: research the best value`） | 実装済（値は未調査） |
| 分割 | `BreakIterator.getSentenceInstance(入力ロケール)` で文分割 → 隣接文を「合計 < maxLength」または「後続が 5 トークン未満」で再結合 | 実装済 |
| ja⇔ne ライブラリ内の分割 | `JaSegmenter.split`（丁寧形の文末と接続助詞で日本語を分割）、ne は `।?!` で分割し「sentences」候補として whole 候補と競わせる | 実装済 |
| N-best の扱い | 200 トークン以上の入力では N-best を出さず 1-best のみ（`silentTranslateNBest`） | 実装済 |
| 文間の文脈引き継ぎ | 無し（各チャンクは独立に翻訳） | 未実装 |
| チャンク間の用語統一 | 会話単位の `TranslationMemory`（用語・名前の訳の記憶）経由で部分的 | 部分 |
| 再構成（句読点・改行の保持） | 連結のみ。改行・段落の保持は未保証 | 部分 |
| 長文の評価データ | シードセットに長文カテゴリはほぼ無い | 未実装 |

## 15.2 構成

```text
LongTextTranslationManager
├── DocumentAnalyzer        段落・文・箇条書き・引用・数式/URL 等の構造を検出
├── ChunkPlanner            モデル入力上限内に収まるチャンク計画を作る
├── ContextPreserver        前チャンクの要約ではなく「事実」（固有名・用語・人称・未解決の照応）を引き継ぐ
├── ChunkTranslator         各チャンクを第3部推論基盤・第5部候補選択で翻訳
└── DocumentReconstructor   構造（改行・段落・番号）を戻し、境界の整合性を検査
```

## 15.3 データモデル

```kotlin
data class DocumentStructure(
    val documentId: String,
    val paragraphs: List<ParagraphSpan>,
    val protectedSpans: List<ProtectedSpan>,   // URL, メール, コード, 数式, 電話番号 等：翻訳しない
    val language: LanguageCode
)

data class ChunkPlan(
    val chunks: List<TextChunk>,
    val maxInputTokens: Int,
    val tokenizerId: String,
    val strategy: ChunkStrategy
)

data class TextChunk(
    val chunkId: String,
    val span: TextSpan,              // UTF-16 オフセット（採用済み判断）
    val text: String,
    val tokenCount: Int,
    val boundaryRisk: BoundaryRisk,  // 分割点で意味が切れる危険度
    val paragraphIndex: Int
)

enum class ChunkStrategy { SENTENCE, CLAUSE, HARD_TOKEN_SPLIT }

enum class BoundaryRisk { LOW, CONDITION_SPLIT, NEGATION_SPLIT, QUOTE_SPLIT, LIST_SPLIT, HARD_SPLIT }

data class CarriedContext(
    val entities: List<CarriedEntity>,      // 原文表記と、採用した訳表記
    val termRenderings: Map<String, String>,
    val openReferences: List<String>,        // 未解決の照応（「それ」「その人」）— 推測で埋めない
    val register: RegisterType?
)
```

## 15.4 チャンク計画の規則

1. **分割は文境界を最優先**する（現行どおり）。文境界の検出は言語別に行う：ja は `。！？` と `JaSegmenter` の文末規則、ne は `।` `?` `!` と ASCII のピリオド。
2. 1文が上限を超える場合のみ**節境界**（ja：「が」「ので」「けど」「て」等の接続、ne：`र`, `तर`, `किनभने`, `भने` 等）で分ける。分けた場合は `boundaryRisk` を付ける。
3. 節でも収まらない場合に限り**トークン数での強制分割**（`HARD_TOKEN_SPLIT`）。強制分割した出力は第5部で必ず `NEEDS_REVIEW` 扱いにする。
4. 条件節（「〜なら」「〜ば」、ne の `भने`）と主節、否定の作用域、引用（「」, “”）、箇条書きの1項目を**またいで分割しない**。またがざるを得ない場合は `CONDITION_SPLIT` / `NEGATION_SPLIT` / `QUOTE_SPLIT` を付ける。
5. **保護スパン**（URL、メールアドレス、電話番号、コード、型番）はチャンクの途中で切らず、翻訳対象から外して位置を保持する。
6. 上限値はモデルの Manifest の `maxInputTokens` を使う（現行の定数 200 / 5000 をハードコードしない）。上限値そのものは第3部 §19 の手順で実測して決める。

## 15.5 文脈の引き継ぎ（ContextPreserver）

- 前チャンクの**訳文を次チャンクの入力に混ぜない**（Madlad は文脈入力の形式を持たず、混ぜると二重翻訳や欠落を起こしうる。第3部 §8.3「独自記号を原文へ混ぜない」）。
- 引き継ぐのは**事実のみ**：固有名詞の訳表記、用語の採用訳、話者の敬意レベル。これらは第4部の `TerminologyContext`（スコープ `DOCUMENT`）と、現行 `TranslationMemory` を通じて次チャンクの候補選択に効かせる（**部分**：現行は用語と名前のみ）。
- 未解決の照応（「それ」等）は記録するだけで、推測で補わない（第2部 §4.4）。

## 15.6 再構成（DocumentReconstructor）

- 段落・改行・箇条書き記号・番号を原文の位置関係どおりに戻す。
- 保護スパンを元の文字列のまま戻す。
- 境界検査：隣接チャンクの訳で、**同じ固有名・用語の表記が食い違っていないか**、**文末の句読点（ja の「。」、ne の「।」）が欠けていないか**、**同じ文の重複出力が無いか**を検査する。食い違いは第5部の `DISCOURSE_CONSISTENCY` の問題として渡す。
- 数値・否定の照合（現行 `TranslationValidator`）は**チャンク単位と文書全体の両方**で行う。文書全体での数値の欠落は、チャンク単位で見落とされることがあるため。

## 15.7 性能・メモリ

- チャンクは**順に**翻訳する（現行の公平ロックで直列化）。並列化はメモリ（Madlad 3B int4）を倍増させるため行わない（第3部 §16.6）。
- キャンセル（実装済：`TranslationCancellation`）はチャンクの間でも確認し、キャンセル後に残りのチャンクを翻訳しない。
- 長文で N-best を出さない現行の制限（200 トークン以上）は、チャンク単位で N-best を出す形に改める（**未実装**）。チャンク数×beam の計算量になるため、上限（例：チャンク数）を設定し、超えたら 1-best に落とす。

## 15.8 禁止事項

- 前チャンクの訳文を、次チャンクの原文に連結して入力しない。
- 条件節・否定の作用域・引用を、警告なしにまたいで分割しない。
- 強制分割した出力を、検査なしに確定訳として返さない。
- 保護スパン（URL・電話番号等）を翻訳・音訳しない。
- 段落・改行を黙って消さない。
- 上限値をモデルと無関係な定数で決めない。

## 15.9 テスト

| 種別 | 内容 |
|---|---|
| Unit | 文分割（ja/ne）、節分割、保護スパンの検出と復元、上限超過時の強制分割、`boundaryRisk` の付与 |
| Unit | 再構成：段落・改行・番号の保持、句読点の補完、重複文の検出 |
| Integration | 長文（10文以上）で、チャンク間の固有名・用語表記が一致すること |
| Regression | 条件文・否定文を含む長文で、チャンク単位と文書単位の validator 判定 |
| 評価 | 長文カテゴリの評価データ（第3部 §6.1 の 17）を方向別に作り、文単位 chrF++ と文書単位の重大誤訳率を記録（**未実施**） |
| 性能 | チャンク数に対する所要時間とピークメモリを実機で記録（**未実施**） |

## 15.10 受入条件

1. モデルの入力上限を超える入力で、例外なく翻訳が完了する。
2. 条件・否定・引用をまたぐ分割が発生した場合、警告が第5部に渡っている。
3. 保護スパンが原文どおり残る。
4. 段落・改行が保持される。
5. チャンク間で固有名・用語の表記が一致する、または不一致が検出されている。
6. キャンセルが残りのチャンクに及ぶ。
7. 長文評価データでの方向別の結果が記録されている（数値基準は初回の実測から設定する）。
