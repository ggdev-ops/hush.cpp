#include "core/AudioRecorder.h"
#include "core/AudioPlayer.h"
#include <iostream>
#include <string>
#include <chrono>
#include <thread>
#include <csignal>
#include <atomic>

std::atomic<bool> g_keep_running(true);

void signal_handler(int signal) {
    g_keep_running = false;
}

void draw_vu_meter(double db, double threshold, long long totalSamples) {
    const int width = 30;
    double minDb = -60.0;
    double maxDb = 0.0;
    double normalized = (db - minDb) / (maxDb - minDb);
    if (normalized < 0) normalized = 0;
    if (normalized > 1) normalized = 1;

    int filled = static_cast<int>(normalized * width);
    int threshPos = static_cast<int>((threshold - minDb) / (maxDb - minDb) * width);

    std::string meter = "[";
    for (int i = 0; i < width; ++i) {
        if (i == threshPos) meter += "|";
        else if (i < filled) meter += "#";
        else meter += "-";
    }
    meter += "]";

    std::string status = (db <= threshold) ? "\033[1;30m[SILENT]\033[0m" : "\033[1;32m[VOICE]\033[0m";
    
    double sizeKb = (totalSamples * 2.0) / 1024.0;
    char sizeBuf[32];
    if (sizeKb >= 1024) {
        snprintf(sizeBuf, sizeof(sizeBuf), "%.2f MB", sizeKb / 1024.0);
    } else {
        snprintf(sizeBuf, sizeof(sizeBuf), "%.1f KB", sizeKb);
    }

    printf("\r%s %6.1f dB %s %s    ", meter.c_str(), db, status.c_str(), sizeBuf);
    fflush(stdout);
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::cout << "Hush! Terminal Voice Recorder" << std::endl;
        std::cout << "Usage: hush-terminal-recorder <output_file.wav> [threshold_db] [aggression] [--play] [--stop-record <seconds>]" << std::endl;
        return 1;
    }

    AudioRecorder::Config config;
    config.output_file = "";
    bool should_play = false;
    double stop_after_seconds = -1.0;

    for(int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--play") {
            should_play = true;
        } else if (arg == "--stop-record") {
            if (i + 1 < argc) {
                try { stop_after_seconds = std::stod(argv[++i]); } catch(...) {}
            }
        } else if (config.output_file == "") {
            config.output_file = arg;
        } else if (arg[0] != '-') {
             try {
                 double val = std::stod(arg);
                 if (config.threshold_db == -40.0 && val < 0) config.threshold_db = val;
                 else if (config.aggression_level == 1.0) config.aggression_level = val;
             } catch(...) {}
        }
    }

    if (config.output_file == "") {
        std::cerr << "Error: Output filename required." << std::endl;
        return 1;
    }

    std::signal(SIGINT, signal_handler);

    AudioRecorder recorder(config);
    if (!recorder.start()) {
        std::cerr << "Failed to start recorder." << std::endl;
        return 1;
    }

    if (stop_after_seconds > 0) {
        std::cout << "Recording for " << stop_after_seconds << " seconds..." << std::endl;
    } else {
        std::cout << "Recording... Press Ctrl+C to stop." << std::endl;
    }

    auto start_time = std::chrono::steady_clock::now();
    while (g_keep_running) {
        if (stop_after_seconds > 0) {
            auto now = std::chrono::steady_clock::now();
            std::chrono::duration<double> elapsed = now - start_time;
            if (elapsed.count() >= stop_after_seconds) break;
        }

        if (config.use_silence_removal) {
            HushStats stats = recorder.getStats();
            draw_vu_meter(recorder.getCurrentDb(), config.threshold_db, stats.totalOutputSamples);
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    recorder.stop();
    std::cout << "\nRecording saved to " << config.output_file << std::endl;

    HushStats stats = recorder.getStats();
    if (config.use_silence_removal) {
        std::cout << "Stats: " << stats.silentSegmentsDetected << " segments removed, " 
                  << stats.reductionPercentage << "% reduction." << std::endl;
    }

    if (should_play) {
        std::cout << "Playing back..." << std::endl;
        AudioPlayer player;
        player.play(config.output_file);
        while (player.isPlaying() && !player.isFinished()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }

    return 0;
}
