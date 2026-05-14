#include "gtest/gtest.h"
#include "core/Logger.h"
#include <sstream>

// Custom Logger implementation for testing
class TestLogger : public Logger {
public:
    TestLogger() : Logger() {} // Call base constructor

    static std::string getOutput() {
        return _testOutput.str();
    }

    static void clearOutput() {
        _testOutput.str("");
        _testOutput.clear();
    }

protected:
    void log(LogLevel level, const char* format, va_list args) override {
        if (level <= Logger::getLogLevel()) { // Use Logger::getLogLevel()
            // Log to stringstream for testing
            char buffer[1024];
            vsnprintf(buffer, sizeof(buffer), format, args);
            _testOutput << "[" << levelToString(level) << "] " << buffer << std::endl;
        }
    }

private:
    static std::stringstream _testOutput;
};

std::stringstream TestLogger::_testOutput;

// Set a custom logger for testing purposes
struct LoggerTestEnvironment : public ::testing::Environment {
    void SetUp() override {
        // Store original instance to restore later
        _originalLoggerInstance = Logger::getInstance(); 
        Logger::setInstance(new TestLogger()); // Set a test logger instance
    }
    void TearDown() override {
        // Restore original logger instance. 
        // The TestLogger instance created in SetUp is deleted by Google Test runner.
        Logger::setInstance(_originalLoggerInstance); // Restore original
    }

    Logger* _originalLoggerInstance = nullptr;
};

// Register the environment
[[maybe_unused]] static ::testing::Environment* const logger_env = ::testing::AddGlobalTestEnvironment(new LoggerTestEnvironment);


TEST(LoggerTest, SetLogLevel) {
    TestLogger::clearOutput();
    Logger::setLogLevel(Logger::LOG_ERROR);
    Logger::info("This is an info message.");
    Logger::error("This is an error message.");
    EXPECT_EQ(TestLogger::getOutput().find("[INFO]"), std::string::npos);
    EXPECT_NE(TestLogger::getOutput().find("[ERROR] This is an error message."), std::string::npos);

    TestLogger::clearOutput();
    Logger::setLogLevel(Logger::LOG_INFO);
    Logger::info("Another info message.");
    Logger::error("Another error message.");
    EXPECT_NE(TestLogger::getOutput().find("[INFO] Another info message."), std::string::npos);
    EXPECT_NE(TestLogger::getOutput().find("[ERROR] Another error message."), std::string::npos);
}

TEST(LoggerTest, DebugMessage) {
    TestLogger::clearOutput();
    Logger::setLogLevel(Logger::LOG_DEBUG);
    Logger::debug("A debug message.");
    EXPECT_NE(TestLogger::getOutput().find("[DEBUG] A debug message."), std::string::npos);
}

TEST(LoggerTest, NoOutputForLowerLevel) {
    TestLogger::clearOutput();
    Logger::setLogLevel(Logger::LOG_INFO);
    Logger::debug("This debug message should not appear.");
    EXPECT_EQ(TestLogger::getOutput().find("[DEBUG]"), std::string::npos);
}

