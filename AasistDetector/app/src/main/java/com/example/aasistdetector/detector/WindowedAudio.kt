package com.example.aasistdetector.detector

/**
 * Represents the outcome of silence classification on one fixed-length
 * audio window from MicWindowSource.
 *
 * Speech  — the window passed the energy threshold; samples should be
 *           forwarded to AasistOnnxDetector.runInference().
 * Silence — the window was below the energy threshold; inference should
 *           be skipped entirely and the UI should show "No speech detected".
 */
sealed class WindowedAudio {
    data class Speech(val samples: FloatArray) : WindowedAudio() {
        // FloatArray doesn't implement structural equals/hashCode, so
        // data class defaults are wrong for it. Override here so that
        // two Speech instances with the same samples compare equal in
        // tests. In production this rarely matters but it prevents
        // subtle bugs if WindowedAudio ever ends up in a set or map.
        override fun equals(other: Any?): Boolean =
            other is Speech && samples.contentEquals(other.samples)
        override fun hashCode(): Int = samples.contentHashCode()
    }
    object Silence : WindowedAudio()
}
