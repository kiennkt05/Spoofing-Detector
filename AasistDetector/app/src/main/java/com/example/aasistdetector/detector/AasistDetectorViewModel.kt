package com.example.aasistdetector.detector

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

enum class PermissionState { UNKNOWN, GRANTED, DENIED, PERMANENTLY_DENIED }
enum class DetectionMode { CONTINUOUS, SINGLE_SHOT }

data class DetectorUiState(
    val permissionState: PermissionState = PermissionState.UNKNOWN,
    val mode: DetectionMode = DetectionMode.CONTINUOUS,
    val isRecording: Boolean = false,
    val lastResult: DetectionResult? = null,
    val recordedAudio: FloatArray? = null,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Detector ViewModel, replacing the transcription-style SpeechViewModel
 * pattern. The key behavioral difference from an ASR ViewModel: there is no
 * acceptWaveform() -> decode() -> getResult() streaming loop and no partial
 * transcript state, because AASIST.Model does utterance-level classification
 * over a fixed window. Each completed window produces one spoof score and
 * one label -- nothing is appended or refined across windows.
 */
class AasistDetectorViewModel(assetManager: AssetManager) : ViewModel() {

    private val detector = AasistOnnxDetector(assetManager)
    private val micSource = MicWindowSource(
        sampleRate = detector.metadata.sampleRate,
        windowSamples = detector.metadata.numSamples
    )
    private val audioReplayer = AudioReplayer()

    private val _uiState = MutableStateFlow(DetectorUiState())
    val uiState: StateFlow<DetectorUiState> = _uiState.asStateFlow()

    private var recordingJob: Job? = null

    fun onPermissionResult(granted: Boolean, canRequestAgain: Boolean) {
        _uiState.value = _uiState.value.copy(
            permissionState = when {
                granted -> PermissionState.GRANTED
                canRequestAgain -> PermissionState.DENIED
                else -> PermissionState.PERMANENTLY_DENIED
            }
        )
    }

    fun toggleDetection() {
        if (_uiState.value.isRecording) stopDetection() else startDetection()
    }

    fun switchMode(newMode: DetectionMode) {
        if (_uiState.value.mode == newMode) return
        stopDetection()
        audioReplayer.stop()
        _uiState.value = _uiState.value.copy(
            mode = newMode,
            lastResult = null,
            recordedAudio = null,
            isPlaying = false,
            errorMessage = null
        )
    }

    fun toggleReplay() {
        val audio = _uiState.value.recordedAudio ?: return
        if (_uiState.value.isPlaying) {
            audioReplayer.stop()
            _uiState.value = _uiState.value.copy(isPlaying = false)
        } else {
            _uiState.value = _uiState.value.copy(isPlaying = true)
            audioReplayer.play(audio, detector.metadata.sampleRate) {
                _uiState.value = _uiState.value.copy(isPlaying = false)
            }
        }
    }

    private fun startDetection() {
        if (_uiState.value.permissionState != PermissionState.GRANTED) return
        if (recordingJob?.isActive == true) return

        _uiState.value = _uiState.value.copy(isRecording = true, errorMessage = null, isPlaying = false)
        audioReplayer.stop()

        val isSingleShot = _uiState.value.mode == DetectionMode.SINGLE_SHOT
        var flow = micSource.windows()
        if (isSingleShot) {
            flow = flow.take(1)
        }

        recordingJob = flow
            .map { window ->
                val result = detector.runInference(window)
                Pair(window, result)
            }
            .flowOn(Dispatchers.IO)
            .onEach { (window, result) ->
                _uiState.value = _uiState.value.copy(lastResult = result, recordedAudio = window)
                if (isSingleShot) {
                    _uiState.value = _uiState.value.copy(isRecording = false)
                }
            }
            .catch { e ->
                _uiState.value = _uiState.value.copy(
                    isRecording = false,
                    errorMessage = e.message ?: "Microphone or inference error"
                )
            }
            .launchIn(viewModelScope)
    }

    private fun stopDetection() {
        recordingJob?.cancel()
        recordingJob = null
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    override fun onCleared() {
        stopDetection()
        audioReplayer.stop()
        detector.close()
        super.onCleared()
    }
}
