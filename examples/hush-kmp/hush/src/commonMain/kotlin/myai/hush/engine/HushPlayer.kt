package myai.hush.engine

/**
 * High-performance audio player wrapper.
 * Provides low-latency real-time audio playback using the C++ miniaudio engine.
 */
expect class HushPlayer() {
    /**
     * Start playing an audio file.
     */
    fun play(filepath: String): Boolean

    /**
     * Start playing audio from a float buffer.
     */
    fun playBuffer(samples: FloatArray, count: Int, sampleRate: Int): Boolean

    /**
     * Toggle pause state of the player.
     */
    fun togglePause()

    /**
     * Stop playing.
     */
    fun stop()

    /**
     * Check if the player is currently playing.
     */
    fun isPlaying(): Boolean

    /**
     * Check if playback is finished.
     */
    fun isFinished(): Boolean

    /**
     * Destroy the player instance.
     */
    fun close()
}
