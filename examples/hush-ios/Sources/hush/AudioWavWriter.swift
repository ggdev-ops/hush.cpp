import AVFoundation

// MARK: - WAV Writer
// Writes S16 mono PCM samples to a .wav file via AVAudioFile.
// Used in Single File mode to save processed output.

enum AudioWavWriter {

    /// Write S16 mono PCM to a WAV file at the given URL.
    static func write(
        samples: [Int16],
        sampleRate: Int32,
        to outputURL: URL
    ) throws {
        guard !samples.isEmpty else { throw WavWriterError.emptySamples }

        let format = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(sampleRate),
            channels: 1,
            interleaved: true
        )!

        let frameCount = AVAudioFrameCount(samples.count)
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: frameCount
        ) else {
            throw WavWriterError.bufferAllocFailed
        }

        buffer.frameLength = frameCount

        // AVAudioPCMBuffer with Int16 interleaved format gives us direct channel data access
        guard let int16Data = buffer.int16ChannelData else {
            throw WavWriterError.noInt16Data
        }
        for i in 0..<samples.count {
            int16Data[0][i] = samples[i]
        }

        let file = try AVAudioFile(
            forWriting: outputURL,
            settings: format.settings,
            commonFormat: .pcmFormatInt16,
            interleaved: true
        )
        try file.write(from: buffer)
    }
}

enum WavWriterError: LocalizedError {
    case emptySamples
    case bufferAllocFailed
    case noInt16Data

    var errorDescription: String? {
        switch self {
        case .emptySamples: return "No samples to write"
        case .bufferAllocFailed: return "Failed to allocate audio buffer"
        case .noInt16Data: return "No Int16 channel data in audio buffer"
        }
    }
}
