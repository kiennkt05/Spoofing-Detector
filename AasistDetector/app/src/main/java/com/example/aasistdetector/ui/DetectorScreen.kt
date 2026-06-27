package com.example.aasistdetector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.isActive
import com.example.aasistdetector.detector.DetectionMode
import com.example.aasistdetector.detector.DetectorUiState
import com.example.aasistdetector.detector.PermissionState
import com.example.aasistdetector.detector.SpoofLabel

/**
 * Detector screen: mic permission state -> recording state -> spoof score ->
 * live/spoof decision. There is no transcript view anywhere here, because
 * the model is a 2-class spoof classifier, not an ASR model -- the
 * evaluation pipeline (main.py) reads class index 1 as "the score", and
 * that's what's surfaced to the user, not text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectorScreen(
    uiState: DetectorUiState,
    onRequestPermission: () -> Unit,
    onToggleDetection: () -> Unit,
    onSwitchMode: (DetectionMode) -> Unit,
    onToggleReplay: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spoof Detector") },
                navigationIcon = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Continuous Detection") },
                            onClick = {
                                onSwitchMode(DetectionMode.CONTINUOUS)
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Single-Shot Detection") },
                            onClick = {
                                onSwitchMode(DetectionMode.SINGLE_SHOT)
                                menuExpanded = false
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (uiState.permissionState) {
                PermissionState.UNKNOWN -> {
                    Text("Microphone access is needed to run detection.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermission) {
                        Text("Grant microphone permission")
                    }
                }

                PermissionState.DENIED -> {
                    Text("Microphone permission was denied.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermission) {
                        Text("Try again")
                    }
                }

                PermissionState.PERMANENTLY_DENIED -> {
                    Text(
                        "Microphone permission is permanently denied. " +
                            "Enable it from system Settings to use the detector."
                    )
                }

                PermissionState.GRANTED -> {
                    DetectionPanel(
                        uiState = uiState, 
                        onToggleDetection = onToggleDetection,
                        onToggleReplay = onToggleReplay
                    )
                }
            }

            uiState.errorMessage?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(text = message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DetectionPanel(
    uiState: DetectorUiState,
    onToggleDetection: () -> Unit,
    onToggleReplay: () -> Unit
) {
    val result = uiState.lastResult

    var progress by remember { mutableFloatStateOf(0f) }
    var showScores by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isRecording) {
        if (uiState.isRecording) {
            if (uiState.mode == DetectionMode.CONTINUOUS) {
                while (isActive) {
                    progress = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
                    ) { value, _ ->
                        progress = value
                    }
                }
            } else {
                progress = 0f
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 4000, easing = LinearEasing)
                ) { value, _ ->
                    progress = value
                }
            }
        } else {
            progress = 0f
        }
    }

    Text(
        text = if (uiState.mode == DetectionMode.CONTINUOUS) "Continuous Mode" else "Single-Shot Mode",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(24.dp))

    Box(
        modifier = Modifier.size(180.dp),
        contentAlignment = Alignment.Center
    ) {
        if (uiState.isRecording || uiState.isPlaying) {
            CircularProgressIndicator(
                progress = if (uiState.isPlaying) 1f else progress, // Indeterminate during replay could be used, or just full ring
                modifier = Modifier.fillMaxSize(),
                color = if (uiState.isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )
        }

        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(80.dp))
                .background(
                    when (result?.label) {
                        SpoofLabel.LIVE -> MaterialTheme.colorScheme.primaryContainer
                        SpoofLabel.SPOOF -> MaterialTheme.colorScheme.errorContainer
                        null -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when (result?.label) {
                    SpoofLabel.LIVE -> "LIVE"
                    SpoofLabel.SPOOF -> "SPOOF"
                    null -> if (uiState.isRecording) "Listening…" else "Idle"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(Modifier.height(24.dp))

    if (result != null) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showScores = !showScores }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (showScores) "Hide Scores" else "Show Scores", fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (showScores) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
        
        if (showScores) {
            Spacer(Modifier.height(8.dp))
            Text(text = "Bonafide logit: ${"%.3f".format(result.bonafideLogit)}", fontSize = 14.sp)
            Text(text = "Spoof logit: ${"%.3f".format(result.spoofLogit)}", fontSize = 14.sp)
        }
        
        Spacer(Modifier.height(24.dp))
    }

    Button(onClick = onToggleDetection, enabled = !uiState.isPlaying) {
        Text(
            if (uiState.isRecording) "Stop detection" 
            else if (uiState.mode == DetectionMode.SINGLE_SHOT) "Record (4s)"
            else "Start detection"
        )
    }

    if (uiState.mode == DetectionMode.SINGLE_SHOT && uiState.recordedAudio != null) {
        Spacer(Modifier.height(16.dp))
        Button(onClick = onToggleReplay, enabled = !uiState.isRecording) {
            Text(if (uiState.isPlaying) "Stop Replay" else "Replay Recording")
        }
    }
}
