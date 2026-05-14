package klama.hush.kmp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

import klama.hush.kmp.permissions.AudioPermissionRequester

@Composable
fun App(viewModel: AppViewModel, permissionRequester: AudioPermissionRequester) {
    val state by viewModel.state.collectAsState()
    val originalFile by viewModel.originalFile.collectAsState()
    val hushedFile by viewModel.hushedFile.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val lastReduction by viewModel.lastReduction.collectAsState()
    val progress by viewModel.playbackProgress.collectAsState()
    val time by viewModel.playbackTime.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val volume by viewModel.volume.collectAsState()
    
    var showPermissionDialog by remember { mutableStateOf(false) }

    if (showPermissionDialog) {
        permissionRequester.RequestPermission { granted ->
            showPermissionDialog = false
            if (granted) {
                viewModel.toggleRecording()
            }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Hush KMP Sample", style = MaterialTheme.typography.headlineMedium)

                // Status Card
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Status: ${state::class.simpleName}", style = MaterialTheme.typography.bodyLarge)
                        }
                        
                        lastReduction?.let { reduction ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Silence Reduction: ${reduction.toString().take(5)}%",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                HorizontalDivider()
                
                Text("Record and Silence", style = MaterialTheme.typography.titleMedium)

                // Record Button
                Button(
                    onClick = {
                        if (state is AppState.Recording) {
                            viewModel.toggleRecording()
                        } else {
                            if (permissionRequester.isPermissionGranted()) {
                                viewModel.toggleRecording()
                            } else {
                                showPermissionDialog = true
                            }
                        }
                    },
                    enabled = state is AppState.Idle || state is AppState.Recording,
                    colors = if (state is AppState.Recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state is AppState.Recording) "Stop Recording" else "Start Recording")
                }

                if (originalFile != null && state is AppState.Idle) {
                    Text("Hush Configuration", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HushPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = { viewModel.setPreset(preset) },
                                label = { Text(preset.label) }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.playOriginal() },
                        enabled = originalFile != null && state is AppState.Idle && !isPlaying
                    ) {
                        Text("Play Original")
                    }
                    
                    Button(
                        onClick = { viewModel.applyHush() },
                        enabled = originalFile != null && state is AppState.Idle
                    ) {
                        Text("Hush (${selectedPreset.label})")
                    }

                    Button(
                        onClick = { viewModel.playHushed() },
                        enabled = hushedFile != null && state is AppState.Idle && !isPlaying
                    ) {
                        Text("Play Hushed")
                    }
                }

                // Audio Player UI
                if (isPlaying || (originalFile != null || hushedFile != null)) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(formatTime(time), style = MaterialTheme.typography.labelMedium)
                                Slider(
                                    value = progress,
                                    onValueChange = { viewModel.seekTo(it) },
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                    enabled = isPlaying
                                )
                                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
                                Slider(
                                    value = volume,
                                    onValueChange = { viewModel.setVolume(it) },
                                    modifier = Modifier.width(120.dp).padding(start = 8.dp)
                                )
                                Spacer(Modifier.weight(1f))
                                if (isPlaying) {
                                    IconButton(onClick = { viewModel.stopPlayback() }) {
                                        Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                if (state is AppState.Error) {
                    Text("Error: ${(state as AppState.Error).message}", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}


