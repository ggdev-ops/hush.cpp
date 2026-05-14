cmake_minimum_required(VERSION 3.22.1)
project("hush_android")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Include Hush headers
include_directories(${HUSH_ROOT_DIR}/include)

# We need to compile the Hush core library as part of this build 
# so it compiles for the correct Android ABI
add_subdirectory(${HUSH_ROOT_DIR} hush_core_build)

# Add our JNI library
add_library(klama_hush_android SHARED HushJni.cpp)

# Link against the core libraries and Android specific libs
target_link_libraries(klama_hush_android
    hush_ffi
    hush_core
    log
    android
)

