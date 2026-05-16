cmake_minimum_required(VERSION 3.10)
project(hush VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

enable_testing() # Enable CTest

# Add Google Test
# Removed local googletest subdirectory

# Define version for the code
add_definitions(-Dhush_VERSION="1.0.0")

option(hush_BUILD_CLI "Build the CLI tool (requires FFmpeg)" ON)

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

if (hush_BUILD_CLI)
    # Find FFmpeg
    find_package(PkgConfig QUIET)
    if (PKG_CONFIG_FOUND)
        pkg_check_modules(AVFORMAT REQUIRED libavformat)
        pkg_check_modules(AVCODEC REQUIRED libavcodec)
        pkg_check_modules(AVUTIL REQUIRED libavutil)
        pkg_check_modules(SWRESAMPLE REQUIRED libswresample)
    endif()

    if (NOT AVFORMAT_FOUND OR NOT AVCODEC_FOUND OR NOT AVUTIL_FOUND OR NOT SWRESAMPLE_FOUND)
        message(STATUS "PkgConfig for FFmpeg not found or failed, attempting manual find.")
        set(FFMPEG_INCLUDE_DIR /usr/local/include)
        set(FFMPEG_LIB_DIR /usr/local/lib)
        include_directories(${FFMPEG_INCLUDE_DIR})
        link_directories(${FFMPEG_LIB_DIR})
        set(FFMPEG_LIBRARIES avformat avcodec avutil swresample)
    else()
        include_directories(${AVFORMAT_INCLUDE_DIRS} ${AVCODEC_INCLUDE_DIRS} ${AVUTIL_INCLUDE_DIRS} ${SWRESAMPLE_INCLUDE_DIRS})
        set(FFMPEG_LIBRARIES ${AVFORMAT_LIBRARIES} ${AVCODEC_LIBRARIES} ${AVUTIL_LIBRARIES} ${SWRESAMPLE_LIBRARIES})
    endif()
endif()

# Find required packages
find_package(PkgConfig REQUIRED)

# ONNX Runtime
find_package(onnxruntime QUIET CONFIG)
if(NOT onnxruntime_FOUND)
    pkg_check_modules(ONNXRUNTIME QUIET libonnxruntime)
    if(ONNXRUNTIME_FOUND)
        set(ONNXRUNTIME_INCLUDE_DIR ${ONNXRUNTIME_INCLUDE_DIRS})
        set(ONNXRUNTIME_LIB ${ONNXRUNTIME_LIBRARIES})
    else()
        find_path(ONNXRUNTIME_INCLUDE_DIR NAMES onnxruntime_cxx_api.h PATHS /usr/local/include /usr/include PATH_SUFFIXES onnxruntime)
        find_library(ONNXRUNTIME_LIB NAMES onnxruntime libonnxruntime PATHS /usr/local/lib /usr/lib)
    endif()
endif()

# nlohmann/json
find_package(nlohmann_json REQUIRED)

# Add hush Core
add_library(hush_core STATIC 
    src/core/SilenceDetector.cpp 
    src/core/Logger.cpp
    src/core/AudioPlayer.cpp
    src/core/AudioRecorder.cpp
    src/core/StateManager.cpp
    src/core/TtsEngine.cpp
)
set_target_properties(hush_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(hush_core PUBLIC include)

if(onnxruntime_FOUND)
    target_link_libraries(hush_core PUBLIC onnxruntime::onnxruntime)
else()
    target_include_directories(hush_core PUBLIC ${ONNXRUNTIME_INCLUDE_DIR})
    target_link_libraries(hush_core PUBLIC ${ONNXRUNTIME_LIB})
endif()
target_link_libraries(hush_core PUBLIC nlohmann_json::nlohmann_json)

# Add hush FFI
add_library(hush_ffi STATIC src/ffi/hush_api.cpp)
set_target_properties(hush_ffi PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(hush_ffi PRIVATE hush_core)
target_include_directories(hush_ffi PUBLIC include include/ffi)

if (hush_BUILD_CLI)
    # Add CLI Tool
    add_executable(hush 
        src/cli/main.cpp 
        src/codecs/AudioProcessor.cpp
    )
    target_link_libraries(hush PRIVATE hush_core ${FFMPEG_LIBRARIES})

    # System libraries for miniaudio (used by AudioPlayer in hush_core)
    if(UNIX AND NOT APPLE)
        target_link_libraries(hush PRIVATE pthread m dl)
    endif()
    if(APPLE)
        target_link_libraries(hush PRIVATE "-framework CoreAudio" "-framework CoreFoundation" "-framework AudioUnit")
    endif()

    target_include_directories(hush PRIVATE include)
endif()

# Examples
add_subdirectory(examples/hush-terminal-recorder)

# Tests
# file(MAKE_DIRECTORY test/unit)

# add_executable(hush_Logger_tests test/unit/LoggerTests.cpp src/core/Logger.cpp)
# target_link_libraries(hush_Logger_tests PRIVATE gtest gtest_main)
# target_include_directories(hush_Logger_tests PRIVATE include)
# add_test(NAME run_Logger_tests COMMAND hush_Logger_tests)

# add_executable(hush_AudioProcessor_tests test/unit/AudioProcessorTests.cpp src/core/Logger.cpp)
# target_link_libraries(hush_AudioProcessor_tests PRIVATE hush_core gtest gtest_main)
# target_include_directories(hush_AudioProcessor_tests PRIVATE include)
# add_test(NAME run_AudioProcessor_tests COMMAND hush_AudioProcessor_tests)

