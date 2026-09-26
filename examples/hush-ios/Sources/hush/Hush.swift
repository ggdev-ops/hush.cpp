import Foundation

// MARK: - Data Types (mirrors HushConfig and HushStats from Hush.kt)

struct HushConfig {
    var thresholdDb: Double = -30.0
    var aggressionLevel: Double = 0.5
    var sampleRate: Int32 = 16000
}

struct HushStats {
    var totalInputSamples: Int64
    var totalOutputSamples: Int64
    var totalRemovedSamples: Int64
    var reductionPercentage: Double
    var silentSegmentsDetected: Int32
}

// MARK: - Hush Engine (mirrors Hush.kt)

/// Wraps the hush_engine_t C API for silence detection.
///
/// Android equivalent: class Hush(config: HushConfig) : AutoCloseable
/// On Android, this calls JNI external fun hushCreate/hushProcess/etc.
/// On iOS, Swift calls C functions directly via the bridging header.
class Hush: @unchecked Sendable {
    private var handle: OpaquePointer?  // hush_engine_t*

    // C11: failable init — a nil C handle must not crash the app
    init?(config: HushConfig) {
        let cConfig = hush_config_t(
            threshold_db: config.thresholdDb,
            aggression_level: config.aggressionLevel,
            sample_rate: config.sampleRate
        )
        handle = hush_engine_create(cConfig)
        if handle == nil {
            return nil
        }
    }

    /// Process a chunk of S16 Mono PCM samples.
    /// Returns the processed samples with silence removed.
    func process(input: [Int16]) -> [Int16] {
        guard let handle, !input.isEmpty else { return [] }

        let inputCount = Int32(input.count)
        var output = [Int16](repeating: 0, count: input.count)
        var outputCount = Int32(input.count)

        return output.withUnsafeMutableBufferPointer { outPtr in
            input.withUnsafeBufferPointer { inPtr in
                hush_engine_process(
                    handle,
                    inPtr.baseAddress,
                    inputCount,
                    outPtr.baseAddress,
                    &outputCount
                )
            }
            return Array(outPtr.prefix(Int(outputCount)))
        }
    }

    /// Flush pending samples and final state.
    func flush() -> [Int16] {
        guard let handle else { return [] }

        let capacity = 4096
        var output = [Int16](repeating: 0, count: capacity)
        var outputCount = Int32(capacity)

        return output.withUnsafeMutableBufferPointer { outPtr in
            hush_engine_flush(
                handle,
                outPtr.baseAddress,
                &outputCount
            )
            return Array(outPtr.prefix(Int(outputCount)))
        }
    }

    /// Get current processing statistics.
    func getStats() -> HushStats {
        guard let handle else {
            return HushStats(totalInputSamples: 0, totalOutputSamples: 0,
                            totalRemovedSamples: 0, reductionPercentage: 0,
                            silentSegmentsDetected: 0)
        }

        let cStats = hush_engine_get_stats(handle)
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
        return hush_engine_get_current_db(handle)
    }

    /// Free resources.
    func close() {
        if let handle {
            hush_engine_destroy(handle)
            self.handle = nil
        }
    }

    deinit {
        close()
    }
}
