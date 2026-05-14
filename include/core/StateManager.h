#ifndef STATE_MANAGER_H
#define STATE_MANAGER_H

#include <string>

/**
 * @brief Struct representing the state of a background hush process.
 */
struct HushState {
    int pid = 0;
    std::string status;
    std::string progress;
    std::string currentFile;
    std::string outputPath;
    bool exists = false;
};

/**
 * @brief Utility class to manage background process state via a persistent file.
 */
class StateManager {
public:
    /**
     * @brief Writes the current state to the persistent state file.
     */
    static void writeState(const HushState& state);

    /**
     * @brief Reads the state from the persistent state file.
     */
    static HushState readState();

    /**
     * @brief Deletes the state file.
     */
    static void clearState();

    /**
     * @brief Returns the path to the state file.
     */
    static std::string getStateFilePath();
};

#endif // STATE_MANAGER_H

