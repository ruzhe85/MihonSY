package eu.kanade.tachiyomi.util

// MihonSY: Anime4K disabled — Application/Context only used by the commented-out A4K code.
// import android.app.Application
// import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors

/**
 * Lightweight image enhancement for MihonSY.
 *
 * CPU resampling algorithms are bundled, all cheap enough for mobile:
 *  - Lanczos3 / Catmull-Rom: classic CPU resampling, memory-friendly and
 *    fully deterministic.
 *
 * No heavyweight CNN models (waifu2x / Real-CUGAN / Real-ESRGAN) are included.
 *
 * MihonSY: Anime4K (GPU shaders) was disabled — its native sources are no longer
 * compiled into the build, so the Kotlin bindings below are commented out.
 */
object MihonSyEnhancer {

    // MihonSY: Anime4K disabled.
    // private const val ANIME4K_ASSET_DIR = "anime4k"

    init {
        System.loadLibrary("mihonsy-enhance")
    }

    /** Serialises enhancement work so the single CPU is not contended. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MihonSyEnhancer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // MihonSY: Anime4K state removed.
    // @Volatile
    // var isAnime4kInitialized = false
    //     private set

    // MihonSY: the Anime4K mode (0 Fast / 1 High / 2 Ultra) the native renderer was loaded with.
    // @Volatile
    // private var anime4kInitializedMode = -1

    // JNI bindings ------------------------------------------------------------------

    // MihonSY: Anime4K JNI bindings disabled (native side no longer compiled).
    // private external fun nativeInitAnime4K(shaders: Array<String>, names: Array<String>): Boolean
    // private external fun nativeGetMaxTextureSize(): Int
    // private external fun nativeProcessAnime4K(bitmap: Bitmap): Bitmap
    private external fun nativeLanczosProcess(bitmap: Bitmap, scale: Float): Bitmap
    private external fun nativeResample(bitmap: Bitmap, scale: Float, kernel: Int): Bitmap

    // Initialisation ----------------------------------------------------------------

    // MihonSY: Anime4K disabled — init/size helpers removed with the native build.
    // /**
    //  * Loads the Anime4K shaders for [mode] (0 = Fast, 1 = High, 2 = Ultra) and initialises
    //  * the native GLES renderer. Safe to call multiple times (idempotent).
    //  */
    // fun initAnime4K(context: Context, mode: Int): Boolean {
    //     if (isAnime4kInitialized && anime4kInitializedMode == mode) return true
    //
    //     val shaders = mutableListOf<String>()
    //     val names = mutableListOf<String>()
    //
    //     fun addShader(name: String) {
    //         val content = context.assets.open("$ANIME4K_ASSET_DIR/$name")
    //             .bufferedReader()
    //             .use { it.readText() }
    //         shaders.add(content)
    //         names.add(name)
    //     }
    //
    //     return try {
    //         addShader("Anime4K_Clamp_Highlights.glsl")
    //         when (mode) {
    //             0 -> addShader("Anime4K_Restore_CNN_M.glsl") // Fast
    //             1 -> addShader("Anime4K_Restore_CNN_VL.glsl") // High
    //             else -> { // Ultra
    //                 addShader("Anime4K_Restore_CNN_VL.glsl")
    //                 addShader("Anime4K_Upscale_CNN_x2_VL.glsl")
    //             }
    //         }
    //         isAnime4kInitialized = nativeInitAnime4K(shaders.toTypedArray(), names.toTypedArray())
    //         if (isAnime4kInitialized) {
    //             anime4kInitializedMode = mode
    //         } else {
    //             logcat(LogPriority.WARN) { "Anime4K native init failed" }
    //         }
    //         isAnime4kInitialized
    //     } catch (e: Exception) {
    //         logcat(LogPriority.WARN, e) { "Anime4K init failed" }
    //         false
    //     }
    // }
    //
    // /** Anime4K renders through a full-image framebuffer, so it can only handle images that fit the GPU texture limit. */
    // fun anime4kSupportsSize(width: Int, height: Int, mode: Int = 0): Boolean {
    //     val maxTexture = nativeGetMaxTextureSize()
    //     if (maxTexture <= 0) return true
    //     val scale = if (mode >= 2) 2 else 1
    //     return width * scale <= maxTexture && height * scale <= maxTexture
    // }

    // Enhancement entry points -------------------------------------------------------

    /**
     * Runs [block] on the background enhancement thread and posts [onResult] (or [onError])
     * back to the main thread. Used by the reader to avoid blocking the UI.
     *
     * @param onProgress receives the real elapsed time (ms) while [block] runs, about
     *   every 500ms. The overlay uses it to show a stopwatch so the user can observe
     *   how long enhancement actually takes.
     */
    fun submit(
        block: () -> Bitmap?,
        onResult: (Bitmap) -> Unit,
        onError: (Exception) -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ) {
        executor.execute {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val startMillis = SystemClock.uptimeMillis()
            val ticker = object : Runnable {
                override fun run() {
                    handler.post { onProgress(SystemClock.uptimeMillis() - startMillis) }
                    handler.postDelayed(this, 500)
                }
            }
            handler.postDelayed(ticker, 500)
            try {
                val result = block()
                handler.removeCallbacks(ticker)
                if (result != null) {
                    handler.post { onResult(result) }
                } else {
                    // MihonSY: a null result (enhancement failed/skipped) still needs
                    // to be surfaced so the reader badge can report 跳过.
                    handler.post { onError(NoEnhancementException()) }
                }
            } catch (e: Exception) {
                handler.removeCallbacks(ticker)
                logcat(LogPriority.WARN, e) { "Enhancement failed" }
                handler.post { onError(e) }
            }
        }
    }

