#ifndef AUDIO_RECORDER_H
#define AUDIO_RECORDER_H

#include <string>
#include <atomic>
#include "core/SilenceDetector.h"

/**
 * @brief A high-level audio recorder wrapper around miniaudio.
 * Supports recording to WAV files with real-time silence removal.
 */
class AudioRecorder {
public:
    struct Config {
        std::string output_file;
        double threshold_db = -40.0;
        double aggression_level = 1.0;
        int sample_rate = 16000; // Default for Whisper
        bool use_silence_removal = true;
    };

    AudioRecorder(const Config& config);
    ~AudioRecorder();

    /**
     * @brief Starts recording to the specified file.
     * @return true if recording started successfully, false otherwise.
     */
    bool start();

    /**
     * @brief Stops recording and releases resources.
     */
    void stop();

    /**
     * @brief Checks if audio is currently being recorded.
     */
    bool isRecording() const;

    /**
     * @brief Get statistics of the current or last recording session.
     */
    HushStats getStats() const;

    /**
     * @brief Get current RMS dB level.
     */
    double getCurrentDb() const;

private:
    static void data_callback(void* pDevice, void* pOutput, const void* pInput, uint32_t frameCount);

    struct Impl;
    Impl* pImpl;
};

#endif // AUDIO_RECORDER_H
