cmake_minimum_required(VERSION 3.18)
project(mihonsy-enhance)

set(CMAKE_CXX_STANDARD 17)

# =====================================================================================
# 1) CPU resampler — always built (Lanczos3 / Catmull-Rom). JNI lives in lanczos3.cpp.
# =====================================================================================
add_library(mihonsy-enhance SHARED
    lanczos3.cpp
)

target_include_directories(mihonsy-enhance PRIVATE ${CMAKE_SOURCE_DIR})

target_link_libraries(mihonsy-enhance
    android
    log
    jnigraphics
)

# =====================================================================================
# 2) GPU upscaler — ncnn + Vulkan, optional.
#
# Kept as a SEPARATE shared library on purpose: ncnn's CMake package exports
# INTERFACE_COMPILE_OPTIONS "-fno-rtti;-fno-exceptions", which would otherwise be
# applied to every source of this target (including lanczos3.cpp).
#
# The SDK is bundled under third_party/ but can be overridden with -DNCNN_SDK_DIR=...
# or the NCNN_SDK_DIR env var. If it is absent, this library is simply not built and
# the app keeps using the CPU resamplers.
# =====================================================================================
set(NCNN_SDK_DIR "${NCNN_SDK_DIR}" CACHE PATH "Path to the ncnn Android Vulkan SDK")
if(NOT NCNN_SDK_DIR AND DEFINED ENV{NCNN_SDK_DIR})
    set(NCNN_SDK_DIR "$ENV{NCNN_SDK_DIR}")
endif()
if(NOT NCNN_SDK_DIR)
    set(NCNN_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ncnn-20260113-android-vulkan")
endif()

set(KOMIHO_NCNN_FOUND FALSE)
if(EXISTS "${NCNN_SDK_DIR}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake"
   OR EXISTS "${NCNN_SDK_DIR}/${ANDROID_ABI}/lib/cmake/ncnn/ncnn-config.cmake")
    set(KOMIHO_NCNN_FOUND TRUE)
endif()

if(KOMIHO_NCNN_FOUND)
    set(ncnn_DIR "${NCNN_SDK_DIR}/${ANDROID_ABI}/lib/cmake/ncnn")
    find_package(ncnn REQUIRED)

    # glslc ships with the NDK; it turns the fused Vulkan compute shaders into the
    # SPIR-V headers that shaders.h #includes.
    file(GLOB GLSLC_CANDIDATES
        "${ANDROID_NDK}/shader-tools/*/glslc"
        "${ANDROID_NDK}/shader-tools/*/glslc.exe"
    )
    if(GLSLC_CANDIDATES)
        list(GET GLSLC_CANDIDATES 0 GLSLC_EXECUTABLE)
    else()
        find_program(GLSLC_EXECUTABLE glslc REQUIRED)
    endif()

    set(GENERATED_SHADER_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated-shaders")
    file(MAKE_DIRECTORY "${GENERATED_SHADER_DIR}")

    set(FUSED_PREPROC_SHADER "${CMAKE_CURRENT_SOURCE_DIR}/waifu2x_fused_preproc.comp")
    set(FUSED_POSTPROC_SHADER "${CMAKE_CURRENT_SOURCE_DIR}/waifu2x_fused_postproc.comp")
    set(FUSED_SHADER_OUTPUTS
        "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp16.inc"
        "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp16.inc"
        "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp32.inc"
        "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp32.inc"
    )

    add_custom_command(
        OUTPUT ${FUSED_SHADER_OUTPUTS}
        COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -DFUSED_FP16=1 -mfmt=c "${FUSED_PREPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp16.inc"
        COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -DFUSED_FP16=1 -mfmt=c "${FUSED_POSTPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp16.inc"
        COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -mfmt=c "${FUSED_PREPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_preproc_fp32.inc"
        COMMAND "${GLSLC_EXECUTABLE}" -g -fshader-stage=compute --target-env=vulkan1.0 -mfmt=c "${FUSED_POSTPROC_SHADER}" -o "${GENERATED_SHADER_DIR}/waifu2x_fused_postproc_fp32.inc"
        DEPENDS "${FUSED_PREPROC_SHADER}" "${FUSED_POSTPROC_SHADER}"
        VERBATIM
    )
    add_custom_target(mihonsy-fused-shaders DEPENDS ${FUSED_SHADER_OUTPUTS})

    add_library(waifu2x-jni SHARED
        waifu2x.cpp
        waifu2x_jni.cpp
        qnn_backend.cpp
    )
    add_dependencies(waifu2x-jni mihonsy-fused-shaders)

    target_include_directories(waifu2x-jni PRIVATE
        ${CMAKE_SOURCE_DIR}
        ${GENERATED_SHADER_DIR}
    )

    # Komiho: QNN/HTP (Qualcomm NPU) backend — headers are vendored under
    # include/QNN/ (extracted from the official QAIRT SDK zip, MIT-licensed API
    # headers). Runtime .so files are dlopen'ed at run time (never linked), and
    # only on arm64-v8a: other ABIs compile the stub path (probe fails).
    if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${CMAKE_SOURCE_DIR}/include/QNN/QnnInterface.h")
        target_compile_definitions(waifu2x-jni PRIVATE MIHON_ENABLE_QNN=1)
        target_include_directories(waifu2x-jni PRIVATE "${CMAKE_SOURCE_DIR}/include/QNN")
    else()
        target_compile_definitions(waifu2x-jni PRIVATE MIHON_ENABLE_QNN=0)
    endif()

    find_package(Threads REQUIRED)

    target_link_libraries(waifu2x-jni
        ncnn
        android
        log
        jnigraphics
        GLESv3
        EGL
        dl
    )
endif()
