#include "core/SilenceDetector.h"
#include <algorithm>
#include <limits>

SilenceDetector::SilenceDetector(const HushConfig& cfg) : config(cfg) {
    int approx_fps = config.sampleRate / ANALYSIS_BLOCK_SIZE;
    if (approx_fps == 0) approx_fps = 1;
    
    minSilenceDurationFrames = static_cast<int>(0.5 * approx_fps / config.aggressionLevel);
    minActiveDurationFrames = static_cast<int>(0.1 * approx_fps * config.aggressionLevel);
    
    if (minSilenceDurationFrames == 0) minSilenceDurationFrames = 1;
    if (minActiveDurationFrames == 0) minActiveDurationFrames = 1;
    
    internalBuffer.reserve(ANALYSIS_BLOCK_SIZE);
}

double SilenceDetector::calculateRmsDb(const int16_t* samples, int numSamples) {
    if (numSamples == 0) return -std::numeric_limits<double>::infinity();
    double sum_sq = 0.0;
    for (int i = 0; i < numSamples; ++i) {
        sum_sq += (double)samples[i] * samples[i];
    }
    double mean_sq = sum_sq / numSamples;
    double rms = std::sqrt(mean_sq);
    return 20.0 * std::log10(rms / 32767.0);
}

void SilenceDetector::process(const int16_t* inSamples, int numInSamples, int16_t* outSamples, int& numOutSamples, int capacity) {
    int maxOut = numOutSamples; // Use the provided capacity
    numOutSamples = 0;
    totalInputSamples += numInSamples;

    int inputIdx = 0;
    while (inputIdx < numInSamples) {
        int spaceInInternal = ANALYSIS_BLOCK_SIZE - internalBuffer.size();
        int toCopy = std::min(spaceInInternal, numInSamples - inputIdx);
        
        internalBuffer.insert(internalBuffer.end(), inSamples + inputIdx, inSamples + inputIdx + toCopy);
        inputIdx += toCopy;

        if (internalBuffer.size() == ANALYSIS_BLOCK_SIZE) {
            int blockOutCount = 0;
            // Ensure we have enough space in outSamples
            if (numOutSamples + ANALYSIS_BLOCK_SIZE <= capacity) {
                processBlock(internalBuffer.data(), ANALYSIS_BLOCK_SIZE, outSamples + numOutSamples, blockOutCount);
                numOutSamples += blockOutCount;
            }
            internalBuffer.clear();
        }
    }
}

void SilenceDetector::processBlock(const int16_t* block, int size, int16_t* outSamples, int& numOutSamples) {
    numOutSamples = 0;
    double rmsDb = calculateRmsDb(block, size);
    lastDb = rmsDb;
    bool frameIsSilent = rmsDb <= config.thresholdDb;

    if (frameIsSilent) {
        silentFramesCount++;
        if (!isCurrentlySilent && silentFramesCount >= minSilenceDurationFrames) {
            isCurrentlySilent = true;
            activeFramesCount = 0;
            silentSegmentsCount++;
        }
    } else {
        activeFramesCount++;
        if (isCurrentlySilent && activeFramesCount >= minActiveDurationFrames) {
            isCurrentlySilent = false;
            silentFramesCount = 0;
        }
    }

    if (!isCurrentlySilent) {
        std::copy(block, block + size, outSamples);
        numOutSamples = size;
        totalOutputSamples += size;
    }
}

void SilenceDetector::flush(int16_t* outSamples, int& numOutSamples, int capacity) {
    numOutSamples = 0;
    if (!internalBuffer.empty() && (int)internalBuffer.size() <= capacity) {
        int blockOutCount = 0;
        processBlock(internalBuffer.data(), (int)internalBuffer.size(), outSamples, blockOutCount);
        numOutSamples = blockOutCount;
        internalBuffer.clear();
    }
}

HushStats SilenceDetector::getStats() const {
    HushStats stats;
    stats.totalInputSamples = totalInputSamples;
    stats.totalOutputSamples = totalOutputSamples;
    stats.totalRemovedSamples = totalInputSamples - totalOutputSamples;
    stats.reductionPercentage = (totalInputSamples > 0) 
        ? (1.0 - (double)totalOutputSamples / totalInputSamples) * 100.0 
        : 0.0;
    stats.silentSegmentsDetected = silentSegmentsCount;
    return stats;
}

double SilenceDetector::getCurrentDb() const {
    return lastDb;
}

