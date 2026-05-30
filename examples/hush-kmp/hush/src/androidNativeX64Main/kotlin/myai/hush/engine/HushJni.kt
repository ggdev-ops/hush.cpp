package myai.hush.engine

import kotlinx.cinterop.*
import platform.posix.memcpy
import kotlin.experimental.ExperimentalNativeApi
import myai.hush.*
import cnames.structs.hush_engine_t
import cnames.structs.hush_recorder_t
import cnames.structs.hush_player_t
import platform.android.*

// For Android Native, we use the platform.android JNI definitions
// which might have slightly different names/types than the custom jni.def

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_Hush_hushCreate")
fun hushCreate(env: CPointer<JNIEnvVar>, thiz: jobject, thresholdDb: Double, aggressionLevel: Double, sampleRate: Int): Long {
    return memScoped {
        val config = alloc<hush_config_t>()
        config.threshold_db = thresholdDb
        config.aggression_level = aggressionLevel
        config.sample_rate = sampleRate
        hush_engine_create(config.readValue()).toLong()
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_Hush_hushProcess")
fun hushProcess(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long, input: jshortArray): jshortArray? {
    val engine = handle.toCPointer<hush_engine_t>() ?: return null
    val jniEnv = env.pointed.pointed!!
    
    val inputLen = jniEnv.GetArrayLength!!.invoke(env, input)
    val inputPtr = jniEnv.GetShortArrayElements!!.invoke(env, input, null) ?: return null
    
    return memScoped {
        val outputBuffer = ShortArray(inputLen)
        val outputSamples = alloc<IntVar>()
        
        outputBuffer.usePinned { outputPinned ->
            hush_engine_process(
                engine,
                inputPtr,
                inputLen,
                outputPinned.addressOf(0).reinterpret(),
                outputSamples.ptr
            )
        }
        
        jniEnv.ReleaseShortArrayElements!!.invoke(env, input, inputPtr, JNI_ABORT)
        
        val result = jniEnv.NewShortArray!!.invoke(env, outputSamples.value) ?: return null
        val resultPtr = jniEnv.GetShortArrayElements!!.invoke(env, result, null) ?: return null
        
        outputBuffer.usePinned { outputPinned ->
            memcpy(resultPtr, outputPinned.addressOf(0), (outputSamples.value * 2).toULong())
        }
        
        jniEnv.ReleaseShortArrayElements!!.invoke(env, result, resultPtr, 0.toByte().toInt()) // JNI Release expect 0 or similar
        result
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_Hush_hushFlush")
fun hushFlush(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): jshortArray? {
    val engine = handle.toCPointer<hush_engine_t>() ?: return null
    val jniEnv = env.pointed.pointed!!
    
    return memScoped {
        val outputBuffer = ShortArray(4096)
        val outputSamples = alloc<IntVar>()
        
        outputBuffer.usePinned { outputPinned ->
            hush_engine_flush(
                engine,
                outputPinned.addressOf(0).reinterpret(),
                outputSamples.ptr
            )
        }
        
        val result = jniEnv.NewShortArray!!.invoke(env, outputSamples.value) ?: return null
        val resultPtr = jniEnv.GetShortArrayElements!!.invoke(env, result, null) ?: return null
        
        outputBuffer.usePinned { outputPinned ->
            memcpy(resultPtr, outputPinned.addressOf(0), (outputSamples.value * 2).toULong())
        }
        
        jniEnv.ReleaseShortArrayElements!!.invoke(env, result, resultPtr, 0)
        result
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_Hush_hushGetStats")
fun hushGetStats(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): jdoubleArray? {
    val engine = handle.toCPointer<hush_engine_t>() ?: return null
    val jniEnv = env.pointed.pointed!!
    
    val stats = hush_engine_get_stats(engine)
    
    val result = jniEnv.NewDoubleArray!!.invoke(env, 5) ?: return null
    val resultPtr = jniEnv.GetDoubleArrayElements!!.invoke(env, result, null) ?: return null
    
    stats.useContents {
        resultPtr[0] = total_input_samples.toDouble()
        resultPtr[1] = total_output_samples.toDouble()
        resultPtr[2] = total_removed_samples.toDouble()
        resultPtr[3] = reduction_percentage
        resultPtr[4] = silent_segments_detected.toDouble()
    }
    
    jniEnv.ReleaseDoubleArrayElements!!.invoke(env, result, resultPtr, 0)
    return result
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_Hush_hushDestroy")
fun hushDestroy(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long) {
    val engine = handle.toCPointer<hush_engine_t>() ?: return
    hush_engine_destroy(engine)
}

// Recorder JNI Export

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderCreate")
fun recorderCreate(
    env: CPointer<JNIEnvVar>, thiz: jobject, outputFile: jstring, 
    thresholdDb: Double, aggressionLevel: Double, sampleRate: Int, useSilenceRemoval: Int
): Long {
    val jniEnv = env.pointed.pointed!!
    val strChars = jniEnv.GetStringUTFChars!!.invoke(env, outputFile, null) ?: return 0L
    
    val handle = memScoped {
        val config = alloc<hush_recorder_config_t>()
        config.output_file = strChars
        config.threshold_db = thresholdDb
        config.aggression_level = aggressionLevel
        config.sample_rate = sampleRate
        config.use_silence_removal = useSilenceRemoval
        
        hush_recorder_create(config.readValue()).toLong()
    }
    
    jniEnv.ReleaseStringUTFChars!!.invoke(env, outputFile, strChars)
    return handle
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderStart")
fun recorderStart(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): Int {
    val recorder = handle.toCPointer<hush_recorder_t>() ?: return 0
    return hush_recorder_start(recorder)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderStop")
fun recorderStop(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long) {
    val recorder = handle.toCPointer<hush_recorder_t>() ?: return
    hush_recorder_stop(recorder)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderIsRecording")
fun recorderIsRecording(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): Int {
    val recorder = handle.toCPointer<hush_recorder_t>() ?: return 0
    return hush_recorder_is_recording(recorder)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderGetStats")
fun recorderGetStats(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): jdoubleArray? {
    val recorder = handle.toCPointer<hush_recorder_t>() ?: return null
    val jniEnv = env.pointed.pointed!!
    
    val stats = hush_recorder_get_stats(recorder)
    
    val result = jniEnv.NewDoubleArray!!.invoke(env, 5) ?: return null
    val resultPtr = jniEnv.GetDoubleArrayElements!!.invoke(env, result, null) ?: return null
    
    stats.useContents {
        resultPtr[0] = total_input_samples.toDouble()
        resultPtr[1] = total_output_samples.toDouble()
        resultPtr[2] = total_removed_samples.toDouble()
        resultPtr[3] = reduction_percentage
        resultPtr[4] = silent_segments_detected.toDouble()
    }
    
    jniEnv.ReleaseDoubleArrayElements!!.invoke(env, result, resultPtr, 0)
    return result
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderDestroy")
fun recorderDestroy(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long) {
    val recorder = handle.toCPointer<hush_recorder_t>() ?: return
    hush_recorder_destroy(recorder)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderGetPressure")
fun recorderGetPressure(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): Int {
    val recorder = handle.toCPointer<hush_recorder_t>() ?: return 0
    return hush_recorder_get_pressure(recorder)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderGetDegradationState")
fun recorderGetDegradationState(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): Int {
    val recorder = handle.toCPointer<hush_recorder_t>() ?: return 0
    return hush_recorder_get_degradation_state(recorder)
}

// Metering & Utilities JNI

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_Hush_hushGetCurrentDb")
fun hushGetCurrentDb(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): Double {
    val engine = handle.toCPointer<hush_engine_t>() ?: return -100.0
    return hush_engine_get_current_db(engine)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushRecorder_recorderGetCurrentDb")
fun recorderGetCurrentDb(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): Double {
    val recorder = handle.toCPointer<hush_recorder_t>() ?: return -100.0
    return hush_recorder_get_current_db(recorder)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushUtils_hushSetLogLevel")
fun hushSetLogLevel(env: CPointer<JNIEnvVar>, thiz: jobject, level: Int) {
    hush_set_log_level(level)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushUtils_hushCalculateRmsDb")
fun hushCalculateRmsDb(env: CPointer<JNIEnvVar>, thiz: jobject, samples: jshortArray): Double {
    val jniEnv = env.pointed.pointed!!
    val len = jniEnv.GetArrayLength!!.invoke(env, samples)
    val ptr = jniEnv.GetShortArrayElements!!.invoke(env, samples, null) ?: return -100.0
    val rms = hush_calculate_rms_db(ptr, len)
    jniEnv.ReleaseShortArrayElements!!.invoke(env, samples, ptr, 0)
    return rms
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushUtils_hushSaveWav")
fun hushSaveWav(env: CPointer<JNIEnvVar>, thiz: jobject, filepath: jstring, samples: jfloatArray, count: Int, sampleRate: Int): Int {
    val jniEnv = env.pointed.pointed!!
    val strChars = jniEnv.GetStringUTFChars!!.invoke(env, filepath, null) ?: return 0
    val floatPtr = jniEnv.GetFloatArrayElements!!.invoke(env, samples, null) ?: run {
        jniEnv.ReleaseStringUTFChars!!.invoke(env, filepath, strChars)
        return 0
    }
    
    val result = hush_save_wav(strChars.toKString(), floatPtr, count, sampleRate)
    
    jniEnv.ReleaseFloatArrayElements!!.invoke(env, samples, floatPtr, 0)
    jniEnv.ReleaseStringUTFChars!!.invoke(env, filepath, strChars)
    return result
}

// HushPlayer JNI Export

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushPlayer_playerCreate")
fun playerCreate(env: CPointer<JNIEnvVar>, thiz: jobject): Long {
    return hush_player_create().toLong()
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushPlayer_playerDestroy")
fun playerDestroy(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long) {
    val player = handle.toCPointer<hush_player_t>() ?: return
    hush_player_destroy(player)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushPlayer_playerPlay")
fun playerPlay(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long, filepath: jstring): Int {
    val player = handle.toCPointer<hush_player_t>() ?: return 0
    val jniEnv = env.pointed.pointed!!
    val strChars = jniEnv.GetStringUTFChars!!.invoke(env, filepath, null) ?: return 0
    val result = hush_player_play(player, strChars.toKString())
    jniEnv.ReleaseStringUTFChars!!.invoke(env, filepath, strChars)
    return result
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushPlayer_playerPlayBuffer")
fun playerPlayBuffer(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long, samples: jfloatArray, count: Int, sampleRate: Int): Int {
    val player = handle.toCPointer<hush_player_t>() ?: return 0
    val jniEnv = env.pointed.pointed!!
    val floatPtr = jniEnv.GetFloatArrayElements!!.invoke(env, samples, null) ?: return 0
    val result = hush_player_play_buffer(player, floatPtr, count, sampleRate)
    jniEnv.ReleaseFloatArrayElements!!.invoke(env, samples, floatPtr, 0)
    return result
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushPlayer_playerTogglePause")
fun playerTogglePause(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long) {
    val player = handle.toCPointer<hush_player_t>() ?: return
    hush_player_toggle_pause(player)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushPlayer_playerStop")
fun playerStop(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long) {
    val player = handle.toCPointer<hush_player_t>() ?: return
    hush_player_stop(player)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushPlayer_playerIsPlaying")
fun playerIsPlaying(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): Int {
    val player = handle.toCPointer<hush_player_t>() ?: return 0
    return hush_player_is_playing(player)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_myai_hush_engine_HushPlayer_playerIsFinished")
fun playerIsFinished(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long): Int {
    val player = handle.toCPointer<hush_player_t>() ?: return 0
    return hush_player_is_finished(player)
}

