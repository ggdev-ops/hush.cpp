package klama.hush.kmp

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import klama.hush.audio.AndroidAudioRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class AndroidMediaManager(private val context: Context) : MediaManager {
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
        val dir = context.cacheDir
        return File(dir, name).absolutePath
    }

    override suspend fun startPlayback(filePath: String) = withContext(Dispatchers.Main) {
        stopPlaybackInternal()

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                java.io.FileInputStream(filePath).use { fis ->
                    setDataSource(fis.fd)
                }
                prepare()
                _duration.value = duration.toLong()
                start()
                _isPlaying.value = true
                
                setOnCompletionListener {
                    stopPlaybackInternal()
                }
            }

            startPolling()
        } catch (e: Exception) {
            e.printStackTrace()
            stopPlaybackInternal()
        }
    }

    private fun startPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        val current = mp.currentPosition.toLong()
                        val total = _duration.value
                        _playbackTime.value = current
                        if (total > 0) {
                            _playbackProgress.value = current.toFloat() / total.toFloat()
                        }
                    }
                }
                delay(100)
            }
        }
    }

    override suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        stopPlaybackInternal()
    }

    override fun setVolume(volume: Float) {
        mediaPlayer?.setVolume(volume, volume)
    }

    override fun seekTo(position: Long) {
        mediaPlayer?.seekTo(position.toInt())
    }

    private fun stopPlaybackInternal() {
        progressJob?.cancel()
        progressJob = null
        _isPlaying.value = false
        _playbackProgress.value = 0f
        _playbackTime.value = 0L
        
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

actual fun getMediaManager(context: Any?): MediaManager {
    return AndroidMediaManager(context as Context)
}

