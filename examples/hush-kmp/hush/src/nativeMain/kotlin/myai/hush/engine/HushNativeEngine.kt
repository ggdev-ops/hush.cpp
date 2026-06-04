package myai.hush.engine

import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalNativeApi
import myai.hush.*
import cnames.structs.hush_engine_t
import cnames.structs.hush_recorder_t
import cnames.structs.hush_player_t

/**
 * Pure Kotlin Native implementation of HushEngine.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
class HushNativeEngine(val config: HushConfig) : HushEngine {
    private var engine: CPointer<hush_engine_t>?
    private var recorder: CPointer<hush_recorder_t>? = null
    private var player: CPointer<hush_player_t>? = null
    private var cachedRecorderStats = HushStats(0, 0, 0, 0.0, 0)

    init {
        engine = memScoped {
            val cConfig = alloc<hush_config_t>()
            cConfig.threshold_db = config.thresholdDb
            cConfig.aggression_level = config.aggressionLevel
            cConfig.sample_rate = config.sampleRate
            hush_engine_create(cConfig.readValue())
        }
        player = hush_player_create()
    }

    override fun process(input: ShortArray): ShortArray {
        val enginePtr = engine ?: return ShortArray(0)
        if (input.isEmpty()) return ShortArray(0)
        
        return memScoped {
            val inputPinned = input.pin()
            val outputBuffer = ShortArray(input.size)
            val outputPinned = outputBuffer.pin()
            val outputSamples = alloc<IntVar>()

            hush_engine_process(
                enginePtr,
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

    override fun flush(): ShortArray {
        val enginePtr = engine ?: return ShortArray(0)
        
        return memScoped {
            val outputBuffer = ShortArray(4096)
            val outputPinned = outputBuffer.pin()
            val outputSamples = alloc<IntVar>()

            hush_engine_flush(
                enginePtr,
                outputPinned.addressOf(0).reinterpret(),
                outputSamples.ptr
            )

            val result = outputBuffer.copyOf(outputSamples.value)
            outputPinned.unpin()
            result
        }
    }

    override fun getStats(): HushStats {
        val enginePtr = engine ?: return HushStats(0, 0, 0, 0.0, 0)
        val stats = hush_engine_get_stats(enginePtr)
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

    override fun getCurrentDb(): Double {
        val enginePtr = engine ?: return -100.0
        return hush_engine_get_current_db(enginePtr)
    }

    override fun play(filepath: String): Boolean {
        val playerPtr = player ?: return false
        return hush_player_play(playerPtr, filepath) != 0
    }

    override fun playBuffer(samples: FloatArray, count: Int, sampleRate: Int): Boolean {
        val playerPtr = player ?: return false
        if (samples.isEmpty()) return false
        return samples.usePinned { pinned ->
            hush_player_play_buffer(playerPtr, pinned.addressOf(0).reinterpret(), count, sampleRate) != 0
        }
    }

    override fun togglePause() {
        player?.let { hush_player_toggle_pause(it) }
    }

    override fun stopPlayer() {
        player?.let { hush_player_stop(it) }
    }

    override fun isPlaying(): Boolean {
        val playerPtr = player ?: return false
        return hush_player_is_playing(playerPtr) != 0
    }

    override fun isPlayerFinished(): Boolean {
        val playerPtr = player ?: return false
        return hush_player_is_finished(playerPtr) != 0
    }

    override fun startRecorder(
        outputFile: String, 
        thresholdDb: Double, 
        aggressionLevel: Double, 
        sampleRate: Int, 
        useSilenceRemoval: Boolean
    ): Boolean {
        if (recorder != null) {
            hush_recorder_destroy(recorder)
            recorder = null
        }
        
        cachedRecorderStats = HushStats(0, 0, 0, 0.0, 0)
        recorder = memScoped {
            val cConfig = alloc<hush_recorder_config_t>()
            cConfig.output_file = outputFile.cstr.getPointer(this)
            cConfig.threshold_db = thresholdDb
            cConfig.aggression_level = aggressionLevel
            cConfig.sample_rate = sampleRate
            cConfig.use_silence_removal = if (useSilenceRemoval) 1 else 0
            hush_recorder_create(cConfig.readValue())
        }
        
        return recorder?.let { hush_recorder_start(it) != 0 } ?: false
    }

    override fun stopRecorder() {
        recorder?.let { 
            cachedRecorderStats = getRecorderStats()
            hush_recorder_stop(it)
            hush_recorder_destroy(it)
            recorder = null
        }
    }

    override fun isRecording(): Boolean {
        val recorderPtr = recorder ?: return false
        return hush_recorder_is_recording(recorderPtr) != 0
    }

    override fun getRecorderStats(): HushStats {
        val recorderPtr = recorder ?: return cachedRecorderStats
        val stats = hush_recorder_get_stats(recorderPtr)
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

    override fun getRecorderPressureLevel(): Int {
        val recorderPtr = recorder ?: return 0
        return hush_recorder_get_pressure(recorderPtr)
    }

    override fun getRecorderDegradationState(): Int {
        val recorderPtr = recorder ?: return 0
        return hush_recorder_get_degradation_state(recorderPtr)
    }

    override fun getRecorderCurrentDb(): Double {
        val recorderPtr = recorder ?: return -100.0
        return hush_recorder_get_current_db(recorderPtr)
    }

    override fun setLogLevel(level: Int) {
        hush_set_log_level(level)
    }

    override fun calculateRmsDb(samples: ShortArray): Double {
        if (samples.isEmpty()) return -100.0
        return samples.usePinned { pinned ->
            hush_calculate_rms_db(pinned.addressOf(0).reinterpret(), samples.size)
        }
    }

    override fun saveWav(filepath: String, samples: FloatArray, count: Int, sampleRate: Int): Boolean {
        if (samples.isEmpty() || count <= 0) return false
        val result = samples.usePinned { pinned ->
            hush_save_wav(filepath, pinned.addressOf(0).reinterpret(), count, sampleRate)
        }
        return result != 0
    }

    override fun close() {
        engine?.let { hush_engine_destroy(it) }
        engine = null
        recorder?.let { 
            hush_recorder_stop(it)
            hush_recorder_destroy(it) 
        }
        recorder = null
        player?.let { hush_player_destroy(it) }
        player = null
    }
}

/**
 * Native actual factory implementation.
 */
actual fun createHushEngine(config: HushConfig): HushEngine = HushNativeEngine(config)
