#ifndef LOGGER_H
#define LOGGER_H

#include <string>
#include <cstdio> // For va_list

class Logger {
public:
    enum LogLevel {
        LOG_NONE = 0,
        LOG_ERROR,
        LOG_INFO,
        LOG_DEBUG
    };

    static void setLogLevel(LogLevel level);
    static LogLevel getLogLevel();

    static void error(const char* format, ...);
    static void info(const char* format, ...);
    static void debug(const char* format, ...);

    // Singleton pattern for testability
    static Logger* getInstance();
    static void setInstance(Logger* instance); // For dependency injection in tests

protected:
    Logger(); // Protected constructor for singleton
    virtual ~Logger(); // Virtual destructor

    virtual void log(LogLevel level, const char* format, va_list args);

    static const char* levelToString(LogLevel level);

private:
    static LogLevel currentLogLevel;
    static Logger* instance; // The single instance
};

#endif // LOGGER_H
