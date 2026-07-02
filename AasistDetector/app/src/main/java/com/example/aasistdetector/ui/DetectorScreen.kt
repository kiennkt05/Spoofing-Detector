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
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetectorScreen(
    uiState: DetectorUiState,
    onRequestPermission: () -> Unit,
    onToggleDetection: () -> Unit,
    onSwitchMode: (DetectionMode) -> Unit,
    onToggleReplay: () -> Unit,
    onSwitchModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 })

    LaunchedEffect(pagerState.currentPage) {
        val newMode = if (pagerState.currentPage == 0) DetectionMode.CONTINUOUS_AVERAGING else DetectionMode.CONTINUOUS_REALTIME
        onSwitchMode(newMode)
    }

    LaunchedEffect(uiState.mode) {
        val targetPage = if (uiState.mode == DetectionMode.CONTINUOUS_AVERAGING) 0 else 1
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spoof Detector") }
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
                    Text(
                        text = "Swipe left/right to switch modes",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            DetectionPanel(
                                uiState = uiState, 
                                pageMode = if (page == 0) DetectionMode.CONTINUOUS_AVERAGING else DetectionMode.CONTINUOUS_REALTIME,
                                onToggleDetection = onToggleDetection,
                                onToggleReplay = onToggleReplay,
                                onSwitchModel = onSwitchModel
                            )
                        }
                    }
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
    pageMode: DetectionMode,
    onToggleDetection: () -> Unit,
    onToggleReplay: () -> Unit,
    onSwitchModel: (String) -> Unit
) {
    val result = uiState.lastResult

    var progress by remember { mutableFloatStateOf(0f) }
    var showScores by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isRecording) {
        if (uiState.isRecording) {
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
        }
    }

    Text(
        text = if (pageMode == DetectionMode.CONTINUOUS_AVERAGING) "Continuous (Averaging)" else "Continuous (Realtime)",
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
                    when {
                        uiState.isSilent -> MaterialTheme.colorScheme.surfaceVariant
                        result?.label == SpoofLabel.LIVE -> MaterialTheme.colorScheme.primaryContainer
                        result?.label == SpoofLabel.SPOOF -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    uiState.isSilent -> "SILENCE"
                    result?.label == SpoofLabel.LIVE -> "LIVE"
                    result?.label == SpoofLabel.SPOOF -> "SPOOF"
                    uiState.isRecording -> "Listening…"
                    else -> "Idle"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(Modifier.height(24.dp))

    if (result != null && !uiState.isSilent) {
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
    } else if (uiState.isSilent) {
        Text(text = "No speech detected", fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Button(onClick = onToggleDetection, enabled = !uiState.isPlaying) {
            Text(
                if (uiState.isRecording) "Stop detection" 
                else "Start detection"
            )
        }

        if (uiState.availableModels.isNotEmpty()) {
            Spacer(Modifier.width(16.dp))
            var modelMenuExpanded by remember { mutableStateOf(false) }
            Box {
                Button(
                    onClick = { modelMenuExpanded = true },
                    enabled = !uiState.isRecording && !uiState.isPlaying
                ) {
                    Text(uiState.selectedModel ?: "Select Model")
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    uiState.availableModels.forEach { modelName ->
                        DropdownMenuItem(
                            text = { Text(modelName) },
                            onClick = {
                                onSwitchModel(modelName)
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (uiState.recordedAudio != null) {
        Spacer(Modifier.height(16.dp))
        Button(onClick = onToggleReplay, enabled = !uiState.isRecording) {
            Text(if (uiState.isPlaying) "Stop Replay" else "Replay Recording")
        }
    }
}
