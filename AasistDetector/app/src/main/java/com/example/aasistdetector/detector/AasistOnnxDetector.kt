package com.example.aasistdetector.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.nio.FloatBuffer

/**
 * Loads model.onnx + metadata.json from app assets and runs utterance-level
 * spoof-detection inference.
 *
 * This is intentionally NOT built around Sherpa-ONNX's OnlineRecognizer
 * pattern (acceptWaveform -> decode -> getResult), because that API shape is
 * for streaming ASR decoding. AASIST.Model.forward() is utterance-level
 * classification: the whole fixed-length window goes in, one (bonafide,
 * spoof) score pair comes out. There is no streaming decode state to manage.
 *
 * Only two asset files are required -- model.onnx and metadata.json -- no
 * tokens.txt, no encoder/decoder/joiner triplet, because this is not a
 * transducer ASR bundle.
 */
class AasistOnnxDetector(
    assetManager: AssetManager,
    modelAssetPath: String = "model/model.onnx",
    metadataAssetPath: String = "model/metadata.json"
) : AutoCloseable {

    val metadata: DetectorMetadata

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val metadataJson = assetManager.open(metadataAssetPath)
            .bufferedReader()
            .use { it.readText() }
        metadata = DetectorMetadata.fromJson(metadataJson)

        // ORT cannot read directly from the compressed APK asset stream for
        // session creation on all API levels, so the model bytes are loaded
        // fully into memory first. model.onnx is a few MB at most for this
        // architecture, so this is cheap and happens once at app start.
        val modelBytes = assetManager.open(modelAssetPath).use { it.readBytes() }

        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        session = ortEnvironment.createSession(modelBytes, sessionOptions)
    }

    /**
     * Runs inference on exactly metadata.numSamples float32 samples in
     * [-1, 1], mono, at metadata.sampleRate. Callers (the detector
     * ViewModel's audio buffer) are responsible for producing a buffer of
     * exactly this length -- the exported graph has a fixed input shape and
     * will throw on any other length, by design (see export_to_onnx.py's
     * docstring on why the time axis is not dynamic).
     */
    fun runInference(waveform: FloatArray): DetectionResult {
        require(waveform.size == metadata.numSamples) {
            "Expected exactly ${metadata.numSamples} samples " +
                "(${metadata.windowSeconds}s @ ${metadata.sampleRate}Hz), got ${waveform.size}"
        }

        val shape = longArrayOf(1, waveform.size.toLong())
        OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(waveform), shape).use { inputTensor ->
            session.run(mapOf(metadata.inputName to inputTensor)).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val logits = (outputs[0].value as Array<FloatArray>)[0]
                val bonafide = logits[0]
                val spoof = logits[metadata.spoofClassIndex]
                val label = if (spoof >= metadata.threshold) SpoofLabel.LIVE else SpoofLabel.SPOOF
                Log.d("AasistOnnxDetector", "Inference complete: bonafide=$bonafide, spoof=$spoof, label=$label")
                return DetectionResult(bonafide, spoof, label)
            }
        }
    }

    override fun close() {
        session.close()
    }
}
