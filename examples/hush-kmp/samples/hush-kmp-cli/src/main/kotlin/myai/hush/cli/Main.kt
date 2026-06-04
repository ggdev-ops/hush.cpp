package myai.hush.cli

import myai.hush.engine.HushConfig
import myai.hush.engine.createHushEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.pow
import kotlin.math.round

class Arguments(val args: List<String>) {
    fun getOption(name: String): String? {
        val idx = args.indexOf(name)
        if (idx != -1 && idx + 1 < args.size) {
            return args[idx + 1]
        }
        return null
    }
    
    fun hasFlag(name: String): Boolean {
        return args.contains(name)
    }
}

fun Double.format(decimals: Int): String {
    if (this.isNaN() || this.isInfinite()) return this.toString()
    val multiplier = 10.0.pow(decimals.toDouble())
    return (round(this * multiplier) / multiplier).toString()
}

fun printUsage() {
    println("==================================================")
    println("           Hush! KMP JVM CLI Application          ")
    println("==================================================")
    println("Usage: hush-kmp-cli <command> [options]")
    println()
    println("Commands:")
    println("  process <input.wav> [output.wav] [options]")
    println("    Detect silence and remove it from the input WAV file.")
    println("    Options:")
    println("      --threshold <db>        Silence threshold in dB (default: -25.0)")
    println("      --aggression <level>    Aggression level (0.0-1.0, default: 0.75)")
    println("      --sample-rate <rate>    Sample rate in Hz (default: 16000)")
    println()
    println("  record <output.wav> [options]")
    println("    Record from default microphone input directly to WAV file.")
    println("    Options:")
    println("      --duration <seconds>    Record for fixed duration in seconds (default: infinite)")
    println("      --threshold <db>        Silence threshold in dB (default: -25.0)")
    println("      --aggression <level>    Aggression level (0.0-1.0, default: 0.75)")
    println("      --sample-rate <rate>    Sample rate in Hz (default: 16000)")
    println("      --no-silence-removal    Disable real-time silence removal")
    println()
    println("  play <input.wav>")
    println("    Play back a WAV file using the real-time player.")
    println("==================================================")
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    val command = args[0]
    when (command) {
        "process" -> handleProcess(args.drop(1))
        "record" -> handleRecord(args.drop(1))
        "play" -> handlePlay(args.drop(1))
        "help", "-h", "--help" -> printUsage()
        else -> {
            println("Error: Unknown command '$command'\n")
            printUsage()
        }
    }
}

fun handleProcess(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: hush-kmp-cli process <input.wav> [output.wav] [options]")
        return
    }
    
    val inputPath = args[0]
    val parsed = Arguments(args.drop(1))
    val outputPath = if (args.size > 1 && !args[1].startsWith("-")) args[1] else "output.wav"
    
    val threshold = parsed.getOption("--threshold")?.toDoubleOrNull() ?: -25.0
    val aggression = parsed.getOption("--aggression")?.toDoubleOrNull() ?: 0.75
    val sampleRate = parsed.getOption("--sample-rate")?.toIntOrNull() ?: 16000
    
    println("Hush! Process WAV File:")
    println("  Input:  $inputPath")
    println("  Output: $outputPath")
    
    val file = File(inputPath)
    if (!file.exists()) {
        println("Error: Could not open $inputPath")
        return
    }
    
    val inputData = try {
        readAudio(file)
    } catch (e: Exception) {
        println("Error reading audio: ${e.message}")
        return
    }
    
    val config = HushConfig(
        thresholdDb = threshold,
        aggressionLevel = aggression,
        sampleRate = sampleRate
    )
    
    val hush = createHushEngine(config)
    val processedPcm = mutableListOf<Short>()
    
    try {
        println("Engine Initialized: ${config.thresholdDb} dB threshold, ${config.aggressionLevel} aggression")
        println("Processing ${inputData.size} samples...")
        
        val blockSize = 1152 * 10
        var offset = 0
        while (offset < inputData.size) {
            val end = minOf(offset + blockSize, inputData.size)
            val chunk = inputData.copyOfRange(offset, end)
            processedPcm.addAll(hush.process(chunk).toList())
            offset += blockSize
        }
        processedPcm.addAll(hush.flush().toList())
        
        val reduction = if (inputData.isNotEmpty()) {
            ((inputData.size - processedPcm.size).toDouble() / inputData.size * 100).toInt()
        } else 0
        
        println("Output size: ${processedPcm.size} samples (reduction of $reduction%)")
        println("Stats: ${hush.getStats()}")
        
    } finally {
        hush.close()
    }
    
    try {
        writeAudio(File(outputPath), processedPcm.toShortArray(), sampleRate)
        println("Saved to $outputPath")
    } catch (e: Exception) {
        println("Error writing audio: ${e.message}")
    }
}

