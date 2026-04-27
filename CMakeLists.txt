cmake_minimum_required(VERSION 3.10)
project(Hush VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

enable_testing() # Enable CTest

# Add Google Test
add_subdirectory(.third_party/googletest)

# Define version for the code
add_definitions(-DHUSH_VERSION="0.1v")

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

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

# Add Hush Core
add_library(hush_core STATIC 
    src/core/SilenceDetector.cpp 
    src/core/Logger.cpp
)
target_include_directories(hush_core PUBLIC include)

# Add Hush FFI
add_library(hush_ffi SHARED src/ffi/hush_api.cpp)
target_link_libraries(hush_ffi PRIVATE hush_core)
target_include_directories(hush_ffi PUBLIC include include/ffi)

# Add CLI Tool
add_executable(hush_remover 
    src/cli/main.cpp 
    src/codecs/AudioProcessor.cpp
)
target_link_libraries(hush_remover PRIVATE hush_core ${FFMPEG_LIBRARIES})
target_include_directories(hush_remover PRIVATE include)

# Tests
file(MAKE_DIRECTORY test/unit)

add_executable(hush_Logger_tests test/unit/LoggerTests.cpp src/core/Logger.cpp)
target_link_libraries(hush_Logger_tests PRIVATE gtest gtest_main)
target_include_directories(hush_Logger_tests PRIVATE include)
add_test(NAME run_Logger_tests COMMAND hush_Logger_tests)

add_executable(hush_AudioProcessor_tests test/unit/AudioProcessorTests.cpp src/core/Logger.cpp)
target_link_libraries(hush_AudioProcessor_tests PRIVATE hush_core gtest gtest_main)
target_include_directories(hush_AudioProcessor_tests PRIVATE include)
add_test(NAME run_AudioProcessor_tests COMMAND hush_AudioProcessor_tests)
