# 個人辞書で学習した PLaMo（Built with PLaMo）

Built with PLaMo. PLaMo 3 Translate（Preferred Networks）を、このリポジトリの辞書（会話集・用語）で追加学習したもの。
PLaMo Community License の「派生モデル」。使用・再配布はライセンス（`PLaMo-Community-License-*.txt`）に従うこと。

| ファイル | 大きさ | 中身 | 使い方 |
|---|---|---|---|
| `plamo-2b_personal-dict_step527.gguf` | 37 MB | PLaMo 3 Translate **2B** 用 LoRA アダプタ（step 527） | アプリの「設定 → 辞書を読み込む」で取り込む。モデルは 2B（大）を選ぶ |
| `plamo-0.8b_personal-dict_step600.gguf` | 15 MB | PLaMo 3 Translate **0.8B** 用 LoRA アダプタ（step 600、会話集4,874文・用語から10,634例、2026-11-27 学習） | 「辞書を読み込む」で取り込む。モデルは 0.8B（小）を選ぶ |
| `plamo-3-translate-2b-personal-step527-Q4_K_M.gguf.part00`〜`part18` | 計 1.8 GB | 上のアダプタを 2B に焼き込んだ単体モデル（Q4_K_M） | 結合してから使う（下） |

結合（PC）:

```bash
cat plamo-3-translate-2b-personal-step527-Q4_K_M.gguf.part* > plamo-3-translate-2b-personal-step527-Q4_K_M.gguf
```

```bash
sha256sum -c SHA256SUMS
```

- 焼き込み版は「元の 2B（f16）＋アダプタ」と出力が一致することを確認してから量子化した（2026-11-27、3文）。
- 精度（別セッションの開発用120組、chrF）：日→ネ 57.8 / ネ→日 52.7。80文の評価は未実施。
- 速さは 2B なので 0.8B の約2.5倍遅い。
- アプリは今のところ焼き込み版の単体モデルを読み込む画面を持たない。スマホではアダプタ（37 MB）を使う。
