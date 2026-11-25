#!/usr/bin/env python3
"""Model-free tests for onnx_runner.py (python3 -m unittest test_onnx_runner)."""
import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import onnx_runner as R  # noqa: E402


class TextAndTokens(unittest.TestCase):
    def test_prefix(self):
        self.assertEqual(R.madlad_prefixed_text("こんにちは", "ne"), "<2ne> こんにちは")

    def test_encoder_inputs_append_eos_no_shift(self):
        ids, mask = R.build_encoder_inputs([10, 11, 12])
        self.assertEqual(ids, [10, 11, 12, 2])
        self.assertEqual(mask, [1, 1, 1, 1])

    def test_correct_text_no_terminator_for_madlad(self):
        self.assertEqual(R.correct_text_madlad("  元気です  "), "元気です")

    def test_correct_text_newlines_tabs_spaces(self):
        # \R -> space, tab (Cntrl) removed, whitespace collapsed
        self.assertEqual(R.correct_text_madlad("a\r\nb\tc  d e\x00"), "a bc d e")

    def test_java_trim_keeps_nbsp(self):
        self.assertEqual(R.java_trim(" x \x01"), " x")

    def test_decode(self):
        pieces = {2: "</s>", 5: "▁नमस्ते", 6: "▁छ", 300000: "X"}
        self.assertEqual(R.decode_ids(pieces.get, [5, 6, 2]), "नमस्ते छ")
        self.assertEqual(R.decode_ids(pieces.get, [0, 1, 3]), "")


class Bookkeeping(unittest.TestCase):
    def test_early_stop_factor(self):
        self.assertEqual(R.early_stop_factor(40, 3), 3)
        self.assertEqual(R.early_stop_factor(25, 3), 4)
        self.assertEqual(R.early_stop_factor(14, 3), 5)
        self.assertEqual(R.early_stop_factor(13, 3), 8)

    def test_patience(self):
        self.assertEqual([R.patience_length(b) for b in (1, 2, 3, 4, 5)], [2, 3, 5, 6, 8])

    def test_past_names(self):
        n = R.past_names()
        self.assertEqual(len(n), 4 * 32)
        self.assertEqual(n[:2], ["past_key_values.0.decoder.key", "past_key_values.0.decoder.value"])
        self.assertEqual(n[-1], "past_key_values.31.encoder.value")
        self.assertEqual(R.empty_decoder_past_shape(), (1, 16, 0, 128))

    def test_tile_and_reorder(self):
        a = np.arange(6, dtype=np.float32).reshape(1, 1, 2, 3)
        b = R.batch_tile(a, 3)
        self.assertEqual(b.shape, (3, 1, 2, 3))
        b[1] += 10
        b[2] += 20
        r = R.reorder_cache(b, [2, 2, 0])
        self.assertTrue(np.array_equal(r[0], b[2]) and np.array_equal(r[1], b[2]) and np.array_equal(r[2], b[0]))

    def test_topk_ties_lower_index_first(self):
        row = np.array([1, 5, 5, 3], dtype=np.float32)
        self.assertEqual(R.topk_java(row, 3), [1, 2, 3])
        self.assertEqual(R.argmax_avoid([1, 5, 5, 3], [1]), 2)

    def test_lse_fast_threshold(self):
        row = np.array([0.0, -25.0], dtype=np.float32)
        self.assertEqual(R.log_sum_exp_fast(row), 0.0)   # -25 is skipped


class BeamUpdate(unittest.TestCase):
    def test_eos_goes_to_finished_and_slot_refilled(self):
        # beam 2, vocab 5, eos=2
        lg = [np.array([0, 0, 9, 8, 0], dtype=np.float32),   # beam0: eos, 3
              np.array([0, 0, 0, 1, 7], dtype=np.float32)]   # beam1: 4, 3
        fin, fs = [], []
        nxt, sc, beams, src = R.update_beam(lg, 2, [0.0, -1.0], [[7], [8]], fin, fs)
        self.assertEqual(fin, [[7, 2]])
        # ranking: (0,eos) ~ -0.31 -> finished, slot refilled; then (1,tok4) ~ -0.50; then (0,tok3) ~ -0.81
        self.assertEqual(src, [1, 0])
        self.assertEqual(nxt, [4, 3])
        self.assertEqual(beams, [[8, 4], [7, 3]])
        lse0 = R.log_sum_exp_fast(lg[0])
        self.assertAlmostEqual(fs[0], 9 - lse0)                       # eos: no reward, EOS_PENALTY=0
        self.assertAlmostEqual(sc[1], 8 - lse0 + 0.5)                 # word reward, no sibling hit
        self.assertAlmostEqual(sc[0], -1.0 + 7 - R.log_sum_exp_fast(lg[1]) + 0.5)

    def test_sibling_penalty(self):
        lg = [np.array([0, 0, 0, 5, 4.95], dtype=np.float32),
              np.array([0, 0, 0, 5, 4.95], dtype=np.float32)]
        # without penalty both slots would pick token 3 (k=0 and k=1); with -0.2 the 2nd pick is token 4
        nxt, _, _, src = R.update_beam(lg, 2, [0.0, 0.0], [[5], [6]], [], [])
        self.assertEqual(nxt, [3, 4])
        self.assertEqual(src, [0, 0])


