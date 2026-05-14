#ifndef AUDIO_PROCESSOR_H
#define AUDIO_PROCESSOR_H

#include <string>
#include <vector>
#include <cstdint>
#include <memory>
#include "core/SilenceDetector.h"

// Forward declarations for FFmpeg structures
struct AVFormatContext;
struct AVCodecContext;
struct SwrContext;
struct AVFrame;
struct AVPacket;
struct AVStream;

// LEGACY COMPATIBILITY BRIDGE
// This class acts as a wrapper around FFmpeg and the new SilenceDetector core.
// It is intended for CLI usage and will eventually be deprecated in favor of 
// direct SilenceDetector or FFI usage.

struct ProcessingSummary {
    double inputDuration;
    double outputDuration;
    double reductionPercentage;
    long long total_input_frames;
    long long total_output_frames;
};

class AudioProcessor {
public:
    AudioProcessor();
    ~AudioProcessor();

    // LEGACY: Process method signature for CLI compatibility
    ProcessingSummary process(const std::string& inputFilePath, const std::string& outputFilePath,
                              double silenceThresholdDb, double aggressionLevel, bool dryRun = false);

private:
    bool openInputFile(const std::string& inputFile);
    bool initializeDecoder();
    bool initializeResampler();
    void closeResources();

    void detectAndRemoveSilence(AVFrame* decodedFrame);

    AVFormatContext* inFormatCtx = nullptr;
    AVCodecContext* inCodecCtx = nullptr;
    int audioStreamIndex = -1;

    SwrContext* swrCtx = nullptr; // Resampler context for silence detection (input to S16 mono)
    SwrContext* encoderSwrCtx = nullptr; // Resampler context for encoding (input to encoder format)

    AVFormatContext* outputFormatContext = nullptr;
    AVCodecContext* outputCodecContext = nullptr;
    AVStream* outAudioStream = nullptr;

    std::unique_ptr<SilenceDetector> detector;

    bool encoderSetupDone = false;
    bool dryRun = false;
    long long totalEncodedSamples = 0;
    std::vector<int16_t> encoderBuffer;

    bool setupEncoder(const std::string& outputFilePath);
    void closeEncoder();
};

#endif // AUDIO_PROCESSOR_H

