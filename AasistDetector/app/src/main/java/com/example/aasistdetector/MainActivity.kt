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

    private val PREFERRED_DEFAULT_MODEL = "model"

    private val viewModel: AasistDetectorViewModel by viewModels {
        AasistDetectorViewModelFactory(assets, PREFERRED_DEFAULT_MODEL)
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
                        onToggleReplay = { viewModel.toggleReplay() },
                        onSwitchModel = { viewModel.switchModel(it) }
                    )
                }
            }
        }

        checkOrRequestPermission()
    }

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
