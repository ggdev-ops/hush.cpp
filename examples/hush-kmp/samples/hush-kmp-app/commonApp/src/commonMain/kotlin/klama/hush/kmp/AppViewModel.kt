package klama.hush.kmp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import klama.hush.audio.AudioRecorder
import klama.hush.engine.Hush
import klama.hush.engine.HushConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class AppState {
    object Idle : AppState()
    object Recording : AppState()
    object Processing : AppState()
    data class Error(val message: String) : AppState()
}

enum class HushPreset(val label: String, val threshold: Double, val aggression: Double) {
    GENTLE("Gentle", -45.0, 0.2),
    DEFAULT("Default", -35.0, 0.5),
    AGGRESSIVE("Aggressive", -25.0, 0.9)
}

class AppViewModel(
    private val recorder: AudioRecorder,
    private val mediaManager: MediaManager
) : ViewModel() {

    private val _state = MutableStateFlow<AppState>(AppState.Idle)
    val state: StateFlow<AppState> = _state

    private val _originalFile = MutableStateFlow<String?>(null)
    val originalFile: StateFlow<String?> = _originalFile

    private val _hushedFile = MutableStateFlow<String?>(null)
    val hushedFile: StateFlow<String?> = _hushedFile

    val isPlaying: StateFlow<Boolean> = mediaManager.isPlaying
    val playbackProgress: StateFlow<Float> = mediaManager.playbackProgress
    val playbackTime: StateFlow<Long> = mediaManager.playbackTime
    val duration: StateFlow<Long> = mediaManager.duration

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume

    private val _selectedPreset = MutableStateFlow(HushPreset.DEFAULT)
    val selectedPreset: StateFlow<HushPreset> = _selectedPreset

    private val _lastReduction = MutableStateFlow<Double?>(null)
    val lastReduction: StateFlow<Double?> = _lastReduction

    fun setPreset(preset: HushPreset) {
        _selectedPreset.value = preset
        _lastReduction.value = null
    }

    fun setVolume(volume: Float) {
        _volume.value = volume
        mediaManager.setVolume(volume)
    }

    fun seekTo(progress: Float) {
        val position = (progress * duration.value).toLong()
        mediaManager.seekTo(position)
    }

    fun toggleRecording() {
        viewModelScope.launch {
            if (_state.value is AppState.Recording) {
                recorder.stopRecording()
                _state.value = AppState.Idle
            } else {
                val path = mediaManager.getTempRecordingPath("original.wav")
                _originalFile.value = path
                _hushedFile.value = null
                _lastReduction.value = null
                recorder.startRecording(path)
                _state.value = AppState.Recording
            }
        }
    }

    fun playOriginal() {
        val path = _originalFile.value ?: return
        viewModelScope.launch {
            mediaManager.startPlayback(path)
            mediaManager.setVolume(_volume.value)
        }
    }

    fun playHushed() {
        val path = _hushedFile.value ?: return
        viewModelScope.launch {
            mediaManager.startPlayback(path)
            mediaManager.setVolume(_volume.value)
        }
    }

    fun applyHush() {
        val inputPath = _originalFile.value ?: return
        val preset = _selectedPreset.value

        viewModelScope.launch(Dispatchers.IO) {
            _state.value = AppState.Processing
            _lastReduction.value = null
            try {
                val outputPath = mediaManager.getTempRecordingPath("hushed.wav")
                val pcm = readWavPcm(inputPath) ?: throw Exception("Failed to read WAV")
                
                println("[Hush] Applying ${preset.label} (Threshold: ${preset.threshold}, Aggression: ${preset.aggression})")
                
                val config = HushConfig(
                    thresholdDb = preset.threshold,
                    aggressionLevel = preset.aggression,
                    sampleRate = 16000
                )
                val hush = Hush(config)
                val processed = hush.process(pcm)
                val flushed = hush.flush()
                val finalPcm = ShortArray(processed.size + flushed.size)
                processed.copyInto(finalPcm)
                flushed.copyInto(finalPcm, processed.size)
                
                val stats = hush.getStats()
                println("[Hush] Done. Reduction: ${stats.reductionPercentage}%")
                _lastReduction.value = stats.reductionPercentage
                hush.close()

                writeWavPcm(outputPath, finalPcm)
                _hushedFile.value = outputPath
                _state.value = AppState.Idle
            } catch (e: Exception) {
                println("[Hush] Error: ${e.message}")
                _state.value = AppState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun readWavPcm(path: String): ShortArray? {
        val file = File(path)
        if (!file.exists()) return null
        val raf = RandomAccessFile(file, "r")
        // Skip header (simplification)
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
        val file = File(path)
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
    
    fun stopPlayback() {
        viewModelScope.launch {
            mediaManager.stopPlayback()
        }
    }
}

