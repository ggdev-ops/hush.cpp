#ifndef SILENCE_DETECTOR_H
#define SILENCE_DETECTOR_H

#include <cstdint>
#include <vector>
#include <cmath>

struct HushStats {
    long long totalInputSamples;
    long long totalOutputSamples;
    long long totalRemovedSamples;
    double reductionPercentage;
    int silentSegmentsDetected;
};

struct HushConfig {
    double thresholdDb;
    double aggressionLevel;
    int sampleRate;
};

class SilenceDetector {
public:
    SilenceDetector(const HushConfig& config);

    // Process a chunk of PCM samples. 
    // outSamples must be pre-allocated and large enough to hold inSamples.
    void process(const int16_t* inSamples, int numInSamples, int16_t* outSamples, int& numOutSamples);

    // Flush any pending samples in the state machine.
    void flush(int16_t* outSamples, int& numOutSamples);

    HushStats getStats() const;

    static double calculateRmsDb(const int16_t* samples, int numSamples);

private:
    HushConfig config;
    
    // Silence detection state
    bool isCurrentlySilent = true;
    int silentFramesCount = 0;
    int activeFramesCount = 0;

    int minSilenceDurationFrames;
    int minActiveDurationFrames;

    // Telemetry
    long long totalInputSamples = 0;
    long long totalOutputSamples = 0;
    int silentSegmentsCount = 0;

    // Internal "Frame" concept preservation (approx 26ms blocks at 44.1k = 1152 samples)
    // We will use a fixed block size for RMS analysis to ensure consistent behavior.
    static constexpr int ANALYSIS_BLOCK_SIZE = 1152; 
    std::vector<int16_t> internalBuffer;

    void processBlock(const int16_t* block, int size, int16_t* outSamples, int& numOutSamples);
};

#endif // SILENCE_DETECTOR_H
