package myai.hush.engine

actual class HushRecorder actual constructor(
    actual val outputFile: String,
    actual val thresholdDb: Double,
    actual val aggressionLevel: Double,
    actual val sampleRate: Int,
    actual val useSilenceRemoval: Boolean
) {
    private var handle: Long = 0

    init {
        handle = recorderCreate(
            outputFile,
            thresholdDb,
            aggressionLevel,
            sampleRate,
            if (useSilenceRemoval) 1 else 0
        )
        if (handle == 0L) {
            throw IllegalStateException("Failed to create Hush recorder")
        }
    }

    actual fun start(): Boolean {
        if (handle == 0L) return false
        return recorderStart(handle) != 0
    }

    actual fun stop() {
        if (handle != 0L) {
            recorderStop(handle)
        }
    }

    actual fun isRecording(): Boolean {
        if (handle == 0L) return false
        return recorderIsRecording(handle) != 0
    }

    actual fun getStats(): HushStats {
        if (handle == 0L) return HushStats(0, 0, 0, 0.0, 0)
        val statsArray = recorderGetStats(handle) ?: return HushStats(0, 0, 0, 0.0, 0)
        return HushStats(
            totalInputSamples = statsArray[0].toLong(),
            totalOutputSamples = statsArray[1].toLong(),
            totalRemovedSamples = statsArray[2].toLong(),
            reductionPercentage = statsArray[3],
            silentSegmentsDetected = statsArray[4].toInt()
        )
    }

    actual fun getPressureLevel(): Int {
        if (handle == 0L) return 0
        return recorderGetPressure(handle)
    }

    actual fun getDegradationState(): Int {
        if (handle == 0L) return 0
        return recorderGetDegradationState(handle)
    }

    actual fun close() {
        if (handle != 0L) {
            recorderDestroy(handle)
            handle = 0
        }
    }

    actual fun getCurrentDb(): Double {
        if (handle == 0L) return -100.0
        return recorderGetCurrentDb(handle)
    }

    private external fun recorderStart(handle: Long): Int
    private external fun recorderStop(handle: Long)
    private external fun recorderIsRecording(handle: Long): Int
    private external fun recorderGetStats(handle: Long): DoubleArray?
    private external fun recorderDestroy(handle: Long)
    private external fun recorderGetPressure(handle: Long): Int
    private external fun recorderGetDegradationState(handle: Long): Int
    private external fun recorderGetCurrentDb(handle: Long): Double

    companion object {
        @JvmStatic
        private external fun recorderCreate(
            outputFile: String,
            thresholdDb: Double,
            aggressionLevel: Double,
            sampleRate: Int,
            useSilenceRemoval: Int
        ): Long

        init {
            // Trigger Hush companion loader to extract and load libmyai_hush_jni
            val dummy = Hush.Companion
        }
    }
}
