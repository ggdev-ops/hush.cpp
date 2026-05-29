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

    // Cache linear amplitude threshold from dB configured threshold
    linearThreshold = 32767.0 * std::pow(10.0, config.thresholdDb / 20.0);
}

double SilenceDetector::calculateRmsDb(const int16_t* samples, int numSamples) {
    if (numSamples == 0) return -std::numeric_limits<double>::infinity();
    int64_t sum_sq = 0;
    for (int i = 0; i < numSamples; ++i) {
        int64_t s = samples[i];
        sum_sq += s * s;
    }
    double mean_sq = static_cast<double>(sum_sq) / numSamples;
    double rms = std::sqrt(mean_sq);
    return 20.0 * std::log10(rms / 32767.0);
}

void SilenceDetector::process(const int16_t* inSamples, int numInSamples, int16_t* outSamples, int& numOutSamples, int capacity, FlowState flowState) {
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
                processBlock(internalBuffer.data(), ANALYSIS_BLOCK_SIZE, outSamples + numOutSamples, blockOutCount, flowState);
                numOutSamples += blockOutCount;
            }
            internalBuffer.clear();
        }
    }
}

void SilenceDetector::processBlock(const int16_t* block, int size, int16_t* outSamples, int& numOutSamples, FlowState flowState) {
    numOutSamples = 0;
    bool frameIsSilent = true;

    if (flowState == FlowState::NORMAL) {
        double rmsDb = calculateRmsDb(block, size);
        lastDb = rmsDb;
        frameIsSilent = (rmsDb <= config.thresholdDb);
    } 
    else if (flowState == FlowState::DEGRADED) {
        // Subsampled RMS: skip every second sample to reduce arithmetic calculations by 50%
        if (size == 0) {
            lastDb = -std::numeric_limits<double>::infinity();
            frameIsSilent = true;
        } else {
            int64_t sum_sq = 0;
            int count = 0;
            for (int i = 0; i < size; i += 2) {
                int64_t s = block[i];
                sum_sq += s * s;
                count++;
            }
            double mean_sq = static_cast<double>(sum_sq) / count;
            double rms = std::sqrt(mean_sq);
            double rmsDb = 20.0 * std::log10(rms / 32767.0);
            lastDb = rmsDb;
            frameIsSilent = (rmsDb <= config.thresholdDb);
        }
    } 
    else if (flowState == FlowState::EMERGENCY) {
        // Cheap subsampled peak-envelope threshold check (skip to check every 4th sample)
        int16_t peak = 0;
        for (int i = 0; i < size; i += 4) {
            int16_t val = std::abs(block[i]);
            if (val > peak) peak = val;
        }

        frameIsSilent = (static_cast<double>(peak) <= linearThreshold);

        // Estimate dB for telemetry
        if (peak > 0) {
            lastDb = 20.0 * std::log10(static_cast<double>(peak) / 32767.0);
        } else {
            lastDb = -100.0;
        }
    }

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
        processBlock(internalBuffer.data(), (int)internalBuffer.size(), outSamples, blockOutCount, FlowState::NORMAL);
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
