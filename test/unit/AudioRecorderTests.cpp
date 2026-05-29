#include "gtest/gtest.h"
#include "core/AudioRecorder.h"
#include "ffi/hush_api.h"

#include <filesystem>
#include <thread>
#include <chrono>
#include <atomic>
#include <iostream>

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

TEST_F(AudioRecorderTest, DecoupledThreadDataFlow) {
    AudioRecorder::Config config;
    config.output_file = test_output;
    config.use_silence_removal = false; // direct pass-through
    
    std::atomic<size_t> callbackCount{0};
    std::atomic<size_t> totalSamplesReceived{0};
    
    config.on_data = [&](const int16_t* samples, size_t count) {
        callbackCount.fetch_add(1, std::memory_order_relaxed);
        totalSamplesReceived.fetch_add(count, std::memory_order_relaxed);
    };
    
    AudioRecorder recorder(config);
    bool started = recorder.start();
    if (started) {
        EXPECT_TRUE(recorder.isRecording());
        // Sleep for a short duration to let hardware capture some real audio
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        recorder.stop();
        EXPECT_FALSE(recorder.isRecording());
        
        // Ensure that we received some audio frames and callbacks
        EXPECT_GT(totalSamplesReceived.load(), 0);
        EXPECT_GT(callbackCount.load(), 0);
    } else {
        std::cout << "[SKIP] Audio device not available for decoupled data flow test." << std::endl;
    }
}

TEST_F(AudioRecorderTest, FlowControllerSimulatedStall) {
    AudioRecorder::Config config;
    config.output_file = test_output;
    config.use_silence_removal = false;
    config.sample_rate = 16000;

    // Stall the worker thread by sleeping 100ms in the callback
    config.on_data = [](const int16_t* samples, size_t count) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    };

    AudioRecorder recorder(config);
    bool started = recorder.start();
    if (started) {
        EXPECT_TRUE(recorder.isRecording());

        // Push 8 blocks of 2000 samples.
        // Since we push very fast (faster than 100ms per block), the buffer pressure should quickly build up.
        std::vector<int16_t> dummyAudio(2000, 0);

        for (int i = 0; i < 8; ++i) {
            recorder.injectAudioSamples(dummyAudio.data(), dummyAudio.size());
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }

        // Give it a tiny moment for worker to fetch the first block and start sleeping, while the rest piles up.
        std::this_thread::sleep_for(std::chrono::milliseconds(20));

        int pressure = recorder.getPressureLevel();
        int latency = recorder.getLatencyEstimateMs();
        std::cout << "[INFO] Simulated Stall - Pressure: " << pressure 
                  << "%, Latency Estimate: " << latency << "ms" << std::endl;

        EXPECT_GT(pressure, 50); // should be degraded or emergency
        EXPECT_GT(latency, 500); // should be high latency estimate

        recorder.stop();
        EXPECT_FALSE(recorder.isRecording());
    } else {
        std::cout << "[SKIP] Audio device not available for simulated stall test." << std::endl;
    }
}

TEST_F(AudioRecorderTest, FfiTelemetryQueries) {
    hush_recorder_config_t config;
    config.output_file = test_output.c_str();
    config.threshold_db = -40.0;
    config.aggression_level = 1.0;
    config.sample_rate = 16000;
    config.use_silence_removal = 1;

    hush_recorder_t* recorder = hush_recorder_create(config);
    ASSERT_NE(recorder, nullptr);

    // Initial FFI state queries
    EXPECT_EQ(hush_recorder_is_recording(recorder), 0);
    EXPECT_EQ(hush_recorder_get_pressure(recorder), 0);
    EXPECT_EQ(hush_recorder_get_degradation_state(recorder), 0);

    // Start recorder FFI
    int started = hush_recorder_start(recorder);
    if (started) {
        EXPECT_EQ(hush_recorder_is_recording(recorder), 1);
        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        // Stop recorder FFI
        hush_recorder_stop(recorder);
        EXPECT_EQ(hush_recorder_is_recording(recorder), 0);
    } else {
        std::cout << "[SKIP] Audio device not available for FFI functional test." << std::endl;
    }

    hush_recorder_destroy(recorder);
}


