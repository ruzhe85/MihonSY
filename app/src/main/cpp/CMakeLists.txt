cmake_minimum_required(VERSION 3.18)
project(mihonsy-enhance)

set(CMAKE_CXX_STANDARD 17)

# Lightweight image enhancement: Lanczos3 / Catmull-Rom CPU resampling only.
# Anime4K (GPU shaders) files are retained on disk but intentionally excluded from
# the build (commented out below) — it is no longer used. No heavy CNN / ncnn models.
set(ENHANCE_SOURCES
    lanczos3.cpp
    # MihonSY: Anime4K disabled — excluded from the build so it never compiles.
    # anime4k.cpp
    # mihonsy_jni.cpp
)

add_library(mihonsy-enhance SHARED ${ENHANCE_SOURCES})

target_include_directories(mihonsy-enhance PRIVATE ${CMAKE_SOURCE_DIR})

target_link_libraries(mihonsy-enhance
    android
    log
    jnigraphics
    # MihonSY: GLESv3 / EGL only needed by Anime4K, now disabled.
    # GLESv3
    # EGL
)
