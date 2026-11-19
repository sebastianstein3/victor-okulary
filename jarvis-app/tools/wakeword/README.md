# hej_wiktor: custom openWakeWord classifier

A classifier for the Polish wake phrase **"hej wiktor"**, including the vocative **"hej wiktorze"**. It runs as the third stage of the standard openWakeWord pipeline: `melspectrogram.onnx`, then `embedding_model.onnx`, then `hej_wiktor.onnx`. It expects 16 kHz mono 16-bit PCM audio.

The model was trained only on synthetic speech (Piper TTS). Its real-world recall on phone audio has not been measured. Test on a device before you rely on the numbers below.

## Files

| file | size | what |
|---|---|---|
| `hej_wiktor.onnx` | 204,080 B (199.3 KiB) | classifier. Input `input` float32 `[batch,16,96]`, output `output` float32 `[batch,1]` (sigmoid score). Opset 13, IR 7. Ops: Gemm, Flatten, Relu, Sigmoid, ReduceMean, Sub, Pow, Add, Sqrt, Div, Mul (LayerNorm is decomposed into these). 50,401 params. |
| `melspectrogram.onnx` | 1,087,958 B | from openWakeWord release v0.5.1 (the copy `openwakeword==0.6.0` downloaded and used). sha256 `ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f` |
| `embedding_model.onnx` | 1,326,578 B | from openWakeWord release v0.5.1. sha256 `70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f` |
| `train_hej_wiktor.py` | | full pipeline: `--stage bank,clips,feats,acav,train,eval,wavs` |
| `dl_acav.sh`, `dl_acav2.sh` | | HTTP Range downloads of the ACAV feature slices and the validation set |
| `metrics.json` | | full evaluation output |
| `test_positive_hej_wiktor.wav` | 2.74 s | "hej wiktor" spoken by the held-out voice (darkman), pink noise at 25 dB SNR. Streaming max score: **0.9999** |
| `test_negative_hej_wiktoria.wav` | 2.93 s | "hej wiktoria" (adversarial), same voice. Max score: **0.0009** |
| `test_negative_sentence.wav` | 5.06 s | "Jutro jadę pociągiem do Krakowa i wrócę w piątek." Max score: **0.0035** |

## Exact preprocessing to mirror in Kotlin (openwakeword 0.6.0, `utils.AudioFeatures` and `Model.predict`)

1. **Audio**: 16 kHz mono int16. Cast samples to float32 **without scaling**, so values stay in the range -32768 to 32767. Do not normalize to [-1, 1].
2. **Chunks**: process in chunks of **1280 samples (80 ms)**. The package buffers any remainder until a full 1280 is available.
3. **Melspectrogram**: for each new 1280-sample chunk, run `melspectrogram.onnx` on the **last 1280 + 480 = 1760 raw samples** (3 hops of 160 samples of left context). Input name `input`, shape `[1,1760]`. Output shape `[1,1,8,32]`; squeeze it to 8 frames x 32 mel bins. Then apply **`mel = mel / 10 + 2`** and append the result to a mel buffer. The mel buffer starts as 76 rows of `1.0` and holds at most 970 rows. Frame hop is 10 ms (160 samples), window 25 ms.
4. **Embedding**: after each chunk, take the **last 76 mel frames**, reshape them to `[1,76,32,1]` (input name `input_1`), and run `embedding_model.onnx`. The output `[1,1,1,96]` gives one 96-dim embedding per 80 ms. The embedding window is therefore 76 frames with a stride of 8 frames. Append the embedding to a feature buffer that holds at most 120 entries.
5. **Classifier**: input is the **last 16 embeddings**, as `[1,16,96]` float32 in time order with the oldest first. The output `[1,1]` is the score. One score is produced every 80 ms, and the window covers about 1.28 s of embeddings (about 2 s of audio context).
6. **Startup**: the package fills the initial feature buffer with embeddings of 4 s of random int16 noise in [-1000, 1000]. It also forces the score to 0 for the first 5 predictions after a reset. On Android it is enough to ignore scores until 16 real embeddings exist (1.28 s).

