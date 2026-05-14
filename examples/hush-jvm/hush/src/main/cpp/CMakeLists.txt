cmake_minimum_required(VERSION 3.10)
project(klama_hush_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS})
include_directories(${HUSH_ROOT_DIR}/include)

add_library(klama_hush_jni SHARED HushJni.cpp)
target_link_libraries(klama_hush_jni PRIVATE
    ${HUSH_CORE_BUILD_DIR}/libhush_ffi.a
    ${HUSH_CORE_BUILD_DIR}/libhush_core.a
)

