// main.cpp: Entry point for the Hush! silence remover utility.

#include "codecs/AudioProcessor.h"
#include "core/Logger.h"
#include "core/AudioPlayer.h"
#include "core/AudioRecorder.h"
#include "core/TtsEngine.h"
#include <fstream>
#include <string>
#include <getopt.h>
#include <iostream>
#include <filesystem>
#include <vector>
#include <algorithm>
#include <chrono>
#include <thread>
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>

#include "core/StateManager.h"
#include <csignal>
#include <fcntl.h>

namespace fs = std::filesystem;

extern "C" {
#include <libavutil/log.h> // Include FFmpeg logging functions
}

#ifndef HUSH_VERSION
#define HUSH_VERSION "1.0.0"
#endif

int handle_status_command() {
    HushState state = StateManager::readState();
    if (!state.exists) {
        Logger::info("No background hush process running.");
        return 0;
    }

    // Check if PID is alive (signal 0 just checks for existence)
    if (kill(state.pid, 0) != 0) {
        Logger::info("Background process (PID: %d) is no longer running. Cleaning up state.", state.pid);
        StateManager::clearState();
        return 0;
    }

    Logger::info("--- Background Process Status ---");
    Logger::info("PID:          %d", state.pid);
    Logger::info("Status:       %s", state.status.c_str());
    Logger::info("Progress:     %s", state.progress.c_str());
    Logger::info("Current:      %s", state.currentFile.c_str());
    Logger::info("Output:       %s", state.outputPath.c_str());
    return 0;
}

int handle_stop_command() {
    HushState state = StateManager::readState();
    if (!state.exists) {
        Logger::error("No background hush process running to stop.");
        return 1;
    }

    Logger::info("Stopping background process (PID: %d)...", state.pid);
    if (kill(state.pid, SIGTERM) == 0) {
        Logger::info("Process terminated.");
        StateManager::clearState();
    } else {
        Logger::error("Failed to terminate process (PID: %d). It may have already exited.", state.pid);
        StateManager::clearState();
    }
    return 0;
}

/**
 * @brief Reads a single keypress from the terminal without blocking or echoing.
 * @return The character code, or -1 if no key was pressed.
 */
int get_keypress() {
    struct termios oldt, newt;
    int ch;
    int oldf;

    tcgetattr(STDIN_FILENO, &oldt);
    newt = oldt;
    newt.c_lflag &= ~(ICANON | ECHO);
    tcsetattr(STDIN_FILENO, TCSANOW, &newt);
    oldf = fcntl(STDIN_FILENO, F_GETFL, 0);
    fcntl(STDIN_FILENO, F_SETFL, oldf | O_NONBLOCK);

    ch = getchar();

    tcsetattr(STDIN_FILENO, TCSANOW, &oldt);
    fcntl(STDIN_FILENO, F_SETFL, oldf);

    return ch;
}

// Helper to check for supported audio extensions case-insensitively
bool is_supported_audio(const fs::path& path) {
    std::string ext = path.extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    return ext == ".mp3" || ext == ".wav";
}

