#include "core/StateManager.h"
#include "core/Logger.h"
#include <fstream>
#include <filesystem>
#include <iostream>

namespace fs = std::filesystem;

std::string StateManager::getStateFilePath() {
    try {
        // Attempt to use system temp directory
        return (fs::temp_directory_path() / "hush.state").string();
    } catch (...) {
        // Fallback for environments where temp_directory_path() might fail
        return "/data/data/com.termux/files/home/.hush.state";
    }
}

void StateManager::writeState(const HushState& state) {
    std::ofstream f(getStateFilePath());
    if (f.is_open()) {
        f << "pid=" << state.pid << "\n";
        f << "status=" << state.status << "\n";
        f << "progress=" << state.progress << "\n";
        f << "currentFile=" << state.currentFile << "\n";
        f << "outputPath=" << state.outputPath << "\n";
    }
}

HushState StateManager::readState() {
    HushState state;
    std::ifstream f(getStateFilePath());
    if (f.is_open()) {
        state.exists = true;
        std::string line;
        while (std::getline(f, line)) {
            size_t pos = line.find('=');
            if (pos != std::string::npos) {
                std::string key = line.substr(0, pos);
                std::string val = line.substr(pos + 1);
                if (key == "pid") {
                    try {
                        state.pid = std::stoi(val);
                    } catch (...) {
                        state.pid = 0;
                    }
                }
                else if (key == "status") state.status = val;
                else if (key == "progress") state.progress = val;
                else if (key == "currentFile") state.currentFile = val;
                else if (key == "outputPath") state.outputPath = val;
            }
        }
    }
    return state;
}

void StateManager::clearState() {
    std::error_code ec;
    fs::remove(getStateFilePath(), ec);
}

