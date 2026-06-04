package klama.hush

class AudioPlayer : AutoCloseable {
    private var handle: Long = 0

    init {
        handle = audioPlayerCreate()
        if (handle == 0L) {
            throw IllegalStateException("Failed to create AudioPlayer")
        }
    }

    fun play(filepath: String): Boolean {
        if (handle == 0L) return false
        return audioPlayerPlay(handle, filepath)
    }

    fun playBuffer(samples: FloatArray, sampleRate: Int): Boolean {
        if (handle == 0L) return false
        return audioPlayerPlayBuffer(handle, samples, sampleRate)
    }

    fun togglePause() {
        if (handle == 0L) return
        audioPlayerTogglePause(handle)
    }

    fun stop() {
        if (handle == 0L) return
        audioPlayerStop(handle)
    }

    fun isPlaying(): Boolean {
        if (handle == 0L) return false
        return audioPlayerIsPlaying(handle)
    }

    fun isFinished(): Boolean {
        if (handle == 0L) return true
        return audioPlayerIsFinished(handle)
    }

    override fun close() {
        if (handle != 0L) {
            audioPlayerDestroy(handle)
            handle = 0
        }
    }

    private external fun audioPlayerPlay(handle: Long, filepath: String): Boolean
    private external fun audioPlayerPlayBuffer(handle: Long, samples: FloatArray, sampleRate: Int): Boolean
    private external fun audioPlayerTogglePause(handle: Long)
    private external fun audioPlayerStop(handle: Long)
    private external fun audioPlayerIsPlaying(handle: Long): Boolean
    private external fun audioPlayerIsFinished(handle: Long): Boolean
    private external fun audioPlayerDestroy(handle: Long)

    companion object {
        @JvmStatic
        private external fun audioPlayerCreate(): Long

        init {
            try {
                System.loadLibrary("hush_core")
                System.loadLibrary("hush_ffi")
            } catch (e: UnsatisfiedLinkError) {
                // Ignore if already loaded or not found
            }
            System.loadLibrary("klama_hush_android")
        }
    }
}
