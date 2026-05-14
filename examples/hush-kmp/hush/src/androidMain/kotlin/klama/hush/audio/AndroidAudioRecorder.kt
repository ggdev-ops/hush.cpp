package klama.hush.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android implementation of [AudioRecorder] using AudioRecord API for raw PCM capture.
 */
class AndroidAudioRecorder(private val context: Context) : AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    override fun startRecording(outputFile: String) {
        if (isRecording) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        recordingJob = scope.launch {
            val file = File(outputFile)
            var totalBytesRead = 0L
            try {
                FileOutputStream(file).use { output ->
                    // Write placeholder header
                    output.write(ByteArray(44))

                    val buffer = ByteArray(bufferSize)

                    while (isActive && isRecording) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            totalBytesRead += read
                        }
                    }
                }
            } finally {
                // Ensure header is updated even if cancelled
                withContext(NonCancellable) {
                    updateWavHeader(file, totalBytesRead)
                }
            }
        }
    }

    override suspend fun stopRecording() {
        isRecording = false
        recordingJob?.cancelAndJoin()
        recordingJob = null
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    override fun isRecording(): Boolean = isRecording

    private fun updateWavHeader(file: File, totalAudioBytes: Long) {
        val raf = RandomAccessFile(file, "rw")
        val header = createWavHeader(totalAudioBytes)
        raf.seek(0)
        raf.write(header)
        raf.close()
    }

    private fun createWavHeader(totalAudioBytes: Long): ByteArray {
        val totalDataLen = totalAudioBytes + 36
        val byteRate = (sampleRate * 2).toLong() // 16 bit = 2 bytes

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen.toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1.toShort()) // AudioFormat (PCM = 1)
        buffer.putShort(1.toShort()) // NumChannels
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate.toInt())
        buffer.putShort(2.toShort()) // BlockAlign
        buffer.putShort(16.toShort()) // BitsPerSample
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioBytes.toInt())

        return header
    }
}

