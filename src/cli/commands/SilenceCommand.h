#ifndef CLI_SILENCE_COMMAND_H
#define CLI_SILENCE_COMMAND_H

#include <string>

struct SilenceRemovalOptions {
    std::string inputPathStr;
    std::string outputPathStr;
    double silenceThresholdDb = -25.0;
    double aggressionLevel = 0.75;
    bool dryRun = false;
    bool detach = false;
    bool shouldPlay = false;
};

int run_silence_command(const SilenceRemovalOptions& options);

#endif // CLI_SILENCE_COMMAND_H
