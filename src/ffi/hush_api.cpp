#include "ffi/hush_api.h"
#include "core/SilenceDetector.h"
#include "core/AudioRecorder.h"
#include "core/AudioPlayer.h"
#include "core/Logger.h"
#include "core/miniaudio.h"
#include <memory>

struct hush_engine_t {
    std::unique_ptr<SilenceDetector> detector;
};

struct hush_recorder_t {
    std::unique_ptr<AudioRecorder> recorder;
};

struct hush_player_t {
    std::unique_ptr<AudioPlayer> player;
};

extern "C" {

hush_engine_t* hush_engine_create(hush_config_t config) {
    HushConfig internal_config;
    internal_config.thresholdDb = config.threshold_db;
    internal_config.aggressionLevel = config.aggression_level;
    internal_config.sampleRate = config.sample_rate;

    hush_engine_t* engine = new hush_engine_t();
    engine->detector = std::make_unique<SilenceDetector>(internal_config);
    return engine;
}

void hush_engine_process(hush_engine_t* engine, 
                         const int16_t* input, int input_samples, 
                         int16_t* output, int* output_samples) {
    if (!engine || !engine->detector) return;
    int capacity = *output_samples; 
    engine->detector->process(input, input_samples, output, *output_samples, capacity);
}

void hush_engine_flush(hush_engine_t* engine, 
                       int16_t* output, int* output_samples) {
    if (!engine || !engine->detector) return;
    int capacity = *output_samples;
    engine->detector->flush(output, *output_samples, capacity);
}

hush_stats_t hush_engine_get_stats(hush_engine_t* engine) {
    hush_stats_t stats = {0};
    if (!engine || !engine->detector) return stats;

    HushStats internal_stats = engine->detector->getStats();
    stats.total_input_samples = internal_stats.totalInputSamples;
    stats.total_output_samples = internal_stats.totalOutputSamples;
    stats.total_removed_samples = internal_stats.totalRemovedSamples;
    stats.reduction_percentage = internal_stats.reductionPercentage;
    stats.silent_segments_detected = internal_stats.silentSegmentsDetected;
    
    return stats;
}

void hush_engine_destroy(hush_engine_t* engine) {
    if (engine) {
        delete engine;
    }
}

hush_recorder_t* hush_recorder_create(hush_recorder_config_t config) {
    AudioRecorder::Config internal_config;
    internal_config.output_file = config.output_file;
    internal_config.threshold_db = config.threshold_db;
    internal_config.aggression_level = config.aggression_level;
    internal_config.sample_rate = config.sample_rate;
    internal_config.use_silence_removal = (config.use_silence_removal != 0);

    hush_recorder_t* recorder = new hush_recorder_t();
    recorder->recorder = std::make_unique<AudioRecorder>(internal_config);
    return recorder;
}

int hush_recorder_start(hush_recorder_t* recorder) {
    if (!recorder || !recorder->recorder) return 0;
    return recorder->recorder->start() ? 1 : 0;
}

void hush_recorder_stop(hush_recorder_t* recorder) {
    if (recorder && recorder->recorder) {
        recorder->recorder->stop();
    }
}

int hush_recorder_is_recording(hush_recorder_t* recorder) {
    if (!recorder || !recorder->recorder) return 0;
    return recorder->recorder->isRecording() ? 1 : 0;
}

hush_stats_t hush_recorder_get_stats(hush_recorder_t* recorder) {
    hush_stats_t stats = {0};
    if (!recorder || !recorder->recorder) return stats;

    HushStats internal_stats = recorder->recorder->getStats();
    stats.total_input_samples = internal_stats.totalInputSamples;
    stats.total_output_samples = internal_stats.totalOutputSamples;
    stats.total_removed_samples = internal_stats.totalRemovedSamples;
    stats.reduction_percentage = internal_stats.reductionPercentage;
    stats.silent_segments_detected = internal_stats.silentSegmentsDetected;

    return stats;
}

void hush_recorder_destroy(hush_recorder_t* recorder) {
    if (recorder) {
        delete recorder;
    }
}

double hush_recorder_get_current_db(hush_recorder_t* recorder) {
    if (!recorder || !recorder->recorder) return -100.0;
    return recorder->recorder->getCurrentDb();
}

// --- Real-time Playback API ---

hush_player_t* hush_player_create() {
    hush_player_t* player = new hush_player_t();
    player->player = std::make_unique<AudioPlayer>();
    return player;
}

void hush_player_destroy(hush_player_t* player) {
    if (player) {
        delete player;
    }
}

int hush_player_play(hush_player_t* player, const char* filepath) {
    if (!player || !player->player) return 0;
    return player->player->play(filepath) ? 1 : 0;
}

int hush_player_play_buffer(hush_player_t* player, const float* samples, int count, int sample_rate) {
    if (!player || !player->player) return 0;
    return player->player->playBuffer(samples, (size_t)count, sample_rate) ? 1 : 0;
}

void hush_player_toggle_pause(hush_player_t* player) {
    if (player && player->player) {
        player->player->togglePause();
    }
}

void hush_player_stop(hush_player_t* player) {
    if (player && player->player) {
        player->player->stop();
    }
}

int hush_player_is_playing(hush_player_t* player) {
    if (!player || !player->player) return 0;
    return player->player->isPlaying() ? 1 : 0;
}

int hush_player_is_finished(hush_player_t* player) {
    if (!player || !player->player) return 0;
    return player->player->isFinished() ? 1 : 0;
}

// --- SilenceDetector API Extensions ---

double hush_engine_get_current_db(hush_engine_t* engine) {
    if (!engine || !engine->detector) return -100.0;
    return engine->detector->getCurrentDb();
}

// --- Global / Utility API ---

void hush_set_log_level(int level) {
    if (level < 0 || level > 3) return;
    Logger::setLogLevel(static_cast<Logger::LogLevel>(level));
}

double hush_calculate_rms_db(const int16_t* samples, int num_samples) {
    return SilenceDetector::calculateRmsDb(samples, num_samples);
}

int hush_save_wav(const char* filepath, const float* samples, int count, int sample_rate) {
    ma_encoder_config config = ma_encoder_config_init(ma_encoding_format_wav, ma_format_f32, 1, sample_rate);
    ma_encoder encoder;
    if (ma_encoder_init_file(filepath, &config, &encoder) != MA_SUCCESS) {
        return 0;
    }
    ma_encoder_write_pcm_frames(&encoder, samples, (ma_uint64)count, NULL);
    ma_encoder_uninit(&encoder);
    return 1;
}

} // extern "C"

