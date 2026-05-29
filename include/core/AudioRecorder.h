#ifndef AUDIO_RECORDER_H
#define AUDIO_RECORDER_H

#include <string>
#include <atomic>
#include <functional>
#include "core/SilenceDetector.h"

/**
 * @brief A high-level audio recorder wrapper around miniaudio.
 * Supports recording to WAV files with real-time silence removal.
 */
class AudioRecorder {
public:
    friend class AudioRecorderTest;

    typedef std::function<void(const int16_t* samples, size_t count)> DataCallback;

    struct Config {
        std::string output_file;
        double threshold_db = -40.0;
        double aggression_level = 1.0;
        int sample_rate = 16000; // Default for Moonshine
        bool use_silence_removal = true;
        DataCallback on_data = nullptr;
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
     * @brief Set or update the data callback.
     */
    void setDataCallback(DataCallback callback);

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

    /**
     * @brief Get current buffer pressure level (0-100).
     */
    int getPressureLevel() const;

    /**
     * @brief Get current latency estimate in milliseconds.
     */
    int getLatencyEstimateMs() const;

    /**
     * @brief Get count of dropped samples due to buffer overflow.
     */
    uint64_t getDropCount() const;

    /**
     * @brief Get current degradation state (0: NORMAL, 1: DEGRADED, 2: EMERGENCY).
     */
    int getDegradationState() const;


    /**
     * @brief Inject audio samples directly into the pipeline (for testing or external streams).
     * @return Number of samples successfully pushed.
     */
    size_t injectAudioSamples(const int16_t* samples, size_t count);




private:
    static void data_callback(void* pDevice, void* pOutput, const void* pInput, uint32_t frameCount);

    struct Impl;
    Impl* pImpl;
};

#endif // AUDIO_RECORDER_H
