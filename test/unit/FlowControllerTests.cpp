#include "gtest/gtest.h"
#include "core/FlowController.h"

TEST(FlowControllerTest, BasicCalculations) {
    FlowController::Config config;
    config.sampleRate = 16000;
    FlowController controller(config);

    EXPECT_EQ(controller.getCurrentState(), FlowState::NORMAL);
    EXPECT_EQ(controller.getPressureLevel(), 0);
    EXPECT_EQ(controller.getLatencyEstimateMs(), 0);

    // Update with 1600 samples out of 16000 capacity
    // Pressure = 10%
    // Latency = 1600 * 1000 / 16000 = 100ms
    controller.update(1600, 16000);
    EXPECT_EQ(controller.getCurrentState(), FlowState::NORMAL);
    EXPECT_EQ(controller.getPressureLevel(), 10);
    EXPECT_EQ(controller.getLatencyEstimateMs(), 100);
}

TEST(FlowControllerTest, HysteresisTransitions) {
    FlowController::Config config;
    config.degradedThreshold = 0.50;      // 50%
    config.emergencyThreshold = 0.80;     // 80%
    config.degradedReleaseThreshold = 0.40;  // 40%
    config.emergencyReleaseThreshold = 0.70; // 70%
    config.sampleRate = 16000;

    FlowController controller(config);

    // 1. Stays normal under thresholds
    EXPECT_EQ(controller.update(3000, 10000), FlowState::NORMAL);

    // 2. Crosses degraded threshold (50%)
    EXPECT_EQ(controller.update(5500, 10000), FlowState::DEGRADED);

    // 3. Stays degraded below emergency, but above degraded release
    EXPECT_EQ(controller.update(4500, 10000), FlowState::DEGRADED);

    // 4. Crosses emergency threshold (80%)
    EXPECT_EQ(controller.update(8500, 10000), FlowState::EMERGENCY);

    // 5. Stays in emergency when falling slightly (above 70% release)
    EXPECT_EQ(controller.update(7500, 10000), FlowState::EMERGENCY);

    // 6. Drops below emergency release (70%), goes to DEGRADED
    EXPECT_EQ(controller.update(6500, 10000), FlowState::DEGRADED);

    // 7. Drops below degraded release (40%), goes to NORMAL
    EXPECT_EQ(controller.update(3500, 10000), FlowState::NORMAL);
}

TEST(FlowControllerTest, ResetBehavior) {
    FlowController::Config config;
    FlowController controller(config);

    controller.update(900, 1000);
    EXPECT_EQ(controller.getCurrentState(), FlowState::EMERGENCY);

    controller.reset();
    EXPECT_EQ(controller.getCurrentState(), FlowState::NORMAL);
    EXPECT_EQ(controller.getPressureLevel(), 0);
    EXPECT_EQ(controller.getLatencyEstimateMs(), 0);
}
