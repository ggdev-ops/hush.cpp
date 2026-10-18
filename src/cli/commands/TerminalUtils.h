#ifndef CLI_TERMINAL_UTILS_H
#define CLI_TERMINAL_UTILS_H

#include <string>
#include <filesystem>

namespace fs = std::filesystem;

/**
 * @brief Reads a single keypress from the terminal without blocking or echoing.
 * @return The character code, or -1 if no key was pressed.
 */
int get_keypress();

/**
 * @brief Checks if a given file has a supported audio extension (.mp3, .wav).
 */
bool is_supported_audio(const fs::path& path);

/**
 * @brief Renders an interactive VU audio meter on the current line.
 */
void draw_vu_meter(double db, double threshold, long long totalSamples);

#endif // CLI_TERMINAL_UTILS_H
