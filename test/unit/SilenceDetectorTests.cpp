#include "gtest/gtest.h"
#include "core/SilenceDetector.h"
#include <vector>
#include <algorithm>
#include <chrono>
#include <iostream>
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class SilenceDetectorTest : public ::testing::Test {
protected:
    HushConfig config;

    void SetUp() override {
        config.thresholdDb = -30.0;
        config.aggressionLevel = 1.0;
        config.sampleRate = 44100;
    }
};

TEST_F(SilenceDetectorTest, InitialStateIsSilent) {
    SilenceDetector detector(config);
    EXPECT_EQ(detector.getCurrentDb(), -100.0);
}

TEST_F(SilenceDetectorTest, ProcessSilenceDoesNotOutput) {
    SilenceDetector detector(config);
    std::vector<int16_t> inSamples(2000, 0);
    std::vector<int16_t> outSamples(2000);
    int numOut = 0;

    detector.process(inSamples.data(), inSamples.size(), outSamples.data(), numOut, outSamples.size());
    EXPECT_EQ(numOut, 0);
    EXPECT_GT(detector.getStats().totalRemovedSamples, 0);
}

TEST_F(SilenceDetectorTest, ProcessSoundOutputsAfterActivation) {
    SilenceDetector detector(config);
    // Loud samples (half max amplitude)
    std::vector<int16_t> inSamples(5000, 16000); 
    std::vector<int16_t> outSamples(10000);
    int numOut = 0;

    detector.process(inSamples.data(), inSamples.size(), outSamples.data(), numOut, outSamples.size());
    
    // Activation takes some time (minActiveDurationFrames)
    // At 44100, block size 1152, approx 38 fps.
    // minActiveDurationFrames = 0.1 * 38 * 1.0 = 3.8 -> 4 frames.
    // 4 frames * 1152 = 4608 samples.
    // So 5000 samples should be enough to trigger output for at least one block.
    
    EXPECT_GT(numOut, 0);
    EXPECT_EQ(detector.getStats().totalOutputSamples, numOut);
}

TEST_F(SilenceDetectorTest, FlushOutputsPendingSamples) {
    SilenceDetector detector(config);
    // Loud samples, but less than block size
    std::vector<int16_t> inSamples(500, 16000);
    std::vector<int16_t> outSamples(1000);
    int numOut = 0;

    detector.process(inSamples.data(), inSamples.size(), outSamples.data(), numOut, outSamples.size());
    EXPECT_EQ(numOut, 0); // Still in internal buffer

    detector.flush(outSamples.data(), numOut, outSamples.size());
}

TEST_F(SilenceDetectorTest, AggressionLevelImpact) {
    config.aggressionLevel = 0.1; // Low aggression -> slower silence detection
    SilenceDetector detectorLow(config);
    
    config.aggressionLevel = 5.0; // High aggression -> faster silence detection
    SilenceDetector detectorHigh(config);
}

TEST_F(SilenceDetectorTest, AdaptiveDSPModesAccuracyAndPerformance) {
    SilenceDetector detector(config);

    // Create a block of sample data (sine wave + some active signal)
    const int blockSize = 1152;
    std::vector<int16_t> block(blockSize);
    for (int i = 0; i < blockSize; ++i) {
        block[i] = static_cast<int16_t>(10000.0 * std::sin(2.0 * M_PI * i * 440.0 / 44100.0));
    }

    // 1. Accuracy Check
    // Verify NORMAL mode RMS
    std::vector<int16_t> outNormal(blockSize);
    int numOutNormal = 0;
    detector.process(block.data(), blockSize, outNormal.data(), numOutNormal, blockSize, FlowState::NORMAL);
    double normalDb = detector.getCurrentDb();

    // Reset detector state
    detector.flush(outNormal.data(), numOutNormal, blockSize);

    // Verify DEGRADED mode RMS (subsampled)
    std::vector<int16_t> outDegraded(blockSize);
    int numOutDegraded = 0;
    detector.process(block.data(), blockSize, outDegraded.data(), numOutDegraded, blockSize, FlowState::DEGRADED);
    double degradedDb = detector.getCurrentDb();

    // Verify that subsampled RMS is very close to full RMS (within 0.5 dB)
    EXPECT_NEAR(normalDb, degradedDb, 0.5);

    // Reset detector state
    detector.flush(outDegraded.data(), numOutDegraded, blockSize);

    // Verify EMERGENCY mode
    std::vector<int16_t> outEmergency(blockSize);
    int numOutEmergency = 0;
    detector.process(block.data(), blockSize, outEmergency.data(), numOutEmergency, blockSize, FlowState::EMERGENCY);
    double emergencyDb = detector.getCurrentDb();

    // Peak DB is ~3 dB higher than RMS DB for a sine wave.
    EXPECT_NEAR(emergencyDb, normalDb + 3.01, 0.5);

    // 2. Performance/Timing Check
    const int iterations = 10000;
    
    // Normal mode timing
    auto startNormal = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < iterations; ++i) {
        detector.process(block.data(), blockSize, outNormal.data(), numOutNormal, blockSize, FlowState::NORMAL);
    }
    auto endNormal = std::chrono::high_resolution_clock::now();
    auto durationNormal = std::chrono::duration_cast<std::chrono::microseconds>(endNormal - startNormal).count();

    // Degraded mode timing
    auto startDegraded = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < iterations; ++i) {
        detector.process(block.data(), blockSize, outDegraded.data(), numOutDegraded, blockSize, FlowState::DEGRADED);
    }
    auto endDegraded = std::chrono::high_resolution_clock::now();
    auto durationDegraded = std::chrono::duration_cast<std::chrono::microseconds>(endDegraded - startDegraded).count();

    // Emergency mode timing
    auto startEmergency = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < iterations; ++i) {
        detector.process(block.data(), blockSize, outEmergency.data(), numOutEmergency, blockSize, FlowState::EMERGENCY);
    }
    auto endEmergency = std::chrono::high_resolution_clock::now();
    auto durationEmergency = std::chrono::duration_cast<std::chrono::microseconds>(endEmergency - startEmergency).count();

    std::cout << "[INFO] Performance Benchmarks (" << iterations << " blocks):" << std::endl;
    std::cout << "[INFO]   NORMAL mode   : " << durationNormal << " us" << std::endl;
    std::cout << "[INFO]   DEGRADED mode : " << durationDegraded << " us (subsampled RMS)" << std::endl;
    std::cout << "[INFO]   EMERGENCY mode: " << durationEmergency << " us (peak envelope check)" << std::endl;

    // Degraded mode and Emergency mode should both recover CPU cycles
    EXPECT_LT(durationEmergency, durationNormal);
    EXPECT_LT(durationDegraded, durationNormal);
}
