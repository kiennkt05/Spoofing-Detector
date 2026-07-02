package com.example.aasistdetector.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import com.example.aasistdetector.detector.DetectionMode
import com.example.aasistdetector.detector.DetectionResult
import com.example.aasistdetector.detector.DetectorUiState
import com.example.aasistdetector.detector.PermissionState
import com.example.aasistdetector.detector.SpoofLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetectorScreen(
    uiState: DetectorUiState,
    onRequestPermission: () -> Unit,
    onToggleDetection: () -> Unit,
    onSwitchMode: (DetectionMode) -> Unit,
    onToggleReplay: () -> Unit,
    onSwitchModel: (String) -> Unit,
    onSetDecisionThreshold: (Float) -> Unit,
    onSetRmsThreshold: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = if (uiState.mode == DetectionMode.CONTINUOUS_REALTIME) 1 else 0,
        pageCount = { 2 }
    )
    val coroutineScope = rememberCoroutineScope()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spoof detector", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (uiState.permissionState) {
                PermissionState.UNKNOWN -> {
                    Spacer(Modifier.height(48.dp))
                    Text("Microphone access is needed to run detection.", modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermission) {
                        Text("Grant microphone permission")
                    }
                }
                PermissionState.DENIED -> {
                    Spacer(Modifier.height(48.dp))
                    Text("Microphone permission was denied.", modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermission) {
                        Text("Try again")
                    }
                }
                PermissionState.PERMANENTLY_DENIED -> {
                    Spacer(Modifier.height(48.dp))
                    Text(
                        "Microphone permission is permanently denied. Enable it from system Settings to use the detector.",
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                PermissionState.GRANTED -> {
                    TabRow(selectedTabIndex = pagerState.currentPage) {
                        Tab(
                            selected = pagerState.currentPage == 0,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                            text = { Text("Averaging") }
                        )
                        Tab(
                            selected = pagerState.currentPage == 1,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                            text = { Text("Realtime") }
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        DetectionPanel(
                            uiState = uiState,
                            onToggleDetection = onToggleDetection,
                            onToggleReplay = onToggleReplay,
                            onSwitchModel = onSwitchModel
                        )
                    }
                }
            }

            uiState.errorMessage?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(text = message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }

        if (showSettings) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { showSettings = false }
            ) {
                SettingsSheetContent(
                    uiState = uiState,
                    onSetDecisionThreshold = onSetDecisionThreshold,
                    onSetRmsThreshold = onSetRmsThreshold,
                    onSwitchModel = onSwitchModel
                )
            }
        }
    }
}

