#ifndef HUSH_API_H
#define HUSH_API_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>

typedef struct {
    double threshold_db;
    double aggression_level;
    int sample_rate;
} hush_config_t;

typedef struct {
    long long total_input_samples;
    long long total_output_samples;
    long long total_removed_samples;
    double reduction_percentage;
    int silent_segments_detected;
} hush_stats_t;

// Opaque handle to the engine
typedef struct hush_engine_t hush_engine_t;

// Opaque handle to the recorder
typedef struct hush_recorder_t hush_recorder_t;

typedef struct {
    const char* output_file;
    double threshold_db;
    double aggression_level;
    int sample_rate;
    int use_silence_removal;
} hush_recorder_config_t;

/**
 * Create a new Hush! engine instance.
 */
hush_engine_t* hush_engine_create(hush_config_t config);

/**
 * Process a chunk of S16 Mono PCM samples.
 * output buffer must be pre-allocated.
 * output_samples must be initialized with the capacity of the output buffer.
 * On return, output_samples will be set to the number of samples actually written.
 */
void hush_engine_process(hush_engine_t* engine, 
                         const int16_t* input, int input_samples, 
                         int16_t* output, int* output_samples);

/**
 * Flush pending samples and final state.
 * output_samples must be initialized with the capacity of the output buffer.
 */
void hush_engine_flush(hush_engine_t* engine, 
                       int16_t* output, int* output_samples);

/**
 * Get current processing statistics.
 */
hush_stats_t hush_engine_get_stats(hush_engine_t* engine);

/**
 * Destroy the engine instance and free resources.
 */
void hush_engine_destroy(hush_engine_t* engine);

/**
 * Create a new Hush! recorder instance.
 */
hush_recorder_t* hush_recorder_create(hush_recorder_config_t config);

/**
 * Start recording.
 */
int hush_recorder_start(hush_recorder_t* recorder);

/**
 * Stop recording.
 */
void hush_recorder_stop(hush_recorder_t* recorder);

/**
 * Check if currently recording.
 */
int hush_recorder_is_recording(hush_recorder_t* recorder);

/**
 * Get recorder statistics.
 */
hush_stats_t hush_recorder_get_stats(hush_recorder_t* recorder);

/**
 * Destroy the recorder instance and free resources.
 */
void hush_recorder_destroy(hush_recorder_t* recorder);

#ifdef __cplusplus
}
#endif

#endif // HUSH_API_H

