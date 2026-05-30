package myai.hush.engine

import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalNativeApi
import myai.hush.*
import myai.hush.engine.*
import cnames.structs.hush_engine_t

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual class Hush actual constructor(config: HushConfig) {
    private val engine: CPointer<hush_engine_t>?

    init {
        engine = memScoped {
            val cConfig = alloc<hush_config_t>()
            cConfig.threshold_db = config.thresholdDb
            cConfig.aggression_level = config.aggressionLevel
            cConfig.sample_rate = config.sampleRate
            hush_engine_create(cConfig.readValue())
        }
    }

    actual fun process(input: ShortArray): ShortArray {
        if (engine == null) return ShortArray(0)
        
        return memScoped {
            val inputPinned = input.pin()
            val outputBuffer = ShortArray(input.size)
            val outputPinned = outputBuffer.pin()
            val outputSamples = alloc<IntVar>()

            hush_engine_process(
                engine,
                inputPinned.addressOf(0).reinterpret(),
                input.size,
                outputPinned.addressOf(0).reinterpret(),
                outputSamples.ptr
            )

            inputPinned.unpin()
            val result = outputBuffer.copyOf(outputSamples.value)
            outputPinned.unpin()
            result
        }
    }

    actual fun flush(): ShortArray {
        if (engine == null) return ShortArray(0)
        
        return memScoped {
            // Flush might return up to a few thousand samples (e.g. 1152 * factor)
            val outputBuffer = ShortArray(4096)
            val outputPinned = outputBuffer.pin()
            val outputSamples = alloc<IntVar>()

            hush_engine_flush(
                engine,
                outputPinned.addressOf(0).reinterpret(),
                outputSamples.ptr
            )

            val result = outputBuffer.copyOf(outputSamples.value)
            outputPinned.unpin()
            result
        }
    }

    actual fun getStats(): HushStats {
        if (engine == null) return HushStats(0, 0, 0, 0.0, 0)
        
        val stats = hush_engine_get_stats(engine)
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

    actual fun getCurrentDb(): Double {
        if (engine == null) return -100.0
        return hush_engine_get_current_db(engine)
    }

    actual fun close() {
        if (engine != null) {
            hush_engine_destroy(engine)
        }
    }
}

