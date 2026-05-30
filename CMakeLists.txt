cmake_minimum_required(VERSION 3.10)
project(hush VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

enable_testing() # Enable CTest

# Add Google Test via FetchContent
include(FetchContent)
FetchContent_Declare(
  googletest
  URL https://github.com/google/googletest/archive/refs/heads/main.zip
)
# Forcing googletest to not use its own install target
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

# Define version for the code
add_definitions(-Dhush_VERSION="1.0.0")

option(hush_BUILD_CLI "Build the CLI tool (requires FFmpeg)" ON)
option(hush_BUILD_TESTS "Build tests" ON)
option(hush_BUILD_EXAMPLES "Build examples" ON)

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

# Add hush Core
add_library(hush_core STATIC 
    src/core/SilenceDetector.cpp 
    src/core/Logger.cpp
    src/core/AudioPlayer.cpp
    src/core/AudioRecorder.cpp
    src/core/StateManager.cpp
    src/core/FlowController.cpp
)
set_target_properties(hush_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(hush_core PUBLIC include)

# System libraries for miniaudio (used by AudioPlayer/AudioRecorder in hush_core)
if(UNIX AND NOT APPLE)
    target_link_libraries(hush_core PUBLIC pthread m dl)
endif()
if(APPLE)
    target_link_libraries(hush_core PUBLIC "-framework CoreAudio" "-framework CoreFoundation" "-framework AudioUnit")
endif()

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
    target_include_directories(hush PRIVATE include)
endif()

# Examples
if (hush_BUILD_EXAMPLES)
    add_subdirectory(examples/hush-terminal-recorder)
endif()

# Tests
if (hush_BUILD_TESTS)
    file(MAKE_DIRECTORY ${CMAKE_BINARY_DIR}/test/unit)

    add_executable(hush_Logger_tests test/unit/LoggerTests.cpp src/core/Logger.cpp)
    target_link_libraries(hush_Logger_tests PRIVATE gtest gtest_main)
    target_include_directories(hush_Logger_tests PRIVATE include)
    add_test(NAME run_Logger_tests COMMAND hush_Logger_tests)

    add_executable(hush_SilenceDetector_tests test/unit/SilenceDetectorTests.cpp)
    target_link_libraries(hush_SilenceDetector_tests PRIVATE hush_core gtest gtest_main)
    target_include_directories(hush_SilenceDetector_tests PRIVATE include)
    add_test(NAME run_SilenceDetector_tests COMMAND hush_SilenceDetector_tests)

    add_executable(hush_StateManager_tests test/unit/StateManagerTests.cpp)
    target_link_libraries(hush_StateManager_tests PRIVATE hush_core gtest gtest_main)
    target_include_directories(hush_StateManager_tests PRIVATE include)
    add_test(NAME run_StateManager_tests COMMAND hush_StateManager_tests)

    add_executable(hush_AudioRecorder_tests test/unit/AudioRecorderTests.cpp)
    target_link_libraries(hush_AudioRecorder_tests PRIVATE hush_core hush_ffi gtest gtest_main)
    target_include_directories(hush_AudioRecorder_tests PRIVATE include)
    add_test(NAME run_AudioRecorder_tests COMMAND hush_AudioRecorder_tests)

    if (hush_BUILD_CLI)
        add_executable(hush_AudioProcessor_tests test/unit/AudioProcessorTests.cpp src/codecs/AudioProcessor.cpp)
        target_link_libraries(hush_AudioProcessor_tests PRIVATE hush_core gtest gtest_main ${FFMPEG_LIBRARIES})
        target_include_directories(hush_AudioProcessor_tests PRIVATE include)
        add_test(NAME run_AudioProcessor_tests COMMAND hush_AudioProcessor_tests)
    endif()

    add_executable(hush_RingBuffer_tests test/unit/RingBufferTests.cpp)
    target_link_libraries(hush_RingBuffer_tests PRIVATE hush_core gtest gtest_main)
    target_include_directories(hush_RingBuffer_tests PRIVATE include)
    add_test(NAME run_RingBuffer_tests COMMAND hush_RingBuffer_tests)

    add_executable(hush_FlowController_tests test/unit/FlowControllerTests.cpp)
    target_link_libraries(hush_FlowController_tests PRIVATE hush_core gtest gtest_main)
    target_include_directories(hush_FlowController_tests PRIVATE include)
    add_test(NAME run_FlowController_tests COMMAND hush_FlowController_tests)
endif()
