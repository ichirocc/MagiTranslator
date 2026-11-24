# モデルをパソコンから入れる（サイドロード）について

**現在の MagiTranslator（3.0 系）では、パソコンから入れたモデルを読み込む処理が見当たりません。**
元の RTranslator 2.x には、`Android/data/<アプリID>/files` に置いたモデルを初回設定時に取り込む機能がありました。
しかし、このコードベースにはそのフォルダからファイルを移す処理がありません（アプリが作るのは `Readme.txt` だけです）。
そのため、モデルはアプリの中からダウンロードしてください。

- ダウンロード元：このリポジトリのリリース [`models-v1`](https://github.com/ichirocc/MagiTranslator/releases/tag/models-v1)
- 各ファイルの SHA-256：[models/models.tsv](models/models.tsv)。アプリはダウンロード後にこの値と照合し、違うファイルは使いません

サイドロードを復活させる場合、MagiTranslator のアプリ ID は `com.magi.translator` なので、フォルダは `Android/data/com.magi.translator/files` になります。
取り込むときも、`ModelSources.verify` で同じ SHA-256 照合をしてください。
