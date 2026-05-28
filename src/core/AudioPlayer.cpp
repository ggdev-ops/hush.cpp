#define MINIAUDIO_IMPLEMENTATION
#include "core/miniaudio.h"
#include "core/AudioPlayer.h"
#include "core/Logger.h"
#include <vector>

struct AudioPlayer::Impl {
    ma_engine engine;
    ma_sound sound;
    ma_audio_buffer audioBuffer;
    std::vector<float> bufferData;
    bool soundInitialized = false;
    bool engineInitialized = false;
    bool audioBufferInitialized = false;
};

AudioPlayer::AudioPlayer() : pImpl(new Impl()) {
    ma_result result = ma_engine_init(NULL, &pImpl->engine);
    if (result != MA_SUCCESS) {
        Logger::error("Failed to initialize miniaudio engine (Error: %d).", result);
    } else {
        pImpl->engineInitialized = true;
    }
}

AudioPlayer::~AudioPlayer() {
    stop();
    if (pImpl->engineInitialized) {
        ma_engine_uninit(&pImpl->engine);
    }
    delete pImpl;
}

bool AudioPlayer::play(const std::string& filepath) {
    if (!pImpl->engineInitialized) return false;
    
    stop(); // Stop any current sound

    ma_result result = ma_sound_init_from_file(&pImpl->engine, filepath.c_str(), 0, NULL, NULL, &pImpl->sound);
    if (result != MA_SUCCESS) {
        Logger::error("Failed to load audio file: %s (Error: %d)", filepath.c_str(), result);
        return false;
    }

    pImpl->soundInitialized = true;
    result = ma_sound_start(&pImpl->sound);
    if (result != MA_SUCCESS) {
        Logger::error("Failed to start sound playback.");
        return false;
    }

    return true;
}

bool AudioPlayer::playBuffer(const float* samples, size_t count, int sampleRate) {
    if (!pImpl->engineInitialized) return false;
    
    stop();

    pImpl->bufferData.assign(samples, samples + count);
    
    ma_audio_buffer_config config = ma_audio_buffer_config_init(ma_format_f32, 1, count, pImpl->bufferData.data(), NULL);
    config.sampleRate = sampleRate;
    
    ma_result result = ma_audio_buffer_init(&config, &pImpl->audioBuffer);
    if (result != MA_SUCCESS) {
        Logger::error("Failed to initialize audio buffer (Error: %d)", result);
        return false;
    }
    pImpl->audioBufferInitialized = true;

    result = ma_sound_init_from_data_source(&pImpl->engine, &pImpl->audioBuffer, 0, NULL, &pImpl->sound);
    if (result != MA_SUCCESS) {
        Logger::error("Failed to initialize sound from data source (Error: %d)", result);
        ma_audio_buffer_uninit(&pImpl->audioBuffer);
        pImpl->audioBufferInitialized = false;
        return false;
    }

    pImpl->soundInitialized = true;
    result = ma_sound_start(&pImpl->sound);
    if (result != MA_SUCCESS) {
        Logger::error("Failed to start sound playback.");
        return false;
    }

    return true;
}

void AudioPlayer::togglePause() {
    if (!pImpl->soundInitialized) return;

    if (ma_sound_is_playing(&pImpl->sound)) {
        ma_sound_stop(&pImpl->sound);
    } else {
        ma_sound_start(&pImpl->sound);
    }
}

void AudioPlayer::stop() {
    if (pImpl->soundInitialized) {
        ma_sound_stop(&pImpl->sound);
        ma_sound_uninit(&pImpl->sound);
        pImpl->soundInitialized = false;
    }
    if (pImpl->audioBufferInitialized) {
        ma_audio_buffer_uninit(&pImpl->audioBuffer);
        pImpl->audioBufferInitialized = false;
    }
}

bool AudioPlayer::isPlaying() const {
    if (!pImpl->soundInitialized) return false;
    return ma_sound_is_playing(&pImpl->sound) == MA_TRUE;
}

bool AudioPlayer::isFinished() const {
    if (!pImpl->soundInitialized) return true;
    return ma_sound_at_end(&pImpl->sound) == MA_TRUE;
}

