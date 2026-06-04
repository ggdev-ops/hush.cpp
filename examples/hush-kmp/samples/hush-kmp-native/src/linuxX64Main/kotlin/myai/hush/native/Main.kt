package myai.hush.native

import myai.hush.engine.HushConfig
import myai.hush.engine.createHushEngine
import kotlinx.cinterop.*
import platform.posix.*
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
    val multiplier = pow(10.0, decimals.toDouble())
    return (round(this * multiplier) / multiplier).toString()
}

fun printUsage() {
    println("==================================================")
    println("          Hush! KMP Native CLI Application        ")
    println("==================================================")
    println("Usage: hush-kmp-native <command> [options]")
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

@OptIn(ExperimentalForeignApi::class)
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

@OptIn(ExperimentalForeignApi::class)
fun handleProcess(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: hush-kmp-native process <input.wav> [output.wav] [options]")
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
    
    val file = fopen(inputPath, "rb")
    if (file == null) {
        println("Error: Could not open $inputPath")
        return
    }
    
    fseek(file, 0, SEEK_END)
    val fileSize = ftell(file).toInt()
    rewind(file)
    
    if (fileSize <= 44) {
        println("Error: File is too small to be a valid WAV")
        fclose(file)
        return
    }
    
    val header = ByteArray(44)
    header.usePinned { pinned ->
        fread(pinned.addressOf(0), 1u, 44u, file)
    }
    
    val dataSize = fileSize - 44
    val numSamples = dataSize / 2 // Assuming 16-bit PCM (2 bytes per sample)
    val inputData = ShortArray(numSamples)
    
    inputData.usePinned { pinned ->
        fread(pinned.addressOf(0), 2u, numSamples.toULong(), file)
    }
    fclose(file)
    
    val config = HushConfig(
        thresholdDb = threshold,
        aggressionLevel = aggression,
        sampleRate = sampleRate
    )
    
    val hush = createHushEngine(config)
    val processedPcm = mutableListOf<Short>()
    
    try {
        println("Engine Initialized: ${config.thresholdDb} dB threshold, ${config.aggressionLevel} aggression")
        println("Processing $numSamples samples...")
        
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
    
    // Write output WAV
    val outFile = fopen(outputPath, "wb")
    if (outFile != null) {
        val outDataSize = processedPcm.size * 2
        val outFileSize = outDataSize + 36
        
        // Update sizes in header (little-endian)
        header[4] = (outFileSize and 0xFF).toByte()
        header[5] = ((outFileSize shr 8) and 0xFF).toByte()
        header[6] = ((outFileSize shr 16) and 0xFF).toByte()
        header[7] = ((outFileSize shr 24) and 0xFF).toByte()
        
        header[40] = (outDataSize and 0xFF).toByte()
        header[41] = ((outDataSize shr 8) and 0xFF).toByte()
        header[42] = ((outDataSize shr 16) and 0xFF).toByte()
        header[43] = ((outDataSize shr 24) and 0xFF).toByte()
        
        header.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1u, 44u, outFile)
        }
        
        val outArray = processedPcm.toShortArray()
        outArray.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 2u, outArray.size.toULong(), outFile)
        }
        fclose(outFile)
        println("Saved to $outputPath")
    } else {
        println("Error: Could not write to $outputPath")
    }
}

@OptIn(ExperimentalForeignApi::class)
fun handleRecord(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: hush-kmp-native record <output.wav> [options]")
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
                platform.posix.sleep(1u)
                val db = hush.getRecorderCurrentDb()
                val pressure = hush.getRecorderPressureLevel()
                val degradation = hush.getRecorderDegradationState()
                print("\rTime: $i/$duration s | Level: ${db.format(1)} dB | Pressure: $pressure% | Degradation State: $degradation")
                platform.posix.fflush(platform.posix.stdout)
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

@OptIn(ExperimentalForeignApi::class)
fun handlePlay(args: List<String>) {
    if (args.isEmpty()) {
        println("Usage: hush-kmp-native play <input.wav>")
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
            platform.posix.usleep(100000u) // 100ms sleep
        }
        println("\nPlayback finished.")
    } finally {
        hush.close()
    }
}