    /**
     * Synchronously enhances [input] according to the current reader preferences.
     * Returns the enhanced bitmap, or null when no enhancement applies / fails.
     *
     * @param input must be an ARGB_8888 bitmap.
     * @param onComplete optional callback invoked with (enhanced != null, elapsedMillis)
     *   so callers can show a meaningful status (time taken / success).
     */
    fun enhance(
        input: Bitmap,
        preferences: ReaderPreferences = Injekt.get(),
        onComplete: ((enhanced: Boolean, elapsedMillis: Long) -> Unit)? = null,
    ): Bitmap? {
        val start = SystemClock.uptimeMillis()
        if (input.isRecycled) {
            onComplete?.invoke(false, SystemClock.uptimeMillis() - start)
            return null
        }
        // MihonSY: never enhance hardware bitmaps — reading their pixels is unreliable
        // (can produce all-black frames on some devices). Decode-time enhancement runs
        // on software bitmaps, so a HARDWARE input simply skips enhancement.
        if (input.config == Bitmap.Config.HARDWARE) {
            onComplete?.invoke(false, SystemClock.uptimeMillis() - start)
            return null
        }

        // Single selector: 0 = Off, 2 = Lanczos3, 3 = Catmull-Rom.
        // (MihonSY: Anime4K (1) and Spline36 (4) are disabled and excluded from the build.)
        val mode = preferences.enhancementMode.get()
        val result = when (mode) {
            // MihonSY: Anime4K branch disabled — native side no longer compiled.
            // 1 -> {
            //     val a4kMode = preferences.anime4kMode.get()
            //     val latch = java.util.concurrent.CountDownLatch(1)
            //     var a4kResult: Bitmap? = null
            //     var a4kError: Exception? = null
            //     executor.execute {
            //         try {
            //             val argb = ensureArgb(input)
            //             if (argb != null &&
            //                 initAnime4K(Injekt.get<Application>(), a4kMode) &&
            //                 anime4kSupportsSize(argb.width, argb.height, a4kMode)
            //             ) {
            //                 a4kResult = nativeProcessAnime4K(argb).takeUnless { it === argb }
            //             }
            //         } catch (e: Exception) {
            //             a4kError = e
            //         } finally {
            //             latch.countDown()
            //         }
            //     }
            //     try { latch.await() } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            //     if (a4kError != null) { logcat(LogPriority.WARN, a4kError) { "Anime4K enhancement failed" } }
            //     a4kResult
            // }

            // MihonSY: CPU resamplers — Lanczos3 (2), Catmull-Rom (3).
            // (Spline36 (4) disabled; kernel id: 0 = Lanczos3, 1 = Catmull-Rom native side.)
            in 2..3 -> {
                val scale = preferences.lanczosScale.get() / 100f
                val argb = ensureArgb(input) ?: run {
                    onComplete?.invoke(false, SystemClock.uptimeMillis() - start)
                    return null
                }
                if (scale > 1f) {
                    when (mode) {
                        3 -> nativeResample(argb, scale, 1)
                        // MihonSY: Spline36 (4) disabled.
                        // 4 -> nativeResample(argb, scale, 2)
                        else -> nativeLanczosProcess(argb, scale)
                    }.takeUnless { it === argb }
                } else {
                    null
                }
            }

            else -> null
        }
        onComplete?.invoke(result != null && result !== input, SystemClock.uptimeMillis() - start)
        return result
    }

    /** Returns [input] if it is already a mutable ARGB_8888 bitmap, otherwise a copy. */
    private fun ensureArgb(input: Bitmap): Bitmap? {
        if (input.config == Bitmap.Config.ARGB_8888 && input.isMutable) {
            return input
        }
        // MihonSY fix: RGB_565 sources (Coil's memory-saving default) have no alpha
        // channel and Bitmap.copy()/Canvas conversion may fill alpha with 0, making
        // the Lanczos3 alpha-weighted resampler output fully transparent (black).
        // Copy to ARGB_8888 and force the alpha channel opaque.
        val copied = input.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        if (input.config != Bitmap.Config.ARGB_8888) {
            copied.setHasAlpha(false)
        }
        return copied
    }
}

/**
 * MihonSY: thrown when enhancement produced no bitmap (failed or skipped), so the
 * submit() flow can surface a non-success outcome to the reader badge.
 */
class NoEnhancementException : Exception("Enhancement produced no result")
