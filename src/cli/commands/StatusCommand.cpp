#include "StatusCommand.h"
#include "core/Logger.h"
#include "core/StateManager.h"
#include <csignal>

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
