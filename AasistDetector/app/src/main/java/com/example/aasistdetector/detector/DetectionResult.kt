package com.example.aasistdetector.detector

enum class SpoofLabel { LIVE, SPOOF }

/**
 * Result of one inference pass over a fixed-length audio window.
 *
 * spoofLogit mirrors exactly what the training/eval pipeline calls "the
 * score": main.py's produce_evaluation_file() reads batch_out[:, 1] as the
 * spoof score. We no longer apply softmax inside the exported graph,
 * so spoofLogit here is a raw logit.
 */
data class DetectionResult(
    val bonafideLogit: Float,
    val spoofLogit: Float,
    val label: SpoofLabel
)
