# 第4部 整合性メモ（現状コード・確定判断・実測との差分）

## A. 要判断
- **A1 部番号**: 本部は自身を「第4部＝辞書」とし、第5部＝候補統合・品質推定。00_README で採用済みの判断（第3部 §0.1 を正: 4＝候補、5＝辞書）と**逆**。第3部 §24 とは一致。推奨: **本部の番号（4＝辞書・用語・TM、5＝候補統合・品質）を正に改める**（原稿の大勢が 4＝辞書。§0.1 側を読み替え）。
- **A2 データ源**: 一般対訳辞書・基本語彙辞書（ja⇔ne）の実データが無い。ライセンスの明確な ja–ne 辞書は乏しく、現状は `Glossary.withDefaults()` の手作り少数語。推奨: まず高リスク用語（医療・行政・数量・地名）を少数手作り＋出典/検証状態付きで管理し、ネイティブ確認を待つ。外部コーパス取込は出典・ライセンス確認後。
- **A3 永続化**: Room 等は未導入（現行 TM は会話単位のメモリ内 LRU200、永続化なし）。§1.6「一時的な用語を保存許可なしに永続化しない」とは現状で整合。永続 TM/ユーザー辞書は第11部（DB）の仕様確定後。
- **A4 MANDATORY の文法調整（§1.4）**: 現行の Glossary 注入は ne 側の格標識付き語尾一致（`injectReverse`）程度。活用調整を伴う必須適用は未実装。制約未達は validator の TERM_MISSING（hard）で候補を落とす形で、§24.4「未達を成功扱いにしない」とは整合するが、未達理由の返却は無い。

## B. 既存コードとの対応
| 第4部 | 既存 | 状態 |
|---|---|---|
| DictionaryEntry / TerminologyRule | `jane/Glossary`（ja→ne 用語・ne→ja 逆注入） | 単一訳語・強度なし・出典なし |
| ProperNounPolicy | `ProperNameResolver`（TRANSLITERATE / TRANSLITERATE_BARE / KEEP / NOUN） | ほぼ対応（KEEP_SOURCE, TRANSLITERATE, TRANSLATE 相当） |
| Place 辞書 | `jane/ir/Places`（ネパール・カトマンズ・ポカラ・日本・東京・インド等） | 検証用の小表 |
| FORBIDDEN 訳語 | validator の CURRENCY_MISMATCH / PLACE_ADDED | 個別規則として部分対応 |
| Translation Memory | `jane/TranslationMemory`（会話単位 LRU200, Exact のみ） | Fuzzy/Structural/Semantic なし、provenance なし |
| 反復表現（§8.3） | validator REPETITION は日本語カタカナ語の直後重複のみ。ネパール語の反復は削除も検出もしない | 整合 |
| 正規化（§8.1） | 原文保持＋照合キーのみ寛容（採用済み判断） | 整合 |

## C. 文言・規約の整合
- C1 LanguageCode: 本部は JA/NE のみ（第1部は AUTO あり、VI は採用済み判断で維持）。統合時に JA/NE/VI(+AUTO) へ一本化。
- C2 `TextSpan`: 本部 §5.3 は「コードポイントまたは UTF-16」、第2部 §3.3 は UTF-16 統一。→ **UTF-16（Java String）に統一**。
- C3 `RegisterType`: 第1部 TranslationContext の preferredRegister、第2部 SentenceAnalysis.register と同型にする。
- C4 第10部・第11部への参照あり（言語資源配布、DB/履歴）。第3部 §17 の Manifest/署名/rollback を辞書にも適用する前提。

## D. 実測からの示唆
- 用語注入は ne→ja で改善・ja→ne で悪化した実測（v1〜v3）。§1.4/§4.3 の「強制力の分離」はこの問題への正しい方向：ja→ne は PREFERRED を既定にし、MANDATORY はユーザー明示時のみ。

## E. 未定義型
`DictionarySource`, `TerminologyScope`（`TerminologyScopeType` との関係）, `DictionaryMatchType`, `TextSpan`, `ConversationContext`（第2部 DiscourseContext と統合要）, `TermRiskLevel`, `TranslationMemoryQualityStatus`, §12.6 以降（受領途切れ）。
