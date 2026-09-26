import AVFoundation

// MARK: - Audio Decoder
// Decodes any audio file (WAV, MP3, M4A) to S16 PCM via AVAudioFile.
// Android equivalent: MediaExtractor + MediaCodec in MainActivity.kt.
// iOS has no MediaCodec — AVAudioFile is the native path.

struct DecodedAudio {
    let samples: [Int16]
    let sampleRate: Int32
    let channelCount: Int32
}

enum AudioDecoder {

    /// Decode a file URL to S16 mono PCM.
    /// Stereo files are downmixed by averaging channels.
    static func decode(fileAt url: URL) throws -> DecodedAudio {
        let audioFile = try AVAudioFile(forReading: url)

        let format = audioFile.processingFormat
        let sourceFrameCount = AVAudioFrameCount(audioFile.length)
        guard sourceFrameCount > 0 else {
            throw AudioDecoderError.emptyFile
        }

        // Read into a float buffer (AVAudioFile's native format)
        guard let buffer = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: sourceFrameCount
        ) else {
            throw AudioDecoderError.bufferAllocFailed
        }
        try audioFile.read(into: buffer)

        let sourceChannels = Int(format.channelCount)
        let frameCount = Int(buffer.frameLength)

        guard let floatData = buffer.floatChannelData else {
            throw AudioDecoderError.noFloatData
        }

        // Downmix to mono and convert to Int16
        var monoSamples = [Int16](repeating: 0, count: frameCount)
        for frame in 0..<frameCount {
            var sum: Float = 0
            for ch in 0..<sourceChannels {
                sum += floatData[ch][frame]
            }
            let avg = sum / Float(sourceChannels)

            // Clamp to [-1.0, 1.0] then scale to Int16 range
            let clamped = max(-1.0, min(1.0, avg))
            monoSamples[frame] = Int16(clamped * 32767.0)
        }

        return DecodedAudio(
            samples: monoSamples,
            sampleRate: Int32(format.sampleRate),
            channelCount: 1
        )
    }
}

enum AudioDecoderError: LocalizedError {
    case emptyFile
    case bufferAllocFailed
    case noFloatData

    var errorDescription: String? {
        switch self {
        case .emptyFile: return "Audio file is empty"
        case .bufferAllocFailed: return "Failed to allocate audio buffer"
        case .noFloatData: return "No float channel data in audio buffer"
        }
    }
}