Training features came from `AudioFeatures.embed_clips` (the batch path). It applies the same /10+2 transform and the same 76/8 windowing to whole 3.2 s clips. All evaluation below ran through the streaming `openwakeword.Model(wakeword_models=["hej_wiktor.onnx"], inference_framework="onnx").predict()` path in 1280-sample chunks, which is identical to steps 1 to 6.

## Metrics (shipped model, held-out data, streaming through `openwakeword.Model`)

A held-out clip counts as detected if, at any 80 ms frame, the score is at or above the threshold for **N consecutive frames**. Clips are 3.2 s long with 1 s of zero padding on each side.

Held-out speakers: the Polish voice **pl_PL-darkman-medium** in full, plus **90 of the 904 LibriTTS-R speakers**. None of these speakers were used in training, and their background-babble bank was separate too.

| thr | N | recall, all positives (1330) | recall, Polish voice darkman (700) | recall, LibriTTS Polish phonemes (270) | recall, LibriTTS "hey victor" (360) | adversarial clips triggering (1710) | ordinary sentences triggering (384) | **validation false accepts/h** (10.7 h) |
|---|---|---|---|---|---|---|---|---|
| 0.3 | 1 | 89.0% | 85.6% | 91.5% | 93.9% | 5.6% | 0.5% | 2.15 |
| 0.3 | 2 | 84.5% | 79.3% | 87.4% | 92.5% | 2.6% | 0.0% | 0.84 |
| 0.3 | 3 | 79.5% | 72.6% | 83.7% | 90.0% | 1.3% | 0.0% | 0.19 |
| 0.5 | 1 | **87.7%** | 83.9% | 90.0% | 93.3% | 4.1% | 0.0% | **1.31** |
| 0.5 | 2 | **82.9%** | 77.4% | 86.3% | 90.8% | 1.9% | 0.0% | **0.65** |
| 0.5 | 3 | 76.5% | 68.3% | 82.2% | 88.3% | 0.9% | 0.0% | 0.19 |
| 0.7 | 1 | 84.7% | 80.0% | 87.0% | 92.2% | 2.6% | 0.0% | 0.75 |
| 0.7 | 2 | 79.4% | 73.1% | 82.6% | 89.2% | 1.4% | 0.0% | 0.47 |
| 0.7 | 3 | 73.7% | 65.3% | 77.8% | 86.9% | 0.8% | 0.0% | 0.09 |

How false accepts per hour were counted: we used `validation_set_features.npy`, which is 481,345 frames, about 10.7 h of DiPCo dinner-party speech, Santa Barbara conversational English, and reverberated music. Scores came from sliding 16-frame windows with stride 1. An accept is N consecutive frames at or above the threshold, followed by a 2 s refractory period. This file was never used for training or model selection. Raw frames above threshold per hour were 3.6 at 0.3, 2.5 at 0.5 and 1.7 at 0.7.

The adversarial phrases that trigger most often at 0.5/N1 are "hey vic" (6/15), "hey hector" (2/8), "hej inspektorze" (11/45), "hej rektorze" (10/42), "hej wiktorio" (6/44) and "hej hektor" (5/46). "Wiktor" on its own, "hej", "wiktoria", "dzień dobry" and "hej kolego" rarely or never trigger. Per-phrase numbers are in `metrics.json`.

### Recommendation

- **Threshold 0.5 with N = 2 consecutive frames** (160 ms) gives 82.9% recall and 0.65 FA/h. It is the best balance.
- If false accepts matter more, use **0.3 with N = 3**: 79.5% recall and 0.19 FA/h.
- If recall matters more, use **0.5 with N = 1**: 87.7% recall and 1.3 FA/h.
- **The N-consecutive-frames rule helps a lot**. Going from N = 1 to N = 3 cuts validation false accepts about 7x and adversarial triggers about 4x, at a cost of 10 to 15 points of recall. For the same recall, a lower threshold with N = 2 or 3 beats a higher threshold with N = 1: 0.3/N2 gives 84.5% recall at 0.84/h, against 0.7/N1 at 84.7% and 0.75/h.
- Add a debounce of about 2 s after a detection.

