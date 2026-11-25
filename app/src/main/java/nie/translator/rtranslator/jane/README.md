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

## 内蔵辞書
`app/src/main/res/raw/jane_dictionary.tsv`（日本語<TAB>ネパール語、約 90 語）を、アプリが自動で読み込みます。
地名・曜日・お金・医療・役所や書類・仕事と学校・家族・災害などの名詞です。
各語は訳文に必ず含める「必須の訳語」になるので、訳語が 1 つに決まる語だけを載せています。動詞や、部分一致で誤爆する 1 文字の語は載せていません。
追加の基準はファイル冒頭のコメントにあります。ネパール語の話者に確認してから追加してください。
ネ→日の方向には `app/src/main/res/raw/jane_dictionary_ne_ja.tsv`（ネパール語<TAB>日本語、約 95 語）を使います（`NeJaGlossary`）。
ネパール語は名詞の後ろに複数形や後置詞を続けて書く（अस्पतालमा、साथीहरूलाई）ので、それらが付いた形にも一致させます。後置詞は日本語の語の後ろに残します。
長い語の一部には一致しません（नेपाल は नेपाली に、कर は करोड に一致しない）。表記の揺れ（आइतबार/आइतवार など）は別の行として載せています。

## ユーザー用語集
アプリ内部ストレージ `files/jane_glossary.tsv` に `日本語<TAB>ネパール語` を1行ずつ。`#` 始まりはコメント。内蔵辞書より後に読むので、同じ見出し語なら上書きします（このフォルダは利用者からは直接見えないため、今は開発用です）。

## テスト
```
javac -encoding UTF-8 -d out app/src/main/java/nie/translator/rtranslator/jane/*.java app/src/test/java/nie/translator/rtranslator/jane/JaNeLibraryTest.java
java -Dstdout.encoding=UTF-8 -cp out nie.translator.rtranslator.jane.JaNeLibraryTest
```

## 精度の主張について
「市販ソフト以上」かどうかは、同じテスト文で比較・計測するまで主張しない。実機では Logcat タグ `jane` に候補ごとの点数が出る。
