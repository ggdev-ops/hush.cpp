#include "gtest/gtest.h"
#include "core/SilenceDetector.h"
#include <vector>
#include <limits>
#include <cmath>

TEST(SilenceDetectorUtilsTest, CalculateRmsDb_ZeroSamples) {
    std::vector<int16_t> samples = {};
    EXPECT_EQ(SilenceDetector::calculateRmsDb(samples.data(), samples.size()), -std::numeric_limits<double>::infinity());
}

TEST(SilenceDetectorUtilsTest, CalculateRmsDb_AllZeros) {
    std::vector<int16_t> samples(100, 0);
    EXPECT_TRUE(std::isinf(SilenceDetector::calculateRmsDb(samples.data(), samples.size())));
}

TEST(SilenceDetectorUtilsTest, CalculateRmsDb_MaxAmplitude) {
    std::vector<int16_t> samples(100, std::numeric_limits<int16_t>::max());
    // Max amplitude RMS should be 0 dB, but due to floating point precision, it might be very close.
    // Check if it's within a small epsilon of 0.
    EXPECT_NEAR(SilenceDetector::calculateRmsDb(samples.data(), samples.size()), 0.0, 0.001);
}

TEST(SilenceDetectorUtilsTest, CalculateRmsDb_HalfAmplitude) {
    std::vector<int16_t> samples(100, std::numeric_limits<int16_t>::max() / 2);
    // 20 * log10(0.5) = -6.02 dB
    EXPECT_NEAR(SilenceDetector::calculateRmsDb(samples.data(), samples.size()), -6.02, 0.01);
}

TEST(SilenceDetectorUtilsTest, CalculateRmsDb_MixedAmplitudes) {
    std::vector<int16_t> samples = {1000, -2000, 3000, -4000, 5000};
    // Expected RMS for these values: sqrt((1000^2 + (-2000)^2 + 3000^2 + (-4000)^2 + 5000^2) / 5) = 3316.62
    // 20 * log10(3316.62 / 32767.0) = -19.86 dB
    EXPECT_NEAR(SilenceDetector::calculateRmsDb(samples.data(), samples.size()), -19.86, 0.04);
}