**Target status: not reached.** The target was recall of at least 85% with fewer than 0.5 FA/h. The closest points are 0.3/N2 (84.5% at 0.84/h) and 0.7/N2 (79.4% at 0.47/h). What limited it:

1. All positives are TTS. The held-out Polish voice is the hardest group, 5 to 15 points below the LibriTTS groups. With only 5 Polish Piper voices, 4 of which were used in training, Polish speaker diversity is the main bottleneck. Recordings of real speakers would very likely help more than anything else.
2. The negative data is a 7 GB slice (about 40%) of the 17 GB ACAV feature file, with no real Polish conversational speech. The validation set (English conversation) is much harder than ACAV. The first model trained on a 3 GB slice with weaker negative weighting reached 53 FA/h. Adding 400 more evenly spread slices and raising the negative weight to 100x brought it to 1.3 FA/h.
3. Training is short: 24k steps, about 18 minutes on CPU.

## Training data

- **Positives**: 9,828 augmented 3.2 s clips: 8,498 for training and 1,330 held out, giving 53,978 positive training windows.
  - Polish voices: 700 clips per voice with texts "hej wiktor", "hej, wiktor", "hej wiktorze", "hej, wiktorze", and variants ending in "!" and ".". **The vocative counts as positive.**
  - en_US-libritts_r-medium, all 904 speakers: 4 clips each of "hey victor", "hey viktor", "hay victor", "hey, victor" and similar, plus 3 clips each of "hej wiktor"/"hej, wiktor"/"hej wiktorze" phonemized with **Polish espeak** and spoken by the English multi-speaker model. Every Polish phoneme exists in its phoneme map, so this gives 904 accented Polish speakers.
  - Piper parameters were drawn uniformly per clip: length_scale 0.8 to 1.3, noise_scale 0.3 to 0.9, noise_w 0.3 to 1.0.
- **Augmentation** for positives and TTS negatives:
  - Synthetic reverb with probability 0.35: an exponentially decaying noise impulse response, RT60 0.15 to 0.8 s, wet level 0.1 to 0.6.
  - Background with probability 0.85: white, pink or brown noise and/or babble made of unrelated TTS sentences (a 0.7 h bank, kept separate for train and held-out speakers), at 5 to 20 dB SNR. Otherwise a noise floor at 30 to 45 dB SNR.
  - Telephone band-pass (about 300 to 3400 Hz) with probability 0.15, or a 80 to 250 Hz high-pass with probability 0.25.
  - Random peak level between -30 and -1 dBFS.
  - The phrase end is placed at 1.85 to 2.45 s. Positive training windows end 0.12 to 0.7 s after the phrase end and contain the whole phrase. Windows ending within 0.12 s of the phrase end are not used, so that "hej wiktor" is not confused with the prefix of "hej wiktoria".
- **TTS negatives**: 10,820 adversarial clips and 2,374 ordinary-sentence clips, with the same voices, split and augmentation. Every 16-window of these clips is used as a negative.
  - Polish: "hej", "wiktor", "wiktoria", "hej wiktoria", "hej victoria", "dzień dobry", "hej kolego", "piktor", "hej doktor", "hej, kto to", "rektor", "inspektor", "hej doktorze", "dyrektor", "hej rektorze", "hej hektor", "wiktorze" (alone), "hej wiktorio", "hej tato", "hej mamo", "okej", "hejka", "pan wiktor" and others.
  - English: "hey", "victor", "hey doctor", "hey victoria", "hey vic", "hey hector" and others.
  - 147 hand-written Polish sentences and 30 English sentences.
- **ACAV100M features** (general negatives): HTTP Range downloads of `openwakeword_features_ACAV100M_2000_hrs_16bit.npy`. The `.npy` header was parsed by hand: `<f2`, shape (5625000,16,96), 128-byte header, 3072 bytes per row.
  - Slices: 30 x 32,768 rows evenly spaced, plus 400 x 3,277 rows evenly spaced, about 6.8 GB in total.
  - Training used 2,215,196 rows. Slices 28 and 29 of the first set (65,536 rows) were held out as a dev set for model selection, together with a 5% internal split of the training clips.
