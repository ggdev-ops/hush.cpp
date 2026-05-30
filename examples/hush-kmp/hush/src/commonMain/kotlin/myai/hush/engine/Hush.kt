package myai.hush.engine

/**
 * High-level wrapper for the Hush! silence detection engine.
 */
expect class Hush(config: HushConfig) {
    /**
     * Process a chunk of S16 Mono PCM samples.
     * Returns the processed (clean) PCM samples.
     */
    fun process(input: ShortArray): ShortArray

    /**
     * Flush pending samples and final state.
     * Returns any remaining processed PCM samples.
     */
    fun flush(): ShortArray

    /**
     * Get current processing statistics.
     */
    fun getStats(): HushStats

    /**
     * Get the current audio level in dB from the silence detector engine.
     */
    fun getCurrentDb(): Double

    /**
     * Close the engine and release resources.
     */
    fun close()
}

data class HushConfig(
    val thresholdDb: Double = -30.0,
    val aggressionLevel: Double = 0.5,
    val sampleRate: Int = 16000
)

data class HushStats(
    val totalInputSamples: Long,
    val totalOutputSamples: Long,
    val totalRemovedSamples: Long,
    val reductionPercentage: Double,
    val silentSegmentsDetected: Int
)

