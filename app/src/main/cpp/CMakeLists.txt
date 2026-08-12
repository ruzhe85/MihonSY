cmake_minimum_required(VERSION 3.18)
project(mihonsy-enhance)

set(CMAKE_CXX_STANDARD 17)

# Lightweight image enhancement: Anime4K (GPU shaders) + Lanczos3 (CPU resampling).
# No heavy CNN / ncnn models are used.
set(ENHANCE_SOURCES
    anime4k.cpp
    lanczos3.cpp
    mihonsy_jni.cpp
)

add_library(mihonsy-enhance SHARED ${ENHANCE_SOURCES})

target_include_directories(mihonsy-enhance PRIVATE ${CMAKE_SOURCE_DIR})

target_link_libraries(mihonsy-enhance
    android
    log
    jnigraphics
    GLESv3
    EGL
)
