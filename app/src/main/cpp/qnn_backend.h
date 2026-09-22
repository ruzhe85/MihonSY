// Komiho: QNN/HTP (Qualcomm NPU) backend, ported verbatim from
// HaoweiLi97/mihon_img_upscale (Apache-2.0), minus the spatial-depth parts.
// MIHON_ENABLE_QNN=0 compiles to stubs (probe fails, process returns -1).
#ifndef MIHON_QNN_BACKEND_H
#define MIHON_QNN_BACKEND_H

#include <atomic>
#include <cstdint>
#include <string>

namespace qnn_backend {

bool is_runtime_loadable();
int architecture();
bool initialize(const std::string &context_path, int padding);
bool is_initialized();
int scale();
int process_rgba(const uint8_t *input, int width, int height, int input_stride,
                 uint8_t *output, int output_stride,
                 std::atomic<int> *progress,
                 const std::atomic<bool> *should_abort);
void shutdown();

// Komiho (2026-09-18): 把 ADSP_LIBRARY_PATH 指向 nativeLibraryDir。
// **必须在任何一次 dlopen("libQnnHtp.so") 之前调用**（见 qnn_backend.cpp 里的长注释）。
// preferred_dir 为 null/空时用 dladdr 自定位本 .so 所在目录。
void ensure_dsp_path(const char *preferred_dir);

} // namespace qnn_backend

#endif // MIHON_QNN_BACKEND_H
