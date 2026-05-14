#include "core/Logger.h"
#include <cstdarg>
#include <iostream>

Logger::LogLevel Logger::currentLogLevel = Logger::LOG_INFO;
Logger* Logger::instance = nullptr; // Initialize static member

Logger::Logger() {
    // Default constructor
}

Logger::~Logger() {
    // Default destructor
}

Logger* Logger::getInstance() {
    if (instance == nullptr) {
        instance = new Logger();
    }
    return instance;
}

void Logger::setInstance(Logger* newInstance) {
    instance = newInstance;
}


void Logger::setLogLevel(LogLevel level) {
    currentLogLevel = level;
}

Logger::LogLevel Logger::getLogLevel() {
    return currentLogLevel;
}

const char* Logger::levelToString(LogLevel level) {
    switch (level) {
        case LOG_NONE:  return "NONE";
        case LOG_ERROR: return "ERROR";
        case LOG_INFO:  return "INFO";
        case LOG_DEBUG: return "DEBUG";
        default:        return "UNKNOWN";
    }
}

void Logger::log(LogLevel level, const char* format, va_list args) {
    if (level <= currentLogLevel) {
        std::cerr << "[" << levelToString(level) << "] ";
        vfprintf(stderr, format, args);
        std::cerr << std::endl;
    }
}

void Logger::error(const char* format, ...) {
    va_list args;
    va_start(args, format);
    getInstance()->log(LOG_ERROR, format, args);
    va_end(args);
}

void Logger::info(const char* format, ...) {
    va_list args;
    va_start(args, format);
    getInstance()->log(LOG_INFO, format, args);
    va_end(args);
}

void Logger::debug(const char* format, ...) {
    va_list args;
    va_start(args, format);
    getInstance()->log(LOG_DEBUG, format, args);
    va_end(args);
}

