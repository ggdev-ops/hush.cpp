#include <jni.h>
#include "ffi/hush_api.h"
#include <vector>
#include <android/log.h>

#define LOG_TAG "HushJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL Java_klama_hush_Hush_hushCreate(
    JNIEnv *env, jclass clazz, jdouble thresholdDb, jdouble aggressionLevel, jint sampleRate) {
    
    hush_config_t config;
    config.threshold_db = thresholdDb;
    config.aggression_level = aggressionLevel;
    config.sample_rate = sampleRate;
    
    hush_engine_t* engine = hush_engine_create(config);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jshortArray JNICALL Java_klama_hush_Hush_hushProcess(
    JNIEnv *env, jobject thiz, jlong handle, jshortArray input) {
    
    hush_engine_t* engine = reinterpret_cast<hush_engine_t*>(handle);
    if (!engine) return nullptr;

    jsize inputLen = env->GetArrayLength(input);
    jshort* inputPtr = env->GetShortArrayElements(input, nullptr);
    if (!inputPtr) return nullptr;

    std::vector<int16_t> outputBuffer(inputLen);
    int outputSamples = 0;

    hush_engine_process(engine, inputPtr, inputLen, outputBuffer.data(), &outputSamples);

    env->ReleaseShortArrayElements(input, inputPtr, JNI_ABORT);

    jshortArray result = env->NewShortArray(outputSamples);
    if (!result) return nullptr;

    env->SetShortArrayRegion(result, 0, outputSamples, outputBuffer.data());
    return result;
}

JNIEXPORT jshortArray JNICALL Java_klama_hush_Hush_hushFlush(
    JNIEnv *env, jobject thiz, jlong handle) {
    
    hush_engine_t* engine = reinterpret_cast<hush_engine_t*>(handle);
    if (!engine) return nullptr;

    std::vector<int16_t> outputBuffer(4096);
    int outputSamples = 0;

    hush_engine_flush(engine, outputBuffer.data(), &outputSamples);

    jshortArray result = env->NewShortArray(outputSamples);
    if (!result) return nullptr;

    env->SetShortArrayRegion(result, 0, outputSamples, outputBuffer.data());
    return result;
}

JNIEXPORT jdoubleArray JNICALL Java_klama_hush_Hush_hushGetStats(
    JNIEnv *env, jobject thiz, jlong handle) {
    
    hush_engine_t* engine = reinterpret_cast<hush_engine_t*>(handle);
    if (!engine) return nullptr;

    hush_stats_t stats = hush_engine_get_stats(engine);

    jdoubleArray result = env->NewDoubleArray(5);
    if (!result) return nullptr;

    jdouble statsData[5] = {
        static_cast<jdouble>(stats.total_input_samples),
        static_cast<jdouble>(stats.total_output_samples),
        static_cast<jdouble>(stats.total_removed_samples),
        static_cast<jdouble>(stats.reduction_percentage),
        static_cast<jdouble>(stats.silent_segments_detected)
    };

    env->SetDoubleArrayRegion(result, 0, 5, statsData);
    return result;
}

JNIEXPORT void JNICALL Java_klama_hush_Hush_hushDestroy(
    JNIEnv *env, jobject thiz, jlong handle) {
    
    hush_engine_t* engine = reinterpret_cast<hush_engine_t*>(handle);
    if (engine) {
        hush_engine_destroy(engine);
    }
}

} // extern "C"

