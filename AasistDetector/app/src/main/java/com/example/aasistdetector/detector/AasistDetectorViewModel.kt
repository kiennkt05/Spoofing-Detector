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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

enum class PermissionState { UNKNOWN, GRANTED, DENIED, PERMANENTLY_DENIED }
enum class DetectionMode { CONTINUOUS, SINGLE_SHOT }

sealed class InferenceOutcome {
    class Result(val samples: FloatArray, val detection: DetectionResult) : InferenceOutcome()
    object Silent : InferenceOutcome()
}

data class DetectorUiState(
    val permissionState: PermissionState = PermissionState.UNKNOWN,
    val mode: DetectionMode = DetectionMode.CONTINUOUS,
    val isRecording: Boolean = false,
    val isSilent: Boolean = false,
    val lastResult: DetectionResult? = null,
    val recordedAudio: FloatArray? = null,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
    val availableModels: List<String> = emptyList(),
    val selectedModel: String? = null
)

/**
 * Detector ViewModel, replacing the transcription-style SpeechViewModel
 * pattern. The key behavioral difference from an ASR ViewModel: there is no
 * acceptWaveform() -> decode() -> getResult() streaming loop and no partial
 * transcript state, because AASIST.Model does utterance-level classification
 * over a fixed window. Each completed window produces one spoof score and
 * one label -- nothing is appended or refined across windows.
 */
class AasistDetectorViewModel(
    private val assetManager: AssetManager,
    preferredDefaultModel: String
) : ViewModel() {

    private var detector: AasistOnnxDetector? = null
    private var micSource: MicWindowSource? = null
    private val silenceDetector = SilenceDetector(
        rmsThreshold = 0.03f,
        holdWindowCount = 1
    )
    private val audioReplayer = AudioReplayer()

    private val _uiState = MutableStateFlow(DetectorUiState())
    val uiState: StateFlow<DetectorUiState> = _uiState.asStateFlow()

    private var recordingJob: Job? = null

    init {
        val models = discoverModels()
        _uiState.value = _uiState.value.copy(availableModels = models)

        val defaultModel = if (models.contains(preferredDefaultModel)) {
            preferredDefaultModel
        } else {
            models.firstOrNull()
        }

        defaultModel?.let { switchModel(it) }
    }

    private fun discoverModels(): List<String> {
        val models = mutableListOf<String>()
        try {
            val rootItems = assetManager.list("") ?: emptyArray()
            for (item in rootItems) {
                // Check if directory has both model.onnx and metadata.json
                val dirItems = assetManager.list(item) ?: emptyArray()
                if (dirItems.contains("model.onnx") && dirItems.contains("metadata.json")) {
                    models.add(item)
                }
            }
        } catch (e: Exception) {
            // Ignore asset listing errors
        }
        return models
    }

    fun switchModel(modelDir: String) {
        if (_uiState.value.selectedModel == modelDir) return
        stopDetection()
        audioReplayer.stop()

        try {
            detector?.close()
            val newDetector = AasistOnnxDetector(assetManager, modelDir)
            detector = newDetector
            micSource = MicWindowSource(
                sampleRate = newDetector.metadata.sampleRate,
                windowSamples = newDetector.metadata.numSamples
            )
            _uiState.value = _uiState.value.copy(
                selectedModel = modelDir,
                lastResult = null,
                recordedAudio = null,
                isPlaying = false,
                errorMessage = null
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Failed to load model: $modelDir\n${e.message}"
            )
        }
    }

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
        val currentDetector = detector ?: return
        if (_uiState.value.isPlaying) {
            audioReplayer.stop()
            _uiState.value = _uiState.value.copy(isPlaying = false)
        } else {
            _uiState.value = _uiState.value.copy(isPlaying = true)
            audioReplayer.play(audio, currentDetector.metadata.sampleRate) {
                _uiState.value = _uiState.value.copy(isPlaying = false)
            }
        }
    }

    private fun startDetection() {
        if (_uiState.value.permissionState != PermissionState.GRANTED) return
        if (recordingJob?.isActive == true) return

        _uiState.value = _uiState.value.copy(
            isRecording = true, 
            isSilent = false, 
            errorMessage = null, 
            isPlaying = false
        )
        audioReplayer.stop()

        val currentDetector = detector
        val currentMicSource = micSource
        if (currentDetector == null || currentMicSource == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "No model selected")
            return
        }

        val isSingleShot = _uiState.value.mode == DetectionMode.SINGLE_SHOT
        var windowedFlow = silenceDetector.filter(currentMicSource.windows())
        if (isSingleShot) {
            windowedFlow = windowedFlow.filterIsInstance<WindowedAudio.Speech>().take(1)
        }

        recordingJob = windowedFlow
            .map { windowed ->
                when (windowed) {
                    is WindowedAudio.Speech -> {
                        val result = currentDetector.runInference(windowed.samples)
                        InferenceOutcome.Result(windowed.samples, result)
                    }
                    is WindowedAudio.Silence -> InferenceOutcome.Silent
                }
            }
            .flowOn(Dispatchers.IO)
            .onEach { outcome ->
                when (outcome) {
                    is InferenceOutcome.Silent -> {
                        _uiState.value = _uiState.value.copy(isSilent = true)
                    }
                    is InferenceOutcome.Result -> {
                        _uiState.value = _uiState.value.copy(
                            isSilent = false,
                            lastResult = outcome.detection,
                            recordedAudio = outcome.samples
                        )
                        if (isSingleShot) {
                            _uiState.value = _uiState.value.copy(isRecording = false)
                        }
                    }
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
        detector?.close()
        super.onCleared()
    }
}
