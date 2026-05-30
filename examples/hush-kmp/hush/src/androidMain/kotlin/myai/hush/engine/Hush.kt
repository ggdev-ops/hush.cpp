package myai.hush.engine

/**
 * Android JNI implementation of Hush.
 */
actual class Hush actual constructor(config: HushConfig) {
    private var handle: Long = 0

    init {
        handle = hushCreate(config.thresholdDb, config.aggressionLevel, config.sampleRate)
    }

    actual fun process(input: ShortArray): ShortArray {
        if (handle == 0L) return ShortArray(0)
        return hushProcess(handle, input) ?: ShortArray(0)
    }

    actual fun flush(): ShortArray {
        if (handle == 0L) return ShortArray(0)
        return hushFlush(handle) ?: ShortArray(0)
    }

    actual fun getStats(): HushStats {
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

    actual fun close() {
        if (handle != 0L) {
            hushDestroy(handle)
            handle = 0
        }
    }

    actual fun getCurrentDb(): Double {
        if (handle == 0L) return -100.0
        return hushGetCurrentDb(handle)
    }

    private external fun hushProcess(handle: Long, input: ShortArray): ShortArray?
    private external fun hushFlush(handle: Long): ShortArray?
    private external fun hushGetStats(handle: Long): DoubleArray?
    private external fun hushDestroy(handle: Long)
    private external fun hushGetCurrentDb(handle: Long): Double

    companion object {
        @JvmStatic
        private external fun hushCreate(thresholdDb: Double, aggressionLevel: Double, sampleRate: Int): Long
        
        init {
            try {
                System.loadLibrary("myai_hush_android")
            } catch (e: UnsatisfiedLinkError) {
                // Ignore if already loaded or not available (handled by caller usually)
            }
        }
    }
}

