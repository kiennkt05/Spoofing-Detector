# AASIST Mobile Spoof Detector

On-device audio spoof detection: AASIST checkpoint -> ONNX -> Android app
that records from the mic and shows a live/spoof decision with a score.

## What's here

```
aasist_export/              Python export pipeline (run on desktop first)
  models/AASIST.py            <- your uploaded model source, unmodified
  models/leaf_frontend.py     <- your uploaded frontend source, unmodified
  utils.py                    <- minimal str_to_bool shim main.py expects
  onnx_wrapper.py              wraps Model to output softmax(logits) directly
  export_to_onnx.py            export + desktop parity check (run this)

AasistDetector/              Android Studio project
  app/src/main/assets/model/   model.onnx + metadata.json go here
  app/.../detector/            AasistOnnxDetector, MicWindowSource, ViewModel
  app/.../ui/                  Compose screen
  app/.../MainActivity.kt      permission flow + Compose host
```

## Status

The bundled `AasistDetector/app/src/main/assets/model/` contains a **real
export from your uploaded `aasist.pth` checkpoint** (LA track config:
`filts[0]=16`, `gat_dims=[64,32]`, `use_gabor=True`, `use_spcen=True`,
`use_sm=False`, `nb_samp=64600` -> 4.0375s windows @ 16kHz).

- Checkpoint loaded with `strict=True`: 0 missing keys, 0 unexpected keys.
- PyTorch vs. ONNX Runtime parity checked on 6 waveforms (random noise at
  two seeds, silence, full-scale clipped noise, a 440Hz tone, a single-
  sample impulse): max abs diff ranged from `0.0` to `6.6e-6`, all far
  under the `1e-3` warning threshold.
- **Not yet validated against real bonafide/spoof speech.** The parity
  check only proves PyTorch and ONNX agree with each other on the same
  input -- it says nothing about whether the model correctly classifies
  real audio, since none was used. Run `export_to_onnx.py --wav
  your_sample.wav` against a few real ASVspoof-style files and compare the
  printed score to your desktop evaluation output for the same files
  before trusting on-device results.

## 1. Re-export your checkpoint (if you change it)

```bash
cd aasist_export
pip install torch numpy onnx onnxruntime soundfile --break-system-packages

python export_to_onnx.py \
    --config /path/to/your/config.json \
    --checkpoint /path/to/your/best.pth \
    --out_dir ./out \
    --window_seconds 4.0 \
    --sample_rate 16000
```

`config.json` must have the same `model_config` block your training run
used (`filts`, `gat_dims`, `pool_ratios`, `temperatures`, `first_conv`, and
optionally `use_gabor` / `use_spcen` / `use_sm`) -- these are frozen
architecture choices baked into the checkpoint, not export options.

The script prints a parity check comparing PyTorch vs. ONNX Runtime output
on the same waveform. Anything above `1e-3` max-abs-diff is worth
investigating before going further -- the script's stderr warning names the
three most likely culprits (GraphPool top-k/gather, GaborConv1D's
logit/softplus/sigmoid filter construction, or the in-place EMA slice-assign
in `differentiable_ema`).

Pass `--wav /path/to/real_sample.wav` to parity-check against a real
utterance instead of random noise (needs `librosa` if its sample rate
differs from `--sample_rate`).

**Why the input length is fixed, not dynamic:** `GraphPool.top_k_graph`
computes `n_nodes = max(int(n_nodes * k), 1)` from the traced tensor's
Python-int shape, and `differentiable_ema` derives its kernel length `K`
the same way. Both get baked into the ONNX graph as constants at export
time. A dynamic time axis would silently use whatever length happened to be
traced for every other input length, which is worse than just requiring a
fixed window. The Android app buffers mic audio into non-overlapping
windows of exactly this length (see `MicWindowSource.kt`).

## 2. Copy the export into the Android project

```bash
cp out/assets/model/model.onnx out/assets/model/metadata.json \
   ../AasistDetector/app/src/main/assets/model/
```

This **replaces the placeholder model** currently in that directory (see the
big warning in `assets/model/README.txt` -- the placeholder was exported
from random weights purely to prove the pipeline works, and produces
meaningless scores).

## 3. Build and run

Open `AasistDetector/` in Android Studio (Giraffe or newer). The project
ships `gradle/wrapper/gradle-wrapper.properties` (pinned to Gradle 8.7) but
not the wrapper jar/scripts themselves -- Android Studio regenerates those
automatically on first Gradle sync. If you're building from the command
line instead of Android Studio, run `gradle wrapper` once first (any local
Gradle 8.x install) to generate `gradlew` / `gradlew.bat` /
`gradle-wrapper.jar`, then use `./gradlew assembleDebug` as normal.

Run on a physical `arm64-v8a` device -- the emulator's mic usually doesn't
have real audio input, which makes spoof-detection testing meaningless on
it.

## Key deviations from a generic "Sherpa-ONNX Android" template

This is **not** built on Sherpa-ONNX. Sherpa-ONNX is an ASR/TTS/VAD/speaker-
ID/diarization framework built around `OnlineRecognizer`-style streaming
decode (`acceptWaveform -> decode -> getResult`) and transducer ASR bundles
(`encoder.onnx` + `decoder.onnx` + `joiner.onnx` + `tokens.txt`). None of
that fits a 2-class utterance-level spoof classifier with a custom AASIST
graph attention architecture. Concretely:

- **Runtime**: plain `com.microsoft.onnxruntime:onnxruntime-android` AAR,
  not Sherpa-ONNX's JNI bindings. This gives `OrtEnvironment` /
  `OrtSession` directly -- the correct minimal layer for a custom ONNX
  graph -- with no ASR-shaped API to route around.
- **Assets**: just `model.onnx` + `metadata.json`. No `tokens.txt`, no
  encoder/decoder/joiner triplet.
- **ViewModel shape**: `AasistDetectorViewModel` buffers fixed windows and
  calls `runInference()` once per window, exposing `spoofScore` /
  `SpoofLabel` in `DetectorUiState`. There's no streaming decode state,
  partial transcript, or `acceptWaveform()` loop, because the model
  classifies complete utterances, matching how `main.py`'s
  `produce_evaluation_file()` evaluates it (whole file in, one score out).
- **UI**: live/spoof + numeric score, no transcript text anywhere.
- **Score semantics**: `main.py` reads `batch_out[:, 1]` as the spoof score
  from raw logits. The exported ONNX graph applies `softmax` before
  returning, so `metadata.json`'s `spoof_class_index: 1` indexes into a
  `[bonafide_prob, spoof_prob]` pair already in `[0, 1]` -- one less
  place for desktop/mobile numerics to drift apart.

## Before shipping

Re-run the parity check in `export_to_onnx.py` against a few real audio
samples (bonafide and spoof) and sanity-check the printed scores match your
desktop evaluation numbers for the same files, not just that the max-abs-
diff is small in isolation.