@Composable
private fun SettingsSheetContent(
    uiState: DetectorUiState,
    onSetDecisionThreshold: (Float) -> Unit,
    onSetRmsThreshold: (Float) -> Unit,
    onSwitchModel: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

        Text("Model Selection", fontWeight = FontWeight.Medium)
        if (uiState.availableModels.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = false,
                    onClick = { expanded = true },
                    label = { Text(uiState.selectedModel ?: "Select Model") },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    uiState.availableModels.forEach { modelName ->
                        DropdownMenuItem(
                            text = { Text(modelName) },
                            onClick = {
                                onSwitchModel(modelName)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        Text("Decision Threshold: ${String.format("%.2f", uiState.threshold)}", fontWeight = FontWeight.Medium)
        androidx.compose.material3.Slider(
            value = uiState.threshold,
            onValueChange = { onSetDecisionThreshold(it) },
            valueRange = -10f..10f
        )

        Text("RMS Threshold: ${String.format("%.3f", uiState.rmsThreshold)}", fontWeight = FontWeight.Medium)
        androidx.compose.material3.Slider(
            value = uiState.rmsThreshold,
            onValueChange = { onSetRmsThreshold(it) },
            valueRange = 0.001f..0.2f
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetectionPanel(
    uiState: DetectorUiState,
    onToggleDetection: () -> Unit,
    onToggleReplay: () -> Unit,
    onSwitchModel: (String) -> Unit
) {
    val result = uiState.lastResult
    val view = LocalView.current

    // Trigger haptics on state change
    val stateId = when {
        uiState.isSilent -> 1
        result?.label == SpoofLabel.LIVE -> 2
        result?.label == SpoofLabel.SPOOF -> 3
        else -> 0
    }
    LaunchedEffect(stateId) {
        if (stateId > 0) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }

    var progress by remember { mutableFloatStateOf(0f) }
    var showScores by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isRecording, uiState.isPlaying) {
        if (uiState.isRecording || uiState.isPlaying) {
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

    val targetColor = when {
        uiState.isSilent -> MaterialTheme.colorScheme.surfaceVariant
        result?.label == SpoofLabel.LIVE -> MaterialTheme.colorScheme.primaryContainer
        result?.label == SpoofLabel.SPOOF -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val animatedColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(300), label = "color_anim")

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(32.dp))

        // Status indicator
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center
        ) {
            // Loading arc
            if (uiState.isRecording || uiState.isPlaying) {
                val pColor = if (uiState.isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.size(168.dp)) {
                    drawArc(
                        color = pColor,
                        startAngle = progress * 360f,
                        sweepAngle = 90f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Main circle
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(80.dp))
                    .background(animatedColor)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        uiState.isSilent -> {
                            Text("Silence", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        }
                        uiState.isRecording && uiState.mode == DetectionMode.CONTINUOUS_AVERAGING -> {
                            Text("Analyzing...", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        }
                        result?.label == SpoofLabel.LIVE -> {
                            Text("Live voice", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                        result?.label == SpoofLabel.SPOOF -> {
                            Text("Spoof voice", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                        }
                        else -> {
                            Text("Idle", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Start/Stop CTA (pill button)
        Button(
            onClick = onToggleDetection,
            enabled = !uiState.isPlaying,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                if (uiState.isRecording) Icons.Default.Clear else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (uiState.isRecording) "Stop detection" else "Start detection",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(16.dp))

        if (uiState.recordedAudio != null) {
            Button(
                onClick = onToggleReplay,
                enabled = !uiState.isRecording,
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                Text(if (uiState.isPlaying) "Stop Replay" else "Replay Recording")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Expandable scores
        if (result != null && !uiState.isSilent) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .clickable { showScores = !showScores },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (showScores) "Hide scores" else "Detection scores", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(
                            imageVector = if (showScores) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(visible = showScores) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            Text(text = "Bonafide logit: ${String.format("%.3f", result.bonafideLogit)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = "Spoof logit: ${String.format("%.3f", result.spoofLogit)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = "Threshold: ${String.format("%.3f", uiState.threshold)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Idle")
@Composable
fun DetectorScreenIdlePreview() {
    MaterialTheme {
        DetectorScreen(
            uiState = DetectorUiState(
                permissionState = PermissionState.GRANTED,
                availableModels = listOf("tt16_100eps", "model_v2"),
                selectedModel = "tt16_100eps"
            ),
            onRequestPermission = {},
            onToggleDetection = {},
            onSwitchMode = {},
            onToggleReplay = {},
            onSwitchModel = {},
            onSetDecisionThreshold = {},
            onSetRmsThreshold = {}
        )
    }
}

@Preview(showBackground = true, name = "Live Result")
@Composable
fun DetectorScreenLivePreview() {
    MaterialTheme {
        DetectorScreen(
            uiState = DetectorUiState(
                permissionState = PermissionState.GRANTED,
                lastResult = DetectionResult(bonafideLogit = 2.0f, spoofLogit = -2.0f, label = SpoofLabel.LIVE),
                availableModels = listOf("tt16_100eps"),
                selectedModel = "tt16_100eps"
            ),
            onRequestPermission = {},
            onToggleDetection = {},
            onSwitchMode = {},
            onToggleReplay = {},
            onSwitchModel = {},
            onSetDecisionThreshold = {},
            onSetRmsThreshold = {}
        )
    }
}

@Preview(showBackground = true, name = "Spoof Result")
@Composable
fun DetectorScreenSpoofPreview() {
    MaterialTheme {
        DetectorScreen(
            uiState = DetectorUiState(
                permissionState = PermissionState.GRANTED,
                lastResult = DetectionResult(bonafideLogit = -2.0f, spoofLogit = 2.0f, label = SpoofLabel.SPOOF),
                availableModels = listOf("tt16_100eps"),
                selectedModel = "tt16_100eps"
            ),
            onRequestPermission = {},
            onToggleDetection = {},
            onSwitchMode = {},
            onToggleReplay = {},
            onSwitchModel = {},
            onSetDecisionThreshold = {},
            onSetRmsThreshold = {}
        )
    }
}

@Preview(showBackground = true, name = "Detection Finished")
@Composable
fun DetectorScreenFinishedPreview() {
    MaterialTheme {
        DetectorScreen(
            uiState = DetectorUiState(
                permissionState = PermissionState.GRANTED,
                isRecording = false,
                lastResult = DetectionResult(bonafideLogit = 1.5f, spoofLogit = -3.2f, label = SpoofLabel.LIVE),
                availableModels = listOf("tt16_100eps"),
                selectedModel = "tt16_100eps"
            ),
            onRequestPermission = {},
            onToggleDetection = {},
            onSwitchMode = {},
            onToggleReplay = {},
            onSwitchModel = {},
            onSetDecisionThreshold = {},
            onSetRmsThreshold = {}
        )
    }
}

@Preview(showBackground = true, name = "Realtime Mode")
@Composable
fun DetectorScreenRealtimePreview() {
    MaterialTheme {
        DetectorScreen(
            uiState = DetectorUiState(
                permissionState = PermissionState.GRANTED,
                mode = DetectionMode.CONTINUOUS_REALTIME,
                availableModels = listOf("tt16_100eps"),
                selectedModel = "tt16_100eps"
            ),
            onRequestPermission = {},
            onToggleDetection = {},
            onSwitchMode = {},
            onToggleReplay = {},
            onSwitchModel = {},
            onSetDecisionThreshold = {},
            onSetRmsThreshold = {}
        )
    }
}

@Preview(showBackground = true, name = "Settings Sheet Content")
@Composable
fun SettingsSheetContentPreview() {
    MaterialTheme {
        Surface {
            SettingsSheetContent(
                uiState = DetectorUiState(
                    availableModels = listOf("tt16_100eps", "model_v2"),
                    selectedModel = "tt16_100eps",
                    threshold = 1.5f
                ),
                onSetDecisionThreshold = {},
                onSetRmsThreshold = {},
                onSwitchModel = {}
            )
        }
    }
}
