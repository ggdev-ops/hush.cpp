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

/**
 * Create a new Hush! engine instance.
 */
hush_engine_t* hush_engine_create(hush_config_t config);

/**
 * Process a chunk of S16 Mono PCM samples.
 * output buffer must be large enough to hold input_samples.
 */
void hush_engine_process(hush_engine_t* engine, 
                         const int16_t* input, int input_samples, 
                         int16_t* output, int* output_samples);

/**
 * Flush pending samples and final state.
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

#ifdef __cplusplus
}
#endif

#endif // HUSH_API_H
