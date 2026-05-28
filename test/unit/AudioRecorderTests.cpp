#include "gtest/gtest.h"
#include "core/AudioRecorder.h"
#include <filesystem>
#include <thread>
#include <chrono>

namespace fs = std::filesystem;

class AudioRecorderTest : public ::testing::Test {
protected:
    std::string test_output = "test_record_functional.wav";

    void SetUp() override {
        if (fs::exists(test_output)) fs::remove(test_output);
    }

    void TearDown() override {
        if (fs::exists(test_output)) fs::remove(test_output);
    }
};

TEST_F(AudioRecorderTest, StartStopLifeCycle) {
    AudioRecorder::Config config;
    config.output_file = test_output;
    config.use_silence_removal = true;
    
    AudioRecorder recorder(config);
    
    // This might fail if no audio device is present, which is fine.
    // We want to ensure it doesn't crash.
    bool started = recorder.start();
    if (started) {
        EXPECT_TRUE(recorder.isRecording());
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        recorder.stop();
        EXPECT_FALSE(recorder.isRecording());
        EXPECT_TRUE(fs::exists(test_output));
    } else {
        std::cout << "[SKIP] Audio device not available for functional test." << std::endl;
    }
}

TEST_F(AudioRecorderTest, StatsAndDbInitialValues) {
    AudioRecorder::Config config;
    config.output_file = test_output;
    AudioRecorder recorder(config);
    
    EXPECT_EQ(recorder.getCurrentDb(), -100.0);
    HushStats stats = recorder.getStats();
    EXPECT_EQ(stats.silentSegmentsDetected, 0);
}
