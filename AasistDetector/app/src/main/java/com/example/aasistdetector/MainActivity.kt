package com.example.aasistdetector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.aasistdetector.detector.AasistDetectorViewModel
import com.example.aasistdetector.detector.AasistDetectorViewModelFactory
import com.example.aasistdetector.ui.DetectorScreen

class MainActivity : ComponentActivity() {

    // Created eagerly via the activity-scoped `by viewModels` delegate so it
    // exists before onCreate's permission check runs -- assigning it lazily
    // inside the setContent {} composable lambda would race with
    // checkOrRequestPermission() being called right after setContent.
    private val viewModel: AasistDetectorViewModel by viewModels {
        AasistDetectorViewModelFactory(assets)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val canRequestAgain = granted || ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.RECORD_AUDIO
        )
        viewModel.onPermissionResult(granted, canRequestAgain)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            MaterialTheme {
                Surface(modifier = Modifier) {
                    DetectorScreen(
                        uiState = uiState,
                        onRequestPermission = { checkOrRequestPermission() },
                        onToggleDetection = { viewModel.toggleDetection() },
                        onSwitchMode = { viewModel.switchMode(it) },
                        onToggleReplay = { viewModel.toggleReplay() }
                    )
                }
            }
        }

        checkOrRequestPermission()
    }

    // NOTE: AasistDetectorViewModel's constructor eagerly loads model.onnx +
    // metadata.json from assets via AasistOnnxDetector's init block. If those
    // files are missing (see assets/model/README.txt), the `by viewModels`
    // delegate above will throw when first accessed and the activity will
    // crash on launch with a clear stack trace pointing at the missing
    // asset path -- this is intentional fail-fast behavior for a build-time
    // packaging contract, not something to silently recover from at runtime.

    private fun checkOrRequestPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.onPermissionResult(granted = true, canRequestAgain = true)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
