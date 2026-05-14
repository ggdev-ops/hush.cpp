package klama.hush.native

import klama.hush.engine.Hush
import klama.hush.engine.HushConfig
import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: ./hush-kmp-native <input.wav> [output.wav]")
        return
    }

    val inputPath = args[0]
    val outputPath = if (args.size > 1) args[1] else "output.wav"

    println("Hush! KMP Native Sample")
    println("Input: $inputPath")
    println("Output: $outputPath")

    val file = fopen(inputPath, "rb")
    if (file == null) {
        println("Error: Could not open $inputPath")
        return
    }

    // A standard WAV header is 44 bytes.
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
        thresholdDb = -40.0,
        aggressionLevel = 1.0,
        sampleRate = 16000
    )
    
    val hush = Hush(config)
    val processedPcm = mutableListOf<Short>()
    
    try {
        println("Engine Initialized: ${config.thresholdDb}dB threshold, ${config.aggressionLevel} aggression")
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
        println("Done. Stats: ${hush.getStats()}")
        
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

