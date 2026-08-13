package eu.kanade.tachiyomi.util

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors

/**
 * Lightweight image enhancement for MihonSY.
 *
 * Only two algorithms are bundled, both are cheap enough for mobile:
 *  - Anime4K: real-time GPU (GLES) shader upscaling, great for line art / webtoon style.
 *  - Lanczos3: classic CPU resampling, memory-friendly and fully deterministic.
 *
 * No heavyweight CNN models (waifu2x / Real-CUGAN / Real-ESRGAN) are included.
 */
object MihonSyEnhancer {

    private const val ANIME4K_ASSET_DIR = "anime4k"

    init {
        System.loadLibrary("mihonsy-enhance")
    }

    /** Serialises enhancement work so the single GPU context / CPU is not contended. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MihonSyEnhancer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    @Volatile
    var isAnime4kInitialized = false
        private set

    // JNI bindings ------------------------------------------------------------------

    private external fun nativeInitAnime4K(shaders: Array<String>, names: Array<String>): Boolean
    private external fun nativeGetMaxTextureSize(): Int
    private external fun nativeProcessAnime4K(bitmap: Bitmap): Bitmap
    private external fun nativeLanczosProcess(bitmap: Bitmap, scale: Float): Bitmap

    // Initialisation ----------------------------------------------------------------

    /**
     * Loads the Anime4K shaders for [mode] (0 = Fast, 1 = High, 2 = Ultra) and initialises
     * the native GLES renderer. Safe to call multiple times (idempotent).
     */
    fun initAnime4K(context: Context, mode: Int): Boolean {
        if (isAnime4kInitialized) return true

        val shaders = mutableListOf<String>()
        val names = mutableListOf<String>()

        fun addShader(name: String) {
            val content = context.assets.open("$ANIME4K_ASSET_DIR/$name")
                .bufferedReader()
                .use { it.readText() }
            shaders.add(content)
            names.add(name)
        }

        return try {
            addShader("Anime4K_Clamp_Highlights.glsl")
            when (mode) {
                0 -> addShader("Anime4K_Restore_CNN_M.glsl") // Fast
                1 -> addShader("Anime4K_Restore_CNN_VL.glsl") // High
                else -> { // Ultra
                    addShader("Anime4K_Restore_CNN_VL.glsl")
                    addShader("Anime4K_Upscale_CNN_x2_VL.glsl")
                }
            }
            isAnime4kInitialized = nativeInitAnime4K(shaders.toTypedArray(), names.toTypedArray())
            if (!isAnime4kInitialized) {
                logcat(LogPriority.WARN) { "Anime4K native init failed" }
            }
            isAnime4kInitialized
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Anime4K init failed" }
            false
        }
    }

    /** Anime4K renders through a full-image framebuffer, so it can only handle images that fit the GPU texture limit. */
    fun anime4kSupportsSize(width: Int, height: Int): Boolean {
        val maxTexture = nativeGetMaxTextureSize()
        return maxTexture <= 0 || (width <= maxTexture && height <= maxTexture)
    }

    // Enhancement entry points -------------------------------------------------------

    /**
     * Runs [block] on the background enhancement thread and posts [onResult] (or [onError])
     * back to the main thread. Used by the reader to avoid blocking the UI.
     */
    fun submit(block: () -> Bitmap?, onResult: (Bitmap) -> Unit, onError: (Exception) -> Unit = {}) {
        executor.execute {
            try {
                val result = block()
                if (result != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Enhancement failed" }
                android.os.Handler(android.os.Looper.getMainLooper()).post { onError(e) }
            }
        }
    }

    /**
     * Synchronously enhances [input] according to the current reader preferences.
     * Returns the enhanced bitmap, or null when no enhancement applies / fails.
     *
     * @param input must be an ARGB_8888 bitmap.
     */
    fun enhance(input: Bitmap, preferences: ReaderPreferences = Injekt.get()): Bitmap? {
        if (input.isRecycled) return null

        // Single selector: 0 = Off, 1 = Anime4K, 2 = Lanczos3. Only one algorithm runs,
        // so there is never a question of which one takes priority.
        return when (preferences.enhancementMode.get()) {
            1 -> {
                val mode = preferences.anime4kMode.get()
                val argb = ensureArgb(input) ?: return null
                if (initAnime4K(Injekt.get<Application>(), mode) && anime4kSupportsSize(argb.width, argb.height)) {
                    nativeProcessAnime4K(argb).takeUnless { it === argb }
                } else {
                    null
                }
            }

            2 -> {
                val scale = preferences.lanczosScale.get() / 100f
                val argb = ensureArgb(input) ?: return null
                if (scale > 1f) {
                    nativeLanczosProcess(argb, scale).takeUnless { it === argb }
                } else {
                    null
                }
            }

            else -> null
        }
    }

    /** Returns [input] if it is already a mutable ARGB_8888 bitmap, otherwise a copy. */
    private fun ensureArgb(input: Bitmap): Bitmap? {
        // Hardware bitmaps cannot be copied directly; go through a software pixel read.
        if (input.config == Bitmap.Config.HARDWARE) {
            return input.copy(Bitmap.Config.ARGB_8888, true)
        }
        return if (input.config == Bitmap.Config.ARGB_8888 && input.isMutable) {
            input
        } else {
            input.copy(Bitmap.Config.ARGB_8888, true)
        }
    }
}
