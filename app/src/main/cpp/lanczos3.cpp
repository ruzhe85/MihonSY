#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>

#define TAG "MihonSyLanczos"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int LANCZOS_A = 3;

inline float lanczosKernel(float x) {
  if (x == 0.0f) return 1.0f;
  if (x <= -LANCZOS_A || x >= LANCZOS_A) return 0.0f;
  const float pix = static_cast<float>(M_PI * x);
  return (LANCZOS_A * std::sin(pix) * std::sin(pix / LANCZOS_A)) / (pix * pix);
}

/**
 * Straight-alpha aware Lanczos-3 resampler over RGBA_8888 pixels.
 */
void resizeLanczos3(const unsigned char *src, int sw, int sh, unsigned char *dst, int dw, int dh) {
  const float sx = sw / static_cast<float>(dw);
  const float sy = sh / static_cast<float>(dh);

  for (int y = 0; y < dh; ++y) {
    const float cy = (y + 0.5f) * sy - 0.5f;
    const int y0 = static_cast<int>(std::floor(cy - LANCZOS_A));
    const int y1 = static_cast<int>(std::ceil(cy + LANCZOS_A));
    unsigned char *drow = dst + static_cast<size_t>(y) * dw * 4;
    for (int x = 0; x < dw; ++x) {
      const float cx = (x + 0.5f) * sx - 0.5f;
      const int x0 = static_cast<int>(std::floor(cx - LANCZOS_A));
      const int x1 = static_cast<int>(std::ceil(cx + LANCZOS_A));

      float r = 0, g = 0, b = 0, alpha = 0;
      for (int j = y0; j <= y1; ++j) {
        const int syy = std::max(0, std::min(sh - 1, j));
        const float wy = lanczosKernel(cy - j);
        const unsigned char *srow = src + static_cast<size_t>(syy) * sw * 4;
        for (int i = x0; i <= x1; ++i) {
          const int sxx = std::max(0, std::min(sw - 1, i));
          const float w = wy * lanczosKernel(cx - i);
          const unsigned char *p = srow + static_cast<size_t>(sxx) * 4;
          const float pa = p[3] / 255.0f;
          r += p[0] * pa * w;
          g += p[1] * pa * w;
          b += p[2] * pa * w;
          alpha += pa * w;
        }
      }

      unsigned char *d = drow + static_cast<size_t>(x) * 4;
      if (alpha > 0.0001f) {
        d[0] = static_cast<unsigned char>(std::min(255.0f, r / alpha));
        d[1] = static_cast<unsigned char>(std::min(255.0f, g / alpha));
        d[2] = static_cast<unsigned char>(std::min(255.0f, b / alpha));
        d[3] = static_cast<unsigned char>(std::min(255.0f, alpha * 255.0f));
      } else {
        d[0] = d[1] = d[2] = d[3] = 0;
      }
    }
  }
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeLanczosProcess(
    JNIEnv *env, jobject thiz, jobject bitmap, jfloat scale) {
  if (scale <= 1.0f) return bitmap;

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    LOGE("AndroidBitmap_getInfo failed");
    return bitmap;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    LOGE("Unsupported bitmap format %d", info.format);
    return bitmap;
  }

  const int dw = static_cast<int>(info.width * scale);
  const int dh = static_cast<int>(info.height * scale);
  if (dw <= 0 || dh <= 0 || dw > 16384 || dh > 65536) {
    LOGE("Output size %dx%d out of bounds", dw, dh);
    return bitmap;
  }

  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  jmethodID createBitmapMethod = env->GetStaticMethodID(
      bitmapClass, "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
  jfieldID configField =
      env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
  jobject config = env->GetStaticObjectField(configClass, configField);
  jobject outBitmap =
      env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, dw, dh, config);
  if (!outBitmap) {
    LOGE("Failed to create output bitmap");
    return bitmap;
  }

  void *pixels = nullptr;
  void *outPixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    LOGE("Failed to lock input pixels");
    return bitmap;
  }
  if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    AndroidBitmap_unlockPixels(env, bitmap);
    LOGE("Failed to lock output pixels");
    return bitmap;
  }

  resizeLanczos3(static_cast<const unsigned char *>(pixels), info.width, info.height,
                 static_cast<unsigned char *>(outPixels), dw, dh);

  AndroidBitmap_unlockPixels(env, bitmap);
  AndroidBitmap_unlockPixels(env, outBitmap);
  return outBitmap;
}
