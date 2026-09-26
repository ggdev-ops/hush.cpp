# CMake wrapper for iOS cross-compilation
# Fixes Objective-C++ compilation for miniaudio.h on iOS
#
# miniaudio.h includes Apple's AVFoundation/Foundation headers
# which use Objective-C syntax (@class NSString, @protocol, etc.)
# These files must be compiled as Objective-C++ on Apple platforms.

cmake_minimum_required(VERSION 3.10)
project(hush VERSION 1.0.0 LANGUAGES CXX C OBJCXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../include)

# hush Core — compile AudioPlayer.cpp and AudioRecorder.cpp as Objective-C++
# STATIC because iOS does not support .dylib — only static libs or frameworks
add_library(hush_core STATIC
    ../../src/core/SilenceDetector.cpp
    ../../src/core/Logger.cpp
    ../../src/core/AudioPlayer.cpp
    ../../src/core/AudioRecorder.cpp
    ../../src/core/StateManager.cpp
    ../../src/core/FlowController.cpp
)
set_target_properties(hush_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(hush_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/../../include)

# Force AudioPlayer.cpp and AudioRecorder.cpp to compile as Objective-C++
# because miniaudio.h includes Apple's AVFoundation/Foundation headers
set_source_files_properties(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../src/core/AudioPlayer.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../../src/core/AudioRecorder.cpp
    PROPERTIES LANGUAGE OBJCXX
)

# Apple frameworks for miniaudio (linked into final app, not this static lib)
if(APPLE)
    find_library(COREAUDIO_FRAMEWORK CoreAudio)
    find_library(COREFOUNDATION_FRAMEWORK CoreFoundation)
    find_library(AUDIOUNIT_FRAMEWORK AudioUnit)
    find_library(AVFOUNDATION_FRAMEWORK AVFoundation)
    target_link_libraries(hush_core PUBLIC
        ${COREAUDIO_FRAMEWORK}
        ${COREFOUNDATION_FRAMEWORK}
        ${AUDIOUNIT_FRAMEWORK}
        ${AVFOUNDATION_FRAMEWORK}
    )
endif()

# hush FFI
add_library(hush_ffi STATIC ../../src/ffi/hush_api.cpp)
set_target_properties(hush_ffi PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(hush_ffi PUBLIC hush_core)
target_include_directories(hush_ffi PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/../../include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../include/ffi
)
