package klama.hush.engine

class HushRecorder(
    val outputFile: String,
    val thresholdDb: Double = -40.0,
    val aggressionLevel: Double = 1.0,
    val sampleRate: Int = 16000,
    val useSilenceRemoval: Boolean = true
) : AutoCloseable {
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

    fun start(): Boolean {
        if (handle == 0L) return false
        return recorderStart(handle) != 0
    }

    fun stop() {
        if (handle != 0L) {
            recorderStop(handle)
        }
    }

    fun isRecording(): Boolean {
        if (handle == 0L) return false
        return recorderIsRecording(handle) != 0
    }

    fun getStats(): HushStats {
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

    override fun close() {
        if (handle != 0L) {
            recorderDestroy(handle)
            handle = 0
        }
    }

    private external fun recorderStart(handle: Long): Int
    private external fun recorderStop(handle: Long)
    private external fun recorderIsRecording(handle: Long): Int
    private external fun recorderGetStats(handle: Long): DoubleArray?
    private external fun recorderDestroy(handle: Long)

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
            // Load the library using the same logic as Hush class
            HushLoader.load()
        }
    }
}

/**
 * Internal helper to ensure the library is loaded only once.
 */
internal object HushLoader {
    private var loaded = false
    fun load() {
        if (loaded) return
        try {
            System.loadLibrary("klama_hush_jni")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            // Extraction logic same as in Hush.kt
            val libName = System.mapLibraryName("klama_hush_jni")
            val resourceStream = HushRecorder::class.java.classLoader.getResourceAsStream(libName)
            if (resourceStream != null) {
                val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "klama_hush_native")
                if (!tmpDir.exists()) tmpDir.mkdirs()
                val outFile = java.io.File(tmpDir, libName)
                if (outFile.exists()) outFile.delete()
                try {
                    resourceStream.use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    System.load(outFile.absolutePath)
                    loaded = true
                } catch (ex: Exception) {
                    System.err.println("[Hush] Failed to extract and load native library: ${ex.message}")
                }
            }
        }
    }
}
