#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <vector>

#define TAG "MihonSyLanczos"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int LANCZOS_A = 3;

// Lanczos-3 kernel (a=3 windowed sinc).
inline float lanczosKernel(float x) {
  if (x == 0.0f) return 1.0f;
  if (x <= -LANCZOS_A || x >= LANCZOS_A) return 0.0f;
  const float pix = static_cast<float>(M_PI * x);
  return (LANCZOS_A * std::sin(pix) * std::sin(pix / LANCZOS_A)) / (pix * pix);
}

// Catmull-Rom cubic spline (Mitchell-Netravali with b=0, c=0.5), support |x| < 2.
inline float catmullRomKernel(float x) {
  x = std::fabs(x);
  if (x >= 2.0f) return 0.0f;
  if (x < 1.0f) return 1.5f * x * x * x - 2.5f * x * x + 1.0f;
  return -0.5f * x * x * x + 2.5f * x * x - 4.0f * x + 2.0f;
}

// Spline36 (AviSynth 6-tap spline), support |x| < 3. Coefficients from the
// reference implementation: ((13/11)x - 453/209)x - 3/209)x + 1 and the
// shifted (x-1), (x-2) pieces.
inline float spline36Kernel(float x) {
  x = std::fabs(x);
  if (x >= 3.0f) return 0.0f;
  if (x < 1.0f) return ((13.0f / 11.0f) * x - 453.0f / 209.0f) * x * x - (3.0f / 209.0f) * x + 1.0f;
  if (x < 2.0f) {
    const float u = x - 1.0f;
    return ((-6.0f / 11.0f) * u - 270.0f / 209.0f) * u * u - (156.0f / 209.0f) * u;
  }
  const float v = x - 2.0f;
  return ((1.0f / 11.0f) * v - 45.0f / 209.0f) * v * v + (26.0f / 209.0f) * v;
}

using KernelFn = float (*)(float);

/**
 * Straight-alpha aware separable resampler over RGBA_8888 pixels.
 *
 * Implemented as two separable 1D passes (horizontal then vertical) instead of
 * a direct 2D convolution: the kernel factorizes k(x,y)=k(x)*k(y), so a 2D
 * window collapses to 2*radius taps per output pixel — ~3x faster on CPU with
 * bit-identical results. [kernel] selects Lanczos3 / Catmull-Rom / Spline36.
 */
void resizeGeneric(const unsigned char *src, int sw, int sh, unsigned char *dst, int dw,
                   int dh, KernelFn kernel, int radius) {
  // Pass 1: horizontal scale into a temporary buffer (dw x sh).
  std::vector<unsigned char> tmp(static_cast<size_t>(dw) * sh * 4);
  {
    const float sx = sw / static_cast<float>(dw);
    for (int y = 0; y < sh; ++y) {
      const unsigned char *srow = src + static_cast<size_t>(y) * sw * 4;
      unsigned char *trow = tmp.data() + static_cast<size_t>(y) * dw * 4;
      for (int x = 0; x < dw; ++x) {
        const float cx = (x + 0.5f) * sx - 0.5f;
        const int x0 = static_cast<int>(std::floor(cx - radius));
        const int x1 = static_cast<int>(std::ceil(cx + radius));
        float r = 0, g = 0, b = 0, alpha = 0;
        for (int i = x0; i <= x1; ++i) {
          const int sxx = std::max(0, std::min(sw - 1, i));
          const float w = kernel(cx - i);
          const unsigned char *p = srow + static_cast<size_t>(sxx) * 4;
          const float pa = p[3] / 255.0f;
          r += p[0] * pa * w;
          g += p[1] * pa * w;
          b += p[2] * pa * w;
          alpha += pa * w;
        }
        unsigned char *t = trow + static_cast<size_t>(x) * 4;
        const float alphaSum = std::max(0.0f, alpha);
        if (alphaSum > 0.0001f) {
          const float inv = 1.0f / alphaSum;
          t[0] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, r * inv)));
          t[1] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, g * inv)));
          t[2] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, b * inv)));
          t[3] = static_cast<unsigned char>(std::min(255.0f, alphaSum * 255.0f));
        } else {
          t[0] = t[1] = t[2] = t[3] = 0;
        }
      }
    }
  }

  // Pass 2: vertical scale (tmp dw x sh -> dst dw x dh).
  {
    const float sy = sh / static_cast<float>(dh);
    for (int y = 0; y < dh; ++y) {
      const float cy = (y + 0.5f) * sy - 0.5f;
      const int y0 = static_cast<int>(std::floor(cy - radius));
      const int y1 = static_cast<int>(std::ceil(cy + radius));
      unsigned char *drow = dst + static_cast<size_t>(y) * dw * 4;
      for (int x = 0; x < dw; ++x) {
        float r = 0, g = 0, b = 0, alpha = 0;
        const unsigned char *trow0 = tmp.data() + static_cast<size_t>(x) * 4;
        for (int j = y0; j <= y1; ++j) {
          const int syy = std::max(0, std::min(sh - 1, j));
          const float w = kernel(cy - j);
          const unsigned char *t = trow0 + static_cast<size_t>(syy) * dw * 4;
          const float pa = t[3] / 255.0f;
          r += t[0] * pa * w;
          g += t[1] * pa * w;
          b += t[2] * pa * w;
          alpha += pa * w;
        }
        unsigned char *d = drow + static_cast<size_t>(x) * 4;
        const float alphaSum = std::max(0.0f, alpha);
        if (alphaSum > 0.0001f) {
          const float inv = 1.0f / alphaSum;
          d[0] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, r * inv)));
          d[1] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, g * inv)));
          d[2] = static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, b * inv)));
          d[3] = static_cast<unsigned char>(std::min(255.0f, alphaSum * 255.0f));
        } else {
          d[0] = d[1] = d[2] = d[3] = 0;
        }
      }
    }
  }
}