class FakeSP:
    def encode(self, t):
        return [10 + (ord(c) % 50) for c in t]

    def piece_to_id(self, p):
        return 2

    def id_to_piece(self, i):
        return "▁w%d" % i


class FakeSess:
    """Asserts the feeds the Java code would build; logits follow a scripted token sequence."""
    def __init__(self, kind, script=None, beam=1, test=None):
        self.kind, self.script, self.beam, self.t, self.step = kind, script, beam, test, 0

    def run(self, names, feeds):
        t = self.t
        if self.kind == "embed":
            x = feeds["input_ids"]
            t.assertEqual(x.dtype, np.int64)
            return [np.zeros(x.shape + (4,), np.float32)]
        if self.kind == "encoder":
            t.assertEqual(set(feeds), {"input_ids", "attention_mask", "embed_matrix"})
            return [np.zeros((1, feeds["input_ids"].shape[1], 4), np.float32)]
        if self.kind == "cache":
            L = feeds["encoder_hidden_states"].shape[1]
            return [np.zeros((1, 16, L, 128), np.float32) for _ in names]
        # decoder
        self.step += 1
        b = 1 if self.step == 1 else self.beam
        t.assertEqual(len(feeds), 3 + 4 * 32)
        t.assertEqual(feeds["input_ids"].shape, (b, 1))
        t.assertEqual(feeds["input_ids"].dtype, np.int64)
        if self.step == 1:
            t.assertEqual(int(feeds["input_ids"][0, 0]), 0)
        t.assertEqual(feeds["encoder_attention_mask"].shape[0], b)
        t.assertEqual(feeds["past_key_values.5.decoder.key"].shape, (b, 16, self.step - 1, 128))
        t.assertEqual(feeds["past_key_values.5.encoder.value"].shape[0], b)
        V = 60
        logits = np.zeros((b, 1, V), np.float32)
        tok = self.script[min(self.step - 1, len(self.script) - 1)]
        logits[:, 0, tok] = 10.0
        present = [np.zeros((b, 16, self.step, 128), np.float32) for _ in range(64)]
        return [logits] + present


def fake_runner(test, script, beam):
    s = {"embed": FakeSess("embed", test=test), "encoder": FakeSess("encoder", test=test),
         "cache_initializer": FakeSess("cache", test=test),
         "decoder": FakeSess("decoder", script, beam, test)}
    return R.MadladRunner(sessions=s, sp=FakeSP())


class EndToEndFake(unittest.TestCase):
    def test_greedy_stops_at_eos(self):
        r = fake_runner(self, [20, 21, 2], 1)
        self.assertEqual(r.translate_ids("あい", "ne", beam=1), [20, 21, 2])
        r = fake_runner(self, [20, 21, 2], 1)   # fresh fake (step counter)
        self.assertEqual(r.translate("あい", "ne", beam=1), "w20 w21")

    def test_greedy_early_stop(self):
        r = fake_runner(self, [20], 1)   # never emits eos
        out = r.translate_ids("あ", "ne", beam=1)
        n_ids = len(R.tokenize(FakeSP().encode, "あ", "ne")[0])
        self.assertEqual(len(out), 8 * n_ids)   # j runs 2..8n+1 -> 8n tokens

    def test_beam_shapes_and_finish(self):
        r = fake_runner(self, [20, 21, 2], 4)
        out = r.translate_ids("あい", "ne", beam=4)
        self.assertEqual(out[-1], 2)
        self.assertEqual(out[:2], [20, 21])


class DryRun(unittest.TestCase):
    def test_dry_run_missing_files(self):
        import tempfile
        d = tempfile.mkdtemp()
        ts = os.path.join(d, "t.tsv")
        with open(ts, "w", encoding="utf-8") as f:
            f.write("x1\tja-ne\tg\tこんにちは\tनमस्ते\n")
        rc = R.main(["--models", d, "--in", ts, "--out", os.path.join(d, "o.tsv"),
                     "--src", "ja", "--tgt", "ne", "--dry-run"])
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
