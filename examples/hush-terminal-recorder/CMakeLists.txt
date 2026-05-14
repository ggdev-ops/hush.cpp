cmake_minimum_required(VERSION 3.10)
project(hush_terminal_recorder LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)

add_executable(hush-terminal-recorder main.cpp)

# Include directories
target_include_directories(hush-terminal-recorder PRIVATE 
    ${CMAKE_SOURCE_DIR}/include
    ${CMAKE_SOURCE_DIR}/include/ffi
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link against hush_ffi and hush_core
# hush_ffi is a static library that uses hush_core
target_link_libraries(hush-terminal-recorder PRIVATE hush_ffi hush_core)

# System libraries for miniaudio
if(UNIX AND NOT APPLE)
    target_link_libraries(hush-terminal-recorder PRIVATE pthread m dl)
endif()

if(APPLE)
    target_link_libraries(hush-terminal-recorder PRIVATE "-framework CoreAudio" "-framework CoreFoundation" "-framework AudioUnit")
endif()