fun handleRecord(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: hush-kmp-cli record <output.wav> [options]")
        return
    }
    
    val outputPath = args[0]
    val parsed = Arguments(args.drop(1))
    
    val duration = parsed.getOption("--duration")?.toIntOrNull()
    val threshold = parsed.getOption("--threshold")?.toDoubleOrNull() ?: -25.0
    val aggression = parsed.getOption("--aggression")?.toDoubleOrNull() ?: 0.75
    val sampleRate = parsed.getOption("--sample-rate")?.toIntOrNull() ?: 16000
    val useSilenceRemoval = !parsed.hasFlag("--no-silence-removal")
    
    val config = HushConfig(
        thresholdDb = threshold,
        aggressionLevel = aggression,
        sampleRate = sampleRate
    )
    
    val hush = createHushEngine(config)
    try {
        println("Starting recorder to output: $outputPath")
        val success = hush.startRecorder(
            outputFile = outputPath,
            thresholdDb = threshold,
            aggressionLevel = aggression,
            sampleRate = sampleRate,
            useSilenceRemoval = useSilenceRemoval
        )
        if (!success) {
            println("Error: Failed to start recorder. Check audio input device permissions and connection.")
            return
        }
        
        println("Recording in progress...")
        println("Config: Threshold $threshold dB, Aggression $aggression, Sample Rate $sampleRate Hz, Silence Removal $useSilenceRemoval")
        
        if (duration != null) {
            println("Recording for $duration seconds...")
            for (i in 1..duration) {
                Thread.sleep(1000)
                val db = hush.getRecorderCurrentDb()
                val pressure = hush.getRecorderPressureLevel()
                val degradation = hush.getRecorderDegradationState()
                print("\rTime: $i/$duration s | Level: ${db.format(1)} dB | Pressure: $pressure% | Degradation State: $degradation")
                System.out.flush()
            }
            println()
        } else {
            println("Press ENTER to stop recording...")
            readlnOrNull()
        }
        
        hush.stopRecorder()
        println("\nRecording stopped.")
        
        val stats = hush.getRecorderStats()
        println("Recording Statistics:")
        println("  Total Input Samples: ${stats.totalInputSamples}")
        println("  Total Output Samples: ${stats.totalOutputSamples}")
        println("  Total Removed Samples: ${stats.totalRemovedSamples}")
        println("  Reduction Percentage: ${stats.reductionPercentage.format(2)}%")
        println("  Silent Segments Detected: ${stats.silentSegmentsDetected}")
        
    } finally {
        hush.close()
    }
}

fun handlePlay(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: hush-kmp-cli play <input.wav>")
        return
    }
    
    val inputPath = args[0]
    println("Initializing player for: $inputPath")
    
    val hush = createHushEngine(HushConfig())
    try {
        val success = hush.play(inputPath)
        if (!success) {
            println("Error: Failed to play $inputPath")
            return
        }
        
        println("Playing... (Press Ctrl+C to exit or wait until finished)")
        
        while (!hush.isPlayerFinished()) {
            Thread.sleep(100) // 100ms sleep
        }
        println("\nPlayback finished.")
    } finally {
        hush.close()
    }
}

private fun readAudio(file: File): ShortArray {
    val audioStream = AudioSystem.getAudioInputStream(file)
    val format = audioStream.format
    val bytes = audioStream.readAllBytes()
    val order = if (format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
    val shortBuffer = ByteBuffer.wrap(bytes).order(order).asShortBuffer()
    val shorts = ShortArray(shortBuffer.remaining())
    shortBuffer.get(shorts)
    return shorts
}

private fun writeAudio(file: File, pcm: ShortArray, sampleRate: Int) {
    val byteBuffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (s in pcm) {
        byteBuffer.putShort(s)
    }
    val bytes = byteBuffer.array()
    val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
    val bais = java.io.ByteArrayInputStream(bytes)
    val outStream = AudioInputStream(bais, format, pcm.size.toLong())
    AudioSystem.write(outStream, AudioFileFormat.Type.WAVE, file)
}
