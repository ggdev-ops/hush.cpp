#include "TerminalUtils.h"
#include <cstdio>
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>
#include <algorithm>

int get_keypress() {
    struct termios oldt, newt;
    int ch;
    int oldf;

    tcgetattr(STDIN_FILENO, &oldt);
    newt = oldt;
    newt.c_lflag &= ~(ICANON | ECHO);
    tcsetattr(STDIN_FILENO, TCSANOW, &newt);
    oldf = fcntl(STDIN_FILENO, F_GETFL, 0);
    fcntl(STDIN_FILENO, F_SETFL, oldf | O_NONBLOCK);

    ch = getchar();

    tcsetattr(STDIN_FILENO, TCSANOW, &oldt);
    fcntl(STDIN_FILENO, F_SETFL, oldf);

    return ch;
}

bool is_supported_audio(const fs::path& path) {
    std::string ext = path.extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    return ext == ".mp3" || ext == ".wav";
}

void draw_vu_meter(double db, double threshold, long long totalSamples) {
    const int width = 30;
    // Normalize dB to 0-1 range for the meter (e.g., -60dB to 0dB)
    double minDb = -60.0;
    double maxDb = 0.0;
    double normalized = (db - minDb) / (maxDb - minDb);
    if (normalized < 0) normalized = 0;
    if (normalized > 1) normalized = 1;

    int filled = static_cast<int>(normalized * width);
    int threshPos = static_cast<int>((threshold - minDb) / (maxDb - minDb) * width);

    std::string meter = "[";
    for (int i = 0; i < width; ++i) {
        if (i == threshPos) meter += "|";
        else if (i < filled) meter += "#";
        else meter += "-";
    }
    meter += "]";

    std::string status = (db <= threshold) ? "\033[1;30m[SILENT]\033[0m" : "\033[1;32m[VOICE]\033[0m";

    double sizeKb = (totalSamples * 2.0) / 1024.0;
    char sizeBuf[32];
    if (sizeKb >= 1024) {
        snprintf(sizeBuf, sizeof(sizeBuf), "%.2f MB", sizeKb / 1024.0);
    } else {
        snprintf(sizeBuf, sizeof(sizeBuf), "%.1f KB", sizeKb);
    }

    printf("\r%s %6.1f dB %s %s    ", meter.c_str(), db, status.c_str(), sizeBuf);
    fflush(stdout);
}
