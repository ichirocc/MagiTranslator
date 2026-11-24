# jane — 日本語⇄ネパール語 直接翻訳ライブラリ

英語を経由しない日→ネ特化パイプライン。純 Java（Android 非依存）で、翻訳モデル（Madlad）は `Backend` として差し込む。速度より精度を優先する。

## 処理の流れ（日→ネ）
1. `JaNormalizer` — NFKC、話し言葉のフィラー除去（「あの人」は残す）、漢数字→数字（三時半→3時30分）、文末補完
2. 候補の入力を作る — 全文 / 文ごと（`JaSegmenter`：句読点の無い音声認識結果も文末表現で分割）/ 用語注入（`Glossary`：訳語を原文へ埋め込む。カタカナ人名＋さん等は `KanaDevanagari` で翻字）
3. 各候補を翻訳し `NePostEditor`（NFC、「.」→「।」、ループ畳み込み）
4. `QualityScorer` で採点 — 逆翻訳(ne→ja)と原文の chrF、数字の保持、必須用語、文字種の純度、敬語の整合（です/ます→तिमी/तँ を減点）、長さ比、候補間の合意度（MBR）
5. 最高点を採用（候補が1つなら逆翻訳を省略）

## 根拠
- chrF: Popović 2015 / 品質考慮の選択: Fernandes et al. 2022 / MBR: Freitag et al. 2022, 2023
- 目標語注釈による用語指定: Exel et al. 2020, Bergmanis & Pinnis 2021

## ユーザー用語集
アプリ内部ストレージ `files/jane_glossary.tsv` に `日本語<TAB>ネパール語` を1行ずつ。`#` 始まりはコメント。

## テスト
```
javac -encoding UTF-8 -d out app/src/main/java/nie/translator/rtranslator/jane/*.java app/src/test/java/nie/translator/rtranslator/jane/JaNeLibraryTest.java
java -Dstdout.encoding=UTF-8 -cp out nie.translator.rtranslator.jane.JaNeLibraryTest
```

## 精度の主張について
「市販ソフト以上」かどうかは、同じテスト文で比較・計測するまで主張しない。実機では Logcat タグ `jane` に候補ごとの点数が出る。
