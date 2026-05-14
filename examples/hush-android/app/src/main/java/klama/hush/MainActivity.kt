package klama.hush

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : Activity() {

    private lateinit var statusTextView: TextView
    private lateinit var selectButton: Button
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    private lateinit var configSpinner: Spinner
    private var selectedFileUri: Uri? = null
    private var processedAudio: ShortArray? = null
    private val sampleRate = 16000

    private val configs = listOf(
        PresetConfig("Slightly", -45.0, 0.2),
        PresetConfig("Gentle", -35.0, 0.4),
        PresetConfig("Default", -30.0, 0.5),
        PresetConfig("Aggressive", -25.0, 0.8),
        PresetConfig("More Aggressive", -20.0, 0.95)
    )

    data class PresetConfig(val name: String, val thresholdDb: Double, val aggressionLevel: Double) {
        override fun toString() = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        TextView(this).apply {
            text = "Hush Aggression Level:"
            setPadding(0, 0, 0, 16)
            root.addView(this)
        }

        configSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, configs)
            setSelection(2) // Default
            setPadding(0, 0, 0, 32)
            root.addView(this)
        }

        selectButton = Button(this).apply {
            text = "Select .wav File"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    val mimeTypes = arrayOf("audio/wav", "audio/x-wav", "audio/vnd.wave")
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
                startActivityForResult(intent, PICK_WAV_FILE)
            }
            root.addView(this)
        }

        startButton = Button(this).apply {
            text = "Start Hush"
            isEnabled = false
            setOnClickListener {
                selectedFileUri?.let { processWavFile(it) }
            }
            root.addView(this)
        }

        saveButton = Button(this).apply {
            text = "Save Processed File"
            isEnabled = false
            setOnClickListener {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/wav"
                    putExtra(Intent.EXTRA_TITLE, "hushed_audio.wav")
                }
                startActivityForResult(intent, SAVE_WAV_FILE)
            }
            root.addView(this)
        }

        statusTextView = TextView(this).apply {
            text = "Hush Engine Ready.\nSelect a .wav file to process."
            textSize = 16f
            setPadding(0, 32, 0, 0)
            root.addView(this)
        }

        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            when (requestCode) {
                PICK_WAV_FILE -> data.data?.let { uri ->
                    selectedFileUri = uri
                    processedAudio = null
                    saveButton.isEnabled = false
                    startButton.isEnabled = true
                    statusTextView.text = "File Selected: ${uri.lastPathSegment}\nClick 'Start Hush' to begin."
                }
                SAVE_WAV_FILE -> data.data?.let { saveProcessedFile(it) }
            }
        }
    }

    private fun processWavFile(uri: Uri) {
        val preset = configSpinner.selectedItem as PresetConfig
        statusTextView.text = "Processing ${uri.path}...\nMode: ${preset.name}"
        selectButton.isEnabled = false
        startButton.isEnabled = false
        saveButton.isEnabled = false
        configSpinner.isEnabled = false
        processedAudio = null

        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Failed to open input stream")
                val audioData = readWavPcmData(inputStream)
                
                val config = HushConfig(
                    thresholdDb = preset.thresholdDb, 
                    aggressionLevel = preset.aggressionLevel, 
                    sampleRate = sampleRate
                )
                
                Hush(config).use { hush ->
                    Log.i("Hush", "Engine initialized for file processing. Mode: ${preset.name}")
                    
                    val processed = hush.process(audioData)
                    val flushed = hush.flush()
                    
                    processedAudio = ShortArray(processed.size + flushed.size).apply {
                        processed.copyInto(this)
                        flushed.copyInto(this, processed.size)
                    }
                    
                    val stats = hush.getStats()
                    val result = """
                        Processing complete!
                        
                        Mode: ${preset.name}
                        File: ${uri.lastPathSegment}
                        Total Input: ${stats.totalInputSamples} samples
                        Total Output: ${stats.totalOutputSamples} samples
                        Removed: ${stats.totalRemovedSamples}
                        Reduction: ${String.format("%.2f", stats.reductionPercentage)}%
                        Silent Segments: ${stats.silentSegmentsDetected}
                    """.trimIndent()
                    
                    runOnUiThread {
                        statusTextView.text = result
                        selectButton.isEnabled = true
                        startButton.isEnabled = true
                        saveButton.isEnabled = true
                        configSpinner.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusTextView.text = "Error: ${e.message}"
                    selectButton.isEnabled = true
                    startButton.isEnabled = true
                    configSpinner.isEnabled = true
                }
                Log.e("Hush", "Error processing file", e)
            }
        }.start()
    }

    private fun saveProcessedFile(uri: Uri) {
        val audio = processedAudio ?: return
        statusTextView.text = "Saving to ${uri.path}..."
        
        Thread {
            try {
                val outputStream = contentResolver.openOutputStream(uri) ?: throw Exception("Failed to open output stream")
                writeWavFile(outputStream, audio, sampleRate)
                runOnUiThread {
                    statusTextView.text = "File saved successfully!\n" + statusTextView.text
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusTextView.text = "Error saving file: ${e.message}"
                }
                Log.e("Hush", "Error saving file", e)
            }
        }.start()
    }

    private fun readWavPcmData(inputStream: InputStream): ShortArray {
        val buffer = ByteBuffer.wrap(inputStream.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        
        // Check RIFF header
        val riff = buffer.getInt()
        if (riff != 0x46464952) throw Exception("Not a RIFF file")
        buffer.getInt() // skip size
        val wave = buffer.getInt()
        if (wave != 0x45564157) throw Exception("Not a WAVE file")

        var dataPos = -1
        var dataSize = -1

        while (buffer.remaining() >= 8) {
            val chunkId = buffer.getInt()
            val chunkSize = buffer.getInt()
            if (chunkId == 0x61746164) { // "data"
                dataPos = buffer.position()
                dataSize = chunkSize
                break
            } else {
                try {
                    buffer.position(buffer.position() + chunkSize)
                } catch (e: Exception) {
                    throw Exception("Malformed WAV file: could not find data chunk")
                }
            }
        }

        if (dataPos == -1) throw Exception("No 'data' chunk found in WAV")

        val shorts = ShortArray(dataSize / 2)
        buffer.position(dataPos)
        buffer.asShortBuffer().get(shorts)
        return shorts
    }

    private fun writeWavFile(outputStream: OutputStream, audioData: ShortArray, sampleRate: Int) {
        val bytes = ByteBuffer.allocate(audioData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(audioData)
        val audioBytes = bytes.array()
        
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        
        // RIFF header
        header.putInt(0x46464952) // "RIFF"
        header.putInt(36 + audioBytes.size) // Total size - 8
        header.putInt(0x45564157) // "WAVE"
        
        // FMT chunk
        header.putInt(0x20746d66) // "fmt "
        header.putInt(16) // Subchunk size (16 for PCM)
        header.putShort(1.toShort()) // Audio format (1 for PCM)
        header.putShort(1.toShort()) // Num channels (1 for mono)
        header.putInt(sampleRate) // Sample rate
        header.putInt(sampleRate * 2) // Byte rate (SampleRate * NumChannels * BitsPerSample/8)
        header.putShort(2.toShort()) // Block align (NumChannels * BitsPerSample/8)
        header.putShort(16.toShort()) // Bits per sample
        
        // Data chunk
        header.putInt(0x61746164) // "data"
        header.putInt(audioBytes.size)
        
        outputStream.use {
            it.write(header.array())
            it.write(audioBytes)
        }
    }

    companion object {
        private const val PICK_WAV_FILE = 1
        private const val SAVE_WAV_FILE = 2
    }
}

