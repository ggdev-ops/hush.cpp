package klama.hush

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class AudioRecorder(
    private val context: Context,
    val outputFile: String,
    val thresholdDb: Double = -30.0,
    val aggressionLevel: Double = 0.5,
    val sampleRate: Int = 16000,
    val useSilenceRemoval: Boolean = true
) : AutoCloseable {

    private var handle: Long = 0

    init {
        handle = audioRecorderCreate(
            outputFile,
            thresholdDb,
            aggressionLevel,
            sampleRate,
            useSilenceRemoval
        )
        if (handle == 0L) {
            throw IllegalStateException("Failed to create AudioRecorder")
        }
    }

    /**
     * Helper function to check if RECORD_AUDIO permission is granted.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts recording. Handles permission safety check.
     * Returns true if successfully started, false otherwise.
     */
    fun start(): Boolean {
        if (handle == 0L) return false
        if (!hasPermission()) {
            // Safety guard: library handles checking permission results
            // and refuses to start native recorder if permission is missing, avoiding crashes.
            return false
        }
        return audioRecorderStart(handle)
    }

    fun stop() {
        if (handle == 0L) return
        audioRecorderStop(handle)
    }

    fun isRecording(): Boolean {
        if (handle == 0L) return false
        return audioRecorderIsRecording(handle)
    }

    fun getStats(): HushStats {
        if (handle == 0L) return HushStats(0, 0, 0, 0.0, 0)
        val statsArray = audioRecorderGetStats(handle) ?: return HushStats(0, 0, 0, 0.0, 0)
        return HushStats(
            totalInputSamples = statsArray[0].toLong(),
            totalOutputSamples = statsArray[1].toLong(),
            totalRemovedSamples = statsArray[2].toLong(),
            reductionPercentage = statsArray[3],
            silentSegmentsDetected = statsArray[4].toInt()
        )
    }

    fun getCurrentDb(): Double {
        if (handle == 0L) return -100.0
        return audioRecorderGetCurrentDb(handle)
    }

    fun getPressure(): Int {
        if (handle == 0L) return 0
        return audioRecorderGetPressure(handle)
    }

    fun getDegradationState(): Int {
        if (handle == 0L) return 0
        return audioRecorderGetDegradationState(handle)
    }

    override fun close() {
        if (handle != 0L) {
            audioRecorderDestroy(handle)
            handle = 0
        }
    }

    private external fun audioRecorderStart(handle: Long): Boolean
    private external fun audioRecorderStop(handle: Long)
    private external fun audioRecorderIsRecording(handle: Long): Boolean
    private external fun audioRecorderGetStats(handle: Long): DoubleArray?
    private external fun audioRecorderDestroy(handle: Long)
    private external fun audioRecorderGetCurrentDb(handle: Long): Double
    private external fun audioRecorderGetPressure(handle: Long): Int
    private external fun audioRecorderGetDegradationState(handle: Long): Int

    companion object {
        @JvmStatic
        private external fun audioRecorderCreate(
            outputFile: String,
            thresholdDb: Double,
            aggressionLevel: Double,
            sampleRate: Int,
            useSilenceRemoval: Boolean
        ): Long

        init {
            try {
                System.loadLibrary("hush_core")
                System.loadLibrary("hush_ffi")
            } catch (e: UnsatisfiedLinkError) {
                // Ignore if already loaded or not found
            }
            System.loadLibrary("klama_hush_android")
        }
    }
}
