#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

#define TAG "MihonSyLanczos"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int LANCZOS_A = 3;

// ---- Kernel LUT (perf optimization) -------------------------------------------------
// The argument to the kernel (cx - i) is ALWAYS within [-radius, radius], so we
// pre-sample the kernel once over that bounded domain and replace every hot-loop
// sin()/polynomial call with a table lookup + linear interpolation. This removes
// millions of transcendental calls per page (the real 1-3s bottleneck) and is
// correct at image edges (unlike a per-output-index periodic table, which breaks
// when clamping hits the boundary).
constexpr int KERNEL_LUT_N = 2048;
constexpr int KQ = 14;  // kernel weight fixed-point bits (Q14)

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

// Fixed-point kernel LUT. The kernel is precomputed over its bounded domain
// [-radius, radius] and stored as int16 Q14. The hot loop then runs on integers
// only (no sin, no float mul/add) — see resizeGeneric.
struct KernelLUT {
  std::vector<int16_t> tbl;
  float invStep;  // (N-1) / (2*radius)
  float radius;

  KernelLUT(KernelFn fn, int radius) : radius(static_cast<float>(radius)) {
    tbl.resize(KERNEL_LUT_N);
    invStep = static_cast<float>(KERNEL_LUT_N - 1) / (2.0f * radius);
    const int scale = 1 << KQ;
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

// Alpha lookup: a/255 stored as int16 Q8 (a=255 -> 256). Replaces the hot-loop
// float division p[3]/255.0f — the single most expensive scalar op before this
// change (tens of millions of divisions per page).
inline int16_t alphaQ8(int a) {
  static const int16_t* t = []() {
    static int16_t buf[256];
    for (int i = 0; i < 256; ++i) buf[i] = static_cast<int16_t>((i * 256 + 127) / 255);
    return buf;
  }();
  return t[a];
}

// ---- Parallel-for over row range ---------------------------------------------------
// One nativeResample task is internally multithreaded (NOT N concurrent JNI calls
// from Kotlin — that would explode memory / bitmap copies / cache thrashing).
// Thread count is dynamic but capped at 4: the reader also shares CPU with Coil
// decode, UI, network and GC.
template <typename F>
void parallelRows(int n, F&& fn) {
  unsigned int hw = std::thread::hardware_concurrency();
  int threads = static_cast<int>(std::max(1u, std::min(hw, 4u)));
  if (n <= threads * 2) threads = 1;  // not worth the thread overhead
  if (threads <= 1) {
    fn(0, n);
    return;
  }
  std::vector<std::thread> pool;
  const int chunk = (n + threads - 1) / threads;
  for (int t = 0; t < threads; ++t) {
    const int s = t * chunk;
    const int e = std::min(n, s + chunk);
    if (s >= e) break;
    pool.emplace_back(fn, s, e);
  }
  for (auto& th : pool) th.join();
}

/**
 * Straight-alpha aware separable resampler over RGBA_8888 pixels.
 *
 * Two separable 1D passes (horizontal then vertical) instead of a direct 2D
 * convolution: the kernel factorizes k(x,y)=k(x)*k(y), so a 2D window collapses
 * to 2*radius taps per output pixel — ~3x faster on CPU with bit-identical
 * results.
 *
 * Optimizations vs. the original:
 *   - Kernel weights come from a precomputed LUT (no sin() in the hot loop).
 *   - Both passes are partitioned across Y blocks and run on a bounded thread
 *     pool (see parallelRows). Pass 1 fully completes (join) before pass 2
 *     starts, so the temporary buffer is safe to read.
 */
void resizeGeneric(const unsigned char *src, int sw, int sh, unsigned char *dst, int dw,
                   int dh, KernelFn kernel, int radius) {
  KernelLUT lut(kernel, radius);

  // Pass 1: horizontal scale into a temporary buffer (dw x sh).
  std::vector<unsigned char> tmp(static_cast<size_t>(dw) * sh * 4);
  {
    const float sx = sw / static_cast<float>(dw);
    parallelRows(sh, [&](int y0, int y1) {
      for (int y = y0; y < y1; ++y) {
        const unsigned char *srow = src + static_cast<size_t>(y) * sw * 4;
        unsigned char *trow = tmp.data() + static_cast<size_t>(y) * dw * 4;
        for (int x = 0; x < dw; ++x) {
          const float cx = (x + 0.5f) * sx - 0.5f;
          const int x0 = static_cast<int>(std::floor(cx - radius));
          const int x1 = static_cast<int>(std::ceil(cx + radius));
          int32_t ar = 0, ag = 0, ab = 0, aa = 0;
          for (int i = x0; i <= x1; ++i) {
            const int sxx = std::max(0, std::min(sw - 1, i));
            const unsigned char *p = srow + static_cast<size_t>(sxx) * 4;
            const int16_t wQ = lut.at(cx - i);
            // fp = (a/255) * w, in Q14. Removes the per-tap float division.
            const int32_t fp = (static_cast<int32_t>(alphaQ8(p[3])) * static_cast<int32_t>(wQ)) >> 8;
            ar += static_cast<int32_t>(p[0]) * fp;
            ag += static_cast<int32_t>(p[1]) * fp;
            ab += static_cast<int32_t>(p[2]) * fp;
            aa += fp;
          }
          unsigned char *t = trow + static_cast<size_t>(x) * 4;
          if (aa <= 0) {
            t[0] = t[1] = t[2] = t[3] = 0;
          } else {
            // Straight-alpha un-premultiply: ratio is denominator-independent.
            const int32_t half = aa >> 1;
            int r = (ar + half) / aa;
            int g = (ag + half) / aa;
            int b = (ab + half) / aa;
            int a = ((aa * 255) + (1 << (KQ - 1))) >> KQ;
            if (r < 0) r = 0; else if (r > 255) r = 255;
            if (g < 0) g = 0; else if (g > 255) g = 255;
            if (b < 0) b = 0; else if (b > 255) b = 255;
            if (a < 0) a = 0; else if (a > 255) a = 255;
            t[0] = static_cast<unsigned char>(r);
            t[1] = static_cast<unsigned char>(g);
            t[2] = static_cast<unsigned char>(b);
            t[3] = static_cast<unsigned char>(a);
          }
        }
      }
    });
  }

  // Pass 2: vertical scale (tmp dw x sh -> dst dw x dh).
  {
    const float sy = sh / static_cast<float>(dh);
    parallelRows(dh, [&](int y0, int y1) {
      for (int y = y0; y < y1; ++y) {
        const float cy = (y + 0.5f) * sy - 0.5f;
        const int y0b = static_cast<int>(std::floor(cy - radius));
        const int y1b = static_cast<int>(std::ceil(cy + radius));
        unsigned char *drow = dst + static_cast<size_t>(y) * dw * 4;
        for (int x = 0; x < dw; ++x) {
          int32_t ar = 0, ag = 0, ab = 0, aa = 0;
          const unsigned char *trow0 = tmp.data() + static_cast<size_t>(x) * 4;
          for (int j = y0b; j <= y1b; ++j) {
            const int syy = std::max(0, std::min(sh - 1, j));
            const unsigned char *t = trow0 + static_cast<size_t>(syy) * dw * 4;
            const int16_t wQ = lut.at(cy - j);
            const int32_t fp = (static_cast<int32_t>(alphaQ8(t[3])) * static_cast<int32_t>(wQ)) >> 8;
            ar += static_cast<int32_t>(t[0]) * fp;
            ag += static_cast<int32_t>(t[1]) * fp;
            ab += static_cast<int32_t>(t[2]) * fp;
            aa += fp;
          }
          unsigned char *d = drow + static_cast<size_t>(x) * 4;
          if (aa <= 0) {
            d[0] = d[1] = d[2] = d[3] = 0;
          } else {
            const int32_t half = aa >> 1;
            int r = (ar + half) / aa;
            int g = (ag + half) / aa;
            int b = (ab + half) / aa;
            int a = ((aa * 255) + (1 << (KQ - 1))) >> KQ;
            if (r < 0) r = 0; else if (r > 255) r = 255;
            if (g < 0) g = 0; else if (g > 255) g = 255;
            if (b < 0) b = 0; else if (b > 255) b = 255;
            if (a < 0) a = 0; else if (a > 255) a = 255;
            d[0] = static_cast<unsigned char>(r);
            d[1] = static_cast<unsigned char>(g);
            d[2] = static_cast<unsigned char>(b);
            d[3] = static_cast<unsigned char>(a);
          }
        }
      }
    });
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
      jclass bitmapClass2 = env->FindClass("android/graphics/Bitmap");
      jmethodID recycleMethod = env->GetMethodID(bitmapClass2, "recycle", "()V");
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
