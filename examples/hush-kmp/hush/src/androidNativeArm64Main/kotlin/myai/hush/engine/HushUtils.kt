package myai.hush.engine

import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalNativeApi
import myai.hush.*

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual object HushUtils {
    actual fun setLogLevel(level: Int) {
        hush_set_log_level(level)
    }

    actual fun calculateRmsDb(samples: ShortArray): Double {
        if (samples.isEmpty()) return -100.0
        return samples.usePinned { pinned ->
            hush_calculate_rms_db(pinned.addressOf(0).reinterpret(), samples.size)
        }
    }

    actual fun saveWav(filepath: String, samples: FloatArray, count: Int, sampleRate: Int): Boolean {
        if (samples.isEmpty() || count <= 0) return false
        val result = samples.usePinned { pinned ->
            hush_save_wav(filepath, pinned.addressOf(0).reinterpret(), count, sampleRate)
        }
        return result != 0
    }
}
