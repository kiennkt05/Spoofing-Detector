package com.example.aasistdetector.detector

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.sqrt

/**
 * Classifies fixed-length float32 audio windows as speech or silence
 * using RMS (root mean square) energy, then wraps a Flow<FloatArray>
 * into a Flow<WindowedAudio> that the ViewModel can act on.
 *
 * Why RMS:
 * RMS is the standard measure of signal power and maps directly to
 * perceived loudness. A window of pure digital silence has RMS = 0.0.
 * Ambient room noise on a phone mic typically has RMS 0.002–0.008.
 * Conversational speech at a normal distance sits between 0.02 and 0.15.
 * A threshold of 0.01 sits cleanly between those two distributions and
 * is the recommended starting point.
 *
 * Why not zero-crossing rate:
 * ZCR is useful for distinguishing voiced from unvoiced speech, but
 * it behaves erratically on a hardware noise floor -- a silent mic
 * still crosses zero constantly due to thermal noise, so ZCR cannot
 * reliably detect silence.
 *
 * Why not a VAD model (WebRTC VAD, Silero):
 * Either would require an extra dependency or an extra ONNX model in
 * assets. RMS is sufficient when the goal is simply skipping windows
 * that contain no useful signal, rather than detecting fine-grained
 * voiced/unvoiced boundaries.
 *
 * @param rmsThreshold  Windows with RMS below this value are classified
 *                      as silence. Units are normalised float amplitude
 *                      in [0, 1]. Default 0.01 (1% of full scale).
 * @param holdWindowCount  Number of consecutive speech windows required
 *                         before inference resumes after a silence gap.
 *                         1 means inference starts on the very first
 *                         window that clears the threshold. Increase to
 *                         2 or 3 if spurious noise spikes cause
 *                         unwanted inference triggers.
 */
class SilenceDetector(
    var rmsThreshold: Float = 0.01f,
    val holdWindowCount: Int = 1
) {

    /**
     * Returns true if the window's RMS energy is below rmsThreshold.
     *
     * The sum-of-squares accumulates in Double to avoid float precision
     * loss over 64600 samples. (64600 * 1.0^2 = 64600, which exceeds
     * Float.MAX_VALUE is not a concern, but repeated addition of small
     * squared values in Float loses mantissa bits -- Double is safer
     * and the cast to Float only happens on the final result.)
     */
    fun calculateRmsAndIsSilent(window: FloatArray): Pair<Float, Boolean> {
        var sumSq = 0.0
        for (sample in window) {
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq / window.size).toFloat()
        return Pair(rms, rms < rmsThreshold)
    }

    /**
     * Wraps a Flow<FloatArray> and classifies each window.
     *
     * Silence windows are passed through immediately as WindowedAudio.Silence
     * so the ViewModel can update isSilent = true in the UI.
     *
     * Speech windows go through a holdWindowCount warm-up counter: after
     * a silence gap, the first (holdWindowCount - 1) speech windows are
     * still emitted as WindowedAudio.Silence. Only once holdWindowCount
     * consecutive speech windows have been seen is the current window
     * emitted as WindowedAudio.Speech and inference triggered.
     *
     * With holdWindowCount = 1 (the default), every speech window is
     * forwarded immediately -- there is no warm-up delay.
     *
     * This is implemented as a plain .map() rather than a stateful
     * operator, using a captured counter, because the state is trivial
     * (one Int) and doesn't need the full machinery of a custom
     * FlowCollector. The counter is safe here because Flow is sequential
     * -- only one window is in flight through .map() at a time.
     */
    fun filter(upstream: Flow<FloatArray>): Flow<WindowedAudio> {
        var consecutiveSpeech = 0
        return upstream.map { window ->
            val (rms, isSilent) = calculateRmsAndIsSilent(window)
            if (isSilent) {
                consecutiveSpeech = 0
                WindowedAudio.Silence(rms)
            } else {
                consecutiveSpeech++
                if (consecutiveSpeech >= holdWindowCount) {
                    WindowedAudio.Speech(window, rms)
                } else {
                    // Still in the warm-up period after a silence gap.
                    // Emit Silence so the UI stays in the "no speech" state
                    // rather than flashing a result from a partial window.
                    WindowedAudio.Silence(rms)
                }
            }
        }
    }
}
