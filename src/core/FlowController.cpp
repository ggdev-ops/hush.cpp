#include "core/FlowController.h"
#include <algorithm>

FlowController::FlowController(const Config& config) : config_(config) {}

FlowState FlowController::update(size_t currentSize, size_t capacity) {
    if (capacity == 0) return FlowState::NORMAL;

    double pressure = static_cast<double>(currentSize) / capacity;
    int pressurePercent = std::clamp(static_cast<int>(pressure * 100.0), 0, 100);
    pressureLevel_.store(pressurePercent, std::memory_order_relaxed);

    int latencyMs = static_cast<int>((static_cast<double>(currentSize) * 1000.0) / config_.sampleRate);
    latencyEstimateMs_.store(latencyMs, std::memory_order_relaxed);

    FlowState currentState = state_.load(std::memory_order_relaxed);
    FlowState newState = currentState;

    if (currentState == FlowState::NORMAL) {
        if (pressure >= config_.emergencyThreshold) {
            newState = FlowState::EMERGENCY;
        } else if (pressure >= config_.degradedThreshold) {
            newState = FlowState::DEGRADED;
        }
    } else if (currentState == FlowState::DEGRADED) {
        if (pressure >= config_.emergencyThreshold) {
            newState = FlowState::EMERGENCY;
        } else if (pressure < config_.degradedReleaseThreshold) {
            newState = FlowState::NORMAL;
        }
    } else if (currentState == FlowState::EMERGENCY) {
        if (pressure < config_.emergencyReleaseThreshold) {
            if (pressure < config_.degradedReleaseThreshold) {
                newState = FlowState::NORMAL;
            } else {
                newState = FlowState::DEGRADED;
            }
        }
    }

    if (newState != currentState) {
        state_.store(newState, std::memory_order_relaxed);
    }

    return newState;
}

FlowState FlowController::getCurrentState() const {
    return state_.load(std::memory_order_relaxed);
}

int FlowController::getPressureLevel() const {
    return pressureLevel_.load(std::memory_order_relaxed);
}

int FlowController::getLatencyEstimateMs() const {
    return latencyEstimateMs_.load(std::memory_order_relaxed);
}

void FlowController::reset() {
    state_.store(FlowState::NORMAL, std::memory_order_relaxed);
    pressureLevel_.store(0, std::memory_order_relaxed);
    latencyEstimateMs_.store(0, std::memory_order_relaxed);
}
