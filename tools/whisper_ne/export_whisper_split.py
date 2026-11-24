#!/usr/bin/env python3
"""Export a Hugging Face Whisper checkpoint into the 6-file ONNX layout that
MagiTranslator's Recognizer.java loads (see INTERFACE.md).

    Whisper_initializer.onnx            audio_pcm f32[1,N]          -> input_features f32[1,n_mels,3000]
    Whisper_encoder.onnx                input_features              -> last_hidden_state f32[batch_size,1500,d]
    Whisper_cache_initializer.onnx      encoder_hidden_states [1,..] -> present.{i}.encoder.key/value
    Whisper_cache_initializer_batch.onnx encoder_hidden_states [2,..] -> present.{i}.encoder.key/value
    Whisper_decoder.onnx                input_ids i64[B,1] + past_key_values.{i}.{decoder,encoder}.{key,value}
                                        -> logits f32[B,1,V] + present.{i}.decoder.{key,value}
    Whisper_detokenizer.onnx            sequences i32[1,1,N] -> text string[1,1] (BpeDecoder, onnxruntime-extensions)

Usage:
    python export_whisper_split.py --model ilprl/Whisper-Small-Nepali-ASR-OSLR54 --out out_ne [--quantize]
    python export_whisper_split.py --model openai/whisper-tiny --out out_tiny   # smoke test

Layer/head counts are read from the HF config.  NOTE: the app currently hard-codes
12 layers / 12 heads / head_dim 64 (whisper-small); a tiny/base export is only for
pipeline testing on a PC, it will NOT run in the app unchanged.
"""
import argparse
import os

import numpy as np
import onnx
import torch
from torch import nn

OPSET = 17
N_SAMPLES = 480000  # 30 s @ 16 kHz
N_FFT = 400
HOP = 160


