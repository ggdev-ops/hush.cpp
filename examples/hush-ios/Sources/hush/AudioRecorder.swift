import Foundation

// MARK: - Audio Recorder (mirrors AudioRecorder.kt)

/// Wraps the hush_recorder_t C API for microphone recording with silence detection.
///
/// Android equivalent: class AudioRecorder(private val context: Context, ...) : AutoCloseable
/// On Android, this has permission checking (RECORD_AUDIO) and JNI calls.
/// On iOS, permissions are handled at the app level (AVAudioSession).
/// Swift calls C functions directly via the bridging header.
class AudioRecorder: @unchecked Sendable {
    private var handle: OpaquePointer?  // hush_recorder_t*
    private var cachedStats = HushStats(totalInputSamples: 0, totalOutputSamples: 0,
                                       totalRemovedSamples: 0, reductionPercentage: 0,
                                       silentSegmentsDetected: 0)

    // C11: failable init — a nil C handle must not crash the app
    init?(outputFile: String,
          thresholdDb: Double = -30.0,
          aggressionLevel: Double = 0.5,
          sampleRate: Int32 = 16000,
          useSilenceRemoval: Bool = true) {

        let config = hush_recorder_config_t(
            output_file: (outputFile as NSString).utf8String,
            threshold_db: thresholdDb,
            aggression_level: aggressionLevel,
            sample_rate: sampleRate,
            use_silence_removal: useSilenceRemoval ? 1 : 0
        )
        handle = hush_recorder_create(config)
        if handle == nil {
            return nil
        }
    }

    /// Start recording.
    func start() -> Bool {
        guard let handle else { return false }
        return hush_recorder_start(handle) != 0
    }

    /// Stop recording.
    func stop() {
        guard let handle else { return }
        cachedStats = getStats()
        hush_recorder_stop(handle)
    }

    /// Check if currently recording.
    func isRecording() -> Bool {
        guard let handle else { return false }
        return hush_recorder_is_recording(handle) != 0
    }

    /// Get recorder statistics.
    func getStats() -> HushStats {
        guard let handle else { return cachedStats }

        let cStats = hush_recorder_get_stats(handle)
        return HushStats(
            totalInputSamples: cStats.total_input_samples,
            totalOutputSamples: cStats.total_output_samples,
            totalRemovedSamples: cStats.total_removed_samples,
            reductionPercentage: cStats.reduction_percentage,
            silentSegmentsDetected: cStats.silent_segments_detected
        )
    }

    /// Get the current audio level in dB.
    func getCurrentDb() -> Double {
        guard let handle else { return -100.0 }
        return hush_recorder_get_current_db(handle)
    }

    /// Get current buffer pressure level (0-100).
    func getPressure() -> Int32 {
        guard let handle else { return 0 }
        return hush_recorder_get_pressure(handle)
    }

    /// Get current degradation state (0: NORMAL, 1: DEGRADED, 2: EMERGENCY).
    func getDegradationState() -> Int32 {
        guard let handle else { return 0 }
        return hush_recorder_get_degradation_state(handle)
    }

    /// Free resources.
    func close() {
        if let handle {
            hush_recorder_destroy(handle)
            self.handle = nil
        }
    }

    deinit {
        close()
    }
}
