# MagiTranslator

日本語⇄ネパール語、日本語⇄ベトナム語に特化した、オフラインで動く Android 用のリアルタイム翻訳アプリです。
[RTranslator](https://github.com/niedev/RTranslator)（Apache License 2.0）を元にしています。

- **会話モード**：Bluetooth で 2 台の端末をつなぎ、それぞれの言語で話す
- **トランシーバーモード**：1 台の端末で、2 つの言語を交互に話す
- **テキスト翻訳**

音声認識は Whisper、翻訳はネパール語が Madlad、ベトナム語が HY-MT です。日本語⇄ネパール語は、英語を経由しない専用の処理（`jane`）を通します。
どれも端末の中で動くので、モデルをダウンロードした後はインターネットなしで使えます。

## インストール

1. [Releases](https://github.com/ichirocc/MagiTranslator/releases) から APK をダウンロードしてインストールする（Android 16 以上）
2. 初回起動時にモデルをダウンロードする（Wi-Fi 推奨）
3. 設定 → モデル管理 で Madlad（ネパール語）と HY-MT（ベトナム語）をダウンロードする

モデルは、このリポジトリのリリース [`models-v1`](https://github.com/ichirocc/MagiTranslator/releases/tag/models-v1) からだけダウンロードします。
ダウンロードしたファイルは SHA-256 を照合し、違っていれば使いません（[models/](models/)）。
パソコンからモデルを入れる（サイドロード）方法は、現在の版では使えません（[Sideloading.md](Sideloading.md)）。

## 通信とプライバシー

- インターネットに接続するのは、モデルのダウンロード時だけです
- 会話モードでは、認識した文章を Bluetooth で相手の端末に送ります
- 解析やクラッシュ報告のライブラリは入っていません
- 読み上げは端末の読み上げエンジンを使います。エンジンによってはネット経由で処理する場合があります

## 開発

- 仕様：[docs/SPEC.md](docs/SPEC.md)
- 日本語⇄ネパール語の処理：[app/src/main/java/nie/translator/rtranslator/jane/README.md](app/src/main/java/nie/translator/rtranslator/jane/README.md)
- 翻訳の評価ツール：[tools/eval/](tools/eval/)、ネパール語 Whisper の書き出し：[tools/whisper_ne/](tools/whisper_ne/)

## ライセンスと謝辞

Apache License 2.0（[LICENSE.txt](LICENSE.txt)、[NOTICE.txt](NOTICE.txt)）。
元になった RTranslator は Luca Martino（niedev）さんの作品です。元の README は [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md) に残してあります。
MagiTranslator への問い合わせや不具合報告は、RTranslator の作者ではなく、このリポジトリの [Issues](https://github.com/ichirocc/MagiTranslator/issues) にお願いします。
