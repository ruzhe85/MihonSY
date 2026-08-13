#include "anime4k.h"

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#define TAG "MihonSyEnhance"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static Anime4K *g_anime4k = nullptr;
static std::mutex g_lock;

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeInitAnime4K(
    JNIEnv *env, jobject thiz, jobjectArray shaders, jobjectArray names) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (g_anime4k) delete g_anime4k;
  g_anime4k = new Anime4K();

  std::vector<std::string> v_shaders;
  std::vector<std::string> v_names;
  jsize len = env->GetArrayLength(shaders);
  for (jsize i = 0; i < len; i++) {
    jstring s = (jstring)env->GetObjectArrayElement(shaders, i);
    jstring n = (jstring)env->GetObjectArrayElement(names, i);
    const char *cs = env->GetStringUTFChars(s, 0);
    const char *cn = env->GetStringUTFChars(n, 0);
    v_shaders.push_back(cs);
    v_names.push_back(cn);
    env->ReleaseStringUTFChars(s, cs);
    env->ReleaseStringUTFChars(n, cn);
  }

  int ret = g_anime4k->load(v_shaders, v_names);
  return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeGetMaxTextureSize(
    JNIEnv *env, jobject thiz) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (!g_anime4k) return 0;
  return g_anime4k->get_max_texture_size();
}

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeProcessAnime4K(
    JNIEnv *env, jobject thiz, jobject bitmap) {
  std::lock_guard<std::mutex> lock(g_lock);
  if (!g_anime4k) return bitmap;

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    LOGE("AndroidBitmap_getInfo failed");
    return bitmap;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    LOGE("Unsupported bitmap format %d", info.format);
    return bitmap;
  }

  int out_w, out_h;
  g_anime4k->get_output_size(info.width, info.height, out_w, out_h);
  if (out_w <= 0 || out_h <= 0) return bitmap;

  // Texture size guard: Anime4K renders through a full-image framebuffer, so it can only
  // handle images that fit into the GPU's max texture size.
  const int maxTex = g_anime4k->get_max_texture_size();
  if (maxTex > 0 && (info.width > maxTex || info.height > maxTex)) {
    LOGD("Anime4K: input %dx%d exceeds max texture size %d, skipping", info.width, info.height,
         maxTex);
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
      env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, out_w, out_h, config);
  if (!outBitmap) {
    LOGE("Failed to create output bitmap for Anime4K");
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

  int actual_out_w, actual_out_h;
  int processResult = g_anime4k->process(info.width, info.height,
                                         static_cast<unsigned char *>(pixels),
                                         actual_out_w, actual_out_h,
                                         static_cast<unsigned char *>(outPixels));

  AndroidBitmap_unlockPixels(env, bitmap);
  AndroidBitmap_unlockPixels(env, outBitmap);

  // MihonSY fix: if the GL pass failed (e.g. context rebind error) the output
  // bitmap is uninitialised/black — return the original instead of a black frame.
  if (processResult != 0) {
    LOGE("Anime4K process failed (%d), returning original bitmap", processResult);
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID recycleMethod = env->GetMethodID(bitmapClass, "recycle", "()V");
    env->CallVoidMethod(outBitmap, recycleMethod);
    return bitmap;
  }

  return outBitmap;
}
