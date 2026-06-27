package com.example.aasistdetector.detector

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive

/**
 * Captures mono, 16kHz, PCM16 audio from the device microphone and emits
 * fixed-length float32 windows in [-1, 1] -- exactly the input shape
 * AasistOnnxDetector.runInference expects.
 *
 * This mirrors the frontend's sample_rate=16000 default used by both
 * GaborConv1D and the SincConv (CONV) frontend in the original model, so no
 * resampling is needed between capture and inference.
 *
 * Buffering strategy: the model is utterance-level, not streaming, so we
 * accumulate exactly `windowSamples` PCM16 samples, convert once, emit one
 * window, then start the next window from scratch (non-overlapping). This
 * keeps the mobile loop simple and matches how the model was trained --
 * evaluated on complete, independent utterances.
 */
class MicWindowSource(
    private val sampleRate: Int,
    private val windowSamples: Int
) {
    private val minBufferSize: Int = run {
        val size = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // getMinBufferSize returns ERROR_BAD_VALUE (-2) or ERROR (-1) when the
        // (sampleRate, channel, encoding) combination isn't supported on this
        // device. Falling through with a negative size would otherwise only
        // be incidentally harmless wherever windowSamples*2 happens to be
        // larger -- fail explicitly here instead so a genuinely unsupported
        // device shows a clear error rather than a silently-wrong buffer.
        require(size > 0) {
            "AudioRecord.getMinBufferSize returned $size for " +
                "sampleRate=$sampleRate -- this sample rate / config is not " +
                "supported for audio capture on this device"
        }
        size
    }

    @SuppressLint("MissingPermission") // caller must have checked RECORD_AUDIO already
    fun windows(): Flow<FloatArray> = callbackFlow {
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferSize, windowSamples * 2 /* bytes per PCM16 sample */)
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            close(IllegalStateException("AudioRecord failed to initialize"))
            return@callbackFlow
        }

        val pcmChunk = ShortArray(minBufferSize.coerceAtLeast(1024))
        val windowBuffer = ShortArray(windowSamples)
        var writeIndex = 0

        audioRecord.startRecording()

        try {
            while (isActive) {
                val read = audioRecord.read(pcmChunk, 0, pcmChunk.size)
                if (read <= 0) continue

                var chunkOffset = 0
                while (chunkOffset < read) {
                    val toCopy = minOf(read - chunkOffset, windowSamples - writeIndex)
                    System.arraycopy(pcmChunk, chunkOffset, windowBuffer, writeIndex, toCopy)
                    writeIndex += toCopy
                    chunkOffset += toCopy

                    if (writeIndex == windowSamples) {
                        val floatWindow = FloatArray(windowSamples) { i ->
                            // PCM16 range is [-32768, 32767]; normalize to [-1, 1].
                            windowBuffer[i] / 32768f
                        }
                        trySend(floatWindow)
                        writeIndex = 0
                    }
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }

        awaitClose {
            // handled in finally above; nothing additional to release here
        }
    }
}
