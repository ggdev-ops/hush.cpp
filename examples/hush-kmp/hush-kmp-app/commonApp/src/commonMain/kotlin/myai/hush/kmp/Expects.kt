package myai.hush.kmp

import kotlinx.coroutines.flow.StateFlow

interface MediaManager {
    fun getTempRecordingPath(name: String): String
    suspend fun startPlayback(filePath: String)
    suspend fun stopPlayback()
    fun setVolume(volume: Float)
    fun seekTo(position: Long)
    
    val playbackProgress: StateFlow<Float>
    val playbackTime: StateFlow<Long>
    val duration: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
}

interface AudioPermissionRequester {
    fun isPermissionGranted(): Boolean
    fun RequestPermission(onResult: (Boolean) -> Unit)
}
