# ネパール語 Whisper 差し替えツール（SPEC §11.3-1）

HF の Whisper チェックポイント（既定 `ilprl/Whisper-Small-Nepali-ASR-OSLR54`）を、アプリが読む
**本家と同じ 6 分割 ONNX** に変換・検証する。I/O 契約は [`INTERFACE.md`](./INTERFACE.md)。

| ファイル | 役割 |
|---|---|
| `export_whisper_split.py` | 6 ファイル出力（任意で encoder/decoder を int8 動的量子化 `--quantize`） |
| `verify_contract.py` | 名前/dtype/rank 照合＋log-mel 一致＋アプリと同じ逐次 greedy デコード vs HF forward greedy の一致＋batch2 経路 |

## 方式（要点）
- initializer: WhisperFeatureExtractor を標準 ONNX 演算（DFT 基底の conv1d）で再実装。カスタム op 不要。
- encoder / cache_initializer(batch1・batch2 固定) / decoder: HF の重みを使った自前 forward を
  `torch.onnx.export`（opset 17, legacy exporter）。decoder は 1 トークン/step、空 past（長さ0）から開始可能。
  語彙行列は転置 1 本だけ保持（embedding と LM head で共用）。
- detokenizer: onnxruntime-extensions の `BpeDecoder`（`sequences` int32 `[N,seq,ids]` → string `[N,text]`）。
  slow tokenizer（`byte_decoder` を持つ）から生成。
- 未確認(VERIFY/TODO): int8 量子化時の WER/CER 劣化、Android 実機（ORT Android 版・NO_OPT）での読込と速度、
  `proj_out` 非共有のチェックポイント（assert で停止する）。

## 手順
```bash
python3 -m venv venv
venv/bin/pip install --index-url https://download.pytorch.org/whl/cpu torch
venv/bin/pip install "transformers<5" onnx onnxruntime onnxruntime-extensions numpy soundfile
# 1) まず tiny で配線確認（数分・CPU）
venv/bin/python export_whisper_split.py --model openai/whisper-tiny --out out_tiny
venv/bin/python verify_contract.py --dir out_tiny --model openai/whisper-tiny --lang en --wav sample16k.wav
# 2) 本番（whisper-small, ネパール語）
venv/bin/python export_whisper_split.py --model ilprl/Whisper-Small-Nepali-ASR-OSLR54 --out out_ne [--quantize]
venv/bin/python verify_contract.py --dir out_ne --model ilprl/Whisper-Small-Nepali-ASR-OSLR54 --lang ne --wav ne16k.wav --strict-app
```
チェックポイントに processor が無い場合は `--processor openai/whisper-small`。
WAV は 16 kHz mono。採否は別途ネパール語テストセットで現行モデルと WER/CER 比較（SPEC §11.3）。

## 必要ハードウェア（目安・small は未実測）
- CPU のみで可（GPU 不要）。RAM 8 GB 以上推奨（small fp32 ≈ 1 GB の重み＋エクスポート時の複製）。
- ディスク: venv(torch CPU) ≈ 2 GB ＋ HF キャッシュ ≈ 1 GB ＋ 出力 fp32 ≈ 1 GB（int8 で約 1/3〜1/4）。
- 参考実測（tiny, 4 vCPU）: export 数十秒、出力 encoder 32.9 MB / decoder 113.5 MB（int8: 11.7 / 28.6 MB）。

## ライセンス確認
`https://huggingface.co/api/models/ilprl/Whisper-Small-Nepali-ASR-OSLR54`（2026-11-24 取得）:
`cardData.license = "apache-2.0"`, gated=false, sha `177fcd3942ea92be4ab55890220d8385b58ef570`, F32 241,734,912 パラメータ。
ベースの openai/whisper-small も Apache-2.0。学習データ OpenSLR 54（Nepali ASR, CC BY-SA 4.0 系）の
帰属表記要否はモデルカードを人手で確認すること（TODO）。配布時は LICENSE/NOTICE と出典を同梱。

## 配布と URL 切替（説明のみ・アプリコードは未変更）
1. 出力 6 ファイルを自前 GitHub リポジトリの Release（例: タグ `whisper-ne-v1`）に添付。
   各ファイルは 2 GB 未満なので Release アセットで可。SHA-256 を控える（SPEC §13 の検証用）。
2. `Global.java` の `getInitialDownloadInfo()` は Whisper 6 ファイルを `baseUrl`
   （`https://github.com/niedev/RTranslator/releases/download/2.0.0/`）から取得している。
   Whisper 用だけ別 URL（例 `https://github.com/<owner>/<repo>/releases/download/whisper-ne-v1/`）に
   差し替え、`DownloadInfo` のサイズ引数（KB 目安: decoder 173000 など）を新ファイルに合わせる。
3. 既存インストールはファイル名が同じため自動更新されない。版管理・再DL導線（SPEC §13）を別途用意。
4. 注意: このモデルは**全言語共通の Whisper を置き換える**。ネパール語 fine-tune で他言語（日本語等）の
   精度が落ちる可能性が高いので、ne 専用として並置する（別ファイル名＋言語で切替）案を推奨。要アプリ改修。
