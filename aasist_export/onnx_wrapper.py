"""
ONNX export wrapper for the AASIST spoof-detection model.

Why a wrapper, and why fixed length:
- Model.forward() does x.unsqueeze(1) -> DynamicFrontend -> encoder -> two GAT
  branches -> GraphPool (torch.topk + torch.gather) -> HtrgGraphAttentionLayer
  x2 -> out_layer(Linear(5*gat_dims[1], 2)).
- GraphPool.top_k_graph computes `n_nodes = max(int(n_nodes * k), 1)` from the
  *Python int* h.size(1). If the time/freq axis is dynamic at trace time, this
  k is still resolved as a constant baked from whatever shape was traced, but
  the safe and correct thing for export is to trace with the EXACT input
  length you will feed at inference time, every time. We do not support
  variable-length input in the exported graph: mobile inference should always
  buffer audio into the same fixed window (see Android AudioRecord loop).
- leaf_frontend.differentiable_ema() also derives K = min(T, 1024) as a Python
  int from x.shape, and builds the EMA kernel and causal pad from K. Same
  constraint: T must be fixed and known at export time.
- The in-place `out[:, :, :K] += ...` slice-assign on the conv1d output is the
  second known watch point (alongside GraphPool) for export correctness;
  verify it survives export by means of the parity check, not by inspection.

This wrapper:
  1. Accepts a raw mono float32 waveform of FIXED length (B, T).
  2. Runs Model.forward(x) which internally returns (last_hidden, output).
       - spoof_score: logit on class index 1 (matches main.py's
         produce_evaluation_file, which reads batch_out[:, 1] as the score)
       - bonafide_score: logit on class index 0
"""
import torch
import torch.nn as nn

from models.AASIST import Model


class AasistOnnxWrapper(nn.Module):
    def __init__(self, d_args: dict):
        super().__init__()
        self.model = Model(d_args)

    @torch.no_grad()
    def forward(self, waveform: torch.Tensor) -> torch.Tensor:
        """
        waveform: (B, T) float32, mono, 16kHz, values in [-1, 1]
        returns:  (B, 2) float32 -> [bonafide_logit, spoof_logit], raw logits
        """
        _, logits = self.model(waveform)
        return logits
