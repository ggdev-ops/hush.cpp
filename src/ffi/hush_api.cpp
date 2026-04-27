#include "ffi/hush_api.h"
#include "core/SilenceDetector.h"
#include <memory>

struct hush_engine_t {
    std::unique_ptr<SilenceDetector> detector;
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
    engine->detector->process(input, input_samples, output, *output_samples);
}

void hush_engine_flush(hush_engine_t* engine, 
                       int16_t* output, int* output_samples) {
    if (!engine || !engine->detector) return;
    engine->detector->flush(output, *output_samples);
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

} // extern "C"
