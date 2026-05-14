package klama.hush

import klama.hush.engine.Hush
import klama.hush.engine.HushConfig
import java.awt.Dimension
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.concurrent.thread

class HushApp : JFrame("Hush JVM - Silence Remover") {

    private val statusArea = JTextArea(10, 40).apply {
        isEditable = false
        text = "Hush Engine Ready.\nSelect a .wav file to process."
    }
    
    private val selectButton = JButton("Select .wav File")
    private val startButton = JButton("Start Hush").apply { isEnabled = false }
    private val saveButton = JButton("Save Processed File").apply { isEnabled = false }
    
    private var selectedFile: File? = null
    private var processedAudio: ShortArray? = null
    private val sampleRate = 16000

    private val presets = listOf(
        PresetConfig("Slightly", -45.0, 0.2),
        PresetConfig("Gentle", -35.0, 0.4),
        PresetConfig("Default", -30.0, 0.5),
        PresetConfig("Aggressive", -25.0, 0.8),
        PresetConfig("More Aggressive", -20.0, 0.95)
    )

    private val configCombo = JComboBox(presets.toTypedArray()).apply {
        selectedIndex = 2 // Default
    }

    data class PresetConfig(val name: String, val thresholdDb: Double, val aggressionLevel: Double) {
        override fun toString() = name
    }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(20, 20, 20, 20)
        }

        root.add(JLabel("Hush Aggression Level:"))
        root.add(Box.createRigidArea(Dimension(0, 5)))
        configCombo.maximumSize = Dimension(Short.MAX_VALUE.toInt(), 30)
        root.add(configCombo)
        root.add(Box.createRigidArea(Dimension(0, 15)))

        selectButton.addActionListener { selectFile() }
        selectButton.alignmentX = CENTER_ALIGNMENT
        root.add(selectButton)
        root.add(Box.createRigidArea(Dimension(0, 10)))

        startButton.addActionListener { startHush() }
        startButton.alignmentX = CENTER_ALIGNMENT
        root.add(startButton)
        root.add(Box.createRigidArea(Dimension(0, 10)))

        saveButton.addActionListener { saveFile() }
        saveButton.alignmentX = CENTER_ALIGNMENT
        root.add(saveButton)
        root.add(Box.createRigidArea(Dimension(0, 15)))

        root.add(JScrollPane(statusArea))

        contentPane.add(root)
        pack()
        setLocationRelativeTo(null)
    }

    private fun selectFile() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedFile = chooser.selectedFile
            processedAudio = null
            saveButton.isEnabled = false
            startButton.isEnabled = true
            statusArea.text = "File Selected: ${selectedFile?.name}\nClick 'Start Hush' to begin."
        }
    }

    private fun startHush() {
        val file = selectedFile ?: return
        val preset = configCombo.selectedItem as PresetConfig
        
        statusArea.text = "Processing ${file.name}...\nMode: ${preset.name}"
        setUIEnabled(false)

        thread {
            try {
                val audioData = readWavPcmData(file)
                
                val config = HushConfig(
                    thresholdDb = preset.thresholdDb,
                    aggressionLevel = preset.aggressionLevel,
                    sampleRate = sampleRate
                )

                Hush(config).use { hush ->
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
                        File: ${file.name}
                        Total Input: ${stats.totalInputSamples} samples
                        Total Output: ${stats.totalOutputSamples} samples
                        Removed: ${stats.totalRemovedSamples}
                        Reduction: ${String.format("%.2f", stats.reductionPercentage)}%
                        Silent Segments: ${stats.silentSegmentsDetected}
                    """.trimIndent()

                    SwingUtilities.invokeLater {
                        statusArea.text = result
                        setUIEnabled(true)
                        saveButton.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusArea.text = "Error: ${e.message}"
                    setUIEnabled(true)
                }
                e.printStackTrace()
            }
        }
    }

    private fun saveFile() {
        val audio = processedAudio ?: return
        val chooser = JFileChooser().apply {
            selectedFile = File("hushed_${selectedFile?.name ?: "audio.wav"}")
        }
        
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val outFile = chooser.selectedFile
            statusArea.text = "Saving to ${outFile.name}..."
            
            thread {
                try {
                    FileOutputStream(outFile).use { os ->
                        writeWavFile(os, audio, sampleRate)
                    }
                    SwingUtilities.invokeLater {
                        statusArea.text = "File saved successfully!\n" + statusArea.text
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        statusArea.text = "Error saving file: ${e.message}"
                    }
                }
            }
        }
    }

    private fun setUIEnabled(enabled: Boolean) {
        selectButton.isEnabled = enabled
        startButton.isEnabled = enabled && selectedFile != null
        saveButton.isEnabled = enabled && processedAudio != null
        configCombo.isEnabled = enabled
    }

    private fun readWavPcmData(file: File): ShortArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
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
                buffer.position(buffer.position() + chunkSize)
            }
        }

        if (dataPos == -1) throw Exception("No 'data' chunk found in WAV")

        val shorts = ShortArray(dataSize / 2)
        buffer.position(dataPos)
        buffer.asShortBuffer().get(shorts)
        return shorts
    }

    private fun writeWavFile(os: OutputStream, audioData: ShortArray, sampleRate: Int) {
        val audioBytes = ByteBuffer.allocate(audioData.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            asShortBuffer().put(audioData)
        }.array()
        
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46464952) // "RIFF"
            putInt(36 + audioBytes.size)
            putInt(0x45564157) // "WAVE"
            putInt(0x20746d66) // "fmt "
            putInt(16)
            putShort(1.toShort()) // PCM
            putShort(1.toShort()) // Mono
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            putInt(0x61746164) // "data"
            putInt(audioBytes.size)
        }.array()
        
        os.write(header)
        os.write(audioBytes)
    }
}

fun main() {
    SwingUtilities.invokeLater {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        HushApp().isVisible = true
    }
}

