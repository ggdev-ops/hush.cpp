#include "gtest/gtest.h"
#include "core/StateManager.h"
#include <fstream>
#include <filesystem>

namespace fs = std::filesystem;

class StateManagerTest : public ::testing::Test {
protected:
    void SetUp() override {
        StateManager::clearState();
    }

    void TearDown() override {
        StateManager::clearState();
    }
};

TEST_F(StateManagerTest, WriteAndReadState) {
    HushState state;
    state.pid = 1234;
    state.status = "processing";
    state.progress = "50%";
    state.currentFile = "input.wav";
    state.outputPath = "output.wav";

    StateManager::writeState(state);

    HushState read = StateManager::readState();
    EXPECT_TRUE(read.exists);
    EXPECT_EQ(read.pid, 1234);
    EXPECT_EQ(read.status, "processing");
    EXPECT_EQ(read.progress, "50%");
    EXPECT_EQ(read.currentFile, "input.wav");
    EXPECT_EQ(read.outputPath, "output.wav");
}

TEST_F(StateManagerTest, ReadNonExistentState) {
    HushState read = StateManager::readState();
    EXPECT_FALSE(read.exists);
    EXPECT_EQ(read.pid, 0);
}

TEST_F(StateManagerTest, HandleCorruptedFile) {
    std::string path = StateManager::getStateFilePath();
    std::ofstream f(path);
    f << "this is not a valid state file\npid=abc\nstatus=ok";
    f.close();

    HushState read = StateManager::readState();
    EXPECT_TRUE(read.exists);
    EXPECT_EQ(read.pid, 0); // Should fail to parse "abc"
    EXPECT_EQ(read.status, "ok");
}

TEST_F(StateManagerTest, ClearState) {
    HushState state;
    state.pid = 1;
    StateManager::writeState(state);
    EXPECT_TRUE(StateManager::readState().exists);

    StateManager::clearState();
    EXPECT_FALSE(StateManager::readState().exists);
}
