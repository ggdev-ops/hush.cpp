package klama.hush

import klama.hush.engine.HushRecorder
import java.util.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Hush! Terminal Recorder (Kotlin)")
        println("Usage: java -jar hush-recorder.jar <output.wav> [threshold_db] [aggression] [--play] [--stop-record <sec>]")
        exitProcess(1)
    }

    var outputFile = ""
    var threshold = -40.0
    var aggression = 1.0
    var shouldPlay = false
    var stopAfterSeconds = -1.0
    var useSilenceRemoval = true

    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--play" -> shouldPlay = true
            arg == "--no-silence-remove" -> useSilenceRemoval = false
            arg == "--stop-record" -> {
                if (i + 1 < args.size) {
                    var valStr = args[++i]
                    if (valStr == "after" && i + 1 < args.size) {
                        valStr = args[++i]
                    }
                    stopAfterSeconds = valStr.toDoubleOrNull() ?: -1.0
                }
            }
            outputFile == "" -> outputFile = arg
            arg.startsWith("-") -> {
                // Ignore other flags or handle threshold/aggression
            }
            else -> {
                val value = arg.toDoubleOrNull()
                if (value != null) {
                    if (threshold == -40.0 && value < 0) threshold = value
                    else if (aggression == 1.0) aggression = value
                }
            }
        }
        i++
    }

    if (outputFile == "") {
        println("Error: Output filename required.")
        exitProcess(1)
    }

    try {
        val recorder = HushRecorder(
            outputFile = outputFile,
            thresholdDb = threshold,
            aggressionLevel = aggression,
            sampleRate = 16000,
            useSilenceRemoval = useSilenceRemoval
        )

        println("Recording to $outputFile...")
        if (useSilenceRemoval) {
            println("Threshold: $threshold dB, Aggression: $aggression")
        } else {
            println("Silence removal disabled.")
        }

        if (!recorder.start()) {
            println("Failed to start recorder.")
            exitProcess(1)
        }

        if (stopAfterSeconds > 0) {
            println("Will stop after $stopAfterSeconds seconds.")
            Thread.sleep((stopAfterSeconds * 1000).toLong())
        } else {
            println("Press Enter to stop recording...")
            val scanner = Scanner(System.`in`)
            if (scanner.hasNextLine()) {
                scanner.nextLine()
            }
        }

        recorder.stop()
        println("Recording finished.")

        if (useSilenceRemoval) {
            val stats = recorder.getStats()
            println("Stats: ${stats.silentSegmentsDetected} silent segments removed, ${"%.2f".format(stats.reductionPercentage)}% reduction.")
        }

        recorder.close()

        if (shouldPlay) {
            println("Playback is not implemented in this Kotlin example yet (requires audio library).")
        }

    } catch (e: Exception) {
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}
