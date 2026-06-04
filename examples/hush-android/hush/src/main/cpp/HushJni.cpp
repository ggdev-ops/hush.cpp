#include <jni.h>
#include "ffi/hush_api.h"
#include "core/AudioPlayer.h"
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
    int outputSamples = inputLen;

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
    int outputSamples = 4096;

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

JNIEXPORT jlong JNICALL Java_klama_hush_AudioPlayer_audioPlayerCreate(
    JNIEnv *env, jclass clazz) {
    auto* player = new AudioPlayer();
    return reinterpret_cast<jlong>(player);
}

JNIEXPORT jboolean JNICALL Java_klama_hush_AudioPlayer_audioPlayerPlay(
    JNIEnv *env, jobject thiz, jlong handle, jstring filepath) {
    auto* player = reinterpret_cast<AudioPlayer*>(handle);
    if (!player || !filepath) return JNI_FALSE;

    const char* pathStr = env->GetStringUTFChars(filepath, nullptr);
    if (!pathStr) return JNI_FALSE;

    bool result = player->play(pathStr);

    env->ReleaseStringUTFChars(filepath, pathStr);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_klama_hush_AudioPlayer_audioPlayerPlayBuffer(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray samples, jint sampleRate) {
    auto* player = reinterpret_cast<AudioPlayer*>(handle);
    if (!player || !samples) return JNI_FALSE;

    jsize count = env->GetArrayLength(samples);
    jfloat* samplesPtr = env->GetFloatArrayElements(samples, nullptr);
    if (!samplesPtr) return JNI_FALSE;

    bool result = player->playBuffer(samplesPtr, count, sampleRate);

    env->ReleaseFloatArrayElements(samples, samplesPtr, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_klama_hush_AudioPlayer_audioPlayerTogglePause(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto* player = reinterpret_cast<AudioPlayer*>(handle);
    if (player) {
        player->togglePause();
    }
}

JNIEXPORT void JNICALL Java_klama_hush_AudioPlayer_audioPlayerStop(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto* player = reinterpret_cast<AudioPlayer*>(handle);
    if (player) {
        player->stop();
    }
}

JNIEXPORT jboolean JNICALL Java_klama_hush_AudioPlayer_audioPlayerIsPlaying(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto* player = reinterpret_cast<AudioPlayer*>(handle);
    if (!player) return JNI_FALSE;
    return player->isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_klama_hush_AudioPlayer_audioPlayerIsFinished(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto* player = reinterpret_cast<AudioPlayer*>(handle);
    if (!player) return JNI_TRUE;
    return player->isFinished() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_klama_hush_AudioPlayer_audioPlayerDestroy(
    JNIEnv *env, jobject thiz, jlong handle) {
    auto* player = reinterpret_cast<AudioPlayer*>(handle);
    if (player) {
        delete player;
    }
}

JNIEXPORT jlong JNICALL Java_klama_hush_AudioRecorder_audioRecorderCreate(
    JNIEnv *env, jclass clazz, jstring outputFile, jdouble thresholdDb, jdouble aggressionLevel, jint sampleRate, jboolean useSilenceRemoval) {
    
    hush_recorder_config_t config;
    config.threshold_db = thresholdDb;
    config.aggression_level = aggressionLevel;
    config.sample_rate = sampleRate;
    config.use_silence_removal = useSilenceRemoval ? 1 : 0;
    
    const char* pathStr = nullptr;
    if (outputFile) {
        pathStr = env->GetStringUTFChars(outputFile, nullptr);
    }
    config.output_file = pathStr;
    
    hush_recorder_t* recorder = hush_recorder_create(config);
    
    if (pathStr) {
        env->ReleaseStringUTFChars(outputFile, pathStr);
    }
    
    return reinterpret_cast<jlong>(recorder);
}

JNIEXPORT jboolean JNICALL Java_klama_hush_AudioRecorder_audioRecorderStart(
    JNIEnv *env, jobject thiz, jlong handle) {
    hush_recorder_t* recorder = reinterpret_cast<hush_recorder_t*>(handle);
    if (!recorder) return JNI_FALSE;
    return hush_recorder_start(recorder) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_klama_hush_AudioRecorder_audioRecorderStop(
    JNIEnv *env, jobject thiz, jlong handle) {
    hush_recorder_t* recorder = reinterpret_cast<hush_recorder_t*>(handle);
    if (recorder) {
        hush_recorder_stop(recorder);
    }
}

JNIEXPORT jboolean JNICALL Java_klama_hush_AudioRecorder_audioRecorderIsRecording(
    JNIEnv *env, jobject thiz, jlong handle) {
    hush_recorder_t* recorder = reinterpret_cast<hush_recorder_t*>(handle);
    if (!recorder) return JNI_FALSE;
    return hush_recorder_is_recording(recorder) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL Java_klama_hush_AudioRecorder_audioRecorderGetStats(
    JNIEnv *env, jobject thiz, jlong handle) {
    hush_recorder_t* recorder = reinterpret_cast<hush_recorder_t*>(handle);
    if (!recorder) return nullptr;
    
    hush_stats_t stats = hush_recorder_get_stats(recorder);
    
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

JNIEXPORT void JNICALL Java_klama_hush_AudioRecorder_audioRecorderDestroy(
    JNIEnv *env, jobject thiz, jlong handle) {
    hush_recorder_t* recorder = reinterpret_cast<hush_recorder_t*>(handle);
    if (recorder) {
        hush_recorder_destroy(recorder);
    }
}

JNIEXPORT jdouble JNICALL Java_klama_hush_AudioRecorder_audioRecorderGetCurrentDb(
    JNIEnv *env, jobject thiz, jlong handle) {
    hush_recorder_t* recorder = reinterpret_cast<hush_recorder_t*>(handle);
    if (!recorder) return -100.0;
    return hush_recorder_get_current_db(recorder);
}

JNIEXPORT jint JNICALL Java_klama_hush_AudioRecorder_audioRecorderGetPressure(
    JNIEnv *env, jobject thiz, jlong handle) {
    hush_recorder_t* recorder = reinterpret_cast<hush_recorder_t*>(handle);
    if (!recorder) return 0;
    return hush_recorder_get_pressure(recorder);
}

JNIEXPORT jint JNICALL Java_klama_hush_AudioRecorder_audioRecorderGetDegradationState(
    JNIEnv *env, jobject thiz, jlong handle) {
    hush_recorder_t* recorder = reinterpret_cast<hush_recorder_t*>(handle);
    if (!recorder) return 0;
    return hush_recorder_get_degradation_state(recorder);
}

} // extern "C"

