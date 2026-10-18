#include "PlayCommand.h"
#include "TerminalUtils.h"
#include "core/Logger.h"
#include "core/AudioPlayer.h"
#include "core/StateManager.h"

#include <vector>
#include <algorithm>
#include <csignal>
#include <unistd.h>
#include <filesystem>

namespace fs = std::filesystem;

int run_play_command(const std::string& pathStr, bool waitMode) {
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
