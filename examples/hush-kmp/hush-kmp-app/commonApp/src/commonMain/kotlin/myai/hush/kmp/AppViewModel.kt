package myai.hush.kmp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import myai.hush.engine.HushConfig
import myai.hush.engine.HushEngine
import myai.hush.engine.createHushEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class RecorderState {
    object Idle : RecorderState()
    object Recording : RecorderState()
    class Error(val message: String) : RecorderState()
}

sealed class ProcessingState {
    object Idle : ProcessingState()
    object Processing : ProcessingState()
    class Success(val reduction: Double, val segments: Int, val outputPath: String) : ProcessingState()
    class Error(val message: String) : ProcessingState()
}

class AppViewModel(
    val mediaManager: MediaManager
) : ViewModel() {
    private var activeEngine: HushEngine? = null
    private var telemetryJob: Job? = null
    
    // Tab Navigation: 0 = Recorder, 1 = File Processor, 2 = Player
    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab

    fun setTab(tab: Int) {
        _currentTab.value = tab
        stopPlayback()
    }

    // Recorder Config & State
    private val _recorderState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val recorderState: StateFlow<RecorderState> = _recorderState

    val thresholdDb = MutableStateFlow(-25.0)
    val aggressionLevel = MutableStateFlow(0.75)
    val useSilenceRemoval = MutableStateFlow(true)
    
    // Recorder Telemetry
    val currentDb = MutableStateFlow(-100.0)
    val pressureLevel = MutableStateFlow(0)
    val degradationState = MutableStateFlow(0)
    val recordingSeconds = MutableStateFlow(0)

    private val _recordedFilePath = MutableStateFlow<String?>(null)
    val recordedFilePath: StateFlow<String?> = _recordedFilePath

    // File Processor State
    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState

    val processInputPath = MutableStateFlow("")
    val processThresholdDb = MutableStateFlow(-25.0)
    val processAggression = MutableStateFlow(0.75)

    // Player State delegates to mediaManager
    val isPlaying: StateFlow<Boolean> = mediaManager.isPlaying
    val playbackProgress: StateFlow<Float> = mediaManager.playbackProgress
    val playbackTime: StateFlow<Long> = mediaManager.playbackTime
    val duration: StateFlow<Long> = mediaManager.duration
    val volume = MutableStateFlow(0.8f)

    init {
        mediaManager.setVolume(volume.value)
    }

    fun setVolume(vol: Float) {
        volume.value = vol
        mediaManager.setVolume(vol)
    }

    fun seekTo(progress: Float) {
        mediaManager.seekTo((progress * duration.value).toLong())
    }

    fun toggleRecording() {
        viewModelScope.launch {
            if (_recorderState.value is RecorderState.Recording) {
                stopRecordingInternal()
            } else {
                startRecordingInternal()
            }
        }
    }

    private suspend fun startRecordingInternal() {
        val path = mediaManager.getTempRecordingPath("recording.wav")
        _recordedFilePath.value = path
        recordingSeconds.value = 0
        currentDb.value = -100.0
        pressureLevel.value = 0
        degradationState.value = 0
        
        try {
            val config = HushConfig(
                thresholdDb = thresholdDb.value,
                aggressionLevel = aggressionLevel.value,
                sampleRate = 16000
            )
            val engine = createHushEngine(config)
            val success = engine.startRecorder(
                outputFile = path,
                thresholdDb = config.thresholdDb,
                aggressionLevel = config.aggressionLevel,
                sampleRate = config.sampleRate,
                useSilenceRemoval = useSilenceRemoval.value
            )
            if (success) {
                activeEngine = engine
                _recorderState.value = RecorderState.Recording
                startTelemetryPolling(engine)
            } else {
                engine.close()
                _recorderState.value = RecorderState.Error("Failed to start recorder device")
            }
        } catch (e: Exception) {
            _recorderState.value = RecorderState.Error(e.message ?: "Initialization error")
        }
    }

    private suspend fun stopRecordingInternal() {
        telemetryJob?.cancel()
        telemetryJob = null
        activeEngine?.let { engine ->
            engine.stopRecorder()
            engine.close()
        }
        activeEngine = null
        _recorderState.value = RecorderState.Idle
    }

    private fun startTelemetryPolling(engine: HushEngine) {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch(Dispatchers.Default) {
            var ms = 0
            while (isActive) {
                delay(100)
                currentDb.value = engine.getRecorderCurrentDb()
                pressureLevel.value = engine.getRecorderPressureLevel()
                degradationState.value = engine.getRecorderDegradationState()
                ms += 100
                if (ms % 1000 == 0) {
                    recordingSeconds.value = ms / 1000
                }
            }
        }
    }

    fun playRecorded() {
        val path = _recordedFilePath.value ?: return
        viewModelScope.launch {
            mediaManager.startPlayback(path)
        }
    }

    fun startPlayback(path: String) {
        viewModelScope.launch {
            mediaManager.startPlayback(path)
        }
    }

    fun stopPlayback() {
        viewModelScope.launch {
            mediaManager.stopPlayback()
        }
    }

    fun applyProcessFile() {
        val inputPath = processInputPath.value
        if (inputPath.isBlank()) {
            _processingState.value = ProcessingState.Error("Input file path cannot be empty")
            return
        }
        
        val file = File(inputPath)
        if (!file.exists()) {
            _processingState.value = ProcessingState.Error("Input file does not exist")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _processingState.value = ProcessingState.Processing
            try {
                val outputPath = mediaManager.getTempRecordingPath("processed.wav")
                val pcm = readWavPcm(inputPath) ?: throw Exception("Failed to parse WAV PCM")

                val config = HushConfig(
                    thresholdDb = processThresholdDb.value,
                    aggressionLevel = processAggression.value,
                    sampleRate = 16000
                )
                val hush = createHushEngine(config)
                try {
                    val processed = hush.process(pcm)
                    val flushed = hush.flush()
                    val finalPcm = ShortArray(processed.size + flushed.size)
                    processed.copyInto(finalPcm)
                    flushed.copyInto(finalPcm, processed.size)

                    val stats = hush.getStats()
                    writeWavPcm(outputPath, finalPcm)
                    
                    _processingState.value = ProcessingState.Success(
                        reduction = stats.reductionPercentage,
                        segments = stats.silentSegmentsDetected,
                        outputPath = outputPath
                    )
                } finally {
                    hush.close()
                }
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error(e.message ?: "Processing failed")
            }
        }
    }

    private fun readWavPcm(path: String): ShortArray? {
        val file = File(path)
        if (!file.exists()) return null
        val raf = RandomAccessFile(file, "r")
        raf.seek(44)
        val pcmSize = (raf.length() - 44).toInt()
        val bytes = ByteArray(pcmSize)
        raf.readFully(bytes)
        raf.close()

        val shorts = ShortArray(pcmSize / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun writeWavPcm(path: String, pcm: ShortArray) {
        val output = File(path).outputStream()
        val totalAudioBytes = pcm.size * 2
        val totalDataLen = totalAudioBytes + 36
        val sampleRate = 16000
        val byteRate = (sampleRate * 2).toLong()

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(1.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate.toInt())
        buffer.putShort(2.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioBytes)

        output.write(header)
        val byteBuffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.asShortBuffer().put(pcm)
        output.write(byteBuffer.array())
        output.close()
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            stopRecordingInternal()
            stopPlayback()
        }
    }
}
