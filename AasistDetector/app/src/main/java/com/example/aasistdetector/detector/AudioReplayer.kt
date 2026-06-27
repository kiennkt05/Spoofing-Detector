package com.example.aasistdetector.detector

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AudioReplayer {
    private var currentTrack: AudioTrack? = null

    fun play(waveform: FloatArray, sampleRate: Int, onComplete: () -> Unit = {}) {
        stop()

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(waveform.size * 4) // 4 bytes per float
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(waveform, 0, waveform.size, AudioTrack.WRITE_BLOCKING)
        
        audioTrack.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) {
                track.release()
                if (currentTrack == track) {
                    currentTrack = null
                    onComplete()
                }
            }

            override fun onPeriodicNotification(track: AudioTrack) {}
        })
        audioTrack.notificationMarkerPosition = waveform.size

        audioTrack.play()
        currentTrack = audioTrack
    }

    fun stop() {
        currentTrack?.let {
            if (it.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    it.stop()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            it.release()
        }
        currentTrack = null
    }
}
