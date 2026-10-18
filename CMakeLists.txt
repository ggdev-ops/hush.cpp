cmake_minimum_required(VERSION 3.16)
project(hush VERSION 1.0.0 LANGUAGES CXX)

# ---------------------------------------------------------------------------
# Policies
# ---------------------------------------------------------------------------
if (POLICY CMP0135)
  cmake_policy(SET CMP0135 NEW)
endif()

# ---------------------------------------------------------------------------
# Global settings
# ---------------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)
set(CMAKE_CXX_EXTENSIONS OFF)

# Install layout (GNUInstallDirs gives us bin/ lib/ include/ share/ etc.)
include(GNUInstallDirs)

# Allow static or shared builds of hush_core / hush_ffi.
# Default ON: matches current behaviour (libhush_*.dylib) and what the KMP
# JVM/desktop binding expects via cinterop. Flip with -DBUILD_SHARED_LIBS=OFF.
option(BUILD_SHARED_LIBS "Build shared libraries" ON)

# Make every library relocatable: the installed dylib looks for its deps
# next to itself, and the installed CLI looks for libs in ../lib.
set(CMAKE_INSTALL_RPATH "@loader_path/../lib")
set(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)
set(CMAKE_BUILD_WITH_INSTALL_RPATH OFF)

# ---------------------------------------------------------------------------
# Options
# ---------------------------------------------------------------------------
option(hush_BUILD_CLI      "Build the CLI tool (requires FFmpeg)" ON)
option(hush_BUILD_TESTS    "Build tests"                          ON)
option(hush_BUILD_EXAMPLES "Build examples"                       ON)

# ---------------------------------------------------------------------------
# GoogleTest (tests only — never installed)
# ---------------------------------------------------------------------------
if (hush_BUILD_TESTS)
  include(FetchContent)
  FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/heads/main.zip
  )
  set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
  FetchContent_MakeAvailable(googletest)
endif()

