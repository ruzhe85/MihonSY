#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <array>
#include <thread>
#include <vector>

#define TAG "MihonSyLanczos"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int LANCZOS_A = 3;
constexpr int KERNEL_LUT_N = 2048;
constexpr int KQ = 14;
constexpr int KQ_ONE = 1 << KQ;

inline float lanczosKernel(float x) {
  if (x == 0.0f) return 1.0f;
  if (x <= -LANCZOS_A || x >= LANCZOS_A) return 0.0f;
  const float pix = static_cast<float>(M_PI * x);
  return (LANCZOS_A * std::sin(pix) * std::sin(pix / LANCZOS_A)) / (pix * pix);
}

inline float catmullRomKernel(float x) {
  x = std::fabs(x);
  if (x >= 2.0f) return 0.0f;
  if (x < 1.0f) return 1.5f * x * x * x - 2.5f * x * x + 1.0f;
  return -0.5f * x * x * x + 2.5f * x * x - 4.0f * x + 2.0f;
}

// MihonSY: Spline36 disabled — kept for reference but no longer compiled into a
// code path. Comments out the kernel and its resizeWithKernel case below.
// inline float spline36Kernel(float x) {
//   x = std::fabs(x);
//   if (x >= 3.0f) return 0.0f;
//   if (x < 1.0f) return ((13.0f / 11.0f) * x - 453.0f / 209.0f) * x * x -
//                        (3.0f / 209.0f) * x + 1.0f;
//   if (x < 2.0f) {
//     const float u = x - 1.0f;
//     return ((-6.0f / 11.0f) * u - 270.0f / 209.0f) * u * u -
//            (156.0f / 209.0f) * u;
//   }
//   const float v = x - 2.0f;
//   return ((1.0f / 11.0f) * v - 45.0f / 209.0f) * v * v +
//          (26.0f / 209.0f) * v;
// }

using KernelFn = float (*)(float);

struct KernelLUT {
  std::vector<int16_t> tbl;
  float invStep;
  float radius;

  KernelLUT(KernelFn fn, int radius) : radius(static_cast<float>(radius)) {
    tbl.resize(KERNEL_LUT_N);
    invStep = static_cast<float>(KERNEL_LUT_N - 1) / (2.0f * radius);
    const int scale = KQ_ONE;
    for (int i = 0; i < KERNEL_LUT_N; ++i) {
      const float t = (static_cast<float>(i) / (KERNEL_LUT_N - 1)) * 2.0f * radius - radius;
      int v = static_cast<int>(fn(t) * scale);
      if (v > 32767) v = 32767;
      if (v < -32768) v = -32768;
      tbl[i] = static_cast<int16_t>(v);
    }
  }

  inline int16_t at(float x) const {
    if (x <= -radius) return tbl[0];
    if (x >= radius) return tbl[KERNEL_LUT_N - 1];
    const float f = (x + radius) * invStep;
    const int i0 = static_cast<int>(f);
    if (i0 >= KERNEL_LUT_N - 1) return tbl[KERNEL_LUT_N - 1];
    const float frac = f - static_cast<float>(i0);
    const int a = tbl[i0];
    const int b = tbl[i0 + 1];
    return static_cast<int16_t>(a + static_cast<int>((b - a) * frac));
  }
};

inline int16_t alphaQ8(int a) {
  static const std::array<int16_t, 256> table = [] {
    std::array<int16_t, 256> t{};
    for (int i = 0; i < 256; ++i) {
      t[i] = static_cast<int16_t>((i * 256 + 127) / 255);
    }
    return t;
  }();
  return table[static_cast<size_t>(a)];
}

// Precomputed sampling plan. The old implementation still calculated floor/ceil,
// clamping and LUT interpolation for every output pixel/tap. This moves all of that
// work out of the hot loop.
struct ResamplePlan {
  int dstSize = 0;
  int taps = 0;
  std::vector<int32_t> indices;
  std::vector<int16_t> weights;
  std::vector<int32_t> weightSums;

