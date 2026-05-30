package myai.hush.engine

/**
 * High-performance native audio recorder wrapper.
 * Records audio using silence detection and decouples capture from processing.
 */
expect class HushRecorder(
    outputFile: String,
    thresholdDb: Double = -40.0,
    aggressionLevel: Double = 1.0,
    sampleRate: Int = 16000,
    useSilenceRemoval: Boolean = true
) {
    val outputFile: String
    val thresholdDb: Double
    val aggressionLevel: Double
    val sampleRate: Int
    val useSilenceRemoval: Boolean

    /**
     * Start recording.
     */
    fun start(): Boolean

    /**
     * Stop recording.
     */
    fun stop()

    /**
     * Check if currently recording.
     */
    fun isRecording(): Boolean

    /**
     * Get recorder statistics.
     */
    fun getStats(): HushStats

    /**
     * Expose real-time buffer pressure (0-100).
     */
    fun getPressureLevel(): Int

    /**
     * Expose current degradation state (0: NORMAL, 1: DEGRADED, 2: EMERGENCY).
     */
    fun getDegradationState(): Int

    /**
     * Get the current audio level in dB from the recorder.
     */
    fun getCurrentDb(): Double

    /**
     * Release all native resources.
     */
    fun close()
}