# ---------------------------------------------------------------------------
# FFmpeg (CLI only)
# ---------------------------------------------------------------------------
if (hush_BUILD_CLI)
  # On macOS, make sure pkg-config can see Homebrew (Apple Silicon + Intel).
  if (APPLE)
    execute_process(COMMAND brew --prefix
      OUTPUT_VARIABLE HOMEBREW_PREFIX
      OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
    if (HOMEBREW_PREFIX)
      set(ENV{PKG_CONFIG_PATH} "$ENV{PKG_CONFIG_PATH}:${HOMEBREW_PREFIX}/lib/pkgconfig")
    endif()
  endif()

  find_package(PkgConfig QUIET)
  if (PKG_CONFIG_FOUND)
    pkg_check_modules(AVFORMAT   IMPORTED_TARGET libavformat)
    pkg_check_modules(AVCODEC    IMPORTED_TARGET libavcodec)
    pkg_check_modules(AVUTIL     IMPORTED_TARGET libavutil)
    pkg_check_modules(SWRESAMPLE IMPORTED_TARGET libswresample)
  endif()

  if (AVFORMAT_FOUND AND AVCODEC_FOUND AND AVUTIL_FOUND AND SWRESAMPLE_FOUND)
    set(FFMPEG_TARGETS
      PkgConfig::AVFORMAT
      PkgConfig::AVCODEC
      PkgConfig::AVUTIL
      PkgConfig::SWRESAMPLE)
  else()
    message(STATUS "pkg-config did not find FFmpeg — falling back to find_library()")
    find_path(FFMPEG_INCLUDE_DIR
      libavformat/avformat.h
      PATHS ${HOMEBREW_PREFIX}/include /opt/homebrew/include /usr/local/include)
    find_library(AVFORMAT_LIB   avformat   PATHS ${HOMEBREW_PREFIX}/lib /opt/homebrew/lib /usr/local/lib)
    find_library(AVCODEC_LIB    avcodec    PATHS ${HOMEBREW_PREFIX}/lib /opt/homebrew/lib /usr/local/lib)
    find_library(AVUTIL_LIB     avutil     PATHS ${HOMEBREW_PREFIX}/lib /opt/homebrew/lib /usr/local/lib)
    find_library(SWRESAMPLE_LIB swresample PATHS ${HOMEBREW_PREFIX}/lib /opt/homebrew/lib /usr/local/lib)

    if (NOT FFMPEG_INCLUDE_DIR OR NOT AVFORMAT_LIB OR NOT AVCODEC_LIB
        OR NOT AVUTIL_LIB OR NOT SWRESAMPLE_LIB)
      message(FATAL_ERROR
        "FFmpeg not found. Install with: brew install ffmpeg  "
        "or configure with -Dhush_BUILD_CLI=OFF")
    endif()

    set(FFMPEG_TARGETS
      ${AVFORMAT_LIB} ${AVCODEC_LIB} ${AVUTIL_LIB} ${SWRESAMPLE_LIB})
    # Include dir is only needed by the CLI / AudioProcessor translation units,
    # so we attach it to those targets, not globally.
  endif()
endif()

# ---------------------------------------------------------------------------
# hush_core
# ---------------------------------------------------------------------------
add_library(hush_core
  src/core/SilenceDetector.cpp
  src/core/Logger.cpp
  src/core/AudioPlayer.cpp
  src/core/AudioRecorder.cpp
  src/core/StateManager.cpp
  src/core/FlowController.cpp
)
add_library(Hush::core ALIAS hush_core)

set_target_properties(hush_core PROPERTIES
  POSITION_INDEPENDENT_CODE ON
  VERSION   ${PROJECT_VERSION}
  SOVERSION ${PROJECT_VERSION_MAJOR}
)

target_include_directories(hush_core
  PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/hush>
)

# Platform system libs needed by miniaudio (compiled into AudioPlayer/AudioRecorder).
if(APPLE)
  target_link_libraries(hush_core PUBLIC
    "-framework CoreAudio"
    "-framework CoreFoundation"
    "-framework AudioUnit")
elseif(ANDROID)
  target_link_libraries(hush_core PUBLIC m dl)
elseif(UNIX)
  target_link_libraries(hush_core PUBLIC pthread m dl)
endif()

# ---------------------------------------------------------------------------
# hush_ffi
# ---------------------------------------------------------------------------
add_library(hush_ffi src/ffi/hush_api.cpp)
add_library(Hush::ffi ALIAS hush_ffi)

set_target_properties(hush_ffi PROPERTIES
  POSITION_INDEPENDENT_CODE ON
  VERSION   ${PROJECT_VERSION}
  SOVERSION ${PROJECT_VERSION_MAJOR}
)

target_link_libraries(hush_ffi PUBLIC hush_core)

# The C API header is installed at <prefix>/include/hush/hush_api.h, so a
# consumer does:  #include <hush/hush_api.h>
target_include_directories(hush_ffi
  PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include/ffi>
    $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/hush>
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
if (hush_BUILD_CLI)
  add_executable(hush
    src/cli/main.cpp
    src/cli/commands/TerminalUtils.cpp
    src/cli/commands/StatusCommand.cpp
    src/cli/commands/StopCommand.cpp
    src/cli/commands/PlayCommand.cpp
    src/cli/commands/RecordCommand.cpp
    src/cli/commands/SilenceCommand.cpp
    src/codecs/AudioProcessor.cpp
  )
  target_link_libraries(hush PRIVATE hush_core ${FFMPEG_TARGETS})
  target_include_directories(hush PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/include/codecs
  )
  if (FFMPEG_INCLUDE_DIR)
    target_include_directories(hush PRIVATE ${FFMPEG_INCLUDE_DIR})
  endif()
  # CLI in <prefix>/bin needs to find libhush_core.dylib in <prefix>/lib.
  set_target_properties(hush PROPERTIES
    INSTALL_RPATH "@loader_path/../lib"
  )
endif()

# ---------------------------------------------------------------------------
# Tests (unchanged behaviour, but target-scoped include dirs)
# ---------------------------------------------------------------------------
if (hush_BUILD_TESTS)
  enable_testing()

  function(hush_add_test name)
    cmake_parse_arguments(T "" "SOURCE" "LIBS" ${ARGN})
    add_executable(${name} ${T_SOURCE})
    target_link_libraries(${name} PRIVATE ${T_LIBS} gtest gtest_main)
    target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    add_test(NAME run_${name} COMMAND ${name})
  endfunction()

  hush_add_test(hush_Logger_tests
    SOURCE test/unit/LoggerTests.cpp
    LIBS   hush_core)

  hush_add_test(hush_SilenceDetector_tests
    SOURCE test/unit/SilenceDetectorTests.cpp
    LIBS   hush_core)

  hush_add_test(hush_StateManager_tests
    SOURCE test/unit/StateManagerTests.cpp
    LIBS   hush_core)

  hush_add_test(hush_AudioRecorder_tests
    SOURCE test/unit/AudioRecorderTests.cpp
    LIBS   hush_core hush_ffi)

  hush_add_test(hush_RingBuffer_tests
    SOURCE test/unit/RingBufferTests.cpp
    LIBS   hush_core)

  hush_add_test(hush_FlowController_tests
    SOURCE test/unit/FlowControllerTests.cpp
    LIBS   hush_core)

  if (hush_BUILD_CLI)
    add_executable(hush_AudioProcessor_tests
      test/unit/AudioProcessorTests.cpp
      src/codecs/AudioProcessor.cpp)
    target_link_libraries(hush_AudioProcessor_tests
      PRIVATE hush_core ${FFMPEG_TARGETS} gtest gtest_main)
    target_include_directories(hush_AudioProcessor_tests PRIVATE
      ${CMAKE_CURRENT_SOURCE_DIR}/include
      ${CMAKE_CURRENT_SOURCE_DIR}/include/codecs)
    if (FFMPEG_INCLUDE_DIR)
      target_include_directories(hush_AudioProcessor_tests PRIVATE ${FFMPEG_INCLUDE_DIR})
    endif()
    add_test(NAME run_hush_AudioProcessor_tests COMMAND hush_AudioProcessor_tests)
  endif()
endif()

# ===========================================================================
# Installation
# ===========================================================================
#
# Layout (all paths relative to CMAKE_INSTALL_PREFIX, e.g. ~/.local):
#
#   bin/hush
#   include/hush/hush_api.h
#   include/hush/SilenceDetector.h
#   include/hush/RingBuffer.h
#   include/hush/FlowController.h
#   include/hush/Logger.h
#   include/hush/StateManager.h
#   lib/libhush_core.1.dylib  +  libhush_core.dylib symlink
#   lib/libhush_ffi.1.dylib   +  libhush_ffi.dylib symlink
#   lib/cmake/Hush/HushTargets.cmake
#   lib/cmake/Hush/HushTargets-release.cmake
#   share/doc/hush/README.md
#
# Intentionally NOT installed:
#   include/core/AudioPlayer.h, AudioRecorder.h  (they pull in miniaudio.h)
#   include/core/miniaudio.h                     (95k-line private impl detail)
#   include/codecs/AudioProcessor.h              (FFmpeg-only, CLI-private)
#   test binaries, gtest, gmock
#
# Consumers then do:
#   #include <hush/hush_api.h>
#   target_link_libraries(app PRIVATE Hush::ffi)   # pulls in Hush::core transitively

# ---- Library targets -------------------------------------------------------
install(TARGETS hush_core hush_ffi
  EXPORT  HushTargets
  RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
  LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
  ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
  INCLUDES DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/hush
)

# ---- CLI -------------------------------------------------------------------
if (hush_BUILD_CLI)
  install(TARGETS hush
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
endif()

# ---- Public headers --------------------------------------------------------
# The C API header lands flat at <prefix>/include/hush/hush_api.h
install(FILES include/ffi/hush_api.h
  DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/hush)

# C++ headers that do NOT include miniaudio.h and are safe to expose.
install(FILES
  include/core/SilenceDetector.h
  include/core/RingBuffer.h
  include/core/FlowController.h
  include/core/Logger.h
  include/core/StateManager.h
  DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/hush)

# ---- CMake package export --------------------------------------------------
install(EXPORT HushTargets
  NAMESPACE  Hush::
  FILE       HushTargets.cmake
  DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/Hush)

# ---- Documentation (optional, cheap) --------------------------------------
install(FILES README.md PROJECT_OVERVIEW.md
  DESTINATION ${CMAKE_INSTALL_DATADIR}/doc/hush
  OPTIONAL)

# ---- CMake package config (find_package(Hush CONFIG REQUIRED) support) -----
include(CMakePackageConfigHelpers)

configure_package_config_file(
  ${CMAKE_CURRENT_SOURCE_DIR}/cmake/HushConfig.cmake.in
  ${CMAKE_CURRENT_BINARY_DIR}/HushConfig.cmake
  INSTALL_DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/Hush
)

write_basic_package_version_file(
  ${CMAKE_CURRENT_BINARY_DIR}/HushConfigVersion.cmake
  VERSION       ${PROJECT_VERSION}
  COMPATIBILITY SameMajorVersion
)

install(FILES
  ${CMAKE_CURRENT_BINARY_DIR}/HushConfig.cmake
  ${CMAKE_CURRENT_BINARY_DIR}/HushConfigVersion.cmake
  DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/Hush)