  ResamplePlan() = default;

  ResamplePlan(int srcSize, int dstSize_, int radius, const KernelLUT &lut)
      : dstSize(dstSize_), taps(2 * radius + 2),
        indices(static_cast<size_t>(dstSize_) * taps),
        weights(static_cast<size_t>(dstSize_) * taps),
        weightSums(dstSize_, 0) {
    const float scale = srcSize / static_cast<float>(dstSize_);

    for (int d = 0; d < dstSize_; ++d) {
      const float center = (d + 0.5f) * scale - 0.5f;
      const int first = static_cast<int>(std::floor(center - radius));
      const int last = static_cast<int>(std::ceil(center + radius));
      const int base = d * taps;

      int sum = 0;
      int k = 0;
      for (int i = first; i <= last && k < taps; ++i, ++k) {
        const int clamped = std::max(0, std::min(srcSize - 1, i));
        const int16_t w = lut.at(center - i);
        indices[base + k] = clamped;
        weights[base + k] = w;
        sum += w;
      }
      // Remaining slots are harmless zero taps. Keeping a fixed tap count makes
      // the hot loops branch-free and works for both 2/3-radius kernels.
      for (; k < taps; ++k) {
        indices[base + k] = indices[base + (k ? k - 1 : 0)];
        weights[base + k] = 0;
      }
      weightSums[d] = sum;
    }
  }
};

template <typename F>
void parallelRows(int n, F&& fn) {
  unsigned int hw = std::thread::hardware_concurrency();
  int threads = static_cast<int>(std::max(1u, std::min(hw, 4u)));
  if (n <= threads * 2) threads = 1;
  if (threads <= 1) {
    fn(0, n);
    return;
  }

  std::vector<std::thread> pool;
  pool.reserve(static_cast<size_t>(threads));
  const int chunk = (n + threads - 1) / threads;
  for (int t = 0; t < threads; ++t) {
    const int s = t * chunk;
    const int e = std::min(n, s + chunk);
    if (s >= e) break;
    pool.emplace_back(fn, s, e);
  }
  for (auto &th : pool) th.join();
}

inline unsigned char clampByte(int v) {
  if (v < 0) return 0;
  if (v > 255) return 255;
  return static_cast<unsigned char>(v);
}

// Fast path for the overwhelmingly common manga case: fully opaque RGBA_8888.
// This removes alphaQ8(), premultiplication and unpremultiplication from every tap.
// The final division by the precomputed kernel sum preserves the same normalization
// principle as the straight-alpha path.
void resizeOpaque(const unsigned char *src, int sw, int sh, unsigned char *dst, int dw,
                  int dh, const ResamplePlan &xPlan, const ResamplePlan &yPlan) {
  std::vector<unsigned char> tmp(static_cast<size_t>(dw) * sh * 4);

  parallelRows(sh, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const unsigned char *srow = src + static_cast<size_t>(y) * sw * 4;
      unsigned char *trow = tmp.data() + static_cast<size_t>(y) * dw * 4;

      for (int x = 0; x < dw; ++x) {
        const int base = x * xPlan.taps;
        int32_t r = 0, g = 0, b = 0;
        for (int k = 0; k < xPlan.taps; ++k) {
          const int16_t w = xPlan.weights[base + k];
          if (w == 0) continue;
          const unsigned char *p = srow + static_cast<size_t>(xPlan.indices[base + k]) * 4;
          r += static_cast<int32_t>(p[0]) * w;
          g += static_cast<int32_t>(p[1]) * w;
          b += static_cast<int32_t>(p[2]) * w;
        }
        const int32_t denom = xPlan.weightSums[x];
        unsigned char *t = trow + static_cast<size_t>(x) * 4;
        if (denom == 0) {
          t[0] = t[1] = t[2] = t[3] = 0;
        } else {
          const int32_t half = denom / 2;
          t[0] = clampByte((r + half) / denom);
          t[1] = clampByte((g + half) / denom);
          t[2] = clampByte((b + half) / denom);
          t[3] = 255;
        }
      }
    }
  });

  parallelRows(dh, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const int baseY = y * yPlan.taps;
      unsigned char *drow = dst + static_cast<size_t>(y) * dw * 4;

      for (int x = 0; x < dw; ++x) {
        int32_t r = 0, g = 0, b = 0;
        const size_t xOffset = static_cast<size_t>(x) * 4;
        for (int k = 0; k < yPlan.taps; ++k) {
          const int16_t w = yPlan.weights[baseY + k];
          if (w == 0) continue;
          const int sy = yPlan.indices[baseY + k];
          const unsigned char *p = tmp.data() +
              (static_cast<size_t>(sy) * dw * 4) + xOffset;
          r += static_cast<int32_t>(p[0]) * w;
          g += static_cast<int32_t>(p[1]) * w;
          b += static_cast<int32_t>(p[2]) * w;
        }
        const int32_t denom = yPlan.weightSums[y];
        unsigned char *d = drow + xOffset;
        if (denom == 0) {
          d[0] = d[1] = d[2] = d[3] = 0;
        } else {
          const int32_t half = denom / 2;
          d[0] = clampByte((r + half) / denom);
          d[1] = clampByte((g + half) / denom);
          d[2] = clampByte((b + half) / denom);
          d[3] = 255;
        }
      }
    }
  });
}

