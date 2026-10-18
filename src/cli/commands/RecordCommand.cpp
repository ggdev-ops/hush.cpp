#include "RecordCommand.h"
#include "PlayCommand.h"
#include "TerminalUtils.h"
#include "core/Logger.h"
#include "core/AudioRecorder.h"

#include <string>
#include <chrono>
#include <thread>
#include <cstdio>

int handle_record_command(int argc, char* argv[]) {
    if (argc < 3) {
        Logger::error("Usage: hush record <output.wav> [options]");
        return 1;
    }

    AudioRecorder::Config config;
    config.output_file = argv[2];
    bool shouldPlay = false;
    double stopAfterSeconds = -1.0;

    for (int i = 3; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "-t" || arg == "--threshold") {
            if (i + 1 < argc) {
                try { config.threshold_db = std::stod(argv[++i]); } catch(...) {}
            }
        } else if (arg == "-a" || arg == "--aggression") {
            if (i + 1 < argc) {
                try { config.aggression_level = std::stod(argv[++i]); } catch(...) {}
            }
        } else if (arg == "--no-silence-remove") {
            config.use_silence_removal = false;
        } else if (arg == "--play") {
            shouldPlay = true;
        } else if (arg == "--stop-record") {
            if (i + 1 < argc) {
                std::string val = argv[++i];
                if (val == "after" && i + 1 < argc) {
                    val = argv[++i];
                }
                try { stopAfterSeconds = std::stod(val); } catch(...) {}
            }
        }
    }

    AudioRecorder recorder(config);
    if (!recorder.start()) {
        Logger::error("Failed to start recorder.");
        return 1;
    }

    if (stopAfterSeconds > 0) {
        Logger::info("Recording... (will stop after %.2f seconds)", stopAfterSeconds);
    } else {
        Logger::info("Recording... Press any key to stop.");
    }

    auto start_time = std::chrono::steady_clock::now();
    while (recorder.isRecording()) {
        if (stopAfterSeconds > 0) {
            auto now = std::chrono::steady_clock::now();
            std::chrono::duration<double> elapsed = now - start_time;
            if (elapsed.count() >= stopAfterSeconds) break;
        } else {
            if (get_keypress() != -1) break;
        }

        if (config.use_silence_removal) {
            HushStats stats = recorder.getStats();
            draw_vu_meter(recorder.getCurrentDb(), config.threshold_db, stats.totalOutputSamples);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    recorder.stop();
    printf("\n"); // Clear VU meter line
    Logger::info("Recording finished.");

    if (config.use_silence_removal) {
        HushStats stats = recorder.getStats();
        Logger::info("Silence Removal Stats:");
        Logger::info("  Silent segments: %d", stats.silentSegmentsDetected);
        Logger::info("  Reduction:       %.2f%%", stats.reductionPercentage);
    }

    if (shouldPlay) {
        Logger::info("Playing back recording...");
        return run_play_command(config.output_file);
    }

    return 0;
}
