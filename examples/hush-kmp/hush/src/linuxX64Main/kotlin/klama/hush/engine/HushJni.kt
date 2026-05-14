package klama.hush.engine

import klama.jni.*
import kotlinx.cinterop.*
import platform.posix.memcpy
import kotlin.experimental.ExperimentalNativeApi
import klama.hush.*
import cnames.structs.hush_engine_t

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_klama_hush_engine_Hush_hushCreate")
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
@CName("Java_klama_hush_engine_Hush_hushProcess")
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
        
        jniEnv.ReleaseShortArrayElements!!.invoke(env, result, resultPtr, 0)
        result
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_klama_hush_engine_Hush_hushFlush")
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
@CName("Java_klama_hush_engine_Hush_hushGetStats")
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
@CName("Java_klama_hush_engine_Hush_hushDestroy")
fun hushDestroy(env: CPointer<JNIEnvVar>, thiz: jobject, handle: Long) {
    val engine = handle.toCPointer<hush_engine_t>() ?: return
    hush_engine_destroy(engine)
}