void resizeAlpha(const unsigned char *src, int sw, int sh, unsigned char *dst, int dw, int dh,
                 const ResamplePlan &xPlan, const ResamplePlan &yPlan) {
  std::vector<unsigned char> tmp(static_cast<size_t>(dw) * sh * 4);

  parallelRows(sh, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const unsigned char *srow = src + static_cast<size_t>(y) * sw * 4;
      unsigned char *trow = tmp.data() + static_cast<size_t>(y) * dw * 4;

      for (int x = 0; x < dw; ++x) {
        const int base = x * xPlan.taps;
        int32_t ar = 0, ag = 0, ab = 0, aa = 0;
        for (int k = 0; k < xPlan.taps; ++k) {
          const int16_t wQ = xPlan.weights[base + k];
          if (wQ == 0) continue;
          const unsigned char *p = srow + static_cast<size_t>(xPlan.indices[base + k]) * 4;
          const int32_t fp = (static_cast<int32_t>(alphaQ8(p[3])) * wQ) >> 8;
          ar += static_cast<int32_t>(p[0]) * fp;
          ag += static_cast<int32_t>(p[1]) * fp;
          ab += static_cast<int32_t>(p[2]) * fp;
          aa += fp;
        }

        unsigned char *t = trow + static_cast<size_t>(x) * 4;
        if (aa <= 0) {
          t[0] = t[1] = t[2] = t[3] = 0;
        } else {
          const int32_t half = aa >> 1;
          t[0] = clampByte((ar + half) / aa);
          t[1] = clampByte((ag + half) / aa);
          t[2] = clampByte((ab + half) / aa);
          t[3] = clampByte((aa * 255 + (1 << (KQ - 1))) >> KQ);
        }
      }
    }
  });

  parallelRows(dh, [&](int y0, int y1) {
    for (int y = y0; y < y1; ++y) {
      const int baseY = y * yPlan.taps;
      unsigned char *drow = dst + static_cast<size_t>(y) * dw * 4;

      for (int x = 0; x < dw; ++x) {
        int32_t ar = 0, ag = 0, ab = 0, aa = 0;
        const size_t xOffset = static_cast<size_t>(x) * 4;
        for (int k = 0; k < yPlan.taps; ++k) {
          const int16_t wQ = yPlan.weights[baseY + k];
          if (wQ == 0) continue;
          const int sy = yPlan.indices[baseY + k];
          const unsigned char *p = tmp.data() +
              (static_cast<size_t>(sy) * dw * 4) + xOffset;
          const int32_t fp = (static_cast<int32_t>(alphaQ8(p[3])) * wQ) >> 8;
          ar += static_cast<int32_t>(p[0]) * fp;
          ag += static_cast<int32_t>(p[1]) * fp;
          ab += static_cast<int32_t>(p[2]) * fp;
          aa += fp;
        }

        unsigned char *d = drow + xOffset;
        if (aa <= 0) {
          d[0] = d[1] = d[2] = d[3] = 0;
        } else {
          const int32_t half = aa >> 1;
          d[0] = clampByte((ar + half) / aa);
          d[1] = clampByte((ag + half) / aa);
          d[2] = clampByte((ab + half) / aa);
          d[3] = clampByte((aa * 255 + (1 << (KQ - 1))) >> KQ);
        }
      }
    }
  });
}

