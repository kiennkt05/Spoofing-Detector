Place exactly two files here, copied from the export script's output
(`out/assets/model/` after running `python export_to_onnx.py ...`):

  model.onnx
  metadata.json

Nothing else goes in this directory. This is a custom AASIST spoof
classifier, not a Sherpa-ONNX transducer ASR bundle, so there is no
tokens.txt, no encoder.onnx / decoder.onnx / joiner.onnx triplet.

File names must match exactly what AasistOnnxDetector.kt expects
(`model/model.onnx`, `model/metadata.json`) -- those are also the
defaults in AasistOnnxDetector's constructor, so as long as you don't
rename anything on either side, this just works.

==============================================================================
STATUS: real trained checkpoint exported and verified.
==============================================================================
model.onnx in this directory was exported from your uploaded aasist.pth
checkpoint (LA track, filts[0]=16, gat_dims=[64,32], use_gabor=True,
use_spcen=True, use_sm=False, nb_samp=64600 -> window_seconds=4.0375 @
16kHz). The checkpoint loaded into the model with strict=True: zero missing
keys, zero unexpected keys.

Parity check (PyTorch vs. ONNX Runtime, same waveform in both):
  random noise (seed 1234):  max |diff| = 4.9e-09
  random noise (seed 99):    max |diff| = 0.0
  silence:                   max |diff| = 5.0e-20
  full-scale clipped noise:  max |diff| = 4.7e-10
  440Hz pure tone:           max |diff| = 6.6e-06
  single-sample impulse:     max |diff| = 2.3e-20
All comfortably under the 1e-3 warning threshold from export_to_onnx.py.

This has NOT been validated against real bonafide/spoof speech recordings
-- only synthetic edge-case waveforms (noise, silence, tone, impulse) to
confirm the graph traces correctly and PyTorch/ONNX agree numerically.
Before trusting on-device scores, run a few real ASVspoof-style wav files
through `export_to_onnx.py --wav your_sample.wav` and compare against your
desktop evaluation numbers for the same files.
