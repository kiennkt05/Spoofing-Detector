# ONNX Runtime's JNI bridge classes must survive obfuscation -- the native
# (.so) side calls back into specific Java/Kotlin class and method
# signatures by name, so renaming them breaks the JNI bridge at runtime
# with no compile-time warning.
-keep class ai.onnxruntime.** { *; }

# This app's own detector + model classes. Keeping the whole package is
# slightly broader than strictly necessary, but this is a small app and the
# size cost is negligible; it avoids subtle bugs from R8 stripping a field
# that's only read via reflection-like JSON parsing (DetectorMetadata).
-keep class com.example.aasistdetector.** { *; }

# Suppress warnings about optional ONNX Runtime execution-provider classes
# (e.g. NNAPI, CoreML) that aren't present when only the core AAR is used.
-dontwarn ai.onnxruntime.**
