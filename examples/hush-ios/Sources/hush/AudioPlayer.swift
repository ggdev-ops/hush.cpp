import Foundation

// MARK: - Audio Player (mirrors AudioPlayer.kt)

/// Wraps the hush_player_t C API for audio playback.
///
/// Android equivalent: class AudioPlayer : AutoCloseable
/// On Android, this calls JNI external fun audioPlayerCreate/audioPlayerPlay/etc.
/// On iOS, Swift calls C functions directly via the bridging header.
class AudioPlayer: @unchecked Sendable {
    private var handle: OpaquePointer?  // hush_player_t*

    // C11: failable init — a nil C handle must not crash the app
    init?() {
        handle = hush_player_create()
        if handle == nil {
            return nil
        }
    }

    /// Start playing an audio file.
    func play(filepath: String) -> Bool {
        guard let handle else { return false }
        return hush_player_play(handle, filepath) != 0
    }

    /// Start playing audio from a float buffer.
    func playBuffer(samples: [Float], sampleRate: Int32) -> Bool {
        guard let handle, !samples.isEmpty else { return false }

        return samples.withUnsafeBufferPointer { ptr in
            hush_player_play_buffer(
                handle,
                ptr.baseAddress,
                Int32(samples.count),
                sampleRate
            ) != 0
        }
    }

    /// Toggle pause state.
    func togglePause() {
        guard let handle else { return }
        hush_player_toggle_pause(handle)
    }

    /// Stop playing.
    func stop() {
        guard let handle else { return }
        hush_player_stop(handle)
    }

    /// Check if currently playing.
    func isPlaying() -> Bool {
        guard let handle else { return false }
        return hush_player_is_playing(handle) != 0
    }

    /// Check if playback is finished.
    func isFinished() -> Bool {
        guard let handle else { return true }
        return hush_player_is_finished(handle) != 0
    }

    /// Free resources.
    func close() {
        if let handle {
            hush_player_destroy(handle)
            self.handle = nil
        }
    }

    deinit {
        close()
    }
}
