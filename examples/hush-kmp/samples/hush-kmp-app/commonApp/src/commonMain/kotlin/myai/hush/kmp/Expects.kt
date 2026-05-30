package myai.hush.kmp

import kotlinx.coroutines.flow.StateFlow

interface MediaManager {
    fun getTempRecordingPath(name: String): String
    
    val playbackProgress: StateFlow<Float>
    val playbackTime: StateFlow<Long>
    val duration: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    
    suspend fun startPlayback(filePath: String)
    suspend fun stopPlayback()
    fun setVolume(volume: Float)
    fun seekTo(position: Long)
}

expect fun getMediaManager(context: Any? = null): MediaManager

