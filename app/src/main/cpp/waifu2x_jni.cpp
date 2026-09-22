#include "waifu2x.h"
#include "qnn_backend.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <vector>

#define TAG "Waifu2xJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static Waifu2x *g_waifu2x = nullptr;
static std::mutex g_lock;
static std::atomic<int> g_progress{0};
static std::atomic<int> g_current_id{-1};
static std::atomic<int> g_ui_busy{0};
static std::atomic<bool> g_abort_processing{false};
// Komiho: 最近一次推理的**纯耗时**（ms，不含任何等锁；-1 = 未知/失败/被抢占）。
// 为什么要它：Kotlin 侧只能量到 `nativeProcess` 的整体耗时，而 `nativeProcess` 内部**自己
// 还要再拿一次 g_lock**（真正的推理排队发生在这里）—— 那段排队会被当成"推理耗时"。
// 实测某页 Kotlin 报 4913ms，而原生三趟都是 ~2450ms ⇒ 角标因此虚高 2.4s。
// 这里把纯耗时单独曝给 Kotlin，让它能把两段等锁都剔除。
static std::atomic<long long> g_last_inference_ms{-1};

// ── Komiho: QNN/HTP (Qualcomm NPU) 接线 ─────────────────────────────────────
// 引擎选择发生在 Kotlin 侧（ensureEngine 按模型 backend 调 nativeInitQnn 或
// nativeInitW2xEx）；nativeProcess 只看 is_initialized() 自动路由，QNN 失败/未
// 初始化时自然落进下方 fused/staged(ncnn) 路径 ⇒ 回退链在原生层天然成立。
//
// ★★ ADSP_LIBRARY_PATH 必须在**首次 dlopen("libQnnHtp.so") 之前**指向本 App 的
// nativeLibraryDir —— HTP Skel（libQnnHtpV<arch>Skel.so）由 DSP 侧加载器按它查找。
// 这个 setenv 以前放在 nativeInitQnn 里，但 Kotlin 会**先**调
// nativeIsQnnRuntimeAvailable / nativeGetQnnArchitecture（两者都会 dlopen
// libQnnHtp.so 并建立 FastRPC/DSP 会话），那时代码还没设 env ⇒ DSP 侧此后就再也
// 装不上 Skel（err 4000，且每次重试都失败）。
// 现在改到 JNI_OnLoad（System.loadLibrary 那一刻）设置，且用 dladdr 自定位目录，
// 不再依赖 Kotlin 的调用顺序。详见 qnn_backend.cpp 里 ensure_dsp_path 的注释。
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
  qnn_backend::ensure_dsp_path(nullptr);
  return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeIsQnnRuntimeAvailable(
    JNIEnv *, jobject) {
  return qnn_backend::is_runtime_loadable() ? JNI_TRUE : JNI_FALSE;
}

