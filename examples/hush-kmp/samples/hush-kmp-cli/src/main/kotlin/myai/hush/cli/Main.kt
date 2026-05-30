package myai.hush.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import myai.hush.engine.Hush
import myai.hush.engine.HushConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class HushCli : CliktCommand() {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "Hush! KMP CLI Tool - Silence Detection for WAV/PCM files"
    
    val inputPath by argument(help = "Input WAV/PCM file or directory").file(mustExist = true)
    val outputPath by argument(help = "Output WAV/PCM file or directory").file()

    val threshold by option("-t", "--threshold", help = "Silence threshold in dB").double().default(-40.0)
    val aggression by option("-a", "--aggression", help = "Aggression level (0.0-1.0)").double().default(1.0)
    val dryRun by option("--dry-run", help = "Analyze without writing output").flag(default = false)

    override fun run() {
        val config = HushConfig(
            thresholdDb = threshold,
            aggressionLevel = aggression,
            sampleRate = 16000 // Default to 16kHz as per project standards
        )

        if (inputPath.isDirectory) {
            processDirectory(inputPath, outputPath, config)
        } else {
            processFile(inputPath, outputPath, config)
        }
    }

    private fun processDirectory(inputDir: File, outputDir: File, config: HushConfig) {
        if (!dryRun && !outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        val files = inputDir.listFiles { _, name -> 
            name.endsWith(".wav", ignoreCase = true) || name.endsWith(".pcm", ignoreCase = true) 
        } ?: emptyArray()

        println("Processing directory: ${inputDir.absolutePath} (${files.size} files found)")
        files.forEach { file ->
            val outFile = if (dryRun) File("dry-run") else File(outputDir, file.name)
            processFile(file, outFile, config)
        }
    }

    private fun processFile(inputFile: File, outputFile: File, config: HushConfig) {
        println("\n--- Processing: ${inputFile.name} ---")
        val hush = Hush(config)
        
        try {
            val (pcmData, originalFormat) = readAudio(inputFile)
            
            println("Engine Initialized: ${config.thresholdDb}dB threshold, ${config.aggressionLevel} aggression")
            println("Input size: ${pcmData.size} samples")

            val processedPcm = mutableListOf<Short>()
            val blockSize = 1152 * 10
            var offset = 0
            while (offset < pcmData.size) {
                val end = minOf(offset + blockSize, pcmData.size)
                val chunk = pcmData.copyOfRange(offset, end)
                processedPcm.addAll(hush.process(chunk).toList())
                offset += blockSize
            }
            processedPcm.addAll(hush.flush().toList())

            val stats = hush.getStats()
            println("Done. Stats: $stats")

            if (!dryRun) {
                writeAudio(outputFile, processedPcm.toShortArray(), originalFormat)
                println("Saved to: ${outputFile.absolutePath}")
            } else {
                println("[Dry Run] Output file would be ${outputFile.name}")
            }

        } catch (e: Exception) {
            println("Error processing ${inputFile.name}: ${e.message}")
        } finally {
            hush.close()
        }
    }

    private fun readAudio(file: File): Pair<ShortArray, AudioFormat?> {
        return if (file.extension.lowercase() == "wav") {
            val audioStream = AudioSystem.getAudioInputStream(file)
            val format = audioStream.format
            
            // Check if we need resampling (Hush prefers 16kHz Mono)
            // For simplicity in this example, we assume 16kHz Mono or we throw.
            // A production tool would use a Resampler here.
            if (format.sampleRate != 16000f || format.channels != 1) {
                println("Warning: ${file.name} is ${format.sampleRate}Hz ${format.channels}ch. Normalization recommended.")
            }

            val bytes = audioStream.readAllBytes()
            val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val shorts = ShortArray(shortBuffer.remaining())
            shortBuffer.get(shorts)
            shorts to format
        } else {
            // Assume raw PCM 16-bit LE
            val bytes = file.readBytes()
            val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val shorts = ShortArray(shortBuffer.remaining())
            shortBuffer.get(shorts)
            shorts to null
        }
    }

    private fun writeAudio(file: File, pcm: ShortArray, format: AudioFormat?) {
        val byteBuffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { byteBuffer.putShort(it) }
        val bytes = byteBuffer.array()

        if (format != null) {
            val bais = java.io.ByteArrayInputStream(bytes)
            val outStream = AudioInputStream(bais, format, pcm.size.toLong())
            AudioSystem.write(outStream, AudioFileFormat.Type.WAVE, file)
        } else {
            file.writeBytes(bytes)
        }
    }
}

fun main(args: Array<String>) {
    HushCli().main(args)
}