int run_play_command(const std::string& pathStr, bool waitMode = false) {
    fs::path p(pathStr);
    std::vector<fs::path> files;
    bool processingFinished = !waitMode;

    auto updateFileList = [&]() {
        if (fs::is_directory(p)) {
            size_t oldSize = files.size();
            for (const auto& entry : fs::directory_iterator(p)) {
                if (entry.is_regular_file() && is_supported_audio(entry.path())) {
                    bool alreadyAdded = false;
                    for (const auto& f : files) {
                        if (f == entry.path()) { alreadyAdded = true; break; }
                    }
                    if (!alreadyAdded) files.push_back(entry.path());
                }
            }
            if (files.size() > oldSize) std::sort(files.begin(), files.end());
        } else if (files.empty() && fs::exists(p) && is_supported_audio(p)) {
            files.push_back(p);
        }
    };

    updateFileList();

    AudioPlayer player;
    size_t currentIndex = 0;
    bool stopAll = false;

    Logger::info("Controls: [Space] Pause, [Enter] Stop, [>] Next, [<] Prev");

    while (!stopAll) {
        if (currentIndex >= files.size()) {
            if (waitMode) {
                HushState state = StateManager::readState();
                if (!state.exists || kill(state.pid, 0) != 0) {
                    processingFinished = true;
                }
                
                if (processingFinished) break;
                
                Logger::info("Waiting for next processed file...");
                while (currentIndex >= files.size() && !stopAll) {
                    updateFileList();
                    if (currentIndex < files.size()) break;
                    
                    int ch = get_keypress();
                    if (ch == '\n' || ch == '\r') { stopAll = true; break; }
                    
                    usleep(500000); // Check every 500ms
                    
                    state = StateManager::readState();
                    if (!state.exists || kill(state.pid, 0) != 0) {
                        processingFinished = true;
                        break;
                    }
                }
                if (stopAll || (processingFinished && currentIndex >= files.size())) break;
            } else {
                break;
            }
        }

        fs::path currentFile = files[currentIndex];
        if (!player.play(currentFile.string())) {
            // Give it a moment, maybe the file was just created but not closed
            usleep(200000);
            if (!player.play(currentFile.string())) {
                Logger::error("Failed to play: %s", currentFile.string().c_str());
                currentIndex++;
                continue;
            }
        }

        Logger::info("[%zu/%zu] Playing: %s", currentIndex + 1, files.size(), currentFile.filename().string().c_str());

        bool nextTrack = false;
        while (!player.isFinished() && !nextTrack && !stopAll) {
            int ch = get_keypress();
            if (ch == ' ') {
                player.togglePause();
                if (player.isPlaying()) Logger::info("Resumed playback.");
                else Logger::info("Paused playback.");
            } else if (ch == '\n' || ch == '\r') {
                Logger::info("Stopped by user.");
                stopAll = true;
            } else if (ch == '>') {
                Logger::info("Skipping to next track...");
                currentIndex++;
                nextTrack = true;
            } else if (ch == '<') {
                if (currentIndex > 0) {
                    Logger::info("Going back to previous track...");
                    currentIndex--;
                    nextTrack = true;
                } else {
                    Logger::info("Already at the first track.");
                }
            }
            
            if (waitMode) updateFileList(); // Keep updating file list for total count
            usleep(10000); // 10ms
        }

        if (player.isFinished() && !nextTrack) {
            currentIndex++; // Auto-advance
        }
        
        player.stop();
    }

    if (stopAll) {
        // If we were in parallel mode, ensure the silencer stops too
        HushState state = StateManager::readState();
        if (state.exists && kill(state.pid, 0) == 0) {
            kill(state.pid, SIGTERM);
            StateManager::clearState();
        }
    }

    if (!stopAll) {
        Logger::info("Playback session finished.");
    }

    return 0;
}

void print_help(const std::string& programName) {
    Logger::info("Hush! - Silence Remover, Player & TTS version %s", HUSH_VERSION);
    Logger::info("Usage:");
    Logger::info("  Hush! [options] <input_path> <output_path>   (Silence Removal)");
    Logger::info("  Hush! record <output_file> [options]         (Audio Recording)");
    Logger::info("  Hush! play <file_path>                       (Audio Playback)");
    Logger::info("  Hush! tts [options]                          (Text to Speech)");
    Logger::info("  Hush! status                                 (Check Background Progress)");
    Logger::info("  Hush! stop                                   (Stop Background Process)");
    Logger::info("");
    Logger::info("TTS Options:");
    Logger::info("  -t, --text <text>       Direct text input for synthesis.");
    Logger::info("  -i, --input <file>      Input text file for synthesis.");
    Logger::info("  -o, --output <file>     Output WAV file (default: output.wav).");
    Logger::info("  -v, --voice <voice>     Voice style name (e.g., M1, F1) or full path.");
    Logger::info("  -s, --speed <float>     Speech speed (default: 1.05).");
    Logger::info("  -n, --steps <int>       Diffusion steps (default: 8).");
    Logger::info("      --play              Play back the synthesized audio immediately.");
    Logger::info("      --detach            Run synthesis in the background.");
    Logger::info("");
    Logger::info("Core Options:");
    Logger::info("  <input_path>          Path to the input audio file or directory.");
    Logger::info("  <output_path>         Path to write the output audio file or directory.");
    Logger::info("");
    Logger::info("Processing Options:");
    Logger::info("  -t, --threshold <dB>    Silence threshold in dB (default: -40.0).");
    Logger::info("  -a, --aggression <lvl>  Aggression level for trimming (default: 1.0).");
    Logger::info("      --dry-run           Perform analysis without writing the output file.");
    Logger::info("      --detach            Run the process in the background.");
    Logger::info("");
    Logger::info("Recording Options:");
    Logger::info("  --no-silence-remove     Disable real-time silence removal.");
    Logger::info("  --stop-record <sec>     Stop recording automatically after N seconds.");
    Logger::info("  --play                  Play back the recording after finishing.");
    Logger::info("");
    Logger::info("General Options:");
    Logger::info("  -q, --quiet             Suppress all output except errors.");
    Logger::info("  -d, --debug             Enable debug logging.");
    Logger::info("  -h, --help              Show this help message.");
    Logger::info("      --version           Show version information.");
}