// Komiho: 本机 HTP 架构号（75/79/81…），0 = 非高通 / 无可用 HTP。
// 与 is_runtime_loadable 的区别很关键：dlopen("libQnnHtp.so") 因为 .so 就在我们自己的
// APK 里，在**任何**设备上都会成功 ⇒ 它只能证明"库能加载"，不能证明"这台机器有 HTP"。
// 真正能区分高通与非高通的判据是 deviceGetPlatformInfo 能否返回 ON_CHIP 设备及其 arch。
extern "C" JNIEXPORT jint JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeGetQnnArchitecture(
    JNIEnv *, jobject) {
  return static_cast<jint>(qnn_backend::architecture());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitQnn(
    JNIEnv *env, jobject, jstring context_path, jstring native_library_dir,
    jint padding) {
  std::lock_guard<std::mutex> lock(g_lock);
  const char *context_path_chars = env->GetStringUTFChars(context_path, nullptr);
  const char *library_dir_chars =
      env->GetStringUTFChars(native_library_dir, nullptr);
  // Komiho (2026-09-18): env 早在 JNI_OnLoad 就设好了；这里用 Kotlin 传来的
  // nativeLibraryDir 再确认一次（幂等，并打印 before/after 便于对日志）。
  qnn_backend::ensure_dsp_path(library_dir_chars);
  const bool initialized =
      qnn_backend::initialize(context_path_chars, static_cast<int>(padding));
  env->ReleaseStringUTFChars(native_library_dir, library_dir_chars);
  env->ReleaseStringUTFChars(context_path, context_path_chars);
  return initialized ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeIsQnnInitialized(
    JNIEnv *, jobject) {
  return qnn_backend::is_initialized() ? JNI_TRUE : JNI_FALSE;
}
// ── Komiho: QNN 接线结束 ────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInit(JNIEnv *env,
                                                         jobject thiz,
                                                         jstring model_dir,
                                                         jint noise_level,
                                                         jint scale_level,
                                                         jint precision,
                                                         jboolean fp16_arithmetic) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;
  qnn_backend::shutdown(); // Komiho: 换 ncnn 引擎 ⇒ QNN 引擎作废


  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // Choose model files based on noise and scale
  std::string param_file;
  std::string bin_file;

  if (noise_level == -1) {
    param_file = model_path + "/scale2.0x_model.param";
    bin_file = model_path + "/scale2.0x_model.bin";
  } else if (scale_level == 1) {
    param_file =
        model_path + "/noise" + std::to_string(noise_level) + "_model.param";
    bin_file =
        model_path + "/noise" + std::to_string(noise_level) + "_model.bin";
  } else {
    param_file = model_path + "/noise" + std::to_string(noise_level) +
                 "_scale2.0x_model.param";
    bin_file = model_path + "/noise" + std::to_string(noise_level) +
               "_scale2.0x_model.bin";
  }

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  g_waifu2x->disable_grayscale_check = true;
  g_waifu2x->noise = noise_level;
  g_waifu2x->scale = scale_level;
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitWaifu2xUpconv7(
    JNIEnv *env, jobject thiz, jstring model_dir, jint noise_level,
    jint scale_level, jint precision, jboolean fp16_arithmetic) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;
  qnn_backend::shutdown(); // Komiho: 换 ncnn 引擎 ⇒ QNN 引擎作废


  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // UpConv7 naming: noise0_scale2.0x_model.param
  // noise: 0, 1, 2, 3
  // scale: 2 (only support 2x for now based on files seen)

  std::string param_file = model_path + "/noise" + std::to_string(noise_level) +
                           "_scale2.0x_model.param";
  std::string bin_file = model_path + "/noise" + std::to_string(noise_level) +
                         "_scale2.0x_model.bin";

  // Handle scale=2 only for now, or if scale=1 (denoise only)
  if (scale_level != 2) {
    // UpConv7 models I saw were all scale2.0x
    // If user selected 1x (denoise only), we might need to use a different
    // model or just fail/fallback For now assume 2x.
  }

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  g_waifu2x->disable_grayscale_check = true;
  g_waifu2x->noise = noise_level;
  g_waifu2x->scale = scale_level;
  g_waifu2x->prepadding = 7; // UpConv7 uses small padding
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcess(JNIEnv *env,
                                                            jobject thiz,
                                                            jobject bitmap,
                                                            jint id) {
  int ret = -1;
  jobject outBitmap = nullptr;

  // Inference Scope (GPU) - Holds Lock for entire duration of incremental
  // process
  {
    std::unique_lock<std::mutex> lock(g_lock);

    // Update ID only after acquiring lock (now we are truly the active process)
    g_current_id.store(id);
    // Komiho: 本次跑完前先清掉，避免并发下把上一次的纯耗时当成这次的（拿不到就保持 -1）
    g_last_inference_ms.store(-1);

    // Komiho: QNN-only 模式下 g_waifu2x 可能尚未加载（ncnn 引擎与 QNN 引擎独立），
    // 所以这里不再以 g_waifu2x 为准入判据 —— 只要 QNN 已初始化就继续往下走；
    // 输出倍率由 QNN 的 context 自报（scale()），两者都没有时才失败返回。
    const bool qnn_active = qnn_backend::is_initialized();
    if (!g_waifu2x && !qnn_active)
      return bitmap;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0)
      return bitmap;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
      return bitmap;

    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0)
      return bitmap;

    int w = info.width;
    int h = info.height;
    int stride = info.stride;

    // Keep a packed RGBA copy for the fused Vulkan upload. This also lets the
    // staged path reconstruct its planar input without keeping Bitmap locked.
    ncnn::Mat packed_input(w, h, (size_t)4u, 1);
    if (packed_input.empty()) {
      AndroidBitmap_unlockPixels(env, bitmap);
      return bitmap;
    }
    for (int y = 0; y < h; y++) {
      memcpy((unsigned char *)packed_input.data + (size_t)y * w * 4,
             (const unsigned char *)pixels + (size_t)y * stride,
             (size_t)w * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);

    // Komiho: 输出倍率 —— QNN 引擎自报 scale；否则用 ncnn 引擎的。两者都无 → 失败。
    int out_scale = qnn_active ? qnn_backend::scale() : 0;
    if (out_scale <= 0 && g_waifu2x) out_scale = g_waifu2x->scale;
    if (out_scale > 0) {
      int out_w = w * out_scale;
      int out_h = h * out_scale;

      // Create result bitmap
      jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
      jmethodID createBitmapMethod = env->GetStaticMethodID(
          bitmapClass, "createBitmap",
          "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

      jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
      jfieldID configField = env->GetStaticFieldID(
          configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
      jobject config = env->GetStaticObjectField(configClass, configField);

      outBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod,
                                              out_w, out_h, config);

      if (outBitmap) {
        void *outPixels;
        if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) == 0) {
          AndroidBitmapInfo outInfo{};
          AndroidBitmap_getInfo(env, outBitmap, &outInfo);

          if (g_waifu2x) {
            g_waifu2x->progress_ptr = &g_progress;
            g_waifu2x->should_abort_ptr = &g_abort_processing;
          }

          bool input_has_alpha =
              (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) !=
              ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
          if (input_has_alpha) {
            input_has_alpha = false;
            const unsigned char *packed_pixels =
                static_cast<const unsigned char *>(packed_input.data);
            for (int i = 0; i < w * h; i++) {
              if (packed_pixels[i * 4 + 3] != 255) {
                input_has_alpha = true;
                break;
              }
            }
          }

          // Komiho: QNN/HTP 优先 —— 引擎由 Kotlin 侧按模型 backend 初始化；
          // 这里 tile 循环在 qnn_backend 内部（自带 should_abort 协作中断）。
          // 失败/未初始化时 ret 保持非 0，自然落进下方 ncnn 路径（原生回退链）。
          if (qnn_backend::is_initialized()) {
            const auto qnn_start = std::chrono::steady_clock::now();
            ret = qnn_backend::process_rgba(
                static_cast<const uint8_t *>(packed_input.data), w, h, w * 4,
                static_cast<uint8_t *>(outPixels), outInfo.stride, &g_progress,
                &g_abort_processing);
            const auto qnn_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                    std::chrono::steady_clock::now() - qnn_start)
                                    .count();
            // Komiho: 成功才登记纯耗时（口径与 Vulkan 路径一致，角标据此剔除排队）
            g_last_inference_ms.store(ret == 0 ? (long long)qnn_ms : -1);
            LOGD("QNN HTP processing %s in %lld ms",
                 ret == 0 ? "completed" : "failed",
                 static_cast<long long>(qnn_ms));
          }

          if (ret != 0 && !g_abort_processing.load() && g_waifu2x &&
              g_waifu2x->has_gpu_pipeline()) {
            const auto fused_start = std::chrono::steady_clock::now();
            ret = g_waifu2x->process_gpu(packed_input, outPixels,
                                         outInfo.stride, input_has_alpha,
                                         &g_progress);
            const auto fused_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                      std::chrono::steady_clock::now() - fused_start)
                                      .count();
            // Komiho: 成功才登记纯耗时（失败/被抢占保持 -1，Kotlin 侧退回旧口径）
            g_last_inference_ms.store(ret == 0 ? (long long)fused_ms : -1);
            LOGD("Fused Vulkan processing %s in %lld ms",
                 ret == 0 ? "completed" : "failed",
                 static_cast<long long>(fused_ms));
          }

          if (ret != 0 && !g_abort_processing.load() && g_waifu2x) {
            LOGD("Fused GPU pipeline unavailable for this image; retrying staged path");
            const auto staged_start = std::chrono::steady_clock::now();
            ncnn::Mat in = ncnn::Mat::from_pixels(
                (const unsigned char *)packed_input.data,
                ncnn::Mat::PIXEL_RGBA, w, h);
            ret = g_waifu2x->process(in, outPixels, outInfo.stride,
                                     input_has_alpha, lock, &g_progress);
            const auto staged_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                                       std::chrono::steady_clock::now() - staged_start)
                                       .count();
            // Komiho: 同上，登记这条回退路径的纯耗时
            g_last_inference_ms.store(ret == 0 ? (long long)staged_ms : -1);
            LOGD("Staged processing %s in %lld ms",
                 ret == 0 ? "completed" : "failed",
                 static_cast<long long>(staged_ms));
          }

          if (g_waifu2x) {
            g_waifu2x->progress_ptr = nullptr;
            g_waifu2x->should_abort_ptr = nullptr;
          }

          AndroidBitmap_unlockPixels(env, outBitmap);
        }
      }
    }
  }

  if (ret != 0 || !outBitmap) {
    LOGE("Waifu2x process failed or aborted");
    return bitmap; // Return original on failure
  }

  return outBitmap;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeScaleBitmap(
    JNIEnv *env, jobject thiz, jobject bitmap, jint target_width,
    jint target_height) {
  if (!bitmap || target_width <= 0 || target_height <= 0) {
    return bitmap;
  }

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
    return bitmap;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    return bitmap;
  }

  void *pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
    return bitmap;
  }

  ncnn::Mat in =
      ncnn::Mat::from_pixels((const unsigned char *)pixels, ncnn::Mat::PIXEL_RGBA,
                             info.width, info.height, info.stride);
  AndroidBitmap_unlockPixels(env, bitmap);

  if (in.empty()) {
    return bitmap;
  }

  ncnn::Mat out;
  ncnn::resize_bicubic(in, out, target_width, target_height);
  if (out.empty()) {
    return bitmap;
  }

  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  jmethodID createBitmapMethod = env->GetStaticMethodID(
      bitmapClass, "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

  jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
  jfieldID configField = env->GetStaticFieldID(
      configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
  jobject config = env->GetStaticObjectField(configClass, configField);

  jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod,
                                                  target_width, target_height,
                                                  config);
  if (!outBitmap) {
    return bitmap;
  }

  void *outPixels = nullptr;
  if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) < 0) {
    return bitmap;
  }

  AndroidBitmapInfo outInfo;
  AndroidBitmap_getInfo(env, outBitmap, &outInfo);
  out.to_pixels((unsigned char *)outPixels, ncnn::Mat::PIXEL_RGBA,
                outInfo.stride);
  AndroidBitmap_unlockPixels(env, outBitmap);

  return outBitmap;
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeDestroy(JNIEnv *env,
                                                            jobject thiz) {
  g_abort_processing.store(true);
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_waifu2x) {
    delete g_waifu2x;
    g_waifu2x = nullptr;
  }
  qnn_backend::shutdown(); // Komiho: 彻底销毁时连 QNN 引擎一起释放
  g_progress.store(0);
  g_current_id.store(-1);
  g_abort_processing.store(false);
  // DO NOT call destroy_gpu_instance here. It should be global.
  // Repeatedly calling it on exit/init is slow and can cause hangs.
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeAbortProcessing(
    JNIEnv *env, jobject thiz) {
  g_abort_processing.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeClearAbortProcessing(
    JNIEnv *env, jobject thiz) {
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing.store(false);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitRealCugan(
    JNIEnv *env, jobject thiz, jstring model_dir, jint noise_level,
    jint scale_level, jint tile_sleep_ms, jint precision,
    jboolean fp16_arithmetic) {
  g_abort_processing = true; // Signal abort to any running process
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false; // Reset
  qnn_backend::shutdown(); // Komiho: 换 ncnn 引擎 ⇒ QNN 引擎作废


  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // Choose model files based on noise and scale
  // Noise mapping: 0: no-denoise, 1: denoise1x, 2: denoise2x, 3: denoise3x, 4:
  // conservative
  std::string noise_str;
  switch (noise_level) {
  case 0:
    noise_str = "no-denoise";
    break;
  case 1:
    noise_str = "denoise1x";
    break;
  case 2:
    noise_str = "denoise2x";
    break;
  case 3:
    noise_str = "denoise3x";
    break;
  case 4:
    noise_str = "conservative";
    break;
  default:
    noise_str = "no-denoise";
    break;
  }

  // Fallback for 3x/4x which only have no-denoise, denoise3x, conservative
  if (scale_level > 2 && noise_level > 0 && noise_level < 3) {
    noise_str = "denoise3x";
  }

  std::string param_file = model_path + "/up" + std::to_string(scale_level) +
                           "x-" + noise_str + ".param";
  std::string bin_file = model_path + "/up" + std::to_string(scale_level) +
                         "x-" + noise_str + ".bin";

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  g_waifu2x->noise = noise_level;
  g_waifu2x->scale = scale_level;
  g_waifu2x->tile_sleep_ms = tile_sleep_ms; // Set configurable sleep
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  // Real-CUGAN SE prepadding: 2x=18, 3x=14, 4x=19?
  // Actually from official impl: 2x=18, 3x=14, 4x=19
  if (scale_level == 2)
    g_waifu2x->prepadding = 18;
  else if (scale_level == 3)
    g_waifu2x->prepadding = 14;
  else if (scale_level == 4)
    g_waifu2x->prepadding = 19;

  int ret = g_waifu2x->load(param_file, bin_file);

  if (ret != 0) {
    LOGE("Real-CUGAN load failed. ret=%d", ret);
    LOGE("Param path: %s", param_file.c_str());
    LOGE("Bin path: %s", bin_file.c_str());
  } else {
    LOGD("Real-CUGAN loaded successfully. Scale=%d, Noise=%d, TileSleep=%dms",
         scale_level, noise_level, tile_sleep_ms);
  }

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcessRealCugan(
    JNIEnv *env, jobject thiz, jobject bitmap, jint id) {
  // Real-CUGAN uses same processing logic as Waifu2x in this simplified impl
  return Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeProcess(
      env, thiz, bitmap, id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitRealESRGAN(
    JNIEnv *env, jobject thiz, jstring model_dir, jint model_scale,
    jint output_scale, jint precision, jboolean fp16_arithmetic) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;
  qnn_backend::shutdown(); // Komiho: 换 ncnn 引擎 ⇒ QNN 引擎作废


  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // General x4v3 keeps its x4 weights while its graph can resize to 2x.
  std::string param_file =
      model_path + "/x" + std::to_string(model_scale) + ".param";
  std::string bin_file =
      model_path + "/x" + std::to_string(model_scale) + ".bin";

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  g_waifu2x->noise = 0;
  g_waifu2x->scale = output_scale;
  g_waifu2x->prepadding = 10; // Real-ESRGAN usually uses smaller padding, 10 is
                              // common in ncnn impls
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  if (ret != 0) {
    LOGE("Real-ESRGAN init failed: %s", param_file.c_str());
  } else {
    LOGD("Real-ESRGAN loaded: model=x%d output=x%d", model_scale,
         output_scale);
  }

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitW2xEx(
    JNIEnv *env, jobject thiz, jstring model_dir, jstring model_stem,
    jint scale, jint precision, jboolean fp16_arithmetic, jint padding) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;
  qnn_backend::shutdown(); // Komiho: 换 ncnn 引擎 ⇒ QNN 引擎作废


  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  const char *model_stem_str = env->GetStringUTFChars(model_stem, 0);
  std::string model_path = std::string(model_dir_str);
  std::string stem = std::string(model_stem_str);

  std::string param_file = model_path + "/" + stem + ".param";
  std::string bin_file = model_path + "/" + stem + ".bin";

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE);
  g_waifu2x->noise = 0;
  g_waifu2x->scale = scale;
  g_waifu2x->prepadding = std::clamp(static_cast<int>(padding), 0, 48);
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  if (ret != 0) {
    LOGE("Generic ncnn model init failed: %s", param_file.c_str());
  } else {
    LOGD("Generic ncnn model loaded: %s x%d", stem.c_str(), scale);
  }

  env->ReleaseStringUTFChars(model_stem, model_stem_str);
  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeInitNose(
    JNIEnv *env, jobject thiz, jstring model_dir, jint precision,
    jboolean fp16_arithmetic) {
  g_abort_processing = true;
  std::lock_guard<std::mutex> lock(g_lock);
  g_abort_processing = false;
  qnn_backend::shutdown(); // Komiho: 换 ncnn 引擎 ⇒ QNN 引擎作废

  ncnn::create_gpu_instance();

  if (g_waifu2x) {
    delete g_waifu2x;
  }

  const char *model_dir_str = env->GetStringUTFChars(model_dir, 0);
  std::string model_path = std::string(model_dir_str);

  // Nose (Real-CUGAN branch?) uses up2x-no-denoise
  std::string param_file = model_path + "/up2x-no-denoise.param";
  std::string bin_file = model_path + "/up2x-no-denoise.bin";

  g_waifu2x = new Waifu2x(0, false, 0, precision,
                          fp16_arithmetic == JNI_TRUE); // GPU 0
  g_waifu2x->noise = 0;
  g_waifu2x->scale = 2;       // Fixed 2x
  g_waifu2x->prepadding = 18; // Assumed 18 for CUGAN 2x
  g_waifu2x->progress_ptr = &g_progress;
  g_waifu2x->ui_busy_ptr = &g_ui_busy;
  g_waifu2x->should_abort_ptr = &g_abort_processing;
  g_progress.store(0);

  int ret = g_waifu2x->load(param_file, bin_file);

  env->ReleaseStringUTFChars(model_dir, model_dir_str);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeGetProgress(JNIEnv *env,
                                                                jobject thiz) {
  // Return packed long: [ID (32)] [Progress (32)]
  jlong id = (jlong)g_current_id.load();
  jlong progress = (jlong)g_progress.load();
  return (id << 32) | (progress & 0xFFFFFFFF);
}

// Komiho: 最近一次推理的纯耗时（ms，不含等锁）；-1 = 未知（失败 / 被 abort / 还没跑过）。
// 调用时机须紧跟 nativeProcess 之后：写值发生在每次运行**结束时**，而下次运行的写入要等
// 至少一次推理（1–3 秒）之后，所以这里的读取不会串到别人的值。
extern "C" JNIEXPORT jlong JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeGetLastInferenceMs(
    JNIEnv *env, jobject thiz) {
  return (jlong)g_last_inference_ms.load();
}
extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeSetUiBusy(JNIEnv *env,
                                                              jobject thiz,
                                                              jboolean busy) {
  g_ui_busy.store(busy ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_Waifu2x_nativeUpdatePerformanceConfig(
    JNIEnv *env, jobject thiz, jint sleep_ms, jint tile_size) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_waifu2x) {
    g_waifu2x->tile_sleep_ms = sleep_ms;
    g_waifu2x->tilesize = tile_size;
    LOGD("Updated performance config: sleep=%dms, tilesize=%d", sleep_ms,
         tile_size);
  }
}
