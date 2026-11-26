# 日本語圏の最新研究の取り込み検討（2026-11-26）

> 第15部の基準（双方向で効くか・意味保持・Android で動くか・STRICT_LOCAL・重大誤訳を増やさない）で取捨選択する。

## 1. 調べた研究

| 研究 | 所属 | 内容 | 日⇔ネへの利用 |
|---|---|---|---|
| **NAIST Simultaneous Speech Translation System for IWSLT 2025**（Tan ほか, IWSLT 2025, pp.369–378） | 奈良先端大（NAIST） | Whisper エンコーダ＋LLM（プロジェクタで接続）の英→独・日・中同時音声翻訳。オフライン翻訳モデルに **local agreement（局所一致）方策**を適用し、入力が伸びるたびに訳し直して、連続する仮説で一致した先頭部分だけを確定させる | **方策は利用可**（モデル不要。既存の Madlad で実装できる）。モデル構成は対象外 |
| **LLM-SI-Corpus**「Simultaneous Interpretation Corpus Construction by LLMs in Distant Language Pair」（arXiv 2404.12299） | NAIST | CWMT 指針（原文を意味のかたまりに区切り、語順を大きく入れ替えずに訳す）に沿って LLM で同時通訳コーパスを作り、英日の同時翻訳で品質と遅延の両方を改善 | **考え方を利用**（チャンク単位・語順を保つ訳し方） |
| **Simul-MuST-C**（EMNLP 2024） | NAIST | LLM による同時多言語音声翻訳コーパス。品質と遅延の両立を強調 | 考え方のみ（評価に遅延を含める） |
| NICT の多言語多段階学習（低資源言語対を、データの多い言語対で事前学習してから段階的に学習） | NICT | 学習手法 | **端末では対象外**。将来 PC で追加学習する場合の参考（第3部 §9） |

## 2. 日⇔ネに特有の示唆
- **日本語もネパール語も SOV（述語が文末）**。英日のような大きな語順の入れ替えが少ないため、**チャンク単位で前から順に訳す同時翻訳が成り立ちやすい**（英日向けの CWMT の考え方が、日⇔ネではより素直に効く見込み。未測定）。
- ただし両言語とも**否定・時制・依頼が文末の述語に乗る**。前半だけで確定すると否定の取り違えを招くため、**述語（文末）が来るまでは最終確定しない**（第17部と同じ方針）。

## 3. 取り入れる設計

| 知見 | MagiTranslator での形 | 状態 |
|---|---|---|
| local agreement（NAIST 2025） | 音声認識の途中結果が伸びるたびに Madlad で訳し直し、**直前の仮訳と一致した先頭部分だけを「確定済み」として表示**、残りは仮表示。文末（述語）到着後に jane の検査付き最終訳で置換 | 未実装（提案・最優先） |
| チャンク単位の語順保持（CWMT, LLM-SI-Corpus） | 節の切れ目（ja「、」「ので」「が」「て」、ne र・तर・किनभने 等）で区切って逐次翻訳 | 未実装（第17部 §4-2 と統合） |
| 品質と遅延の両方を評価（Simul-MuST-C） | 発話終了→仮訳・確定訳の遅延と、確定部分の後からの書き換え率を計測 | 未実装（第17部 §4-1 と統合） |

## 4. 採用しないもの
- Whisper＋LLM の統合モデル（端末の負荷、対象言語外）。
- NICT 流の多段階追加学習（現時点で日⇔ネの学習データ・計算資源が無い）。

## 5. 次の実装（推奨順）
1. 遅延と書き換え率の計測ログ（本文なし）。
2. local agreement による逐次表示（トランシーバー・会話モード）。最終確定は文末＋jane 検査。
3. 実機で遅延・書き換え率・重大誤り率を比べて採否を決める。

## 出典
- NAIST Simultaneous Speech Translation System for IWSLT 2025: https://aclanthology.org/2025.iwslt-1.39
- Simultaneous Interpretation Corpus Construction by LLMs in Distant Language Pair: https://arxiv.org/abs/2404.12299
- Simul-MuST-C (EMNLP 2024): https://aclanthology.org/2024.emnlp-main.1238.pdf
- NICT 多言語多段階学習（ニュースイッチ記事）: https://newswitch.jp/index.php/p/30029
