# 第2部 整合性メモ（現状コード・確定判断・実測との差分）

## A. 要判断
- **A1 範囲の現実性**: 係り受け(UAS/LAS)・意味役割・照応を ja/ne 両方で本格実装するには、ネパール語側に実用的なオフライン解析器/ツリーバンクがほぼ無い（UD Nepali は極小）。推奨: **段階制**。Stage1=規則ベースIR(否定/モダリティ/数量/固有名/格標識/敬意代名詞) → Stage2=日本語形態素(Sudachi/Kuromoji 比較) → Stage3=係り受け・SRL は評価データが揃ってから。
- **A2 IRの用途を「検証」に限定するか**: 現行の実測では翻訳モデル(Madlad)への入力加工は効果が不安定（glossary注入はne→ja改善・ja→ne悪化）。推奨: IRは**まず出力検証（validator強化）と候補選択**に使い、モデル入力の改変（主語補完・制約付与）は実測で効果確認後。
- **A3 ネイティブ評価(受入15)**: ユーザー/協力者の手配が必要。シードセットの参照訳も未検証（AI作成）。
- **A4 日本語形態素ライブラリ**: Sudachi(Apache-2.0, 辞書大) / Kuromoji(Apache-2.0, IPADIC, 軽量) / MeCab系(JNI要)。D5(Pixel 10 Pro XL)前提なら容量は許容範囲。ライセンス・APKサイズで要決定。
- **A5 Kotlin/coroutine 前提**: 第1部A1と同じ（現行はJava）。

## B. 既存コードとの対応
| 第2部 | 既存 | 状態 |
|---|---|---|
| JapaneseNormalizer / UnicodeNormalizer | `jane/JaNormalizer` | 実装済(NFKC・全半角) |
| TextSegmenter | `jane/JaSegmenter` | 文分割・用語抽出 |
| NegationAnalyzer(ja/ne) | `jane/ir/JaAnalyzer`・`NeAnalyzer` の否定検出（否定過去形を含む） | 規則ベース。作用範囲は未解析（有無のみ） |
| LanguageIR（Stage1） | `jane/ir/LanguageIR`（否定・疑問・疑問語・モダリティ・時制・数・通貨・地名・名前候補・反復） | 実装済。validator は IR(原文)×IR(候補) で判定。旧実装と 130,371 組で判定一致を確認 |
| 数量・日時 | `KanjiNumber` + validator 数字照合 | 実装済 |
| EntityAnalyzer / 固有名 | `ProperNameResolver`, `KanaDevanagari` | 音写・KEEP・NOUN |
| ModalityAnalyzer(一部) | validator の謝罪/義務/推量/勧誘≠否定 判定 | 部分的 |
| 会話状態管理 | `TranslationMemory`(LRU200/会話) + resetConversationMemory | 期限・サイズ制御済、IR未保持 |
| デーヴァナーガリー正規化 | containsTerm の NFKC・ँ→ं同一視 | **NFCとの不一致あり(C1)** |
| 形態素/係り受け/SRL/照応/敬語分類 | なし | 未実装 |

## C. 文言・規約の整合
- C1 正規化: 本部はNFC基本。現行は照合時にNFKC＋ँ/ं同一視。推奨: **保持はNFC原文、照合用キーのみ寛容正規化**（第1部7.2「原文と正規化文字列を別保持」と整合）。
- C2 オフセット: UTF-16統一は Java String と一致（追加作業不要）。
- C3 第1部 TranslationContext の relationship/preferredRegister と本部 DiscourseContext.relationship が重複 → 統合時に一本化。
- C4 ネ語→日の敬意対応(5.6)は D4 の会話用途（対面）と関係。既定の丁寧さ(です・ます)を定める必要。

## D. 実測からの示唆
- ja→ne 低下の主因は**逆翻訳chrFによる意味ずれ候補の選択**。IRの否定/モダリティ/数量一致を**HARD検証に加えることが最も直接的な改善策**（第5部とも重複）。
- 「主語を勝手に足さない」(4.4/8.2) は現行Madlad出力でも起きる事象。検証側で「原文IRに無い人称代名詞の追加」を警告化できる。

## E. 未定義型
`LanguageAnalysisRequest`, `NamedEntity`, `Ambiguity`, `AnalysisWarning`, `PredicateAnalysis`, `Tense/Aspect/Mood/Polarity/SpeechAct/RegisterType/PartOfSpeech/DependencyType/SemanticRole/Modality`, `EllipsisCandidate`, `UtteranceReference`, `ContextEntity`, `RelationshipType`(第1部と共有)。統合時に enum 値を確定。
