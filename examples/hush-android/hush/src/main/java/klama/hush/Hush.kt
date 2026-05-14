package klama.hush

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

class Hush(config: HushConfig) : AutoCloseable {
    private var handle: Long = 0

    init {
        handle = hushCreate(config.thresholdDb, config.aggressionLevel, config.sampleRate)
        if (handle == 0L) {
            throw IllegalStateException("Failed to create Hush engine")
        }
    }

    fun process(input: ShortArray): ShortArray {
        if (handle == 0L) return ShortArray(0)
        return hushProcess(handle, input) ?: ShortArray(0)
    }

    fun flush(): ShortArray {
        if (handle == 0L) return ShortArray(0)
        return hushFlush(handle) ?: ShortArray(0)
    }

    fun getStats(): HushStats {
        if (handle == 0L) return HushStats(0, 0, 0, 0.0, 0)
        val statsArray = hushGetStats(handle) ?: return HushStats(0, 0, 0, 0.0, 0)
        return HushStats(
            totalInputSamples = statsArray[0].toLong(),
            totalOutputSamples = statsArray[1].toLong(),
            totalRemovedSamples = statsArray[2].toLong(),
            reductionPercentage = statsArray[3],
            silentSegmentsDetected = statsArray[4].toInt()
        )
    }

    override fun close() {
        if (handle != 0L) {
            hushDestroy(handle)
            handle = 0
        }
    }

    private external fun hushProcess(handle: Long, input: ShortArray): ShortArray?
    private external fun hushFlush(handle: Long): ShortArray?
    private external fun hushGetStats(handle: Long): DoubleArray?
    private external fun hushDestroy(handle: Long)

    companion object {
        @JvmStatic
        private external fun hushCreate(thresholdDb: Double, aggressionLevel: Double, sampleRate: Int): Long
        
        init {
            System.loadLibrary("klama_hush_android")
        }
    }
}

