package myai.hush.engine

actual object HushUtils {
    actual fun setLogLevel(level: Int) {
        hushSetLogLevel(level)
    }

    actual fun calculateRmsDb(samples: ShortArray): Double {
        return hushCalculateRmsDb(samples)
    }

    actual fun saveWav(filepath: String, samples: FloatArray, count: Int, sampleRate: Int): Boolean {
        return hushSaveWav(filepath, samples, count, sampleRate) != 0
    }

    @JvmStatic
    private external fun hushSetLogLevel(level: Int)
    @JvmStatic
    private external fun hushCalculateRmsDb(samples: ShortArray): Double
    @JvmStatic
    private external fun hushSaveWav(filepath: String, samples: FloatArray, count: Int, sampleRate: Int): Int

    init {
        try {
            System.loadLibrary("myai_hush_android")
        } catch (e: UnsatisfiedLinkError) {
            // Ignore
        }
    }
}
