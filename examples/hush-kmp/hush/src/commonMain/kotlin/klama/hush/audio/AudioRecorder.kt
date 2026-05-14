package klama.hush.audio

/**
 * Interface for capturing audio to a file.
 * The implementation should ensure the output is 16kHz, Mono, 16-bit PCM (usually in a WAV container).
 */
interface AudioRecorder {
    /**
     * Start recording audio and save it to the specified file path.
     * @param outputFile The absolute path where the recorded audio will be saved.
     */
    fun startRecording(outputFile: String)

    /**
     * Stop the current recording process and wait for finalization.
     */
    suspend fun stopRecording()

    /**
     * Check if the recorder is currently active.
     */
    fun isRecording(): Boolean
}

