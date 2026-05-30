package myai.hush.engine

actual class HushPlayer actual constructor() {
    private var handle: Long = 0

    init {
        handle = playerCreate()
        if (handle == 0L) {
            throw IllegalStateException("Failed to create Hush player")
        }
    }

    actual fun play(filepath: String): Boolean {
        if (handle == 0L) return false
        return playerPlay(handle, filepath) != 0
    }

    actual fun playBuffer(samples: FloatArray, count: Int, sampleRate: Int): Boolean {
        if (handle == 0L) return false
        return playerPlayBuffer(handle, samples, count, sampleRate) != 0
    }

    actual fun togglePause() {
        if (handle != 0L) {
            playerTogglePause(handle)
        }
    }

    actual fun stop() {
        if (handle != 0L) {
            playerStop(handle)
        }
    }

    actual fun isPlaying(): Boolean {
        if (handle == 0L) return false
        return playerIsPlaying(handle) != 0
    }

    actual fun isFinished(): Boolean {
        if (handle == 0L) return false
        return playerIsFinished(handle) != 0
    }

    actual fun close() {
        if (handle != 0L) {
            playerDestroy(handle)
            handle = 0
        }
    }

    private external fun playerPlay(handle: Long, filepath: String): Int
    private external fun playerPlayBuffer(handle: Long, samples: FloatArray, count: Int, sampleRate: Int): Int
    private external fun playerTogglePause(handle: Long)
    private external fun playerStop(handle: Long)
    private external fun playerIsPlaying(handle: Long): Int
    private external fun playerIsFinished(handle: Long): Int
    private external fun playerDestroy(handle: Long)

    companion object {
        @JvmStatic
        private external fun playerCreate(): Long

        init {
            try {
                System.loadLibrary("myai_hush_android")
            } catch (e: UnsatisfiedLinkError) {
                // Ignore
            }
        }
    }
}
