#include "gtest/gtest.h"
#include "codecs/AudioProcessor.h"
#include <filesystem>
#include <vector>
#include <fstream>
#include <cmath>
#include <algorithm>

namespace fs = std::filesystem;

namespace {

void writeWav(const std::string& filename, const std::vector<float>& audio_data, int sample_rate) {
    std::ofstream file(filename, std::ios::binary);
    int32_t data_size = audio_data.size() * 2;
    int32_t chunk_size = 36 + data_size;
    file.write("RIFF", 4); 
    file.write(reinterpret_cast<const char*>(&chunk_size), 4); 
    file.write("WAVE", 4);
    file.write("fmt ", 4); 
    int32_t fs = 16; 
    file.write(reinterpret_cast<const char*>(&fs), 4);
    int16_t fmt = 1, chan = 1; 
    file.write(reinterpret_cast<const char*>(&fmt), 2); 
    file.write(reinterpret_cast<const char*>(&chan), 2);
    file.write(reinterpret_cast<const char*>(&sample_rate), 4); 
    int32_t br = sample_rate * 2; 
    file.write(reinterpret_cast<const char*>(&br), 4);
    int16_t ba = 2, bps = 16; 
    file.write(reinterpret_cast<const char*>(&ba), 2); 
    file.write(reinterpret_cast<const char*>(&bps), 2);
    file.write("data", 4); 
    file.write(reinterpret_cast<const char*>(&data_size), 4);
    for (float s : audio_data) {
        int16_t is = static_cast<int16_t>(std::max(-1.0f, std::min(1.0f, s)) * 32767);
        file.write(reinterpret_cast<const char*>(&is), 2);
    }
}

} // namespace

class AudioProcessorTest : public ::testing::Test {
protected:
    std::string test_input = "test_input.wav";
    std::string test_output = "test_output.wav";

    void SetUp() override {
        // Create a 1-second WAV file with 0.5s sound and 0.5s silence
        std::vector<float> audio(16000, 0.0f);
        for(int i=0; i<8000; ++i) {
            audio[i] = 0.5f * std::sin(2.0f * M_PI * 440.0f * i / 16000.0f);
        }

        writeWav(test_input, audio, 16000);
    }

    void TearDown() override {
        fs::remove(test_input);
        fs::remove(test_output);
    }
};

TEST_F(AudioProcessorTest, ProcessFullPipeline) {
    AudioProcessor processor;
    // Threshold -20dB, aggression 1.0
    auto summary = processor.process(test_input, test_output, -20.0, 1.0, false);

    EXPECT_GT(summary.inputDuration, 0);
    EXPECT_GT(summary.outputDuration, 0);
    EXPECT_LT(summary.outputDuration, summary.inputDuration);
    EXPECT_TRUE(fs::exists(test_output));
}

TEST_F(AudioProcessorTest, DryRunDoesNotCreateFile) {
    AudioProcessor processor;
    auto summary = processor.process(test_input, test_output, -20.0, 1.0, true);

    EXPECT_FALSE(fs::exists(test_output));
    EXPECT_GT(summary.reductionPercentage, 0);
}

TEST_F(AudioProcessorTest, HandleMissingFile) {
    AudioProcessor processor;
    auto summary = processor.process("non_existent.wav", test_output, -20.0, 1.0, false);
    EXPECT_EQ(summary.inputDuration, 0);
}
