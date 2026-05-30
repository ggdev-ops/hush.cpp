package myai.hush.engine

expect object HushUtils {
    /**
     * Set log level (0: DEBUG, 1: INFO, 2: WARN, 3: ERROR).
     */
    fun setLogLevel(level: Int)

    /**
     * Helper to calculate RMS dB of an arbitrary buffer.
     */
    fun calculateRmsDb(samples: ShortArray): Double

    /**
     * Helper to save a float buffer to a WAV file.
     */
    fun saveWav(filepath: String, samples: FloatArray, count: Int, sampleRate: Int): Boolean
}
