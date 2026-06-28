package com.example.aasistdetector.detector

import org.json.JSONObject

/**
 * Mirrors the metadata.json written by export_to_onnx.py. Loading this at
 * runtime instead of hardcoding window length / sample rate / threshold in
 * Kotlin means a re-export with a different window size or threshold doesn't
 * require an app code change -- only re-copying the two asset files.
 * Note: threshold is explicit to be a logit, not a probability.
 */
data class DetectorMetadata(
    val sampleRate: Int,
    val windowSeconds: Double,
    val numSamples: Int,
    val inputName: String,
    val outputName: String,
    val outputSpace: String,
    val spoofClassIndex: Int,
    val threshold: Float
) {
    companion object {
        fun fromJson(json: String): DetectorMetadata {
            val obj = JSONObject(json)
            return DetectorMetadata(
                sampleRate = obj.getInt("sample_rate"),
                windowSeconds = obj.getDouble("window_seconds"),
                numSamples = obj.getInt("num_samples"),
                inputName = obj.getString("input_name"),
                outputName = obj.getString("output_name"),
                outputSpace = obj.getString("output_space"),
                // Note: The python export script uses "spoof_class_index" to refer to the index of the *bonafide* logit (index 1).
                spoofClassIndex = obj.getInt("spoof_class_index"),
                threshold = obj.getDouble("threshold").toFloat()
            )
        }
    }
}
