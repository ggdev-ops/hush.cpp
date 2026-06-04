package myai.hush.kmp

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidMediaManager(private val context: Context) : MediaManager {
    private var mediaPlayer: MediaPlayer? = null
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
        return File(context.cacheDir, name).absolutePath
    }

    override suspend fun startPlayback(filePath: String): Unit = withContext(Dispatchers.Main) {
        stopPlaybackInternal()
        try {
            val mp = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                setOnCompletionListener {
                    stopPlaybackInternal()
                }
                start()
            }
            mediaPlayer = mp
            _duration.value = mp.duration.toLong()
            _isPlaying.value = true
            startPolling()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                withContext(Dispatchers.Main) {
                    mediaPlayer?.let { mp ->
                        try {
                            if (mp.isPlaying) {
                                val current = mp.currentPosition.toLong()
                                val total = _duration.value
                                _playbackTime.value = current
                                if (total > 0) {
                                    _playbackProgress.value = current.toFloat() / total.toFloat()
                                }
                            }
                        } catch (ignored: Exception) {}
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
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (ignored: Exception) {}
        mediaPlayer = null
        _isPlaying.value = false
        _playbackProgress.value = 0f
        _playbackTime.value = 0L
    }
}

class AndroidAudioPermissionRequester(private val activity: ComponentActivity) : AudioPermissionRequester {
    private val permission = android.Manifest.permission.RECORD_AUDIO
    private var callback: ((Boolean) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        callback?.invoke(isGranted)
        callback = null
    }

    override fun isPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun RequestPermission(onResult: (Boolean) -> Unit) {
        if (isPermissionGranted()) {
            onResult(true)
            return
        }
        callback = onResult
        launcher.launch(permission)
    }
}