void draw_vu_meter(double db, double threshold, long long totalSamples) {
    const int width = 30;
    // Normalize dB to 0-1 range for the meter (e.g., -60dB to 0dB)
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

int handle_tts_command(int argc, char* argv[]) {
    std::string text;
    std::string inputFile;
    std::string outputFile = "output.wav";
    std::string voice = "M1";
    float speed = 1.05f;
    int steps = 8;
    bool shouldPlay = false;
    bool detach = false;
    
    const char* home = std::getenv("HOME");
    std::string hush_dir = home ? std::string(home) + "/.HushAI" : "";
    std::string onnx_dir = hush_dir + "/onnx";

    enum {
        OPT_TTS_PLAY = 1,
        OPT_TTS_DETACH
    };

    static struct option long_options[] = {
        {"text",    required_argument, 0, 't'},
        {"input",   required_argument, 0, 'i'},
        {"output",  required_argument, 0, 'o'},
        {"voice",   required_argument, 0, 'v'},
        {"speed",   required_argument, 0, 's'},
        {"steps",   required_argument, 0, 'n'},
        {"play",    no_argument,       0, OPT_TTS_PLAY},
        {"detach",  no_argument,       0, OPT_TTS_DETACH},
        {"help",    no_argument,       0, 'h'},
        {0, 0, 0, 0}
    };

    int opt;
    optind = 2; // Skip 'hush' and 'tts'
    while ((opt = getopt_long(argc, argv, "t:i:o:v:s:n:h", long_options, nullptr)) != -1) {
        switch (opt) {
            case 't': text = optarg; break;
            case 'i': inputFile = optarg; break;
            case 'o': outputFile = optarg; break;
            case 'v': voice = optarg; break;
            case 's': speed = std::stof(optarg); break;
            case 'n': steps = std::stoi(optarg); break;
            case OPT_TTS_PLAY: shouldPlay = true; break;
            case OPT_TTS_DETACH: detach = true; break;
            case 'h': print_help(argv[0]); return 0;
        }
    }

    if (shouldPlay) detach = true;

    if (detach) {
        pid_t pid = fork();
        if (pid < 0) {
            Logger::error("Failed to fork background process.");
            return 1;
        }
        if (pid > 0) {
            Logger::info("TTS process detached to background (PID: %d).", pid);
            HushState state;
            state.pid = pid;
            state.status = "synthesizing";
            state.outputPath = outputFile;
            StateManager::writeState(state);

            if (shouldPlay) {
                Logger::info("Starting parallel playback...");
                return run_play_command(outputFile, true);
            }
            return 0;
        }
        
        int log_fd = open("hush.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (log_fd != -1) {
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            close(log_fd);
        }
        std::atexit([]() { StateManager::clearState(); });
    }

    if (text.empty() && !inputFile.empty()) {
        std::ifstream f(inputFile);
        if (f.is_open()) {
            text = std::string((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        } else {
            Logger::error("Could not open input file: %s", inputFile.c_str());
            return 1;
        }
    }

    if (text.empty()) {
        Logger::error("No text provided for TTS. Use --text or --input.");
        return 1;
    }

    try {
        Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "TTS");
        Ort::MemoryInfo mem = Ort::MemoryInfo::CreateCpu(OrtAllocatorType::OrtArenaAllocator, OrtMemType::OrtMemTypeDefault);
        auto engine = loadTtsEngine(env, onnx_dir);
        
        std::string style_path = (voice.find("/") != std::string::npos || voice.find(".json") != std::string::npos) 
                                 ? voice 
                                 : hush_dir + "/voice_styles/" + voice + ".json";
        auto style = loadTtsStyle({style_path});

        Logger::info("Synthesizing speech...");
        auto chunks = chunkTtsText(text);
        std::vector<float> full_wav;
        for (const auto& chunk : chunks) {
            auto res = engine->synthesize(mem, chunk, "en", style, steps, speed);
            full_wav.insert(full_wav.end(), res.wav.begin(), res.wav.end());
        }

        writeWav(outputFile, full_wav, engine->getSampleRate());
        Logger::info("Saved to: %s", outputFile.c_str());
    } catch (const std::exception& e) {
        Logger::error("TTS Error: %s", e.what());
        return 1;
    }
    return 0;
}

int main(int argc, char* argv[]) {
    Logger::setLogLevel(Logger::LOG_INFO);
    av_log_set_level(AV_LOG_ERROR); // Default FFmpeg log level

    if (argc > 1) {
        std::string cmd = argv[1];
        if (cmd == "play") {
            if (argc < 3) {
                Logger::error("Usage: hush play <file_path>");
                return 1;
            }
            return run_play_command(argv[2]);
        } else if (cmd == "record") {
            return handle_record_command(argc, argv);
        } else if (cmd == "status") {
            return handle_status_command();
        } else if (cmd == "stop") {
            return handle_stop_command();
        } else if (cmd == "tts") {
            return handle_tts_command(argc, argv);
        }
    }

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "-q" || arg == "--quiet") {
            Logger::setLogLevel(Logger::LOG_ERROR);
            break;
        } else if (arg == "-d" || arg == "--debug") {
            Logger::setLogLevel(Logger::LOG_DEBUG);
            av_log_set_level(AV_LOG_DEBUG);
            break;
        }
    }

    std::string inputPathStr;
    std::string outputPathStr;
    double silenceThresholdDb = -40.0;
    double aggressionLevel = 1.0;
    bool dryRun = false;
    bool detach = false;
    bool shouldPlay = false;

    // --- Argument Parsing ---
    int opt;
    int option_index = 0;

    enum {
        OPT_VERSION = 1,
        OPT_DRY_RUN,
        OPT_DETACH,
        OPT_PLAY
    };

    static struct option long_options[] = {
        {"threshold",    required_argument, 0, 't'},
        {"aggression",   required_argument, 0, 'a'},
        {"quiet",        no_argument,       0, 'q'},
        {"debug",        no_argument,       0, 'd'},
        {"help",         no_argument,       0, 'h'},
        {"version",      no_argument,       0, OPT_VERSION},
        {"dry-run",      no_argument,       0, OPT_DRY_RUN},
        {"detach",       no_argument,       0, OPT_DETACH},
        {"play",         no_argument,       0, OPT_PLAY},
        {0, 0, 0, 0}
    };

    optind = 1;

    while ((opt = getopt_long(argc, argv, "t:a:qdh", long_options, &option_index)) != -1) {
        switch (opt) {
            case OPT_VERSION:
                Logger::info("Hush! version %s", HUSH_VERSION);
                return 0;
            case OPT_DRY_RUN:
                dryRun = true;
                break;
            case OPT_DETACH:
                detach = true;
                break;
            case OPT_PLAY:
                shouldPlay = true;
                break;
            case 't':
                try {
                    silenceThresholdDb = std::stod(optarg);
                } catch (const std::exception&) { Logger::error("Invalid threshold value."); return 1; }
                break;
            case 'a':
                try {
                    aggressionLevel = std::stod(optarg);
                } catch (const std::exception&) { Logger::error("Invalid aggression value."); return 1; }
                break;
            case 'q': case 'd': break;
            case 'h':
                print_help(argv[0]);
                return 0;
            case '?':
                return 1;
            default:
                abort();
        }
    }

    if (optind < argc) inputPathStr = argv[optind++];
    if (optind < argc) outputPathStr = argv[optind++];

    if (inputPathStr.empty() || outputPathStr.empty()) {
        Logger::error("Both input and output paths must be specified.");
        print_help(argv[0]);
        return 1;
    }

    fs::path inputPath(inputPathStr);
    fs::path outputPath(outputPathStr);

    if (!fs::exists(inputPath)) {
        Logger::error("Input path does not exist: %s", inputPathStr.c_str());
        return 1;
    }

    if (shouldPlay) {
        detach = true; // Always detach processing when playing in parallel
    }

    if (detach) {
        pid_t pid = fork();
        if (pid < 0) {
            Logger::error("Failed to fork background process.");
            return 1;
        }
        if (pid > 0) {
            // Parent process
            Logger::info("Hush process detached to background (PID: %d).", pid);
            
            // Initialize state for parent/user to see
            HushState state;
            state.pid = pid;
            state.status = "initializing";
            state.outputPath = outputPathStr;
            StateManager::writeState(state);

            if (shouldPlay) {
                Logger::info("Starting parallel playback...");
                return run_play_command(outputPathStr, true);
            }

            Logger::info("Use 'hush status' to track progress or 'hush stop' to terminate.");
            return 0;
        }
        
        // Child process
        // Redirect stdout/stderr to a log file
        int log_fd = open("hush.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (log_fd != -1) {
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            close(log_fd);
        }
        
        // Ensure child cleans up state on exit
        std::atexit([]() { StateManager::clearState(); });
    }

    // --- Processing ---
    AudioProcessor processor;
    int successCount = 0;
    int failCount = 0;

    auto globalStart = std::chrono::high_resolution_clock::now();

    if (fs::is_directory(inputPath)) {
        // Directory Processing
        if (fs::exists(outputPath) && !fs::is_directory(outputPath)) {
            Logger::error("Output path must be a directory when input is a directory.");
            return 1;
        }
        if (!fs::exists(outputPath)) {
            if (!dryRun) {
                fs::create_directories(outputPath);
            }
        }

        std::vector<fs::path> totalFiles;
        for (const auto& entry : fs::directory_iterator(inputPath)) {
            if (entry.is_regular_file() && is_supported_audio(entry.path())) {
                totalFiles.push_back(entry.path());
            }
        }
        std::sort(totalFiles.begin(), totalFiles.end());

        Logger::info("Processing directory: %s (%zu files)", inputPathStr.c_str(), totalFiles.size());

        for (size_t i = 0; i < totalFiles.size(); ++i) {
            const auto& currentInput = totalFiles[i];
            fs::path currentOutput = outputPath / currentInput.filename();

            if (detach) {
                HushState state;
                state.pid = getpid();
                state.status = "processing";
                state.progress = std::to_string(i + 1) + "/" + std::to_string(totalFiles.size());
                state.currentFile = currentInput.filename().string();
                state.outputPath = outputPathStr;
                StateManager::writeState(state);
            }

            Logger::info("Processing file: %s -> %s", currentInput.string().c_str(), currentOutput.string().c_str());

            auto fileStart = std::chrono::high_resolution_clock::now();
            try {
                ProcessingSummary summary = processor.process(currentInput.string(), currentOutput.string(), silenceThresholdDb, aggressionLevel, dryRun);
                auto fileEnd = std::chrono::high_resolution_clock::now();
                std::chrono::duration<double, std::milli> fileElapsed = fileEnd - fileStart;

                if (summary.total_input_frames > 0) {
                    successCount++;
                         Logger::info("  Reduction: %.2f%%, Time: %.2f ms", summary.reductionPercentage, fileElapsed.count());
                } else {
                    failCount++;
                    Logger::error("  Failed to process file: %s", currentInput.string().c_str());
                }
            } catch (const std::exception& e) {
                failCount++;
                Logger::error("  Error processing file %s: %s", currentInput.string().c_str(), e.what());
            }
        }
        auto globalEnd = std::chrono::high_resolution_clock::now();
        std::chrono::duration<double> globalElapsed = globalEnd - globalStart;
        Logger::info("Batch processing complete. Success: %d, Failed: %d, Total Time: %.2f s", successCount, failCount, globalElapsed.count());

    } else {
        // Single File Processing
        if (detach) {
            HushState state;
            state.pid = getpid();
            state.status = "processing";
            state.progress = "1/1";
            state.currentFile = inputPath.filename().string();
            state.outputPath = outputPathStr;
            StateManager::writeState(state);
        }
        auto fileStart = std::chrono::high_resolution_clock::now();
        try {
            ProcessingSummary summary = processor.process(inputPathStr, outputPathStr, silenceThresholdDb, aggressionLevel, dryRun);
            auto fileEnd = std::chrono::high_resolution_clock::now();
            std::chrono::duration<double, std::milli> fileElapsed = fileEnd - fileStart;

            if (summary.total_input_frames == 0) {
                Logger::error("Processing failed. Check input file and parameters.");
                return 1;
            }

            Logger::info("--- Processing Summary ---");
            Logger::info("Input duration:         %.2f seconds", summary.inputDuration);
            Logger::info("Output duration:        %.2f seconds", summary.outputDuration);
            Logger::info("Reduction:              %.2f%%", summary.reductionPercentage);
            Logger::info("Processing time:        %.2f ms", fileElapsed.count());
            if (!dryRun) {
                Logger::info("Output file written to '%s'", outputPathStr.c_str());
            }

        } catch (const std::runtime_error& e) {
            Logger::error("A runtime error occurred: %s", e.what());
            return 1;
        }
    }

    return (failCount > 0) ? 1 : 0;
}

