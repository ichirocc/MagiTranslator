# モデルの安全な導入（原子的インストール・ロールバック・マニフェスト署名）

SPEC v2 第3部 §4.3 / §17.3–17.6 の実装メモ。

## 原子的インストール（`downloader/ModelStore.java`）
- ダウンロード先は `<dest>/.staging/<name>`（PRDownloader はさらに `<name>.temp` 経由で書く）。
- 完了後に「サイズ（指定時）→ SHA-256（`ModelSources` の固定値）」を検証し、合格したときだけ `rename` で `<dest>/<name>` に移す。
  不合格なら staging を消し、`<dest>/.failed.tsv` に `name<TAB>sha256` を記録。**途中・不正なファイルが本番名に現れることはない。**
- 既存ファイルがあり、移動後も空きが 2GB 超なら旧ファイルを `<dest>/.previous/<name>` に残す（ロールバック用）。
  足りなければ旧ファイルは上書き＝そのファイルはロールバック不可（インストール自体は原子的）。
- `rollback(dir, name)`: `.previous` を戻し、戻される側の sha256 を `.failed.tsv` に記録（自動再有効化の防止）。
- 制約（モデル一式 約5.6GB、Madlad.zip 約1.7GB）: 全ファイルに旧版を保持すると倍の容量が要るため、上記の空き容量条件つき。
  zip は展開後に削除されるので、zip のロールバック対象は展開済みモデルではなく zip 自体。展開済みモデルの revision 管理（§17.4 の `revisions/` 構成）は未実装。
- 現状、アプリから `rollback` を呼ぶ UI/自動判定（ロード失敗・品質回帰時）は未接続。

## マニフェスト署名（`downloader/ManifestSignature.java`）
- 署名対象（正準形）: `magi-models-manifest-v1\n` ＋ ファイル名順に `name<TAB>sha256\n`。
- 状態: `UNSIGNED`（鍵・署名とも空＝**現在のビルド**。従来どおりハッシュのみ）/ `VALID` / `INVALID`（ダウンロード拒否, エラーコード 5）/ `UNSUPPORTED`（端末に Ed25519 が無い。拒否しない）。
- 現在は公開鍵・署名が**プレースホルダ（空）**。マニフェストは APK に埋め込まれているため、現時点の効果は「鍵なしで models.tsv を変えたビルドを検出する」こと。マニフェストをネット取得する段階で本来の信頼起点になる。

### 有効化の手順（ユーザー作業）
1. 鍵を作る（手元 PC、JDK 15+）: `java tools/ManifestSigner.java genkey`
2. 出力の `PRIVATE` を GitHub の Actions secret `MAGI_MANIFEST_SIGNING_KEY` に登録。**リポジトリには絶対にコミットしない。**
3. `PUBLIC_KEY_X509_B64` を `ManifestSignature.PUBLIC_KEY_X509_B64` に貼る。
4. 署名: `MAGI_MANIFEST_SIGNING_KEY=... java tools/ManifestSigner.java sign` の出力を `MANIFEST_SIGNATURE_B64` に貼る（models.tsv を変えるたびに再署名）。
5. 実機で `Ed25519` が使えること（Android 13+ の Conscrypt）を確認してから配布。
- 鍵の更新: 新しい鍵で 1〜4 をやり直す（旧鍵の署名は新ビルドでは無効になる）。