# ---------------------------------------------------------------------------
# 1. Initializer: raw PCM -> log-mel (pure standard ONNX ops, no custom op)
# ---------------------------------------------------------------------------
class LogMel(nn.Module):
    """Re-implements transformers.WhisperFeatureExtractor (numpy path):
    pad/trim to 30 s, reflect-pad n_fft//2, periodic Hann STFT (n_fft=400, hop=160),
    power spectrum, drop last frame, mel (slaney), log10(max(.,1e-10)),
    max(x, x.max()-8), (x+4)/4.  STFT is done with a conv1d against a fixed DFT
    basis so that no STFT/custom op is required at runtime."""

    def __init__(self, mel_filters: np.ndarray):
        super().__init__()
        n = np.arange(N_FFT)
        window = 0.5 - 0.5 * np.cos(2 * np.pi * n / N_FFT)  # periodic Hann
        k = np.arange(N_FFT // 2 + 1)[:, None]
        ang = 2 * np.pi * k * n[None, :] / N_FFT
        real = (np.cos(ang) * window)[:, None, :]
        imag = (-np.sin(ang) * window)[:, None, :]
        self.register_buffer("w_re", torch.tensor(real, dtype=torch.float32))
        self.register_buffer("w_im", torch.tensor(imag, dtype=torch.float32))
        # HF mel_filters shape: (n_freq, n_mels)
        self.register_buffer("mel", torch.tensor(mel_filters.T, dtype=torch.float32))

    def forward(self, audio_pcm):  # [1, N]
        x = audio_pcm[:, :N_SAMPLES]
        x = nn.functional.pad(x, (0, N_SAMPLES - x.shape[1]))  # zero pad to 30 s
        x = x.unsqueeze(1)  # [1,1,N]
        x = nn.functional.pad(x, (N_FFT // 2, N_FFT // 2), mode="reflect")
        re = nn.functional.conv1d(x, self.w_re, stride=HOP)
        im = nn.functional.conv1d(x, self.w_im, stride=HOP)
        power = (re * re + im * im)[:, :, :-1]  # [1, 201, 3000]
        mel = torch.matmul(self.mel, power)  # [1, n_mels, 3000]
        log = torch.log10(torch.clamp(mel, min=1e-10))
        log = torch.maximum(log, log.max() - 8.0)
        return (log + 4.0) / 4.0


# ---------------------------------------------------------------------------
# 2. Encoder
# ---------------------------------------------------------------------------
class Encoder(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.enc = model.model.encoder

    def forward(self, input_features):
        return self.enc(input_features=input_features, return_dict=False)[0]


# ---------------------------------------------------------------------------
# 3. Cache initializer: cross-attention K/V per layer
# ---------------------------------------------------------------------------
class CacheInit(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.layers = model.model.decoder.layers
        cfg = model.config
        self.h = cfg.decoder_attention_heads
        self.dh = cfg.d_model // self.h

    def _split(self, x):  # [B,T,D] -> [B,H,T,dh]
        b, t, _ = x.shape
        return x.view(b, t, self.h, self.dh).transpose(1, 2)

    def forward(self, encoder_hidden_states):
        outs = []
        for layer in self.layers:
            a = layer.encoder_attn
            outs.append(self._split(a.k_proj(encoder_hidden_states)))
            outs.append(self._split(a.v_proj(encoder_hidden_states)))
        return tuple(outs)


# ---------------------------------------------------------------------------
# 4. Decoder with KV cache (hand-written forward over HF weights so that the
#    I/O names/layout are exactly what Recognizer.java expects, independent of
#    the transformers Cache API).  Matches WhisperDecoder (pre-LN, GELU, scaled q,
#    tied proj_out) -- verified numerically in verify_contract.py.
# ---------------------------------------------------------------------------
class Decoder(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.dec = model.model.decoder
        self.proj_out = model.proj_out
        cfg = model.config
        self.h = cfg.decoder_attention_heads
        self.dh = cfg.d_model // self.h
        self.scale = self.dh ** -0.5
        self.n = cfg.decoder_layers
        # Tied vocab matrix stored ONCE, transposed [d, V]: used for the token embedding
        # (gather on axis 1) and the LM head (matmul). Avoids a second copy that constant
        # folding of weight.t() would create. VERIFY: assumes scale_embedding=False (Whisper default).
        assert not getattr(cfg, "scale_embedding", False)
        self.wte_t = nn.Parameter(model.model.decoder.embed_tokens.weight.detach().t().contiguous())

    def _split(self, x):
        b, t, _ = x.shape
        return x.view(b, t, self.h, self.dh).transpose(1, 2)

    def _merge(self, x):
        b, h, t, d = x.shape
        return x.transpose(1, 2).reshape(b, t, h * d)

    @staticmethod
    def _attend(q, k, v):
        w = torch.softmax(torch.matmul(q, k.transpose(-1, -2)), dim=-1)
        return torch.matmul(w, v)

    def forward(self, input_ids, *past):
        # past = [dec_k0, dec_v0, enc_k0, enc_v0, dec_k1, ...]
        past_len = past[0].shape[2]
        pos = torch.arange(input_ids.shape[1], device=input_ids.device) + past_len
        x = self.wte_t[:, input_ids].permute(1, 2, 0) +self.dec.embed_positions.weight[pos]
        presents = []
        for i, layer in enumerate(self.dec.layers):
            pk, pv, ek, ev = past[4 * i: 4 * i + 4]
            # self attention (single new token -> causal mask not needed)
            r = x
            h = layer.self_attn_layer_norm(x)
            sa = layer.self_attn
            q = self._split(sa.q_proj(h) * self.scale)
            k = torch.cat([pk, self._split(sa.k_proj(h))], dim=2)
            v = torch.cat([pv, self._split(sa.v_proj(h))], dim=2)
            presents += [k, v]
            x = r + sa.out_proj(self._merge(self._attend(q, k, v)))
            # cross attention
            r = x
            h = layer.encoder_attn_layer_norm(x)
            ca = layer.encoder_attn
            q = self._split(ca.q_proj(h) * self.scale)
            x = r + ca.out_proj(self._merge(self._attend(q, ek, ev)))
            # MLP
            r = x
            h = layer.final_layer_norm(x)
            h = layer.fc2(layer.activation_fn(layer.fc1(h)))
            x = r + h
        x = self.dec.layer_norm(x)
        # proj_out is tied to embed_tokens in Whisper: reuse the same weight so the
        # vocab matrix is stored once (asserted in main()).
        logits = torch.matmul(x, self.wte_t)
        return (logits, *presents)


def _export(module, args, path, input_names, output_names, dynamic_axes):
    torch.onnx.export(module, args, path, input_names=input_names, output_names=output_names,
                      dynamic_axes=dynamic_axes, opset_version=OPSET, do_constant_folding=True,
                      dynamo=False)
    # consolidate external data (>2GB never happens for small, but keep single file)
    m = onnx.load(path)
    onnx.save_model(m, path, save_as_external_data=False)
    print("  wrote", path, f"{os.path.getsize(path) / 1e6:.1f} MB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="ilprl/Whisper-Small-Nepali-ASR-OSLR54")
    ap.add_argument("--processor", default=None,
                    help="processor/tokenizer id if the checkpoint lacks one (e.g. openai/whisper-small)")
    ap.add_argument("--out", default="out_whisper_ne")
    ap.add_argument("--quantize", action="store_true",
                    help="int8 dynamic quantization of encoder and decoder (VERIFY accuracy with WER/CER)")
    a = ap.parse_args()

    from transformers import WhisperForConditionalGeneration, WhisperProcessor

    os.makedirs(a.out, exist_ok=True)
    model = WhisperForConditionalGeneration.from_pretrained(a.model, torch_dtype=torch.float32).eval()
    proc = WhisperProcessor.from_pretrained(a.processor or a.model)
    cfg = model.config
    L, H = cfg.decoder_layers, cfg.decoder_attention_heads
    D, dh = cfg.d_model, cfg.d_model // cfg.decoder_attention_heads
    tied = torch.equal(model.proj_out.weight, model.model.decoder.embed_tokens.weight)
    assert tied, "proj_out not tied to embed_tokens: adapt Decoder.forward (TODO)"
    print(f"model={a.model} layers={L} heads={H} d_model={D} head_dim={dh} vocab={cfg.vocab_size} mels={cfg.num_mel_bins}")
    if (L, H, dh) != (12, 12, 64):
        print("  WARNING: app hard-codes 12 layers/12 heads/head_dim 64 (Recognizer.java L427-L442); "
              "this export is for PC testing only.")
    p = lambda n: os.path.join(a.out, n)

    with torch.no_grad():
        # 1 initializer
        _export(LogMel(proc.feature_extractor.mel_filters), (torch.zeros(1, 16000 * 5),),
                p("Whisper_initializer.onnx"), ["audio_pcm"], ["input_features"],
                {"audio_pcm": {1: "num_samples"}})
        # 2 encoder
        _export(Encoder(model), (torch.zeros(1, cfg.num_mel_bins, 3000),), p("Whisper_encoder.onnx"),
                ["input_features"], ["last_hidden_state"],
                {"input_features": {0: "batch_size"}, "last_hidden_state": {0: "batch_size"}})
        # 3 cache initializers (batch 1 and 2, fixed batch per file like upstream)
        names = [f"present.{i}.encoder.{kv}" for i in range(L) for kv in ("key", "value")]
        for b, fn in ((1, "Whisper_cache_initializer.onnx"), (2, "Whisper_cache_initializer_batch.onnx")):
            _export(CacheInit(model), (torch.zeros(b, 1500, D),), p(fn), ["encoder_hidden_states"], names, None)
        # 4 decoder
        in_names, args, dyn = ["input_ids"], [torch.zeros(1, 1, dtype=torch.long)], {"input_ids": {0: "batch_size"}}
        for i in range(L):
            for part in ("decoder", "encoder"):
                for kv in ("key", "value"):
                    n = f"past_key_values.{i}.{part}.{kv}"
                    in_names.append(n)
                    if part == "decoder":
                        args.append(torch.zeros(1, H, 3, dh))
                        dyn[n] = {0: "batch_size", 2: "past_decoder_sequence_length"}
                    else:
                        args.append(torch.zeros(1, H, 1500, dh))
                        dyn[n] = {0: "batch_size"}
        out_names = ["logits"] + [f"present.{i}.decoder.{kv}" for i in range(L) for kv in ("key", "value")]
        dyn["logits"] = {0: "batch_size"}
        for i in range(L):
            for kv in ("key", "value"):
                dyn[f"present.{i}.decoder.{kv}"] = {0: "batch_size", 2: "past_decoder_sequence_length + 1"}
        _export(Decoder(model), tuple(args), p("Whisper_decoder.onnx"), in_names, out_names, dyn)

    # 5 detokenizer (BpeDecoder custom op; app registers OrtxPackage)
    from onnxruntime_extensions._torch_cvt import WhisperDataProcGraph
    # the slow tokenizer exposes byte_decoder (the fast one does not -> 'Ġ' left in output)
    from transformers import WhisperTokenizer
    slow = WhisperProcessor(feature_extractor=proc.feature_extractor,
                            tokenizer=WhisperTokenizer.from_pretrained(a.processor or a.model))
    det = WhisperDataProcGraph(slow).post_processing(skip_special_tokens=True)
    onnx.save_model(det, p("Whisper_detokenizer.onnx"))
    print("  wrote", p("Whisper_detokenizer.onnx"))

    if a.quantize:
        # VERIFY: int8 dynamic quant; compare WER/CER vs fp32 before shipping.
        from onnxruntime.quantization import QuantType, quantize_dynamic
        for fn in ("Whisper_encoder.onnx", "Whisper_decoder.onnx"):
            src = p(fn)
            tmp = src + ".fp32"
            os.replace(src, tmp)
            quantize_dynamic(tmp, src, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gather"])
            os.remove(tmp)
            print("  quantized", src, f"{os.path.getsize(src) / 1e6:.1f} MB")
    print("done ->", a.out)


if __name__ == "__main__":
    main()
