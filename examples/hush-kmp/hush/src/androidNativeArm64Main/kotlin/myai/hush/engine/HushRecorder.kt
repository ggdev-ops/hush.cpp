package myai.hush.engine

import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalNativeApi
import myai.hush.*
import cnames.structs.hush_recorder_t

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual class HushRecorder actual constructor(
    actual val outputFile: String,
    actual val thresholdDb: Double,
    actual val aggressionLevel: Double,
    actual val sampleRate: Int,
    actual val useSilenceRemoval: Boolean
) {
    private val recorder: CPointer<hush_recorder_t>?

    init {
        recorder = memScoped {
            val cConfig = alloc<hush_recorder_config_t>()
            cConfig.output_file = outputFile.cstr.getPointer(this)
            cConfig.threshold_db = thresholdDb
            cConfig.aggression_level = aggressionLevel
            cConfig.sample_rate = sampleRate
            cConfig.use_silence_removal = if (useSilenceRemoval) 1 else 0
            hush_recorder_create(cConfig.readValue())
        }
    }

    actual fun start(): Boolean {
        if (recorder == null) return false
        return hush_recorder_start(recorder) != 0
    }

    actual fun stop() {
        if (recorder != null) {
            hush_recorder_stop(recorder)
        }
    }

    actual fun isRecording(): Boolean {
        if (recorder == null) return false
        return hush_recorder_is_recording(recorder) != 0
    }

    actual fun getStats(): HushStats {
        if (recorder == null) return HushStats(0, 0, 0, 0.0, 0)
        val stats = hush_recorder_get_stats(recorder)
        return stats.useContents {
            HushStats(
                totalInputSamples = total_input_samples,
                totalOutputSamples = total_output_samples,
                totalRemovedSamples = total_removed_samples,
                reductionPercentage = reduction_percentage,
                silentSegmentsDetected = silent_segments_detected
            )
        }
    }

    actual fun getPressureLevel(): Int {
        if (recorder == null) return 0
        return hush_recorder_get_pressure(recorder)
    }

    actual fun getDegradationState(): Int {
        if (recorder == null) return 0
        return hush_recorder_get_degradation_state(recorder)
    }

    actual fun getCurrentDb(): Double {
        if (recorder == null) return -100.0
        return hush_recorder_get_current_db(recorder)
    }

    actual fun close() {
        if (recorder != null) {
            hush_recorder_destroy(recorder)
        }
    }
}
