package myai.hush.kmp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun App(viewModel: AppViewModel, permissionRequester: AudioPermissionRequester) {
    val currentTab by viewModel.currentTab.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()
    val isRecording = recorderState is RecorderState.Recording

    var hasPermission by remember { mutableStateOf(permissionRequester.isPermissionGranted()) }

    // Palette against pure black (#000000)
    val blackBg = Color(0xFF000000)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFB0B0B0)
    val accentTeal = Color(0xFF00F0FF) // Neon teal
    val buttonBg = Color(0xFF1A1A1A)
    val cardBg = Color(0xFF0A0A0A)

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = blackBg,
            surface = cardBg,
            primary = accentTeal,
            onPrimary = blackBg,
            onBackground = textPrimary,
            onSurface = textPrimary
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(blackBg)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "HUSH ENGINE",
                    color = accentTeal,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Compose Multiplatform Silence Remover",
                    color = textSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // App Mode Tab Selector
                Row(
                    modifier = Modifier
                        .background(Color(0xFF151515), RoundedCornerShape(8.dp))
                        .padding(4.dp)
                        .fillMaxWidth()
                ) {
                    val tabs = listOf("Recorder", "Processor", "Player")
                    tabs.forEachIndexed { index, mode ->
                        val isSelected = currentTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) Color(0xFF222222) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable(enabled = !isRecording) {
                                    viewModel.setTab(index)
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode,
                                color = if (isSelected) accentTeal else textPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Conditional Views
                when (currentTab) {
                    0 -> RecorderScreen(viewModel, permissionRequester, hasPermission, onPermissionChanged = { hasPermission = it })
                    1 -> ProcessorScreen(viewModel)
                    2 -> PlayerScreen(viewModel)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun RecorderScreen(
    viewModel: AppViewModel,
    permissionRequester: AudioPermissionRequester,
    hasPermission: Boolean,
    onPermissionChanged: (Boolean) -> Unit
) {
    val state by viewModel.recorderState.collectAsState()
    val threshold by viewModel.thresholdDb.collectAsState()
    val aggression by viewModel.aggressionLevel.collectAsState()
    val useSilenceRemoval by viewModel.useSilenceRemoval.collectAsState()
    
    val db by viewModel.currentDb.collectAsState()
    val pressure by viewModel.pressureLevel.collectAsState()
    val degradation by viewModel.degradationState.collectAsState()
    val secs by viewModel.recordingSeconds.collectAsState()
    val filePath by viewModel.recordedFilePath.collectAsState()

    val blackBg = Color(0xFF000000)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFB0B0B0)
    val accentTeal = Color(0xFF00F0FF)
    val buttonBg = Color(0xFF1A1A1A)
    val cardBg = Color(0xFF0A0A0A)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(12.dp),
        border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "NATIVE RECORDER",
                color = textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            // Silence removal toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Silence Removal", color = textPrimary, fontSize = 14.sp)
                    Text(text = "Hush silent portions in real-time", color = textSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = useSilenceRemoval,
                    onCheckedChange = { viewModel.useSilenceRemoval.value = it },
                    enabled = state !is RecorderState.Recording,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accentTeal,
                        checkedTrackColor = Color(0xFF153335)
                    )
                )
            }

            HorizontalDivider(color = Color(0xFF222222))

            // Configuration Sliders
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Silence Threshold: ${threshold.toInt()} dB", color = textPrimary, fontSize = 14.sp)
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { viewModel.thresholdDb.value = it.toDouble() },
                    valueRange = -60f..0f,
                    colors = SliderDefaults.colors(
                        thumbColor = accentTeal,
                        activeTrackColor = accentTeal,
                        inactiveTrackColor = Color(0xFF222222)
                    ),
                    enabled = state !is RecorderState.Recording
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(text = "Aggression Level: ${"%.2f".format(aggression)}", color = textPrimary, fontSize = 14.sp)
                Slider(
                    value = aggression.toFloat(),
                    onValueChange = { viewModel.aggressionLevel.value = it.toDouble() },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = accentTeal,
                        activeTrackColor = accentTeal,
                        inactiveTrackColor = Color(0xFF222222)
                    ),
                    enabled = state !is RecorderState.Recording
                )
            }

            HorizontalDivider(color = Color(0xFF222222))

            // VU Meter
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Signal Level", color = textSecondary, fontSize = 12.sp)
                    Text(
                        text = if (state is RecorderState.Recording) "${db.toInt()} dB" else "— dB",
                        color = if (state is RecorderState.Recording && db > -40.0) accentTeal else textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                val dbNormalized = if (state is RecorderState.Recording) {
                    ((db + 80.0) / 80.0).coerceIn(0.0, 1.0).toFloat()
                } else 0f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(dbNormalized)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF00B0FF), accentTeal, Color(0xFF00FF88))
                                ),
                                RoundedCornerShape(6.dp)
                            )
                    )
                }
            }

            if (state is RecorderState.Recording || pressure > 0) {
                HorizontalDivider(color = Color(0xFF222222))

                // Real-time metrics grid
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Live Metrics:",
                        color = textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Recording Duration:", color = textSecondary, fontSize = 12.sp)
                        Text(
                            text = String.format("%02d:%02d", secs / 60, secs % 60),
                            color = textPrimary,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Buffer Pressure:", color = textSecondary, fontSize = 12.sp)
                        Text(
                            text = "$pressure / 100",
                            color = if (pressure > 70) Color.Red else textPrimary,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "Degradation State:", color = textSecondary, fontSize = 12.sp)
                        val stateText = when (degradation) {
                            0 -> "Normal"
                            1 -> "Degraded"
                            2 -> "Emergency"
                            else -> "Unknown"
                        }
                        val stateColor = when (degradation) {
                            0 -> Color(0xFF00FF88)
                            1 -> Color(0xFFFFCC00)
                            2 -> Color(0xFFFF3333)
                            else -> textPrimary
                        }
                        Text(
                            text = stateText,
                            color = stateColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // Recording action button
    val buttonText: String
    val buttonColor: Color
    val buttonTextColor: Color

    if (!hasPermission) {
        buttonText = "Grant Microphone Permission"
        buttonColor = buttonBg
        buttonTextColor = textPrimary
    } else if (state is RecorderState.Recording) {
        buttonText = "Stop Recording"
        buttonColor = Color(0xFFFF3333)
        buttonTextColor = textPrimary
    } else {
        buttonText = "Start Recording"
        buttonColor = accentTeal
        buttonTextColor = blackBg
    }

    Button(
        onClick = {
            if (!hasPermission) {
                permissionRequester.RequestPermission { granted ->
                    onPermissionChanged(granted)
                }
            } else {
                viewModel.toggleRecording()
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            contentColor = buttonTextColor
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text = buttonText, fontWeight = FontWeight.Bold)
    }

    if (state is RecorderState.Error) {
        Text((state as RecorderState.Error).message, color = Color.Red, fontSize = 12.sp, textAlign = TextAlign.Center)
    }

    if (filePath != null && state is RecorderState.Idle) {
        // Automatically populate paths when recording stops
        LaunchedEffect(filePath) {
            viewModel.processInputPath.value = filePath ?: ""
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            shape = RoundedCornerShape(12.dp),
            border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Last Recorded File:", color = textPrimary, style = MaterialTheme.typography.titleMedium)
                Text(filePath ?: "", color = textSecondary, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { viewModel.playRecorded() },
                    colors = ButtonDefaults.buttonColors(containerColor = buttonBg, contentColor = textPrimary),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Play Recording")
                }
            }
        }
    }
}

@Composable
fun ProcessorScreen(viewModel: AppViewModel) {
    val state by viewModel.processingState.collectAsState()
    val inputPath by viewModel.processInputPath.collectAsState()
    val threshold by viewModel.processThresholdDb.collectAsState()
    val aggression by viewModel.processAggression.collectAsState()

    val isPlaying by viewModel.isPlaying.collectAsState()

    val blackBg = Color(0xFF000000)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFB0B0B0)
    val accentTeal = Color(0xFF00F0FF)
    val buttonBg = Color(0xFF1A1A1A)
    val cardBg = Color(0xFF0A0A0A)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            shape = RoundedCornerShape(12.dp),
            border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "FILE PROCESSOR",
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = inputPath,
                    onValueChange = { viewModel.processInputPath.value = it },
                    label = { Text("Input WAV Absolute File Path", color = textSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentTeal,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedLabelColor = accentTeal,
                        unfocusedLabelColor = textSecondary
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text("Silence Threshold: ${threshold.toInt()} dB", color = textPrimary, fontSize = 14.sp)
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { viewModel.processThresholdDb.value = it.toDouble() },
                    valueRange = -60f..0f,
                    colors = SliderDefaults.colors(
                        thumbColor = accentTeal,
                        activeTrackColor = accentTeal,
                        inactiveTrackColor = Color(0xFF222222)
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text("Aggression Level: ${"%.2f".format(aggression)}", color = textPrimary, fontSize = 14.sp)
                Slider(
                    value = aggression.toFloat(),
                    onValueChange = { viewModel.processAggression.value = it.toDouble() },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = accentTeal,
                        activeTrackColor = accentTeal,
                        inactiveTrackColor = Color(0xFF222222)
                    )
                )

                Button(
                    onClick = { viewModel.applyProcessFile() },
                    enabled = state !is ProcessingState.Processing && inputPath.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentTeal,
                        contentColor = blackBg,
                        disabledContainerColor = Color(0xFF153335)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (state is ProcessingState.Processing) "Processing..." else "Process WAV File",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        when (state) {
            is ProcessingState.Processing -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = accentTeal
                )
            }
            is ProcessingState.Success -> {
                val success = state as ProcessingState.Success
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(12.dp),
                    border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Processing Completed Successfully!", color = accentTeal, style = MaterialTheme.typography.titleMedium)
                        Text("Silence Reduction: ${"%.1f".format(success.reduction)}%", color = textPrimary)
                        Text("Silent Segments Removed: ${success.segments}", color = textPrimary)
                        Text("Output Path: ${success.outputPath}", color = textSecondary, style = MaterialTheme.typography.bodySmall)
                        
                        Button(
                            onClick = { viewModel.startPlayback(success.outputPath) },
                            enabled = !isPlaying,
                            colors = ButtonDefaults.buttonColors(containerColor = buttonBg, contentColor = textPrimary),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Play Processed WAV")
                        }
                    }
                }
            }
            is ProcessingState.Error -> {
                Text(
                    text = (state as ProcessingState.Error).message,
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            else -> {}
        }
    }
}

@Composable
fun PlayerScreen(viewModel: AppViewModel) {
    var filePath by remember { mutableStateOf("") }
    
    val isPlaying by viewModel.isPlaying.collectAsState()
    val progress by viewModel.playbackProgress.collectAsState()
    val time by viewModel.playbackTime.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val volume by viewModel.volume.collectAsState()

    val blackBg = Color(0xFF000000)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFB0B0B0)
    val accentTeal = Color(0xFF00F0FF)
    val buttonBg = Color(0xFF1A1A1A)
    val cardBg = Color(0xFF0A0A0A)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            shape = RoundedCornerShape(12.dp),
            border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "AUDIO PLAYER",
                    color = textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = filePath,
                    onValueChange = { filePath = it },
                    label = { Text("WAV File Path to Play", color = textSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentTeal,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedLabelColor = accentTeal,
                        unfocusedLabelColor = textSecondary
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.startPlayback(filePath) },
                        enabled = !isPlaying && filePath.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentTeal,
                            contentColor = blackBg,
                            disabledContainerColor = Color(0xFF153335)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { viewModel.stopPlayback() },
                        enabled = isPlaying,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF3333),
                            contentColor = textPrimary,
                            disabledContainerColor = Color(0xFF221111)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (isPlaying || duration > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Playback Progress", color = textPrimary, style = MaterialTheme.typography.titleMedium)
                    
                    Slider(
                        value = progress,
                        onValueChange = { viewModel.seekTo(it) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isPlaying,
                        colors = SliderDefaults.colors(
                            thumbColor = accentTeal,
                            activeTrackColor = accentTeal,
                            inactiveTrackColor = Color(0xFF222222)
                        )
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${time / 1000}s", color = textSecondary)
                        Text("${duration / 1000}s", color = textSecondary)
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Volume: ${(volume * 100).toInt()}%", color = textPrimary)
                    Slider(
                        value = volume,
                        onValueChange = { viewModel.setVolume(it) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = accentTeal,
                            activeTrackColor = accentTeal,
                            inactiveTrackColor = Color(0xFF222222)
                        )
                    )
                }
            }
        }
    }
}
