package klama.hush.engine

import java.io.File

/**
 * JVM JNI implementation of Hush.
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

    private external fun hushProcess(handle: Long, input: ShortArray): ShortArray?
    private external fun hushFlush(handle: Long): ShortArray?
    private external fun hushGetStats(handle: Long): DoubleArray?
    private external fun hushDestroy(handle: Long)
    private external fun hushCreate(thresholdDb: Double, aggressionLevel: Double, sampleRate: Int): Long

    companion object {
        init {
            loadNativeLibrary()
        }

        private fun loadNativeLibrary() {
            try {
                System.loadLibrary("klama_hush_jni")
                println("[Hush] Loaded klama_hush_jni from system library path")
            } catch (e: UnsatisfiedLinkError) {
                println("[Hush] klama_hush_jni not found in java.library.path, attempting to extract from JAR...")
                
                val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "klama_hush_native")
                if (!tmpDir.exists()) tmpDir.mkdirs()

                val libs = listOf(
                    "libklama_hush_jni.so"
                )
                
                libs.forEach { libName ->
                    val resourcePath = "/bin/$libName"
                    val resourceStream = Hush::class.java.getResourceAsStream(resourcePath)
                        ?: return@forEach
                    
                    val outFile = java.io.File(tmpDir, libName)
                    if (outFile.exists()) {
                        outFile.delete()
                    }
                    try {
                        resourceStream.use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (ioe: java.io.IOException) {
                        println("[Hush] Failed to extract $libName: ${ioe.message}")
                    }
                }
                
                try {
                    val loadIfExist = { name: String ->
                        val f = java.io.File(tmpDir, name)
                        if (f.exists()) {
                            try {
                                System.load(f.absolutePath)
                                true
                            } catch (le: UnsatisfiedLinkError) { false }
                        } else false
                    }
                    
                    loadIfExist("libklama_hush_jni.so")
                    println("[Hush] Loaded native libraries from temporary directory")
                } catch (le: UnsatisfiedLinkError) {
                    println("[Hush] Failed to load extracted libraries: ${le.message}")
                }
            }
        }
    }
}

