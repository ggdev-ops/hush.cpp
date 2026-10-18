#include "StopCommand.h"
#include "core/Logger.h"
#include "core/StateManager.h"
#include <csignal>

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
