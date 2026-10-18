// main.cpp: Entry point for the Hush! silence remover utility.

#include "commands/TerminalUtils.h"
#include "commands/StatusCommand.h"
#include "commands/StopCommand.h"
#include "commands/PlayCommand.h"
#include "commands/RecordCommand.h"
#include "commands/SilenceCommand.h"

#include "core/Logger.h"
#include <string>
#include <getopt.h>
#include <iostream>

extern "C" {
#include <libavutil/log.h> // Include FFmpeg logging functions
}

#ifndef HUSH_VERSION
#define HUSH_VERSION "1.0.0"
#endif

void print_help(const std::string& /*programName*/) {
    Logger::info("Hush! - Silence Remover & Player version %s", HUSH_VERSION);
    Logger::info("Usage:");
    Logger::info("  Hush! [options] <input_path> <output_path>   (Silence Removal)");
    Logger::info("  Hush! record <output_file> [options]         (Audio Recording)");
    Logger::info("  Hush! play <file_path>                       (Audio Playback)");
    Logger::info("  Hush! status                                 (Check Background Progress)");
    Logger::info("  Hush! stop                                   (Stop Background Process)");
    Logger::info("");
    Logger::info("Core Options:");
    Logger::info("  <input_path>          Path to the input audio file or directory.");
    Logger::info("  <output_path>         Path to write the output audio file or directory.");
    Logger::info("");
    Logger::info("Processing Options:");
    Logger::info("  -t, --threshold <dB>    Silence threshold in dB (default: -25.0).");
    Logger::info("  -a, --aggression <lvl>  Aggression level for trimming (default: 0.75).");
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

    SilenceRemovalOptions options;

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
                options.dryRun = true;
                break;
            case OPT_DETACH:
                options.detach = true;
                break;
            case OPT_PLAY:
                options.shouldPlay = true;
                break;
            case 't':
                try {
                    options.silenceThresholdDb = std::stod(optarg);
                } catch (const std::exception&) { Logger::error("Invalid threshold value."); return 1; }
                break;
            case 'a':
                try {
                    options.aggressionLevel = std::stod(optarg);
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

    if (optind < argc) options.inputPathStr = argv[optind++];
    if (optind < argc) options.outputPathStr = argv[optind++];

    if (options.inputPathStr.empty() || options.outputPathStr.empty()) {
        Logger::error("Both input and output paths must be specified.");
        print_help(argv[0]);
        return 1;
    }

    return run_silence_command(options);
}
