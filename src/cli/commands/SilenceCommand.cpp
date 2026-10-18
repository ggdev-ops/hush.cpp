#include "SilenceCommand.h"
#include "PlayCommand.h"
#include "TerminalUtils.h"
#include "codecs/AudioProcessor.h"
#include "core/Logger.h"
#include "core/StateManager.h"

#include <vector>
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <csignal>
#include <unistd.h>
#include <fcntl.h>

namespace fs = std::filesystem;

int run_silence_command(const SilenceRemovalOptions& options) {
    fs::path inputPath(options.inputPathStr);
    fs::path outputPath(options.outputPathStr);

    if (!fs::exists(inputPath)) {
        Logger::error("Input path does not exist: %s", options.inputPathStr.c_str());
        return 1;
    }

    bool detach = options.detach;
    if (options.shouldPlay) {
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
            state.outputPath = options.outputPathStr;
            StateManager::writeState(state);

            if (options.shouldPlay) {
                Logger::info("Starting parallel playback...");
                return run_play_command(options.outputPathStr, true);
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
            if (!options.dryRun) {
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

        Logger::info("Processing directory: %s (%zu files)", options.inputPathStr.c_str(), totalFiles.size());

        for (size_t i = 0; i < totalFiles.size(); ++i) {
            const auto& currentInput = totalFiles[i];
            fs::path currentOutput = outputPath / currentInput.filename();

            if (detach) {
                HushState state;
                state.pid = getpid();
                state.status = "processing";
                state.progress = std::to_string(i + 1) + "/" + std::to_string(totalFiles.size());
                state.currentFile = currentInput.filename().string();
                state.outputPath = options.outputPathStr;
                StateManager::writeState(state);
            }

            Logger::info("Processing file: %s -> %s", currentInput.string().c_str(), currentOutput.string().c_str());

            auto fileStart = std::chrono::high_resolution_clock::now();
            try {
                ProcessingSummary summary = processor.process(currentInput.string(), currentOutput.string(),
                                                              options.silenceThresholdDb, options.aggressionLevel, options.dryRun);
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
        Logger::info("Batch processing complete. Success: %d, Failed: %d, Total Time: %.2f s",
                     successCount, failCount, globalElapsed.count());

    } else {
        // Single File Processing
        if (detach) {
            HushState state;
            state.pid = getpid();
            state.status = "processing";
            state.progress = "1/1";
            state.currentFile = inputPath.filename().string();
            state.outputPath = options.outputPathStr;
            StateManager::writeState(state);
        }
        auto fileStart = std::chrono::high_resolution_clock::now();
        try {
            ProcessingSummary summary = processor.process(options.inputPathStr, options.outputPathStr,
                                                          options.silenceThresholdDb, options.aggressionLevel, options.dryRun);
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
            if (!options.dryRun) {
                Logger::info("Output file written to '%s'", options.outputPathStr.c_str());
            }

        } catch (const std::runtime_error& e) {
            Logger::error("A runtime error occurred: %s", e.what());
            return 1;
        }
    }

    return (failCount > 0) ? 1 : 0;
}