// Kernel selector: 0 = Lanczos3, 1 = Catmull-Rom, 2 = Spline36.
void resizeWithKernel(const unsigned char *src, int sw, int sh, unsigned char *dst, int dw,
                      int dh, int kernel) {
  switch (kernel) {
    case 1:
      resizeGeneric(src, sw, sh, dst, dw, dh, catmullRomKernel, 2);
      break;
    case 2:
      resizeGeneric(src, sw, sh, dst, dw, dh, spline36Kernel, 3);
      break;
    default:
      resizeGeneric(src, sw, sh, dst, dw, dh, lanczosKernel, LANCZOS_A);
      break;
  }
}

}  // namespace

static jobject nativeResampleImpl(JNIEnv *env, jobject bitmap, jfloat scale, jint kernel);

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeLanczosProcess(
    JNIEnv *env, jobject thiz, jobject bitmap, jfloat scale) {
  return nativeResampleImpl(env, bitmap, scale, 0);
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeResample(
    JNIEnv *env, jobject thiz, jobject bitmap, jfloat scale, jint kernel) {
  return nativeResampleImpl(env, bitmap, scale, kernel);
}

static jobject nativeResampleImpl(JNIEnv *env, jobject bitmap, jfloat scale, jint kernel) {
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

  resizeWithKernel(static_cast<const unsigned char *>(pixels), info.width, info.height,
                   static_cast<unsigned char *>(outPixels), dw, dh, kernel);

  // MihonSY defensive check (BEFORE unlock — the pointer is invalid afterwards):
  // if the resampler produced an all-transparent or all-black image, fall back to
  // the original instead of showing a black frame.
  {
    const auto *px = static_cast<const unsigned char *>(outPixels);
    const size_t total = static_cast<size_t>(dw) * dh * 4;
    size_t step = total / 256;
    if (step < 4) step = 4;
    size_t nonZeroRgb = 0;
    size_t nonZeroAlpha = 0;
    for (size_t k = 0; k < total && (nonZeroRgb < 8 || nonZeroAlpha < 8); k += step) {
      // px[k] R, px[k+1] G, px[k+2] B, px[k+3] A
      if (px[k] != 0 || px[k + 1] != 0 || px[k + 2] != 0) ++nonZeroRgb;
      if (px[k + 3] != 0) ++nonZeroAlpha;
    }
    if (nonZeroRgb == 0 || nonZeroAlpha == 0) {
      LOGE("Resample output is blank (rgb=%zu alpha=%zu), returning original bitmap",
           nonZeroRgb, nonZeroAlpha);
      jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
      jmethodID recycleMethod = env->GetMethodID(bitmapClass, "recycle", "()V");
      env->CallVoidMethod(outBitmap, recycleMethod);
      AndroidBitmap_unlockPixels(env, bitmap);
      AndroidBitmap_unlockPixels(env, outBitmap);
      return bitmap;
    }
  }

  AndroidBitmap_unlockPixels(env, bitmap);
  AndroidBitmap_unlockPixels(env, outBitmap);
  return outBitmap;
}