- **Classifier**: Flatten(1536), Linear 32, LayerNorm, ReLU, Linear 32, LayerNorm, ReLU, Linear 1, Sigmoid. This is the openWakeWord default architecture with a width of 32.
  - Optimizer: AdamW with OneCycle learning rate (max 2e-3).
  - Batch: 256 positives, 256 TTS negatives and 2048 ACAV windows.
  - Negative loss weight ramps from 1 to 100 over the first half of training; TTS negatives get 1.5x that.
  - Hard-negative mining: every 1000 steps, half of the ACAV batch is drawn from the 20k highest-scoring ACAV windows.
  - Regularization: input dropout 0.1 and feature noise.
  - 24k steps; the best dev checkpoint (step 22000) was exported.

To reproduce, in a venv run `pip install torch --index-url https://download.pytorch.org/whl/cpu`, then `pip install openwakeword==0.6.0 piper-tts==1.8.0 onnx scipy`. Download the voices and features (see the scripts), then run `python3 -I train_hej_wiktor.py --stage bank,clips,feats,acav,train,eval,wavs --steps 24000 --max_neg_w 100`. This takes about 50 minutes on 4 CPU cores.

## Voices used (Piper, https://huggingface.co/rhasspy/piper-voices)

| voice | license (per MODEL_CARD) | notes |
|---|---|---|
| pl_PL-bass-high | Apache-2.0 | fine-tuned from en_US-lessac-high |
| pl_PL-darkman-medium (**held out**) | dataset CC0 | fine-tuned from the rdh (nl_BE, CC0) base |
| pl_PL-gosia-medium | dataset CC0 | fine-tuned from the en_US hifi_tts_92 base |
| pl_PL-mc_speech-medium | dataset CC0 (MC Speech) | fine-tuned from en_US-lessac-medium |
| pl_PL-mls_6892-low | dataset CC BY 4.0 (MLS, OpenSLR 94) | fine-tuned from en_US-ryan-low |
| en_US-libritts_r-medium (904 speakers, 90 held out) | dataset CC BY 4.0 (LibriTTS-R, OpenSLR 141) | fine-tuned from en_US-lessac-medium |

Some voices are fine-tuned from lessac base voices. The Blizzard 2013 Lessac data carries its own research-oriented terms, so check upstream if you plan commercial use. The generated audio was used only for training and is not shipped. piper-tts 1.8.0 (the OHF-Voice piper1-gpl package) is GPL-3.0. It was used offline as a tool only; none of its code is in the deliverables.

## Licenses of models and data

- **openWakeWord code**: Apache-2.0. The repository README says all included **pre-trained models are CC BY-NC-SA 4.0**. `melspectrogram.onnx` and `embedding_model.onnx` are a re-implementation of Google's `speech_embedding` (TFHub, Apache-2.0), but they ship as openWakeWord release assets under that statement. Treat them conservatively as CC BY-NC-SA 4.0, or ask the author.
- **openwakeword_features dataset**: ACAV100M slice and `validation_set_features.npy`, licensed **CC BY-NC-SA 4.0** per the dataset card. The ACAV100M audio itself comes from YouTube. The validation set is DiPCo, Santa Barbara Corpus and MUSDB with MIT impulse responses.
- **hej_wiktor.onnx** was trained on the CC BY-NC-SA ACAV features and runs on CC BY-NC-SA feature models. **Assume non-commercial and share-alike unless you clear this.** For a commercial app, retrain the negatives on permissively licensed audio and confirm the feature-model license.

## Known issues

- The 0.7 s positive tail teaches the model to keep firing for about 0.5 s after the phrase. That is harmless with a debounce.
- At 199.3 KiB the classifier is under 200 KiB, but slightly over 200 kB in decimal units.
- `data/` holds about 9 GB of intermediates (clips, embeddings, ACAV arrays) and `dl/` holds 6.8 GB of downloads. Delete them if they are not needed. `data/hej_wiktor_v1.onnx` and `data/metrics_v1.json` are the first, worse model (53 FA/h) and are kept for reference.