void resizeGeneric(const unsigned char *src, int sw, int sh, unsigned char *dst, int dw,
                   int dh, KernelFn kernel, int radius, bool opaque) {
  // Kernel generation remains outside the pixel loops. The important additional
  // optimization here is that all per-destination coordinate math, clamping and
  // LUT interpolation are also moved into these two plans.
  const KernelLUT lut(kernel, radius);
  const ResamplePlan xPlan(sw, dw, radius, lut);
  const ResamplePlan yPlan(sh, dh, radius, lut);

  if (opaque) {
    resizeOpaque(src, sw, sh, dst, dw, dh, xPlan, yPlan);
  } else {
    resizeAlpha(src, sw, sh, dst, dw, dh, xPlan, yPlan);
  }
}

bool isFullyOpaque(const unsigned char *src, int width, int height) {
  const size_t pixels = static_cast<size_t>(width) * height;
  // Full scan is intentional: the opaque fast path must never be selected when
  // any alpha differs from 255. For a 720x2000 page this is only ~1.4M bytes of
  // alpha reads and is much cheaper than running the alpha path for every tap.
  for (size_t i = 0; i < pixels; ++i) {
    if (src[i * 4 + 3] != 255) return false;
  }
  return true;
}

void resizeWithKernel(const unsigned char *src, int sw, int sh, unsigned char *dst, int dw,
                      int dh, int kernel) {
  switch (kernel) {
    case 1: {
      const bool opaque = isFullyOpaque(src, sw, sh);
      resizeGeneric(src, sw, sh, dst, dw, dh, catmullRomKernel, 2, opaque);
      break;
    }
    // MihonSY: Spline36 (kernel id 2) disabled — spline36Kernel is commented out above.
    // case 2: {
    //   const bool opaque = isFullyOpaque(src, sw, sh);
    //   resizeGeneric(src, sw, sh, dst, dw, dh, spline36Kernel, 3, opaque);
    //   break;
    // }
    default: {
      const bool opaque = isFullyOpaque(src, sw, sh);
      resizeGeneric(src, sw, sh, dst, dw, dh, lanczosKernel, LANCZOS_A, opaque);
      break;
    }
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

  const int sw = static_cast<int>(info.width);
  const int sh = static_cast<int>(info.height);
  const int dw = static_cast<int>(info.width * scale);
  const int dh = static_cast<int>(info.height * scale);
  if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0 || dw > 16384 || dh > 65536) {
    LOGE("Output size %dx%d out of bounds", dw, dh);
    return bitmap;
  }

  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  if (!bitmapClass) return bitmap;
  jmethodID createBitmapMethod = env->GetStaticMethodID(
      bitmapClass, "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
  if (!createBitmapMethod || !configClass) return bitmap;
  jfieldID configField =
      env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
  if (!configField) return bitmap;
  jobject config = env->GetStaticObjectField(configClass, configField);
  jobject outBitmap =
      env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, dw, dh, config);
  if (env->ExceptionCheck() || !outBitmap) {
    env->ExceptionClear();
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

  // Android Bitmap stride is allowed to be larger than width*4. The old code
  // assumed tightly packed rows. Handle stride here without changing the JNI API.
  AndroidBitmapInfo outInfo;
  if (AndroidBitmap_getInfo(env, outBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
      outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    AndroidBitmap_unlockPixels(env, bitmap);
    AndroidBitmap_unlockPixels(env, outBitmap);
    LOGE("Failed to get output bitmap info");
    return bitmap;
  }

  const size_t srcStride = info.stride;
  const size_t dstStride = outInfo.stride;
  const unsigned char *srcPixels = static_cast<const unsigned char *>(pixels);
  unsigned char *dstPixels = static_cast<unsigned char *>(outPixels);

  // The resampler expects tightly packed RGBA rows. Avoid an input copy in the
  // common case, but make a compact source only when Android supplied padding.
  std::vector<unsigned char> packedSrc;
  if (srcStride != static_cast<size_t>(sw) * 4) {
    packedSrc.resize(static_cast<size_t>(sw) * sh * 4);
    for (int y = 0; y < sh; ++y) {
      std::copy_n(srcPixels + static_cast<size_t>(y) * srcStride,
                  static_cast<size_t>(sw) * 4,
                  packedSrc.data() + static_cast<size_t>(y) * sw * 4);
    }
    srcPixels = packedSrc.data();
  }

  // createBitmap() normally gives a tightly packed ARGB_8888 bitmap on Android,
  // but keep the stride-safe path for completeness. If the destination has padding,
  // render into a compact buffer and copy rows back after the resample.
  std::vector<unsigned char> packedDst;
  unsigned char *resampleDst = dstPixels;
  if (dstStride != static_cast<size_t>(dw) * 4) {
    packedDst.resize(static_cast<size_t>(dw) * dh * 4);
    resampleDst = packedDst.data();
  }

  resizeWithKernel(srcPixels, sw, sh, resampleDst, dw, dh, kernel);

  // Defensive blank-output check — run against the REAL render target
  // (resampleDst). When dstStride != dw*4 Android gave a padded output bitmap,
  // resampleDst points at a compact scratch buffer; the check must not read the
  // (still unwritten) dstPixels. We copy rows back to dstPixels only after this.
  {
    const unsigned char *checkPixels = resampleDst;
    const size_t rowBytes = static_cast<size_t>(dw) * 4;
    size_t step = (static_cast<size_t>(dh) * rowBytes) / 256;
    if (step < 4) step = 4;
    size_t nonZeroRgb = 0;
    size_t nonZeroAlpha = 0;
    size_t pos = 0;
    const size_t total = static_cast<size_t>(dh) * rowBytes;
    while (pos + 3 < total && (nonZeroRgb < 8 || nonZeroAlpha < 8)) {
      const auto *p = checkPixels + pos;
      if (p[0] != 0 || p[1] != 0 || p[2] != 0) ++nonZeroRgb;
      if (p[3] != 0) ++nonZeroAlpha;
      pos += step;
    }

    if (nonZeroRgb == 0 || nonZeroAlpha == 0) {
      LOGE("Resample output is blank (rgb=%zu alpha=%zu), returning original bitmap",
           nonZeroRgb, nonZeroAlpha);
      AndroidBitmap_unlockPixels(env, bitmap);
      AndroidBitmap_unlockPixels(env, outBitmap);

      jmethodID recycleMethod = env->GetMethodID(bitmapClass, "recycle", "()V");
      if (recycleMethod) env->CallVoidMethod(outBitmap, recycleMethod);
      return bitmap;
    }
  }

  // Only after the blank check: blit the compact result into the (possibly
  // padded) output bitmap if Android handed us a non-tightly-packed destination.
  if (!packedDst.empty()) {
    for (int y = 0; y < dh; ++y) {
      std::copy_n(packedDst.data() + static_cast<size_t>(y) * dw * 4,
                  static_cast<size_t>(dw) * 4,
                  dstPixels + static_cast<size_t>(y) * dstStride);
    }
  }

  AndroidBitmap_unlockPixels(env, bitmap);
  AndroidBitmap_unlockPixels(env, outBitmap);
  return outBitmap;
}
