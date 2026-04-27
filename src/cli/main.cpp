// main.cpp: Entry point for the Hush! silence remover utility.

#include "codecs/AudioProcessor.h"
#include "core/Logger.h"
#include <string>
#include <getopt.h>
#include <iostream>
#include <filesystem>
#include <vector>
#include <algorithm>

namespace fs = std::filesystem;

extern "C" {
#include <libavutil/log.h> // Include FFmpeg logging functions
}

#ifndef HUSH_VERSION
#define HUSH_VERSION "0.1v"
#endif

void print_help(const std::string& programName) {
    Logger::info("Hush! - Silence Remover version %s", HUSH_VERSION);
    Logger::info("Usage: Hush! [options] <input_path> <output_path>");
    Logger::info("");
    Logger::info("Core Options:");
    Logger::info("  <input_path>          Path to the input audio file or directory.");
    Logger::info("  <output_path>         Path to write the output audio file or directory.");
    Logger::info("");
    Logger::info("Processing Options:");
    Logger::info("  -t, --threshold <dB>    Silence threshold in dB (default: -40.0).");
    Logger::info("  -a, --aggression <lvl>  Aggression level for trimming (default: 1.0).");
    Logger::info("      --dry-run           Perform analysis without writing the output file.");
    Logger::info("");
    Logger::info("General Options:");
    Logger::info("  -q, --quiet             Suppress all output except errors.");
    Logger::info("  -d, --debug             Enable debug logging.");
    Logger::info("  -h, --help              Show this help message.");
    Logger::info("      --version           Show version information.");
}

// Helper to check for .mp3 extension case-insensitively
bool is_mp3(const fs::path& path) {
    std::string ext = path.extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    return ext == ".mp3";
}

int main(int argc, char* argv[]) {
    Logger::setLogLevel(Logger::LOG_INFO);
    av_log_set_level(AV_LOG_ERROR); // Default FFmpeg log level

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

    // --- Argument Parsing ---
    int opt;
    int option_index = 0;

    enum {
        OPT_VERSION = 1,
        OPT_DRY_RUN
    };

    static struct option long_options[] = {
        {"threshold",    required_argument, 0, 't'},
        {"aggression",   required_argument, 0, 'a'},
        {"quiet",        no_argument,       0, 'q'},
        {"debug",        no_argument,       0, 'd'},
        {"help",         no_argument,       0, 'h'},
        {"version",      no_argument,       0, OPT_VERSION},
        {"dry-run",      no_argument,       0, OPT_DRY_RUN},
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

    // --- Processing ---
    AudioProcessor processor;
    int successCount = 0;
    int failCount = 0;

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

        Logger::info("Processing directory: %s", inputPathStr.c_str());

        for (const auto& entry : fs::directory_iterator(inputPath)) {
            if (entry.is_regular_file() && is_mp3(entry.path())) {
                fs::path currentInput = entry.path();
                fs::path currentOutput = outputPath / currentInput.filename();

                Logger::info("Processing file: %s -> %s", currentInput.string().c_str(), currentOutput.string().c_str());

                try {
                    ProcessingSummary summary = processor.process(currentInput.string(), currentOutput.string(), silenceThresholdDb, aggressionLevel, dryRun);
                    if (summary.total_input_frames > 0) {
                        successCount++;
                         Logger::info("  Reduction: %.2f%%", summary.reductionPercentage);
                    } else {
                        failCount++;
                        Logger::error("  Failed to process file: %s", currentInput.string().c_str());
                    }
                } catch (const std::exception& e) {
                    failCount++;
                    Logger::error("  Error processing file %s: %s", currentInput.string().c_str(), e.what());
                }
            }
        }
        Logger::info("Batch processing complete. Success: %d, Failed: %d", successCount, failCount);

    } else {
        // Single File Processing
        try {
            ProcessingSummary summary = processor.process(inputPathStr, outputPathStr, silenceThresholdDb, aggressionLevel, dryRun);

            if (summary.total_input_frames == 0) {
                Logger::error("Processing failed. Check input file and parameters.");
                return 1;
            }

            Logger::info("--- Processing Summary ---");
            Logger::info("Input duration:         %.2f seconds", summary.inputDuration);
            Logger::info("Output duration:        %.2f seconds", summary.outputDuration);
            Logger::info("Reduction:              %.2f%%", summary.reductionPercentage);
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
