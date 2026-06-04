package myai.hush.engine

/**
 * Configuration for the Hush! engine.
 */
data class HushConfig(
    val thresholdDb: Double = -25.0,
    val aggressionLevel: Double = 0.75,
    val sampleRate: Int = 16000
)

/**
 * Statistics from the Hush! engine or recorder.
 */
data class HushStats(
    val totalInputSamples: Long,
    val totalOutputSamples: Long,
    val totalRemovedSamples: Long,
    val reductionPercentage: Double,
    val silentSegmentsDetected: Int
)

/**
 * Unified interface for the Hush! audio engine, including recording, playback, and processing.
 */
interface HushEngine {
    // Core Engine Processing
    fun process(input: ShortArray): ShortArray
    fun flush(): ShortArray
    fun getStats(): HushStats
    fun getCurrentDb(): Double
    
    // Player
    fun play(filepath: String): Boolean
    fun playBuffer(samples: FloatArray, count: Int, sampleRate: Int): Boolean
    fun togglePause()
    fun stopPlayer()
    fun isPlaying(): Boolean
    fun isPlayerFinished(): Boolean
    
    // Recorder
    fun startRecorder(
        outputFile: String, 
        thresholdDb: Double = -40.0, 
        aggressionLevel: Double = 1.0, 
        sampleRate: Int = 16000, 
        useSilenceRemoval: Boolean = true
    ): Boolean
    fun stopRecorder()
    fun isRecording(): Boolean
    fun getRecorderStats(): HushStats
    fun getRecorderPressureLevel(): Int
    fun getRecorderDegradationState(): Int
    fun getRecorderCurrentDb(): Double
    
    // Utilities
    fun setLogLevel(level: Int)
    fun calculateRmsDb(samples: ShortArray): Double
    fun saveWav(filepath: String, samples: FloatArray, count: Int, sampleRate: Int): Boolean
    
    // Lifecycle
    fun close()
}

/**
 * Factory method to create a platform-specific HushEngine.
 */
expect fun createHushEngine(config: HushConfig): HushEngine
