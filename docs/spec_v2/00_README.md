# MagiTranslator 総合詳細実装仕様書 v2

> **正本は [`SPEC_v2.md`](./SPEC_v2.md)（統合版）。** 以下の各部ファイルはその出典として残す。`docs/SPEC.md`（v1）は SPEC_v2.md で置き換えられた（superseded）が、履歴として残す。

`docs/SPEC.md`（v1）を置き換える新しい仕様書を、部ごとに作成している。各部の本文はユーザー提示の原稿をそのまま収め、
**各部の末尾に「現状コードとの整合性メモ」** を付ける（実在コード・確定済み判断・実測結果との差分）。
全部がそろった時点で、重複・矛盾・未定義インターフェースを解消して一本化する。

| 部 | ファイル | 状態 |
|---|---|---|
| 1 | `01_architecture.md` 全体設計・基本アーキテクチャ | 原稿受領・整合性メモ付き |
| 2 | `02_language_analysis.md` 日本語・ネパール語専用言語解析エンジン | 原稿受領・整合性メモ付き |
| 3 | `03_translation_model.md`（＋`03b_long_text.md` §15 長文翻訳 Claude 起草） 翻訳モデル・学習・推論最適化 | 原稿受領（前半＋続編§15.1〜§24、長文翻訳の本文欠落）・整合性メモ付き |
| 4 | `04_dictionary_terminology_tm.md` 辞書・用語・翻訳メモリ統合エンジン | 原稿受領（§12.6 途中まで）・整合性メモ付き。追補A `04a_dictionary_data_sources.md`、続き `04b_tm_and_rest.md`（Claude 起草）（辞書データの調達・構築・導入、再構成案） |
| 5 | `05_candidate_quality.md`（＋続き `05b_fusion_decision.md` Claude 起草） 翻訳候補生成・品質推定・候補統合・誤訳検出 | 原稿受領（§11.4 途中まで）・整合性メモ付き |
| 6 | `06_provider_management.md` ローカル・オンライン統合 Provider 管理 | Claude 起草（現状コード準拠） |
| 7 | `07_asr.md` ASR 音声認識エンジン | Claude 起草 |
| 8 | `08_tts.md` TTS 音声合成エンジン | Claude 起草 |
| 9 | `09_realtime_interpretation.md` リアルタイム音声通訳エンジン | Claude 起草 |
| 10 | `10_resource_install.md` モデル・辞書・言語資源の導入管理 | Claude 起草 |
| 11 | `11_android_perf_security.md` Android 性能・メモリ・安定性・セキュリティ | Claude 起草 |
| 12 | `12_storage.md` データベース・履歴・設定・キャッシュ | Claude 起草 |
| 13 | `13_integration_testing.md` 全体統合・テスト・品質評価 | Claude 起草 |
| 15 | `15_research_integration.md` 2020年代研究知見の統合（ユーザー原稿 v1.0） | 原稿受領・整合性メモ付き |
| 14 | `14_migration_acceptance.md` 既存コードからの移行・最終受入基準 | Claude 起草 |

実装の正本は `SPEC_v2.md`。実測の記録は `docs/eval/`。`docs/SPEC.md`（v1）は superseded（決定 D1〜D10 は SPEC_v2 §1 に引き継ぎ）。

## 採用済みの判断（ユーザー「推奨で」2026-11-25）
各部 notes の「A. 要判断」は推奨案で確定:
- 実装言語は Java のまま（Kotlin/coroutine の記述は Java の Executor/Future＋不変クラスで読み替え）。
- VI（ja⇔vi, HY-MT）は維持。基盤は言語ペア非依存。
- オンライン経路は既定 OFF・同意制（実質 STRICT_LOCAL）。学習は PC/クラウドのみ。
- 言語解析(IR)は段階制: Stage1 規則ベース（否定/モダリティ/数量/固有名/格標識/敬意）→ 日本語形態素 → 係り受け・SRL は評価データ後。IR はまず検証と候補選択に使う。
- 正規化: 原文は NFC で保持、照合キーのみ寛容正規化。
- 部番号: 第4部原稿が自身を「4＝辞書・用語・TM、5＝候補統合・品質推定」と定義（第3部 §24 と一致）したため、こちらを正とする（第3部 §0.1 側を読み替え）。※要確認（04 notes A1）
- 量子化: INT8 版を作成し FP32 基準と比較（机上見積り先行）。署名は Ed25519（公開鍵埋め込み・秘密鍵は CI secret）。
- 長文翻訳の節は原稿再送待ち。

## 採用済みの判断（2回目「推奨で」2026-11-25）
- 部番号を確定: 第4部＝辞書・用語・翻訳メモリ、第5部＝候補統合・品質評価・誤訳検出。
- 全候補が hard のときは結果に「要確認」を付けて表示する（05b の abstention）。
- Bluetooth・位置情報の権限は会話モードで使うため維持。
- ユーザー作業（未完）: プライバシーポリシーの運営者名・連絡先、署名鍵の差し替え、両大使館サイトの利用規約、Nepali Whisper のライセンス確認。
