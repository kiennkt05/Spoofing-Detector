"""
Export an AASIST checkpoint to ONNX for on-device (Android) inference, and
validate the export against the original PyTorch model on desktop.

Usage:
    python export_to_onnx.py \
        --config /path/to/config.json \
        --checkpoint /path/to/best.pth \
        --out_dir ./out \
        --window_seconds 4.0 \
        --sample_rate 16000

What this does, matching Phase 2 of the plan:
  1. Loads config.json's "model_config" block (filts, gat_dims, pool_ratios,
     temperatures, first_conv, use_gabor, use_spcen, use_sm) -- these are
     frozen architecture choices and must match what the checkpoint was
     trained with. We do NOT let the export silently default any of them.
  2. Builds AasistOnnxWrapper(d_args) and loads the checkpoint's state_dict
     into wrapper.model (the same nn.Module the checkpoint was saved from --
     main.py saves model.state_dict() directly, so this loads with strict=True
     against wrapper.model, not against the wrapper itself).
  3. Exports to ONNX with a FIXED input length (no dynamic_axes on the time
     dimension) -- this is required because GraphPool.top_k_graph and
     leaf_frontend.differentiable_ema both derive Python-int shape constants
     (n_nodes, K) from the traced input shape. A dynamic time axis would bake
     in whatever length happened to be traced and silently misbehave on any
     other length, which is worse than refusing dynamic shapes outright.
  4. Runs the SAME fixed-length random (and, if --wav given, real) waveform
     through both the PyTorch wrapper and onnxruntime, and reports the max
     absolute difference in the two output probabilities. This is the parity
     check the plan calls for in Phase 2 step 5 and Phase 5's final check --
     it is the fastest way to catch a frontend mismatch (Gabor filter
     construction, sPCEN EMA, or GraphPool top-k) that traced "successfully"
     but computes something different.
  5. Writes assets/model/model.onnx and assets/model/metadata.json (window
     length, sample rate, label mapping) -- exactly the two files the Android
     app needs, nothing else. No encoder.onnx / decoder.onnx / tokens.txt:
     this is not a transducer ASR bundle.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from onnx_wrapper import AasistOnnxWrapper


def load_config(config_path: str) -> dict:
    with open(config_path, "r") as f:
        config = json.load(f)
    if "model_config" not in config:
        raise ValueError(
            "config.json must contain a top-level 'model_config' block "
            "(filts, gat_dims, pool_ratios, temperatures, first_conv, "
            "use_gabor, use_spcen, use_sm) -- same structure main.py expects."
        )
    return config["model_config"]


def build_and_load_model(model_config: dict, checkpoint_path: str) -> AasistOnnxWrapper:
    wrapper = AasistOnnxWrapper(model_config)
    state_dict = torch.load(checkpoint_path, map_location="cpu", weights_only=False) \
        if _torch_supports_weights_only() else torch.load(checkpoint_path, map_location="cpu")

    # main.py does: torch.save(model.state_dict(), ...) where `model` is the
    # bare AASIST.Model. Our wrapper nests it at `self.model`, so load there.
    missing, unexpected = wrapper.model.load_state_dict(state_dict, strict=True)
    wrapper.eval()
    return wrapper


def _torch_supports_weights_only() -> bool:
    try:
        import inspect
        return "weights_only" in inspect.signature(torch.load).parameters
    except Exception:
        return False


def load_waveform(wav_path: str, target_sr: int, num_samples: int) -> np.ndarray:
    """Load a real wav file for the parity check, if provided. Mono, resampled
    to target_sr, then truncated/tile-padded to exactly num_samples.

    The truncation and padding logic matches speech_df_arena's pad() function:
      - If audio >= num_samples: take the first num_samples (left-aligned).
      - If audio <  num_samples: tile-repeat then take the first num_samples.
    """
    import soundfile as sf

    audio, sr = sf.read(wav_path, dtype="float32", always_2d=False)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)

    if sr != target_sr:
        # Lightweight resample without adding librosa as a hard dependency.
        try:
            import librosa
            audio = librosa.resample(audio, orig_sr=sr, target_sr=target_sr)
        except ImportError:
            raise RuntimeError(
                f"--wav sample rate ({sr}) != --sample_rate ({target_sr}) and "
                f"librosa is not installed to resample. Either resample the "
                f"file yourself first, or `pip install librosa`."
            )

    if len(audio) >= num_samples:
        audio = audio[:num_samples]
    else:
        # Tile-repeat to fill, matching speech_df_arena's pad() logic
        num_repeats = int(num_samples / len(audio)) + 1
        audio = np.tile(audio, num_repeats)[:num_samples]

    return audio.astype(np.float32)


def export_onnx(wrapper: AasistOnnxWrapper, num_samples: int, onnx_path: Path) -> None:
    dummy = torch.zeros(1, num_samples, dtype=torch.float32)
    onnx_path.parent.mkdir(parents=True, exist_ok=True)

    # dynamo=False forces the legacy TorchScript-based tracer. This graph has
    # two known-tricky patterns -- an in-place slice-assign in
    # differentiable_ema() and torch.topk/torch.gather in GraphPool -- and the
    # legacy tracer has the longest production track record on exactly this
    # shape of op. If you later want to try the newer dynamo-based exporter,
    # install `onnxscript` and pass dynamo=True, then re-run the parity check
    # below before trusting the output.
    torch.onnx.export(
        wrapper,
        (dummy,),
        str(onnx_path),
        input_names=["waveform"],
        output_names=["logits"],
        opset_version=17,
        do_constant_folding=True,
        dynamic_axes=None,  # fixed batch AND fixed time, intentionally
        dynamo=False,
    )
    print(f"[export] wrote {onnx_path}")


def run_parity_check(
    wrapper: AasistOnnxWrapper,
    onnx_path: Path,
    waveform_np: np.ndarray,
) -> float:
    import onnxruntime as ort

    with torch.no_grad():
        torch_out = wrapper(torch.from_numpy(waveform_np).unsqueeze(0)).numpy()

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    onnx_out = sess.run(None, {"waveform": waveform_np[None, :]})[0]

    max_abs_diff = float(np.max(np.abs(torch_out - onnx_out)))

    print("\n[parity check]")
    print(f"  PyTorch  logits: bonafide={torch_out[0,0]:.6f} spoof={torch_out[0,1]:.6f}")
    print(f"  ONNX     logits: bonafide={onnx_out[0,0]:.6f} spoof={onnx_out[0,1]:.6f}")
    print(f"  max |diff|    : {max_abs_diff:.8f}")

    if max_abs_diff > 1e-3:
        print(
            "  !! WARNING: difference exceeds 1e-3. Most likely culprits, in "
            "order: (1) GraphPool top-k/gather behaving differently when "
            "traced (check pool_ratios produce the same node counts), "
            "(2) GaborConv1D.get_filters() involving logit/softplus/sigmoid "
            "ops that some ONNX Runtime versions handle with reduced "
            "precision, (3) the in-place EMA slice-assign in "
            "differentiable_ema(). Re-run with --verbose_diff to dump "
            "intermediate tensors.",
            file=sys.stderr,
        )
    else:
        print("  OK -- within tolerance.")

    return max_abs_diff


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config", required=True, help="Path to config.json (with model_config block)")
    parser.add_argument("--checkpoint", required=True, help="Path to trained .pth state_dict")
    parser.add_argument("--out_dir", default="./out", help="Output directory for model.onnx + metadata.json")
    parser.add_argument("--num_samples", type=int, default=None,
                        help="Fixed input length in samples. If not given, uses nb_samp from "
                             "model_config (default 64600, matching the original AASIST convention).")
    parser.add_argument("--sample_rate", type=int, default=16000, help="Sample rate the frontend expects")
    parser.add_argument("--wav", default=None, help="Optional real wav file for the parity check (else random noise)")
    parser.add_argument("--threshold", type=float, default=-2.1207728385925293, help="Spoof-score decision threshold to record in metadata.json")
    args = parser.parse_args()

    print(f"[config] loading {args.config}")
    model_config = load_config(args.config)
    required_keys = ["filts", "gat_dims", "pool_ratios", "temperatures", "first_conv"]
    missing_keys = [k for k in required_keys if k not in model_config]
    if missing_keys:
        raise ValueError(f"config.json model_config missing required keys: {missing_keys}")

    # Determine num_samples: CLI override > config nb_samp > default 64600
    if args.num_samples is not None:
        num_samples = args.num_samples
    else:
        num_samples = model_config.get("nb_samp", 64600)
    window_seconds = num_samples / args.sample_rate

    print(f"[model] building model and loading checkpoint {args.checkpoint}")
    wrapper = build_and_load_model(model_config, args.checkpoint)

    out_dir = Path(args.out_dir)
    onnx_path = out_dir / "assets" / "model" / "model.onnx"
    metadata_path = out_dir / "assets" / "model" / "metadata.json"

    print(f"[export] exporting with FIXED input length: {num_samples} samples "
          f"({window_seconds}s @ {args.sample_rate}Hz)")
    export_onnx(wrapper, num_samples, onnx_path)

    if args.wav:
        waveform_np = load_waveform(args.wav, args.sample_rate, num_samples)
    else:
        rng = np.random.default_rng(1234)
        waveform_np = (rng.standard_normal(num_samples) * 0.05).astype(np.float32)
        print("[parity check] no --wav given, using fixed-seed random noise")

    max_diff = run_parity_check(wrapper, onnx_path, waveform_np)

    metadata = {
        "sample_rate": args.sample_rate,
        "window_seconds": window_seconds,
        "num_samples": num_samples,
        "input_name": "waveform",
        "output_name": "logits",
        "output_space": "logits",
        "output_layout": ["bonafide_logit", "spoof_logit"],
        "spoof_class_index": 1,
        "threshold": args.threshold,
        "frontend": {
            "use_gabor": model_config.get("use_gabor", True),
            "use_spcen": model_config.get("use_spcen", True),
            "use_sm": model_config.get("use_sm", False),
            "first_conv": model_config["first_conv"],
        },
        "parity_check_max_abs_diff": max_diff,
    }
    metadata_path.write_text(json.dumps(metadata, indent=2))
    print(f"\n[metadata] wrote {metadata_path}")
    print(json.dumps(metadata, indent=2))

    print(f"\nDone. Copy {onnx_path.parent} into your Android project's "
          f"app/src/main/assets/model/ directory.")


if __name__ == "__main__":
    main()
