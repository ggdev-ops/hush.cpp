package klama.hush.audio

import kotlinx.coroutines.*
import java.io.File
import javax.sound.sampled.*

/**
 * JVM implementation of [AudioRecorder] using Java Sound API.
 */
class JvmAudioRecorder : AudioRecorder {

    private var targetLine: TargetDataLine? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var audioStream: AudioInputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val audioFormat = AudioFormat(16000f, 16, 1, true, false)

    override fun startRecording(outputFile: String) {
        if (isRecording) return

        val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
        if (!AudioSystem.isLineSupported(info)) {
            println("Line not supported")
            return
        }

        targetLine = AudioSystem.getLine(info) as TargetDataLine
        targetLine?.open(audioFormat)
        targetLine?.start()

        isRecording = true
        audioStream = AudioInputStream(targetLine)

        recordingJob = scope.launch {
            val file = File(outputFile)
            try {
                AudioSystem.write(audioStream!!, AudioFileFormat.Type.WAVE, file)
            } catch (e: Exception) {
                if (isRecording) e.printStackTrace()
            }
        }
    }

    override suspend fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        
        targetLine?.stop()
        targetLine?.close()
        targetLine = null
        
        try {
            audioStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioStream = null
        
        recordingJob?.join()
        recordingJob = null
    }

    override fun isRecording(): Boolean = isRecording
}

