#ifndef FLOW_CONTROLLER_H
#define FLOW_CONTROLLER_H

#include <atomic>
#include <cstdint>
#include <cstddef>

enum class FlowState {
    NORMAL,
    DEGRADED,
    EMERGENCY
};

class FlowController {
public:
    struct Config {
        double degradedThreshold = 0.50;  // 50% capacity
        double emergencyThreshold = 0.80; // 80% capacity
        double degradedReleaseThreshold = 0.40;  // 40% capacity (hysteresis)
        double emergencyReleaseThreshold = 0.70; // 70% capacity (hysteresis)
        int sampleRate = 16000;
    };

    explicit FlowController(const Config& config);

    // Update state based on current buffer size and capacity.
    // Thread-safe and lock-free.
    FlowState update(size_t currentSize, size_t capacity);

    // Getters for atomic metrics
    FlowState getCurrentState() const;
    int getPressureLevel() const; // 0 to 100
    int getLatencyEstimateMs() const;

    // Reset the controller state
    void reset();

private:
    Config config_;
    std::atomic<FlowState> state_{FlowState::NORMAL};
    std::atomic<int> pressureLevel_{0};
    std::atomic<int> latencyEstimateMs_{0};
};

#endif // FLOW_CONTROLLER_H
