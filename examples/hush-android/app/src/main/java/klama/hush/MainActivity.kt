package klama.hush

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Brush

class MainActivity : ComponentActivity() {

    private val sampleRate = 16000

    private val configs = listOf(
        PresetConfig("Slightly", -45.0, 0.2),
        PresetConfig("Gentle", -35.0, 0.4),
        PresetConfig("Default", -30.0, 0.5),
        PresetConfig("Aggressive", -25.0, 0.8),
        PresetConfig("More Aggressive", -20.0, 0.95)
    )

    data class PresetConfig(val name: String, val thresholdDb: Double, val aggressionLevel: Double) {
        override fun toString() = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HushAppScreen()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun HushAppScreen() {
        val coroutineScope = rememberCoroutineScope()
        var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
        var processedAudio by remember { mutableStateOf<ShortArray?>(null) }
        var statusText by remember { mutableStateOf("Hush Engine Ready.\nSelect a .wav file to process.") }
        var isProcessing by remember { mutableStateOf(false) }
        var selectedConfig by remember { mutableStateOf(configs[2]) }
        var dropdownExpanded by remember { mutableStateOf(false) }

        // Audio player state variables
        var originalAudioPath by remember { mutableStateOf<String?>(null) }
        var processedAudioPath by remember { mutableStateOf<String?>(null) }
        var originalSamplesCount by remember { mutableStateOf(0) }
        var processedSamplesCount by remember { mutableStateOf(0) }
        var currentPositionMs by remember { mutableStateOf(0f) }
        var playbackDurationMs by remember { mutableStateOf(0f) }
        var isPlaying by remember { mutableStateOf(false) }
        var isPaused by remember { mutableStateOf(false) }
        var playMode by remember { mutableStateOf("Original") }
        var fileSampleRate by remember { mutableStateOf(16000) }

        val audioPlayer = remember {
            try {
                AudioPlayer()
            } catch (e: Exception) {
                Log.e("Hush", "Failed to create native AudioPlayer", e)
                null
            }
        }

        DisposableEffect(audioPlayer) {
            onDispose {
                audioPlayer?.close()
            }
        }

        // Batch Directory state variables
        var appMode by remember { mutableStateOf("Single File") }
        var inputDirectoryUri by remember { mutableStateOf<Uri?>(null) }
        var outputDirectoryUri by remember { mutableStateOf<Uri?>(null) }
        var isBatchProcessing by remember { mutableStateOf(false) }

        // Recorder state variables
        var useSilenceRemoval by remember { mutableStateOf(true) }
        var audioRecorder by remember { mutableStateOf<AudioRecorder?>(null) }
        var isRecording by remember { mutableStateOf(false) }
        var hasMicPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
        var recordDbLevel by remember { mutableStateOf(-100.0) }
        var recordPressure by remember { mutableStateOf(0) }
        var recordDegradation by remember { mutableStateOf(0) }
        var recordStats by remember { mutableStateOf<HushStats?>(null) }

        val requestMicPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasMicPermission = isGranted
            if (isGranted) {
                statusText = "Microphone permission granted. Click 'Start Recording' again to begin."
            } else {
                statusText = "Microphone permission denied. Cannot record audio."
            }
        }

        // Playlist state variables
        var playlistFiles by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
        var currentTrackIndex by remember { mutableStateOf(0) }

