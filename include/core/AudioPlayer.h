#ifndef AUDIO_PLAYER_H
#define AUDIO_PLAYER_H

#include <string>

/**
 * @brief A high-level audio player wrapper around miniaudio.
 * Supports playing MP3 and WAV files asynchronously.
 */
class AudioPlayer {
public:
    AudioPlayer();
    ~AudioPlayer();

    /**
     * @brief Starts playback of an audio file.
     * @param filepath Path to the MP3 or WAV file.
     * @return true if playback started successfully, false otherwise.
     */
    bool play(const std::string& filepath);

    /**
     * @brief Starts playback of audio from a memory buffer.
     * @param samples Pointer to float PCM samples.
     * @param count Number of samples.
     * @param sampleRate Sample rate in Hz.
     * @return true if playback started successfully, false otherwise.
     */
    bool playBuffer(const float* samples, size_t count, int sampleRate);

    /**
     * @brief Toggles between pause and resume states.
     */
    void togglePause();

    /**
     * @brief Stops playback and releases resources.
     */
    void stop();

    /**
     * @brief Checks if audio is currently playing (not paused and not finished).
     */
    bool isPlaying() const;

    /**
     * @brief Checks if the audio has reached the end.
     */
    bool isFinished() const;

private:
    struct Impl;
    Impl* pImpl;
};

#endif // AUDIO_PLAYER_H

