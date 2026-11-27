# Personal PLaMo adapter from your own dictionary (LoRA)

Trains a small LoRA adapter (about 15 MB) for PLaMo 3 Translate 0.8B on your offline dictionary (the private
`#!jane phrases` / `ja-ne-terms` / `ne-ja-terms` files), on a PC. The phone only uses it: import the `.gguf`
in Settings → "import dictionary" (the same button as the dictionaries). The base model is not modified.

**Keep the adapter private.** It is derived from PLaMo (a "derived model" under the PLaMo Community License)
and from the material of your dictionary (for example copyrighted textbooks). It is used on your device only:
never commit it, upload it or share it. "Delete imported dictionaries" in Settings deletes it too.

## Steps

```sh
pip install -r tools/llama/finetune/requirements.txt
# PLaMo 3 Translate 0.8B (bf16, transformers) from the publisher: accepting the PLaMo Community License
#   https://huggingface.co/pfnet/plamo-3-translate-0.8b  ->  ./plamo08-hf
python3 tools/llama/finetune/build_data.py phrases.tsv ja_ne_terms.tsv ne_ja_terms.tsv testset.tsv data/
python3 tools/llama/finetune/train_lora.py plamo08-hf data/train.jsonl lora/ 2       # 2 epochs
sh tools/llama/finetune/export_adapter.sh plamo08-hf lora/step600 personal_lora.gguf   # the checkpoint best on data/dev.tsv
```

- `testset.tsv` (lang<TAB>text) are sentences kept for the evaluation: they and their near duplicates are left out
  of the training data, and 120 more pairs are held out (`data/dev.tsv`) to choose the checkpoint.
- Training data, as the app prompts: both directions (except `ja>ne` lines), 35% with the dictionary terms of the
  sentence as the PFML3 glossary, 20% with the previous 1-2 sentences of the same source as history, half of the
  term pairs as short examples. Loss on the translation only.
- LoRA r=16 / alpha 32 on `qkv_proj`, `o_proj`, `gate_up_proj`, `down_proj`; lr 2e-4 cosine, batch 16.
- CPU with bf16 matrix units (AMX, recent Xeon): about 9 s per step, 527 steps per epoch, under 3 GB of RAM.
  A GPU is much faster. `RESUME=lora/step400:400` continues from a checkpoint.
- Check an adapter with the app's engine: `plamo_cli model.gguf 4 personal_lora.gguf < sentences.tsv`, then
  `python3 tools/llama/finetune/score.py references.tsv name=output.tsv` (chrF, off-topic count).

## On the free Colab GPU (for a PC without enough RAM, e.g. 4 GB)

`colab_train.ipynb`: open it in Colab (File -> Upload notebook), choose a T4 GPU, store a Hugging Face token as the
`HF_TOKEN` secret after accepting the PLaMo Community License, run the cells. It clones this repository, builds the
data from the public dictionaries, trains 600 steps and downloads `personal_lora.gguf`. `train_lora.py` uses the
GPU when there is one (bf16 if supported, float32 on a T4). The time on a T4 has not been measured yet.
The adapter stays private (see above); files on Colab are gone when the session ends.

## Measured (the app's engine, Q4_K_M base + adapter, greedy, no glossary)

| | ja→ne chrF | ne→ja chrF |
|---|---|---|
| dev (120 pairs held out): base | 47.8 | 43.7 |
| dev: step 400 / **600** / 800 | 52.7 / **54.9** / 56.7 | 42.7 / **48.2** / 45.1 |
| test (80 sentences, used once): base | 45.3 | 34.5 |
| test: step 600 | **53.1** | **44.4** |

Step 600 (1.1 epochs) has the best mean on the dev set. Speed with the adapter: 32.4 instead of 34.8 generated
tokens/s on the PC (-7%). Same material as the dictionary, so the gain is largest for that kind of sentence.

## Distillation (tried, no measurable gain)

`extract_mono.py` collects untranslated care-domain sentences (exam text, care news); a teacher translates them;
`kd_data.py` keeps the pairs whose round trip agrees (chrF >= 0.25 through the student) and mixes source -> teacher
(distillation), teacher -> human source (back-translation) and half of the real examples; then a second stage:
`LR=1e-4 RESUME=lora/step600:0 python3 train_lora.py plamo08-hf data/kd_train.jsonl lora_kd/ 1`.

- Teacher PLaMo 2B + glossary: worse than the trained student on the dev set (57.8 / 49.0 vs 60.0 / 49.2), not used.
- Teacher Claude (blind 98-sentence check: 57.4 / 52.3, untrained 2B 56.3 / 43.3): 719 pairs (news and policy text).
  Result vs step 600: dev +2.2 / -0.1, blind -0.2 / -0.7; against step 800 (the same extra training without new data)
  +0.3 / +3.0; every 95% interval includes 0. The teacher data was another domain (news, policy) than the use
  (conversation), and small. Not worth it in this form; conversational source text would be the next thing to try.
  Check the terms of the teacher's provider before training on its output.

Notes on what went wrong on the way (kept so they are not repeated): `model.generate()` of transformers 5.24 with
PLaMo's remote code produces garbage (its cache path); training and the checks here do not use it. Full logits
(batch x length x 107520) exhausted 15 GB of RAM: the output layer is applied to the target positions only, lengths
are padded to multiples of 32 and freed memory is returned (`malloc_trim`); RAM stays at 2.7 GB.