        val pickInputDirLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                inputDirectoryUri = uri
                originalAudioPath = null
                processedAudioPath = null
                originalSamplesCount = 0
                processedSamplesCount = 0
                isPlaying = false
                isPaused = false
                currentPositionMs = 0f
                audioPlayer?.stop()
            }
        }

        val pickOutputDirLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                outputDirectoryUri = uri
            }
        }

        val playTrack: (Int) -> Unit = { index ->
            if (appMode == "Single File") {
                val path = if (playMode == "Original") originalAudioPath else processedAudioPath
                if (path != null && audioPlayer != null) {
                    audioPlayer.stop()
                    val success = audioPlayer.play(path)
                    if (success) {
                        playbackDurationMs = if (playMode == "Original") {
                            (originalSamplesCount.toFloat() / fileSampleRate) * 1000f
                        } else {
                            (processedSamplesCount.toFloat() / fileSampleRate) * 1000f
                        }
                        isPaused = false
                        isPlaying = true
                        currentPositionMs = 0f
                    }
                }
            } else {
                if (index in playlistFiles.indices && audioPlayer != null) {
                    currentTrackIndex = index
                    audioPlayer.stop()
                    isPlaying = false
                    isPaused = false
                    currentPositionMs = 0f

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val file = playlistFiles[index]
                            val extension = file.name?.substringAfterLast('.', "wav") ?: "wav"
                            val tempPlayFile = copyUriToCacheFile(file.uri, "temp_play.$extension")
                            val decoded = decodeAudioToPcm(file.uri)
                            val duration = (decoded.samples.size.toFloat() / decoded.sampleRate) * 1000f

                            withContext(Dispatchers.Main) {
                                val success = audioPlayer.play(tempPlayFile.absolutePath)
                                if (success) {
                                    playbackDurationMs = duration
                                    isPaused = false
                                    isPlaying = true
                                    currentPositionMs = 0f
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Hush", "Failed to play directory track", e)
                        }
                    }
                }
            }
        }

        LaunchedEffect(appMode, playMode, inputDirectoryUri, outputDirectoryUri, isBatchProcessing) {
            if (appMode == "Batch Directory") {
                val uri = if (playMode == "Original") inputDirectoryUri else outputDirectoryUri
                if (uri != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val dirFile = DocumentFile.fromTreeUri(this@MainActivity, uri)
                            val files = dirFile?.listFiles()?.filter {
                                it.isFile && (it.name?.endsWith(".wav", ignoreCase = true) == true ||
                                        it.name?.endsWith(".mp3", ignoreCase = true) == true)
                            }?.sortedBy { it.name } ?: emptyList()
                            
                            withContext(Dispatchers.Main) {
                                playlistFiles = files
                                if (currentTrackIndex >= files.size) {
                                    currentTrackIndex = 0
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Hush", "Failed to update playlist files", e)
                        }
                    }
                } else {
                    playlistFiles = emptyList()
                }
            } else {
                playlistFiles = emptyList()
            }
        }

        LaunchedEffect(isPlaying) {
            if (isPlaying) {
                val startTime = System.currentTimeMillis()
                val startPosition = currentPositionMs
                while (isPlaying) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val newPos = startPosition + elapsed
                    if (newPos >= playbackDurationMs || (audioPlayer != null && audioPlayer.isFinished())) {
                        isPlaying = false
                        isPaused = false
                        currentPositionMs = 0f
                        audioPlayer?.stop()
                        
                        // Auto-advance in Batch Mode
                        if (appMode == "Batch Directory" && currentTrackIndex + 1 < playlistFiles.size) {
                            playTrack(currentTrackIndex + 1)
                        }
                        break
                    }
                    currentPositionMs = newPos.coerceAtMost(playbackDurationMs)
                    kotlinx.coroutines.delay(50)
                }
            }
        }

        // Pick WAV File Launcher
        val pickFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    selectedFileUri = uri
                    processedAudio = null
                    processedAudioPath = null
                    processedSamplesCount = 0
                    isPlaying = false
                    isPaused = false
                    currentPositionMs = 0f
                    audioPlayer?.stop()
                    
                    val mimeType = contentResolver.getType(uri)
                    val extension = if (mimeType == "audio/mpeg" || mimeType == "audio/mp3") "mp3" else "wav"
                    
                    val tempInputFile = try {
                        copyUriToCacheFile(uri, "temp_input.$extension")
                    } catch (e: Exception) {
                        null
                    }
                    if (tempInputFile != null) {
                        originalAudioPath = tempInputFile.absolutePath
                        val decoded = try {
                            decodeAudioToPcm(uri)
                        } catch (e: Exception) {
                            null
                        }
                        if (decoded != null) {
                            originalSamplesCount = decoded.samples.size
                            fileSampleRate = decoded.sampleRate
                        }
                    }
                    statusText = "File Selected: ${uri.lastPathSegment}\nClick 'Start Hush' to begin."
                }
            }
        }

        // Save WAV File Launcher
        val saveFileLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    val audio = processedAudio
                    if (audio != null) {
                        statusText = "Saving to ${uri.path}..."
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val outputStream = contentResolver.openOutputStream(uri) 
                                    ?: throw Exception("Failed to open output stream")
                                writeWavFile(outputStream, audio, sampleRate)
                                withContext(Dispatchers.Main) {
                                    statusText = "File saved successfully!\n" + statusText
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    statusText = "Error saving file: ${e.message}"
                                }
                                Log.e("Hush", "Error saving file", e)
                            }
                        }
                    }
                }
            }
        }

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
                        text = "Pure Android Silence Remover",
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
                        listOf("Single File", "Batch Directory", "Recorder").forEach { mode ->
                            val isSelected = appMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isSelected) Color(0xFF222222) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable(enabled = !isProcessing && !isBatchProcessing && !isRecording) {
                                        appMode = mode
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

                    // Configuration Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        shape = RoundedCornerShape(12.dp),
                        border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Hush Aggression Level:",
                                color = textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { if (!isProcessing && !isBatchProcessing) dropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = selectedConfig.name)
                                        Text(
                                            text = "${selectedConfig.thresholdDb.toInt()}dB / ${selectedConfig.aggressionLevel}",
                                            color = textSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = dropdownExpanded,
                                    onDismissRequest = { dropdownExpanded = false },
                                    modifier = Modifier.background(cardBg)
                                ) {
                                    configs.forEach { config ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(text = config.name, color = textPrimary)
                                                    Spacer(modifier = Modifier.width(24.dp))
                                                    Text(
                                                        text = "${config.thresholdDb.toInt()}dB / ${config.aggressionLevel}",
                                                        color = textSecondary
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedConfig = config
                                                dropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Status and Stats Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        shape = RoundedCornerShape(12.dp),
                        border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = statusText,
                                color = textPrimary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }

                    // Audio Player Card
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "AUDIO PLAYER",
                                    color = accentTeal,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )

                                Row(
                                    modifier = Modifier
                                        .background(Color(0xFF151515), RoundedCornerShape(6.dp))
                                        .padding(2.dp)
                                ) {
                                    listOf("Original", "Processed").forEach { mode ->
                                        val isSelected = playMode == mode
                                        val isAvailable = if (appMode == "Single File" || appMode == "Recorder") {
                                            if (mode == "Original") originalAudioPath != null else processedAudioPath != null
                                        } else {
                                            if (mode == "Original") inputDirectoryUri != null else outputDirectoryUri != null
                                        }

                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    if (isSelected) Color(0xFF222222) else Color.Transparent,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .clickable(enabled = isAvailable) {
                                                    if (playMode != mode) {
                                                        audioPlayer?.stop()
                                                        isPlaying = false
                                                        isPaused = false
                                                        currentPositionMs = 0f
                                                        playMode = mode
                                                    }
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = mode,
                                                color = if (isSelected) accentTeal else if (isAvailable) textPrimary else Color(0xFF555555),
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }

                            val isPlayerActive = if (appMode == "Single File" || appMode == "Recorder") {
                                originalAudioPath != null
                            } else {
                                playlistFiles.isNotEmpty()
                            }

                            if (isPlayerActive) {
                                val totalSeconds = (playbackDurationMs / 1000).toInt()
                                val currentSeconds = (currentPositionMs / 1000).toInt()

                                val totalTimeStr = String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
                                val currentTimeStr = String.format("%02d:%02d", currentSeconds / 60, currentSeconds % 60)

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Slider(
                                        value = currentPositionMs,
                                        onValueChange = { /* read only */ },
                                        valueRange = 0f..playbackDurationMs.coerceAtLeast(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = accentTeal,
                                            activeTrackColor = accentTeal,
                                            inactiveTrackColor = Color(0xFF222222)
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = false
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = currentTimeStr, color = textSecondary, fontSize = 12.sp)
                                        Text(text = totalTimeStr, color = textSecondary, fontSize = 12.sp)
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (appMode == "Batch Directory") {
                                        IconButton(
                                            onClick = { if (currentTrackIndex > 0) playTrack(currentTrackIndex - 1) },
                                            enabled = currentTrackIndex > 0,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .background(if (currentTrackIndex > 0) buttonBg else Color(0xFF101010), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.SkipPrevious,
                                                contentDescription = "Previous Track",
                                                tint = if (currentTrackIndex > 0) textPrimary else Color(0xFF555555),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            if (appMode == "Single File" || appMode == "Recorder") {
                                                val path = if (playMode == "Original") originalAudioPath else processedAudioPath
                                                if (path != null && audioPlayer != null) {
                                                    if (isPlaying) {
                                                        audioPlayer.togglePause()
                                                        isPaused = true
                                                        isPlaying = false
                                                    } else if (isPaused) {
                                                        audioPlayer.togglePause()
                                                        isPaused = false
                                                        isPlaying = true
                                                    } else {
                                                        val success = audioPlayer.play(path)
                                                        if (success) {
                                                            playbackDurationMs = if (playMode == "Original") {
                                                                (originalSamplesCount.toFloat() / sampleRate) * 1000f
                                                            } else {
                                                                (processedSamplesCount.toFloat() / sampleRate) * 1000f
                                                            }
                                                            isPaused = false
                                                            isPlaying = true
                                                            currentPositionMs = 0f
                                                        }
                                                    }
                                                }
                                            } else {
                                                if (audioPlayer != null) {
                                                    if (isPlaying) {
                                                        audioPlayer.togglePause()
                                                        isPaused = true
                                                        isPlaying = false
                                                    } else if (isPaused) {
                                                        audioPlayer.togglePause()
                                                        isPaused = false
                                                        isPlaying = true
                                                    } else {
                                                        playTrack(currentTrackIndex)
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(accentTeal, CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            tint = blackBg,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            audioPlayer?.stop()
                                            isPlaying = false
                                            isPaused = false
                                            currentPositionMs = 0f
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(buttonBg, CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Stop,
                                            contentDescription = "Stop",
                                            tint = textPrimary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    if (appMode == "Batch Directory") {
                                        IconButton(
                                            onClick = { if (currentTrackIndex + 1 < playlistFiles.size) playTrack(currentTrackIndex + 1) },
                                            enabled = currentTrackIndex + 1 < playlistFiles.size,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .background(if (currentTrackIndex + 1 < playlistFiles.size) buttonBg else Color(0xFF101010), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.SkipNext,
                                                contentDescription = "Next Track",
                                                tint = if (currentTrackIndex + 1 < playlistFiles.size) textPrimary else Color(0xFF555555),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }

                                if (appMode == "Batch Directory" && playlistFiles.isNotEmpty()) {
                                    HorizontalDivider(color = Color(0xFF222222))

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        playlistFiles.forEachIndexed { index, file ->
                                            val isCurrent = index == currentTrackIndex
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        if (isCurrent) Color(0xFF151515) else Color.Transparent,
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .clickable {
                                                        playTrack(index)
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${index + 1}. ",
                                                    color = if (isCurrent) accentTeal else textSecondary,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                )
                                                Text(
                                                    text = file.name ?: "Unnamed WAV",
                                                    color = if (isCurrent) textPrimary else textSecondary,
                                                    fontSize = 14.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isCurrent && isPlaying) {
                                                    Text(
                                                        text = "● PLAYING",
                                                        color = accentTeal,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = when (appMode) {
                                        "Single File" -> "Select a WAV file to start the player."
                                        "Batch Directory" -> "Select directories with WAV files to start the player."
                                        else -> "Record a track or select a recorded track to start the player."
                                    },
                                    color = textSecondary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Conditional Action Buttons based on App Mode
                    when (appMode) {
                        "Single File" -> {
                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "audio/*"
                                        val mimeTypes = arrayOf(
                                            "audio/wav", "audio/x-wav", "audio/vnd.wave",
                                            "audio/mpeg", "audio/mp3", "audio/x-mp3"
                                        )
                                        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                                    }
                                    pickFileLauncher.launch(intent)
                                },
                                enabled = !isProcessing,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = buttonBg,
                                    contentColor = textPrimary,
                                    disabledContainerColor = Color(0xFF101010)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(text = "Select Audio File (MP3/WAV)", fontWeight = FontWeight.Medium)
                            }

                            Button(
                                onClick = {
                                    val uri = selectedFileUri
                                    if (uri != null) {
                                        isProcessing = true
                                        statusText = "Processing ${uri.path}...\nMode: ${selectedConfig.name}"
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val decoded = decodeAudioToPcm(uri)
                                                val audioData = decoded.samples
                                                val currentFileSampleRate = decoded.sampleRate
                                                
                                                val config = HushConfig(
                                                    thresholdDb = selectedConfig.thresholdDb, 
                                                    aggressionLevel = selectedConfig.aggressionLevel, 
                                                    sampleRate = currentFileSampleRate
                                                )
                                                
                                                Hush(config).use { hush ->
                                                    Log.i("Hush", "Engine initialized for file processing. Mode: ${selectedConfig.name}")
                                                    
                                                    val processed = hush.process(audioData)
                                                    val flushed = hush.flush()
                                                    
                                                    val finalAudio = ShortArray(processed.size + flushed.size).apply {
                                                        processed.copyInto(this)
                                                        flushed.copyInto(this, processed.size)
                                                    }
                                                    
                                                    val stats = hush.getStats()
                                                    val result = """
                                                        Processing complete!
                                                        
                                                        Mode: ${selectedConfig.name}
                                                        File: ${uri.lastPathSegment}
                                                        Total Input: ${stats.totalInputSamples} samples
                                                        Total Output: ${stats.totalOutputSamples} samples
                                                        Removed: ${stats.totalRemovedSamples}
                                                        Reduction: ${String.format("%.2f", stats.reductionPercentage)}%
                                                        Silent Segments: ${stats.silentSegmentsDetected}
                                                    """.trimIndent()
                                                    
                                                    withContext(Dispatchers.Main) {
                                                        audioPlayer?.stop()
                                                        isPlaying = false
                                                        isPaused = false
                                                        currentPositionMs = 0f
                                                    }

                                                    val tempProcessedFile = java.io.File(cacheDir, "temp_processed.wav")
                                                    try {
                                                        tempProcessedFile.outputStream().use { output ->
                                                            writeWavFile(output, finalAudio, currentFileSampleRate)
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("Hush", "Failed to write temp processed wav", e)
                                                    }

                                                    withContext(Dispatchers.Main) {
                                                        processedAudio = finalAudio
                                                        processedAudioPath = tempProcessedFile.absolutePath
                                                        processedSamplesCount = finalAudio.size
                                                        fileSampleRate = currentFileSampleRate
                                                        playMode = "Processed"
                                                        statusText = result
                                                        isProcessing = false
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    statusText = "Error: ${e.message}"
                                                    isProcessing = false
                                                }
                                                Log.e("Hush", "Error processing file", e)
                                            }
                                        }
                                    }
                                },
                                enabled = !isProcessing && selectedFileUri != null,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentTeal,
                                    contentColor = blackBg,
                                    disabledContainerColor = Color(0xFF153335)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (isProcessing) "Processing..." else "Start Hush", 
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_TITLE, "hushed_audio.wav")
                                    }
                                    saveFileLauncher.launch(intent)
                                },
                                enabled = !isProcessing && processedAudio != null,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = buttonBg,
                                    contentColor = textPrimary,
                                    disabledContainerColor = Color(0xFF101010)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(text = "Save Processed File", fontWeight = FontWeight.Medium)
                            }
                        }
                        "Batch Directory" -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = cardBg),
                                shape = RoundedCornerShape(12.dp),
                                border = AssistChipDefaults.assistChipBorder(borderColor = Color(0xFF333333), enabled = true)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "Batch Folders:",
                                        color = textPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = "Input Directory", color = textSecondary, fontSize = 12.sp)
                                            Text(
                                                text = getDirectoryName(inputDirectoryUri),
                                                color = textPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Button(
                                            onClick = { pickInputDirLauncher.launch(null) },
                                            enabled = !isBatchProcessing,
                                            colors = ButtonDefaults.buttonColors(containerColor = buttonBg, contentColor = textPrimary),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text("Select", fontSize = 12.sp)
                                        }
                                    }

                                    HorizontalDivider(color = Color(0xFF222222))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = "Output Directory", color = textSecondary, fontSize = 12.sp)
                                            Text(
                                                text = getDirectoryName(outputDirectoryUri),
                                                color = textPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                        Button(
                                            onClick = { pickOutputDirLauncher.launch(null) },
                                            enabled = !isBatchProcessing,
                                            colors = ButtonDefaults.buttonColors(containerColor = buttonBg, contentColor = textPrimary),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text("Select", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    val inUri = inputDirectoryUri
                                    val outUri = outputDirectoryUri
                                    if (inUri != null && outUri != null) {
                                        isBatchProcessing = true
                                        statusText = "Scanning input directory..."
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val inputDir = DocumentFile.fromTreeUri(this@MainActivity, inUri)
                                                    ?: throw Exception("Could not open input directory")
                                                val outputDir = DocumentFile.fromTreeUri(this@MainActivity, outUri)
                                                    ?: throw Exception("Could not open output directory")

                                                val files = inputDir.listFiles().filter {
                                                    it.isFile && (it.name?.endsWith(".wav", ignoreCase = true) == true ||
                                                            it.name?.endsWith(".mp3", ignoreCase = true) == true)
                                                }

                                                if (files.isEmpty()) {
                                                    withContext(Dispatchers.Main) {
                                                        statusText = "No WAV/MP3 files found in input directory."
                                                        isBatchProcessing = false
                                                    }
                                                    return@launch
                                                }

                                                var totalProcessed = 0
                                                var totalRemoved = 0L
                                                var totalInput = 0L
                                                var totalOutput = 0L
                                                var silentSegments = 0

                                                files.forEachIndexed { index, file ->
                                                    withContext(Dispatchers.Main) {
                                                        statusText = "Processing (${index + 1}/${files.size}):\n${file.name}"
                                                    }

                                                    val decoded = decodeAudioToPcm(file.uri)
                                                    val audioData = decoded.samples
                                                    val currentFileSampleRate = decoded.sampleRate

                                                    val config = HushConfig(
                                                        thresholdDb = selectedConfig.thresholdDb,
                                                        aggressionLevel = selectedConfig.aggressionLevel,
                                                        sampleRate = currentFileSampleRate
                                                    )

                                                    Hush(config).use { hush ->
                                                        val processed = hush.process(audioData)
                                                        val flushed = hush.flush()

                                                        val finalAudio = ShortArray(processed.size + flushed.size).apply {
                                                            processed.copyInto(this)
                                                            flushed.copyInto(this, processed.size)
                                                        }

                                                        val stats = hush.getStats()
                                                        totalInput += stats.totalInputSamples
                                                        totalOutput += stats.totalOutputSamples
                                                        totalRemoved += stats.totalRemovedSamples
                                                        silentSegments += stats.silentSegmentsDetected

                                                        // Save output as a clean WAV file, replacing any MP3 extension
                                                        val originalName = file.name ?: "audio_${index}"
                                                        val baseName = originalName.substringBeforeLast('.')
                                                        val outputName = "$baseName.wav"

                                                        val newFile = outputDir.createFile("audio/wav", outputName)
                                                            ?: throw Exception("Failed to create output file for ${file.name}")
                                                        val outputStream = contentResolver.openOutputStream(newFile.uri)
                                                            ?: throw Exception("Failed to write output file for ${file.name}")
                                                        
                                                        writeWavFile(outputStream, finalAudio, currentFileSampleRate)
                                                        totalProcessed++
                                                    }
                                                }

                                                val reduction = if (totalInput > 0) {
                                                    (totalRemoved.toDouble() / totalInput.toDouble()) * 100.0
                                                } else 0.0

                                                val resultText = """
                                                    Batch processing complete!
                                                    
                                                    Processed: $totalProcessed / ${files.size} files
                                                    Total Input: $totalInput samples
                                                    Total Output: $totalOutput samples
                                                    Removed: $totalRemoved samples
                                                    Avg Reduction: ${String.format("%.2f", reduction)}%
                                                    Total Silent Segments: $silentSegments
                                                """.trimIndent()

                                                withContext(Dispatchers.Main) {
                                                    statusText = resultText
                                                    isBatchProcessing = false
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    statusText = "Batch Error: ${e.message}"
                                                    isBatchProcessing = false
                                                }
                                                Log.e("Hush", "Error in batch processing", e)
                                            }
                                        }
                                    }
                                },
                                enabled = !isBatchProcessing && inputDirectoryUri != null && outputDirectoryUri != null,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentTeal,
                                    contentColor = blackBg,
                                    disabledContainerColor = Color(0xFF153335)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (isBatchProcessing) "Processing Batch..." else "Start Batch Process",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        "Recorder" -> {
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
                                            onCheckedChange = { useSilenceRemoval = it },
                                            enabled = !isRecording,
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = accentTeal,
                                                checkedTrackColor = Color(0xFF153335)
                                            )
                                        )
                                    }

                                    HorizontalDivider(color = Color(0xFF222222))

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
                                                text = if (isRecording) "${String.format("%.1f", recordDbLevel)} dB" else "— dB",
                                                color = if (isRecording && recordDbLevel > -40.0) accentTeal else textSecondary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        val dbNormalized = if (isRecording) {
                                            ((recordDbLevel + 80.0) / 80.0).coerceIn(0.0, 1.0).toFloat()
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

                                    if (isRecording || recordStats != null) {
                                        HorizontalDivider(color = Color(0xFF222222))

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
                                                Text(text = "Processed Duration:", color = textSecondary, fontSize = 12.sp)
                                                val inputSamples = recordStats?.totalInputSamples ?: 0L
                                                val seconds = inputSamples / 16000
                                                Text(
                                                    text = String.format("%02d:%02d", seconds / 60, seconds % 60),
                                                    color = textPrimary,
                                                    fontSize = 12.sp
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(text = "Reduction Percentage:", color = textSecondary, fontSize = 12.sp)
                                                Text(
                                                    text = "${String.format("%.1f", recordStats?.reductionPercentage ?: 0.0)}%",
                                                    color = accentTeal,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(text = "Silence Segments:", color = textSecondary, fontSize = 12.sp)
                                                Text(
                                                    text = "${recordStats?.silentSegmentsDetected ?: 0}",
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
                                                    text = "$recordPressure / 100",
                                                    color = if (recordPressure > 70) Color.Red else textPrimary,
                                                    fontSize = 12.sp
                                                )
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(text = "Degradation State:", color = textSecondary, fontSize = 12.sp)
                                                val stateText = when (recordDegradation) {
                                                    0 -> "Normal"
                                                    1 -> "Degraded"
                                                    2 -> "Emergency"
                                                    else -> "Unknown"
                                                }
                                                val stateColor = when (recordDegradation) {
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

                            val buttonText: String
                            val buttonColor: Color
                            val buttonTextColor: Color

                            if (!hasMicPermission) {
                                buttonText = "Grant Microphone Permission"
                                buttonColor = buttonBg
                                buttonTextColor = textPrimary
                            } else if (isRecording) {
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
                                    if (!hasMicPermission) {
                                        requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        if (isRecording) {
                                            audioRecorder?.let { recorder ->
                                                recorder.stop()
                                                recorder.close()
                                            }
                                            audioRecorder = null
                                            isRecording = false

                                            val recordedFile = java.io.File(cacheDir, "recorded_audio.wav")
                                            if (recordedFile.exists()) {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    try {
                                                        val decoded = decodeAudioToPcm(Uri.fromFile(recordedFile))
                                                        withContext(Dispatchers.Main) {
                                                            selectedFileUri = Uri.fromFile(recordedFile)
                                                            originalAudioPath = recordedFile.absolutePath
                                                            originalSamplesCount = decoded.samples.size
                                                            fileSampleRate = decoded.sampleRate
                                                            processedAudio = null
                                                            processedAudioPath = null
                                                            processedSamplesCount = 0
                                                            isPlaying = false
                                                            isPaused = false
                                                            currentPositionMs = 0f
                                                            playMode = "Original"
                                                            statusText = "Recording saved: ${recordedFile.name} (${originalSamplesCount} samples)\nRegistered into player. Ready for playback or Hush processing."
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("Hush", "Failed to register recorded file", e)
                                                    }
                                                }
                                            }
                                        } else {
                                            val recordedFile = java.io.File(cacheDir, "recorded_audio.wav")
                                            if (recordedFile.exists()) {
                                                recordedFile.delete()
                                            }
                                            try {
                                                val recorder = AudioRecorder(
                                                    context = this@MainActivity,
                                                    outputFile = recordedFile.absolutePath,
                                                    thresholdDb = selectedConfig.thresholdDb,
                                                    aggressionLevel = selectedConfig.aggressionLevel,
                                                    sampleRate = 16000,
                                                    useSilenceRemoval = useSilenceRemoval
                                                )
                                                val started = recorder.start()
                                                if (started) {
                                                    audioRecorder = recorder
                                                    isRecording = true
                                                    statusText = "Recording started to ${recordedFile.name}..."

                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        while (isRecording && audioRecorder != null) {
                                                            val recorderInstance = audioRecorder
                                                            if (recorderInstance != null) {
                                                                val db = recorderInstance.getCurrentDb()
                                                                val pressure = recorderInstance.getPressure()
                                                                val degradation = recorderInstance.getDegradationState()
                                                                val stats = recorderInstance.getStats()
                                                                withContext(Dispatchers.Main) {
                                                                    recordDbLevel = db
                                                                    recordPressure = pressure
                                                                    recordDegradation = degradation
                                                                    recordStats = stats
                                                                }
                                                            }
                                                            kotlinx.coroutines.delay(80)
                                                        }
                                                    }
                                                } else {
                                                    recorder.close()
                                                    statusText = "Failed to start native recording. Check audio settings."
                                                }
                                            } catch (e: Exception) {
                                                statusText = "Error: ${e.message}"
                                                Log.e("Hush", "Error starting recording", e)
                                            }
                                        }
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
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    private fun readWavPcmData(inputStream: InputStream): ShortArray {
        val buffer = ByteBuffer.wrap(inputStream.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        
        val riff = buffer.getInt()
        if (riff != 0x46464952) throw Exception("Not a RIFF file")
        buffer.getInt() // skip size
        val wave = buffer.getInt()
        if (wave != 0x45564157) throw Exception("Not a WAVE file")

        var dataPos = -1
        var dataSize = -1

        while (buffer.remaining() >= 8) {
            val chunkId = buffer.getInt()
            val chunkSize = buffer.getInt()
            if (chunkId == 0x61746164) { // "data"
                dataPos = buffer.position()
                dataSize = chunkSize
                break
            } else {
                try {
                    buffer.position(buffer.position() + chunkSize)
                } catch (e: Exception) {
                    throw Exception("Malformed WAV file: could not find data chunk")
                }
            }
        }

        if (dataPos == -1) throw Exception("No 'data' chunk found in WAV")

        val shorts = ShortArray(dataSize / 2)
        buffer.position(dataPos)
        buffer.asShortBuffer().get(shorts)
        return shorts
    }

    private fun writeWavFile(outputStream: OutputStream, audioData: ShortArray, sampleRate: Int) {
        val bytes = ByteBuffer.allocate(audioData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(audioData)
        val audioBytes = bytes.array()
        
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        
        header.putInt(0x46464952) // "RIFF"
        header.putInt(36 + audioBytes.size)
        header.putInt(0x45564157) // "WAVE"
        
        header.putInt(0x20746d66) // "fmt "
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        
        header.putInt(0x61746164) // "data"
        header.putInt(audioBytes.size)
        
        outputStream.use {
            it.write(header.array())
            it.write(audioBytes)
        }
    }

    private fun copyUriToCacheFile(uri: Uri, fileName: String): java.io.File {
        val tempFile = java.io.File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    private fun getDirectoryName(uri: Uri?): String {
        if (uri == null) return "Not Selected"
        val doc = DocumentFile.fromTreeUri(this, uri)
        return doc?.name ?: uri.lastPathSegment ?: "Selected Directory"
    }

    data class DecodedAudio(val samples: ShortArray, val sampleRate: Int)

    private fun decodeAudioToPcm(uri: Uri): DecodedAudio {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }

        var trackIndex = -1
        var format: android.media.MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = fmt
                break
            }
        }

        if (trackIndex == -1 || format == null) {
            extractor.release()
            throw Exception("No audio track found in this file")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
        val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)

        val codec = android.media.MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = android.media.MediaCodec.BufferInfo()
        val pcmData = java.util.ArrayList<Short>()
        var isEOS = false

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)
                if (buf != null) {
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            var outIndex = codec.dequeueOutputBuffer(info, 10000)
            while (outIndex >= 0) {
                val buf = codec.getOutputBuffer(outIndex)
                if (buf != null) {
                    val shortBuf = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val chunk = ShortArray(shortBuf.remaining())
                    shortBuf.get(chunk)

                    if (channels > 1) {
                        for (i in 0 until chunk.size step channels) {
                            var sum = 0
                            for (c in 0 until channels) {
                                if (i + c < chunk.size) {
                                    sum += chunk[i + c]
                                }
                            }
                            pcmData.add((sum / channels).toShort())
                        }
                    } else {
                        for (s in chunk) {
                            pcmData.add(s)
                        }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(info, 10000)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val shortArray = ShortArray(pcmData.size)
        for (i in 0 until pcmData.size) {
            shortArray[i] = pcmData[i]
        }

        return DecodedAudio(shortArray, sampleRate)
    }
}
