package myai.hush.engine

/**
 * Android JNI implementation of HushEngine.
 */
class HushAndroidEngine(val config: HushConfig) : HushEngine {
    private var engineHandle: Long = 0
    private var recorderHandle: Long = 0
    private var playerHandle: Long = 0
    private var cachedRecorderStats = HushStats(0, 0, 0, 0.0, 0)

    init {
        engineHandle = hushCreate(config.thresholdDb, config.aggressionLevel, config.sampleRate)
        playerHandle = playerCreate()
    }

    override fun process(input: ShortArray): ShortArray {
        if (engineHandle == 0L) return ShortArray(0)
        return hushProcess(engineHandle, input) ?: ShortArray(0)
    }

    override fun flush(): ShortArray {
        if (engineHandle == 0L) return ShortArray(0)
        return hushFlush(engineHandle) ?: ShortArray(0)
    }

    override fun getStats(): HushStats {
        if (engineHandle == 0L) return HushStats(0, 0, 0, 0.0, 0)
        val statsArray = hushGetStats(engineHandle) ?: return HushStats(0, 0, 0, 0.0, 0)
        return HushStats(
            totalInputSamples = statsArray[0].toLong(),
            totalOutputSamples = statsArray[1].toLong(),
            totalRemovedSamples = statsArray[2].toLong(),
            reductionPercentage = statsArray[3],
            silentSegmentsDetected = statsArray[4].toInt()
        )
    }

    override fun getCurrentDb(): Double {
        if (engineHandle == 0L) return -100.0
        return hushGetCurrentDb(engineHandle)
    }

    override fun play(filepath: String): Boolean {
        if (playerHandle == 0L) return false
        return playerPlay(playerHandle, filepath) != 0
    }

    override fun playBuffer(samples: FloatArray, count: Int, sampleRate: Int): Boolean {
        if (playerHandle == 0L) return false
        return playerPlayBuffer(playerHandle, samples, count, sampleRate) != 0
    }

    override fun togglePause() {
        if (playerHandle != 0L) playerTogglePause(playerHandle)
    }

    override fun stopPlayer() {
        if (playerHandle != 0L) playerStop(playerHandle)
    }

    override fun isPlaying(): Boolean {
        if (playerHandle == 0L) return false
        return playerIsPlaying(playerHandle) != 0
    }

    override fun isPlayerFinished(): Boolean {
        if (playerHandle == 0L) return false
        return playerIsFinished(playerHandle) != 0
    }

    override fun startRecorder(
        outputFile: String, 
        thresholdDb: Double, 
        aggressionLevel: Double, 
        sampleRate: Int, 
        useSilenceRemoval: Boolean
    ): Boolean {
        if (recorderHandle != 0L) {
            recorderDestroy(recorderHandle)
        }
        cachedRecorderStats = HushStats(0, 0, 0, 0.0, 0)
        recorderHandle = recorderCreate(
            outputFile, thresholdDb, aggressionLevel, sampleRate, if (useSilenceRemoval) 1 else 0
        )
        return if (recorderHandle != 0L) recorderStart(recorderHandle) != 0 else false
    }

    override fun stopRecorder() {
        if (recorderHandle != 0L) {
            cachedRecorderStats = getRecorderStats()
            recorderStop(recorderHandle)
            recorderDestroy(recorderHandle)
            recorderHandle = 0
        }
    }

    override fun isRecording(): Boolean {
        if (recorderHandle == 0L) return false
        return recorderIsRecording(recorderHandle) != 0
    }

    override fun getRecorderStats(): HushStats {
        if (recorderHandle == 0L) return cachedRecorderStats
        val statsArray = recorderGetStats(recorderHandle) ?: return cachedRecorderStats
        return HushStats(
            totalInputSamples = statsArray[0].toLong(),
            totalOutputSamples = statsArray[1].toLong(),
            totalRemovedSamples = statsArray[2].toLong(),
            reductionPercentage = statsArray[3],
            silentSegmentsDetected = statsArray[4].toInt()
        )
    }

    override fun getRecorderPressureLevel(): Int {
        if (recorderHandle == 0L) return 0
        return recorderGetPressure(recorderHandle)
    }

    override fun getRecorderDegradationState(): Int {
        if (recorderHandle == 0L) return 0
        return recorderGetDegradationState(recorderHandle)
    }

    override fun getRecorderCurrentDb(): Double {
        if (recorderHandle == 0L) return -100.0
        return recorderGetCurrentDb(recorderHandle)
    }

    override fun setLogLevel(level: Int) {
        hushSetLogLevel(level)
    }

    override fun calculateRmsDb(samples: ShortArray): Double {
        return hushCalculateRmsDb(samples)
    }

    override fun saveWav(filepath: String, samples: FloatArray, count: Int, sampleRate: Int): Boolean {
        return hushSaveWav(filepath, samples, count, sampleRate) != 0
    }

    override fun close() {
        if (engineHandle != 0L) {
            hushDestroy(engineHandle)
            engineHandle = 0
        }
        if (recorderHandle != 0L) {
            recorderStop(recorderHandle)
            recorderDestroy(recorderHandle)
            recorderHandle = 0
        }
        if (playerHandle != 0L) {
            playerDestroy(playerHandle)
            playerHandle = 0
        }
    }

    // JNI Native Methods
    private external fun hushCreate(thresholdDb: Double, aggressionLevel: Double, sampleRate: Int): Long
    private external fun hushProcess(handle: Long, input: ShortArray): ShortArray?
    private external fun hushFlush(handle: Long): ShortArray?
    private external fun hushGetStats(handle: Long): DoubleArray?
    private external fun hushDestroy(handle: Long)
    private external fun hushGetCurrentDb(handle: Long): Double

    private external fun recorderCreate(outputFile: String, thresholdDb: Double, aggressionLevel: Double, sampleRate: Int, useSilenceRemoval: Int): Long
    private external fun recorderStart(handle: Long): Int
    private external fun recorderStop(handle: Long)
    private external fun recorderDestroy(handle: Long)
    private external fun recorderIsRecording(handle: Long): Int
    private external fun recorderGetStats(handle: Long): DoubleArray?
    private external fun recorderGetPressure(handle: Long): Int
    private external fun recorderGetDegradationState(handle: Long): Int
    private external fun recorderGetCurrentDb(handle: Long): Double

    private external fun playerCreate(): Long
    private external fun playerDestroy(handle: Long)
    private external fun playerPlay(handle: Long, filepath: String): Int
    private external fun playerPlayBuffer(handle: Long, samples: FloatArray, count: Int, sampleRate: Int): Int
    private external fun playerTogglePause(handle: Long)
    private external fun playerStop(handle: Long)
    private external fun playerIsPlaying(handle: Long): Int
    private external fun playerIsFinished(handle: Long): Int

    private external fun hushSetLogLevel(level: Int)
    private external fun hushCalculateRmsDb(samples: ShortArray): Double
    private external fun hushSaveWav(filepath: String, samples: FloatArray, count: Int, sampleRate: Int): Int

    companion object {
        init {
            try {
                System.loadLibrary("hush_core")
                System.loadLibrary("hush_ffi")
                System.loadLibrary("myai_hush_android")
            } catch (e: UnsatisfiedLinkError) {
                // Handle loading failure
            }
        }
    }
}

/**
 * Android actual factory implementation.
 */
actual fun createHushEngine(config: HushConfig): HushEngine = HushAndroidEngine(config)
