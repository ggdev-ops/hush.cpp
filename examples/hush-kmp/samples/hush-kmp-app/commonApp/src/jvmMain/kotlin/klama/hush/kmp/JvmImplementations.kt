package klama.hush.kmp

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineEvent

class JvmMediaManager : MediaManager {
    private var clip: Clip? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var progressJob: Job? = null

    private val _playbackProgress = MutableStateFlow(0f)
    override val playbackProgress: StateFlow<Float> = _playbackProgress

    private val _playbackTime = MutableStateFlow(0L)
    override val playbackTime: StateFlow<Long> = _playbackTime

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    override fun getTempRecordingPath(name: String): String {
        val tempDir = System.getProperty("java.io.tmpdir")
        return File(tempDir, name).absolutePath
    }

    override suspend fun startPlayback(filePath: String): Unit = withContext(Dispatchers.IO) {
        stopPlaybackInternal()
        try {
            val audioInputStream = AudioSystem.getAudioInputStream(File(filePath))
            clip = AudioSystem.getClip().apply {
                open(audioInputStream)
                addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP && _isPlaying.value) {
                        if (framePosition >= frameLength) {
                           stopPlaybackInternal()
                        }
                    }
                }
                _duration.value = microsecondLength / 1000
                start()
                _isPlaying.value = true
            }
            startPolling()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                clip?.let { c ->
                    val current = c.microsecondPosition / 1000
                    val total = _duration.value
                    _playbackTime.value = current
                    if (total > 0) {
                        _playbackProgress.value = current.toFloat() / total.toFloat()
                    }
                }
                delay(100)
            }
        }
    }

    override suspend fun stopPlayback() = withContext(Dispatchers.IO) {
        stopPlaybackInternal()
    }

    override fun setVolume(volume: Float) {
        try {
            val gainControl = clip?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl
            if (gainControl != null) {
                // Convert linear 0.0-1.0 to dB
                val dB = (java.lang.Math.log10(volume.coerceAtLeast(0.0001f).toDouble()) * 20.0).toFloat()
                gainControl.value = dB.coerceIn(gainControl.minimum, gainControl.maximum)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun seekTo(position: Long) {
        clip?.microsecondPosition = position * 1000
    }

    private fun stopPlaybackInternal() {
        progressJob?.cancel()
        progressJob = null
        _isPlaying.value = false
        _playbackProgress.value = 0f
        _playbackTime.value = 0L
        
        clip?.stop()
        clip?.close()
        clip = null
    }
}

actual fun getMediaManager(context: Any?): MediaManager {
    return JvmMediaManager()
}

