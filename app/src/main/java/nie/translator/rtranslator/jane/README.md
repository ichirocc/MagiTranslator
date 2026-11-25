# jane — 日本語⇄ネパール語 直接翻訳ライブラリ

英語を経由しない日→ネ特化パイプライン。純 Java（Android 非依存）で、翻訳モデル（Madlad）は `Backend` として差し込む。速度より精度を優先する。

## 処理の流れ（日→ネ）
1. `JaNormalizer` — NFKC、話し言葉のフィラー除去（「あの人」は残す）、漢数字→数字（三時半→3時30分）、文末補完
2. 候補の入力を作る — 全文 / 文ごと（`JaSegmenter`：句読点の無い音声認識結果も文末表現で分割）/ 用語注入（`Glossary`：訳語を原文へ埋め込む。カタカナ人名＋さん等は `KanaDevanagari` で翻字）
3. 各候補を翻訳し `NePostEditor`（NFC、「.」→「।」、ループ畳み込み）
4. `QualityScorer` で採点 — 逆翻訳(ne→ja)と原文の chrF、数字の保持、必須用語、文字種の純度、敬語の整合（です/ます→तिमी/तँ を減点）、長さ比、候補間の合意度（MBR）
5. 最高点を採用（候補が1つなら逆翻訳を省略）。ただし先に `TranslationValidator` の重大問題（HARD）が少ない候補を優先する

## 言語中間表現（`ir/`, 仕様 v2 第2部 Stage1）
`JaAnalyzer` / `NeAnalyzer`（言語ごとに別の規則）が文を `LanguageIR` にする: 否定・疑問・疑問語（`WhConcept`）・
モダリティ（義務/依頼/推量/許可）・時制・数・通貨・地名・カタカナ名候補・異常反復。すべて原文に明示された証拠のみ（推測で補わない）。
原文側は厳密に「述べていること」、候補側は寛容に「表せる形があるか」（cover）を出し、`TranslationValidator` が IR(原文) と IR(候補) を比べる。
Stage1 では未使用の欄（ネ語の述べたモダリティ・日本語のモダリティ cover）は空＝「未解析」。形態素・係り受けは未実装。
Python 移植（`tools/eval/metrics.py` の `validate`）は CI で Java と判定文字列が完全一致することを確認する（`check_validator_parity.py`）。

## 根拠
- chrF: Popović 2015 / 品質考慮の選択: Fernandes et al. 2022 / MBR: Freitag et al. 2022, 2023
- 目標語注釈による用語指定: Exel et al. 2020, Bergmanis & Pinnis 2021

## ユーザー用語集
アプリ内部ストレージ `files/jane_glossary.tsv` に `日本語<TAB>ネパール語` を1行ずつ。`#` 始まりはコメント。

## テスト
```
javac -encoding UTF-8 -d out app/src/main/java/nie/translator/rtranslator/jane/*.java app/src/main/java/nie/translator/rtranslator/jane/ir/*.java app/src/test/java/nie/translator/rtranslator/jane/JaNeLibraryTest.java
java -Dstdout.encoding=UTF-8 -cp out nie.translator.rtranslator.jane.JaNeLibraryTest
```

## 精度の主張について
「市販ソフト以上」かどうかは、同じテスト文で比較・計測するまで主張しない。実機では Logcat タグ `jane` に候補ごとの点数が出る。
