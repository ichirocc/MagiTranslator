# MagiTranslator ゼロベース大規模改良 × 2020年代以降研究知見 統合仕様書（v1.0）

> ユーザー提示の原稿（2026-11-26）を収録。見出し・表・図は原文どおり、箇条書きは一部要約。現状との差分は末尾「整合性メモ」。

**位置づけ**：ゼロベース大規模改良設計に、2020年以降の主要な研究成果を選択的・批判的に取り入れるための統合仕様。既存仕様を置き換えない。

## 0. 基本姿勢
取捨選択の基準：1 日本語・ネパール語の双方向で実測して効果があるか 2 意味保持（否定・数字・固有名詞・情報の追加/欠落）に寄与するか 3 Android端末上で現実的に動作するか 4 STRICT_LOCALを破らないか 5 既存の重大誤訳を増やさないか。論文の数値をそのまま信じず、**対象言語ペアでの検証を必須**とする。

## 1. 取り入れる研究領域と優先度
| 領域 | 代表的な研究動向（2020年代） | 優先度 | 取り入れ方 |
|---|---|---|---|
| 低資源データ拡張 | Back Translation、多様な合成データ生成、品質フィルタリング | **最高** | 学習パイプラインに正式組み込み |
| 幻覚検出 | モデル内部情報（attribution）+ 文類似度の併用 | **最高** | Verification Layerの中核に |
| 参照訳不要品質推定 | COMET系、蒸留版（Cometoidなど） | 高 | 補助指標として条件付き採用 |
| 多言語転移・Adapter | 関連言語からの転移、Parameter-Efficient Tuning | 高 | モデル選定・方向別Adapterで |
| 意味・構造を意識した評価 | 意味役割・否定・数値の個別評価 | **最高** | CriticalRisk検出に直結 |
| 効率的なオンデバイス推論 | 量子化、蒸留、実行時最適化 | 高 | Resource Layerで |
| 文脈・文書レベル翻訳 | 文書文脈を考慮した一貫性 | 中〜高 | Context Layerで |

## 2. データ拡張（Back Translation中心）
### 2.1 採用する知見
単純なBTより生成品質のフィルタリングが重要。無検証の大量投入は品質を下げる。品質区分を付けて混合比率を制御する方が安定する。
### 2.2 設計
```text
単言語コーパス
  → SyntheticDataGenerator（逆方向モデルで生成）
  → SyntheticDataQualityFilter
       ├── 数字・否定・固有名詞の一致検査
       ├── 意味対応の簡易検査
       ├── 文長・重複・言語ID検査
       └── 品質区分付与（VERIFIED / HIGH / LOW / REJECTED）
  → DataProvenanceManager（出典・ライセンス・生成元を記録）
  → 学習データへ（区分に応じたサンプリング）
```
**禁止**：品質フィルタを通さない合成データの正式採用／テストデータへの合成データ混入／出典不明データの使用。

## 3. 幻覚・意味逸脱検出の強化（最重要）
### 3.1 採用する知見（Dale et al., ACL 2023 など）
モデル内部の情報（source contribution / attribution）だけでも一定の検出が可能。文類似度（埋め込み）の併用でさらに改善。内部情報が取れないモデル・Providerでは使えない。
### 3.2 Verification Layer への統合
```text
HallucinationDetector
├── CriticalErrorDetector（ルールベース・高速）
│     ├── NegationFlipDetector / NumericChangeDetector
│     ├── ProperNounErrorDetector / SubjectObjectSwapDetector
├── SourceContributionAnalyzer（モデルが情報を出せる場合のみ）
│     └── token logprob / attribution の利用
├── SemanticConsistencyEvaluator（MeaningRepresentationベース）
│     └── Predicate / Arguments / Negation / Time / Quantity の対応付け
└── EmbeddingSimilarityChecker（補助）
      └── 原文と仮説の意味的距離（過信しない）
```
**設計ルール**：内部情報が取れない場合はルールベース＋意味表現対応を主とする。Attentionやlogprobを正しさの証明として扱わない。CriticalRiskが一つでもあれば、その仮説は原則不採用。

## 4. 参照訳不要品質推定
COMET系・蒸留版は**補助指標**。両方向で人間評価との相関を必ず測定し、相関が低い・重大エラーを見逃す場合は採用しない。軽量化時も品質と速度を実測する。
```text
最終決定の優先順位
1. CriticalRisk の有無
2. MeaningRepresentation の保持度
3. 用語・固有名詞の一貫性
4. 参照訳不要品質推定スコア（補助）
5. 自然さ
```
品質推定スコアが高くても、CriticalRiskがある仮説は採用しない。

## 5. 多言語転移とモデル戦略
関連言語（例：ヒンディー語）からの転移は有望だが**負の転移**が起きる。系統の近さだけで採用しない。方向別に最適モデルが異なりうる。`TransferCompatibilityAnalyzer` で負の転移を検査し、方向別モデル／Adapterを正式に許可、採用前に双方向の重大エラー率を比較する。

