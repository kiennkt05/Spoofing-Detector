plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.aasistdetector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aasistdetector"
        // Sherpa-ONNX's own build script defaults to android-21, but we are
        // not using Sherpa-ONNX here (see README) -- ONNX Runtime Android's
        // AAR supports minSdk 24+. 26 keeps us comfortably inside that and
        // matches "fine if your app already requires it" from the plan.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Ship only the ABIs you actually need. arm64-v8a is the practical
        // first choice for modern phones (this mirrors the plan's Phase 1.1
        // and Phase 5 guidance, just applied to the ORT AAR's native libs
        // instead of Sherpa-ONNX's).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // ONNX Runtime's AAR bundles its own libc++_shared.so. pickFirst
        // avoids a duplicate-.so build failure if any other dependency also
        // ships one -- same reasoning as the plan's Phase 1.3 guidance,
        // just kept here in case you later add another native dependency.
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    // model.onnx lives under assets/model/ -- do not compress it, since
    // ONNX Runtime mmaps/reads it directly and compression only costs CPU
    // and APK-install time for no benefit on a binary blob that's already
    // float32-dense.
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    // Plain ONNX Runtime Android, NOT Sherpa-ONNX. This app is a custom
    // 2-class AASIST spoof classifier, not an ASR/TTS/VAD/speaker-ID
    // pipeline -- Sherpa-ONNX's JNI surface (OnlineRecognizer, tokens.txt,
    // transducer encoder/decoder/joiner) has no role here. ONNX Runtime's
    // own Android AAR is the correct, minimal runtime layer: it gives us
    // InferenceSession/OrtEnvironment directly with no ASR-specific API
    // shaped around the wrong problem.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