## 6. 効率的オンデバイス実行
| 技術 | 取り入れ方 | 条件 |
|---|---|---|
| 知識蒸留 | 品質推定モデルや小型翻訳モデルの候補 | 人間評価との相関を確認 |
| INT8量子化 | 標準候補 | 重大エラー率が悪化しないこと |
| FP16 | 比較的安全な候補 | 実機で速度・品質を測定 |
| 強い量子化（INT4など） | 原則採用しない | 特別な検証なしでは禁止 |
| セッション再利用・遅延ロード | 標準実装 | メモリ圧力時の解放を必須に |

## 7. 評価データのゼロベース構築
日→ネ／ネ→日の**独立した**評価セット、否定・数字・固有名詞・敬語・省略・多義語・長文・ASR誤認識のカテゴリ別セット、人間評価（意味正確さ・自然さ・情報欠落/追加・敬語）、自動指標と人間評価の相関を方向別に記録。他言語ペアの評価や論文の数値だけで採用を決めない。

## 8. アーキテクチャへのマッピング
| 研究領域 | 配置先 |
|---|---|
| Back Translation + 品質フィルタ | データ・学習パイプライン |
| 幻覚検出（内部情報+類似度） | Verification Layer（HallucinationDetector） |
| 参照訳不要品質推定 | Decision Layer の補助指標 |
| 多言語転移・Adapter | Generation Layer / Model管理 |
| 意味役割・構造を意識した評価 | Understanding Layer + Verification Layer |
| 効率化技術 | Resource Layer |
| 文書・文脈一貫性 | Context Layer |

## 9. 実装優先順位
- **P0**：CriticalErrorDetector の堅牢化／MeaningRepresentation に基づく意味保持検査／双方向評価データの構築／合成データの出典管理と品質フィルタ
- **P1**：SourceContributionAnalyzer／Back Translation パイプラインの正式化／参照訳不要品質推定の方向別検証
- **P2**：蒸留・軽量品質推定モデルの条件付き導入／方向別モデル・Adapter／文書レベル一貫性の高度化
- **P3**：研究的な高度手法（大規模合成、複雑なマルチモデル評価など）

## 10. 受け入れ条件
1 両方向で評価 2 重大エラー率を測定 3 既存システムとの比較 4 人間評価との関係を確認 5 Android実機で速度・メモリを測定 6 ライセンスとデータ出典を確認 7 STRICT_LOCALを破らない 8 改善が見られない場合は採用を取り下げ、または条件を限定。

## 11. 最終方針
論文の改善値を期待するのではなく、MagiTranslatorの実環境（日本語・ネパール語・Android・プライバシー制約）で効くかどうかを基準に採用する。「生成された仮説を信じず、検証し、危険なら捨てる」姿勢を研究知見で強化する。

---

## 整合性メモ（現状コード・実測との差分）

| 本仕様 | 現状 | 状態 |
|---|---|---|
| CriticalErrorDetector（否定・数字・固有名詞） | `TranslationValidator`＋`jane/ir`（否定・否定過去・数字・疑問語・義務・依頼・過去・通貨・地名・用語・反復）、Python 版と判定パリティを CI で検査 | 実装済（主語/目的語入れ替えは未実装） |
| CriticalRisk があれば原則不採用 | hard 最少の候補を選び、全候補 hard なら「⚠ 要確認」表示（magi.9） | 実装済（全候補 hard 時は「不採用」でなく「要確認付きで最良を返す」） |
| MeaningRepresentation | `jane/ir/LanguageIR`（Stage1 規則ベース。述語・項構造は未） | 部分 |
| SourceContributionAnalyzer（logprob） | ASR は raw logits でスコア（D7）。翻訳は Madlad の beam スコアを保持するが検証には未使用 | 未実装（取得は可能） |
| EmbeddingSimilarityChecker | なし（逆翻訳 chrF＋MBR が代替。非独立証拠として同点崩しのみ＝05b） | 未実装 |
| 参照訳不要 QE（COMET系） | 未導入（ne 適合性未検証） | 未実装 |
| Back Translation パイプライン | 学習は未着手（端末では行わない） | 未実装 |
| 双方向評価データ | シード40文（参照訳は AI 作成・**未検証**）、日→ネ/ネ→日を別集計 | 部分（ネイティブ確認が P0 のボトルネック） |
| **INT4 は原則採用しない（§6）** | **Madlad 3B は int4 で本番配布中**（第3部 notes A3 と同件）。INT8 版との比較が未実施 | **矛盾**。本仕様に従うなら INT8 版の作成と int4 との重大エラー率比較が必要 |
| 方向別モデル | 方向で分けず Madlad 1本（ne⇔ja） | 未 |

**実測からの示唆（2026-11-26 評価）**：残る誤り（jn19・nj12・nj17）はすべて「全候補が hard」＝検出はできているが beam 内に正解が無いケース。本仕様の P0（検出の堅牢化）よりも、**候補の多様化**（beam 拡大・入力言い換え・文分割）と SourceContributionAnalyzer の方が次の改善に効く見込み（未測定）。
