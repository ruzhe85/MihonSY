package eu.kanade.tachiyomi.util

// Komiho: Application is used by the GPU AI upscaler (model asset extraction).
import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModelRegistry
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Image enhancement for Komiho.
 *
 * Two families, selected by [ReaderPreferences.enhancementMode]:
 *  - Lanczos3 / Catmull-Rom (CPU): classic resampling, cheap and fully deterministic.
 *  - AI upscale (GPU): ncnn + Vulkan 2x super-resolution ported from
 *    HaoweiLi97/mihon_img_upscale. Falls back to the original image when this ABI has no
 *    Vulkan build or when inference fails.
 *
 * MihonSY: Anime4K (GPU shaders) remains disabled — its native sources are not compiled
 * into the build, so the Kotlin bindings below stay commented out.
 */
object MihonSyEnhancer {

    // MihonSY: Anime4K disabled.
    // private const val ANIME4K_ASSET_DIR = "anime4k"

    /**
     * 增强结果允许的最大像素总量（ARGB_8888 下 32MP ≈ 128MB）；超出即等比缩小。
     * 推导见 [capOutputSize]：取值刻意高于实测条漫的 2x 输出（31.8MP），
     * 好让「正常的条漫」不受任何影响，只拦 CPU 倍率档那种极端尺寸。
     */
    private const val MAX_ENHANCE_OUTPUT_PIXELS = 32_000_000L

    /**
     * 倍率。AI 档（mode 5）由模型固定为 2x；CPU 档（2/3）用 [ReaderPreferences.lanczosScale]。
     * 只用于 [exceedsOutputCap] 的预判，实际缩放仍由各自分支决定。
     */
    private const val AI_UPSCALE_FACTOR = 2f

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
     * Returns the enhanced bitmap, or null when no enhancement applies / fails — including the
     * case where the source is already so large that the scaled result would not survive
     * [capOutputSize], in which case enhancement is skipped outright (see [exceedsOutputCap]).
     *
     * @param input must be an ARGB_8888 bitmap.
     * @param onComplete optional callback invoked with (enhanced != null, elapsedMillis, gpuWaitMillis)
     *   so callers can show a meaningful status (time taken / success).
     *   Komiho：`gpuWaitMillis` = 本次**等原生引擎锁的总时长**（两段之和：`clearAbort` 拿到的那次
     *   + `nativeProcess` 内部再拿一次那次的排队），不属于计算耗时；角标显示「实际计算消耗」时
     *   要把它减掉。见 [Waifu2x.Timing.totalWaitMs]。
     * @param sourceTag Komiho 诊断：请求来源标识（`prewarm#12` / `holder#12`），仅用于日志。
     * @param pageIndex Komiho：本页页号。透传给 [Waifu2x.process] / [Waifu2x.markCpuFallback]，
     *   让角标按页登记引擎（全局槽会被并发页覆盖，见 [Waifu2x.engineFor]）。-1 = 未知。
     */
    fun enhance(
        input: Bitmap,
        preferences: ReaderPreferences = Injekt.get(),
        onComplete: ((enhanced: Boolean, elapsedMillis: Long, gpuWaitMillis: Long) -> Unit)? = null,
        sourceTag: String = "",
        pageIndex: Int = -1,
        targetWidth: Int = -1,
        targetHeight: Int = -1,
    ): Bitmap? {
        val start = SystemClock.uptimeMillis()
        if (input.isRecycled) {
            // Komiho: 什么都没做 —— 角标记成 skip，别落回 engineLabel 的默认值 CPU OK。
            EnhanceTimings.markSkipped(pageIndex)
            onComplete?.invoke(false, SystemClock.uptimeMillis() - start, 0L)
            return null
        }
        // MihonSY: never enhance hardware bitmaps — reading their pixels is unreliable
        // (can produce all-black frames on some devices). Decode-time enhancement runs
        // on software bitmaps, so a HARDWARE input simply skips enhancement.
        if (input.config == Bitmap.Config.HARDWARE) {
            EnhanceTimings.markSkipped(pageIndex)
            onComplete?.invoke(false, SystemClock.uptimeMillis() - start, 0L)
            return null
        }

        // Single selector: 0 = Off, 2 = Lanczos3, 3 = Catmull-Rom.
        // (MihonSY: Anime4K (1) and Spline36 (4) are disabled and excluded from the build.)
        val mode = preferences.enhancementMode.get()

        // Komiho (2026-09-23): 拦「注定白做」的增强。输入已经大到 × scale 之后必然超过
        // [MAX_ENHANCE_OUTPUT_PIXELS]，输出就会被 [capOutputSize] 缩回来 —— 先超分再缩，
        // 不但白烧算力（实测单页 2.5~6s，串行排队时单页总耗可达 15s），最后那次非整数重采样
        // 还会抹细节：实测 1600×20164 → AI 2x = 3200×40328 (129MP) → 缩到 1593×20082，
        // **比源宽 1600 还窄**。这类输入（本地已超分好的大图正是典型）直接不增强。
        if (mode != 0 && exceedsOutputCap(input, mode, preferences)) {
            logcat(LogPriority.WARN) {
                val inPx = input.width.toLong() * input.height.toLong()
                val scale = scaleFor(mode, preferences)
                "Enhancement skipped: ${input.width}x${input.height} (${inPx / 1_000_000}MP) " +
                    "x$scale -> ${(inPx * scale * scale / 1_000_000).toLong()}MP " +
                    "exceeds the ${MAX_ENHANCE_OUTPUT_PIXELS / 1_000_000}MP output cap"
            }
            onComplete?.invoke(false, SystemClock.uptimeMillis() - start, 0L)
            // Komiho: 这一页没有引擎参与，角标要显示 skip 而不是 CPU OK。
            EnhanceTimings.markSkipped(pageIndex)
            return null
        }

        // Komiho: GPU 档单独收集耗时拆分 —— 角标要显示「剔除等锁」的实际计算消耗。
        val gpuTiming = if (mode == 5) Waifu2x.Timing() else null
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
                    onComplete?.invoke(false, SystemClock.uptimeMillis() - start, 0L)
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

            // Komiho: GPU AI upscale (ncnn + Vulkan). Scale is fixed by the model (2x).
            5 -> enhanceWithGpu(input, preferences, sourceTag, gpuTiming, pageIndex, targetWidth, targetHeight)

            else -> null
        }
        // 只在结果确实是新对象时才缩（避免误 recycle 调用方仍在用的 input）。
        val capped = if (result != null && result !== input) capOutputSize(result) else result
        onComplete?.invoke(
            capped != null && capped !== input,
            SystemClock.uptimeMillis() - start,
            // Komiho: 传「等锁总时长」而不是单纯的 waitMs —— process() 里其实是两次拿 g_lock，
            // nativeProcess 内部那次排队会被算进 procMs（实测某页 wait=2413 却 proc=4913，
            // 原生自报只跑了 2449）。角标要剔除的是两段之和，见 Waifu2x.Timing.totalWaitMs()。
            gpuTiming?.totalWaitMs() ?: 0L,
        )
        return capped
    }

    /** 当前档位实际会用到的放大倍率（AI 档固定见 [AI_UPSCALE_FACTOR]，CPU 档取偏好）。 */
    private fun scaleFor(mode: Int, preferences: ReaderPreferences): Float =
        if (mode == 5) AI_UPSCALE_FACTOR else preferences.lanczosScale.get() / 100f

    /**
     * [input] 经当前档位放大后，像素总量是否会超过 [MAX_ENHANCE_OUTPUT_PIXELS]。
     *
     * 超过就说明 [capOutputSize] 必然把结果缩回来：这次增强拿不到标称倍率，只白白多出
     * 一次非整数重采样。调用方据此整页跳过（见 [enhance]）。
     */
    private fun exceedsOutputCap(input: Bitmap, mode: Int, preferences: ReaderPreferences): Boolean {
        val scale = scaleFor(mode, preferences)
        if (scale <= 1f) return false
        val output = input.width.toDouble() * input.height.toDouble() * scale * scale
        return output > MAX_ENHANCE_OUTPUT_PIXELS.toDouble()
    }

    /**
     * 增强结果的**像素总量**上限（内存护栏）。
     *
     * 为什么不按单边：单边上限会误伤条漫。实测条漫 800×9927 经 2x 得 1600×19854，高度
     * 19854 一旦按 GL 纹理边长 16384 收口，就被缩成 1320×16384——宽度掉到显示宽度
     * （1440）以下还要再放大，而且是在 AI **之后**做 0.825 的非整数重采样，细节明显变糊
     * （用户实测反馈）。像素总量才对应真正的内存开销（ARGB_8888 = 4B/px）。
     *
     * [MAX_ENHANCE_OUTPUT_PIXELS] 取 32MP ≈ 128MB：
     *  - 实测条漫 2x = 31.8MP → **不触发**，画质不受影响；
     *  - 普通页（输入 ≤2048 → 2x ≤4096 ≈ 16.8MP）→ 永不触发；
     *  - CPU 倍率档在同类条漫上会得到 2400×29781 ≈ 71MP / **286MB**，会被缩到 32MP/128MB
     *    —— 那才是真正需要拦住的极端情况。
     */
    private fun capOutputSize(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return bitmap
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (pixels <= MAX_ENHANCE_OUTPUT_PIXELS) return bitmap
        val factor = sqrt(MAX_ENHANCE_OUTPUT_PIXELS.toDouble() / pixels.toDouble()).toFloat()
        val width = maxOf(1, (bitmap.width * factor).roundToInt())
        val height = maxOf(1, (bitmap.height * factor).roundToInt())
        // 触发即记录：这是真正需要人工关注的极端情况，不能静默。
        logcat(LogPriority.WARN) {
            "Enhancement output cap: ${bitmap.width}x${bitmap.height} " +
                "(${pixels / 1_000_000}MP) -> ${width}x$height"
        }
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled !== bitmap) {
                bitmap.recycle()
                scaled
            } else {
                bitmap
            }
        } catch (e: Throwable) {
            // 缩不下来就用原尺寸（宁可大也不要丢结果）。
            logcat(LogPriority.WARN, e) { "Enhancement output cap failed; keeping full size" }
            bitmap
        }
    }

    /**
     * Komiho: GPU AI upscale branch — used when [ReaderPreferences.enhancementMode] is 5.
     *
     * The bundled ncnn model is a fixed 2x network, so [ReaderPreferences.lanczosScale] does
     * not choose the AI output size. When this ABI has no Vulkan build (or inference fails)
     * we fall back to the CPU Lanczos3 resampler at the configured scale, so the page is
     * still enlarged instead of silently staying at its original size.
     */
    private fun enhanceWithGpu(
        input: Bitmap,
        preferences: ReaderPreferences,
        sourceTag: String = "",
        timing: Waifu2x.Timing? = null,
        pageIndex: Int = -1,
        targetWidth: Int = -1,
        targetHeight: Int = -1,
    ): Bitmap? {
        if (Waifu2x.isSupported) {
            // Komiho: model and tile geometry are user preferences. Both are pushed before
            // the pass and applied lazily — only a real change reaches the native side
            // (the model rebuilds the engine, the tile size takes the engine lock).
            // 2026-09-19 模型插件化: the persisted id may belong to a plugin model package,
            // so resolve through the registry (which lazily scans plugin APKs once per
            // process) instead of the built-in enum alone.
            val app = Injekt.get<Application>()
            UpscaleModelRegistry.ensureScanned(app)
            Waifu2x.setModel(UpscaleModelRegistry.findById(preferences.aiModelId.get()))
            // Komiho（跨 HTP 试验期）：不接 onQnnFallback —— 回写偏好会把用户选的 NPU
            // 条目改成 Default，导致「换台机器重试同一个模型」这件事做不了。失败只留日志
            // （Waifu2x 的 `NPU UNAVAILABLE` WARN 带 on-chip arch），当页仍回落 Vulkan 出图。
            // 回落现在是**可见**的：角标读 Waifu2x.lastEngine，QNN 没跑成就会显示 GPU OK。
            Waifu2x.setTileSize(preferences.aiTileSize.get())
            Waifu2x.process(
                app,
                input,
                id = pageIndex,
                tag = sourceTag,
                timing = timing,
            )?.let { upscaled ->
                // Komiho: AI 固定 2x，SSIV 显示时用双线性把 2x 结果缩到适应显示尺寸，
                // 网点图高频细节被双线性抹糊。改为软件层用 Lanczos3 先把 2x 结果缩到
                // 适应屏幕尺寸（fit-into targetW×targetH），让 SSIV 缩放比≈1，网点细节
                // 由 Lanczos3 保住，不再被双线性重采样一次。
                // 长条页的 targetH 已是全高，fit-into 自然退化为按宽度缩放，无需单独分支。
                if (upscaled.width > input.width) {
                    val goalW = if (targetWidth > 0) targetWidth else input.width
                    val goalH = if (targetHeight > 0) targetHeight else input.height
                    val scale = min(
                        goalW.toFloat() / upscaled.width.toFloat(),
                        goalH.toFloat() / upscaled.height.toFloat(),
                    )
                    if (scale < 1f) {
                        val argb = ensureArgb(upscaled) ?: return upscaled
                        val down = nativeLanczosProcess(argb, scale)
                        if (down != null && down !== argb) {
                            if (argb !== upscaled) argb.recycle()
                            upscaled.recycle()
                            return down
                        }
                        if (argb !== upscaled) argb.recycle()
                    }
                }
                return upscaled
            }
            logcat(LogPriority.WARN) { "AI upscale produced no result; falling back to Lanczos3" }
        } else {
            logcat(LogPriority.WARN) { "AI upscale unavailable for this ABI; falling back to Lanczos3" }
        }

        // Komiho: 落到这里 = GPU/NPU 引擎都没出结果，本页由 CPU 重采样完成。
        // 角标据此显示 CPU OK（引擎选择失败时 lastEngine 保持上一次的值，所以必须在
        // 真正走 CPU 路径时清掉，否则会把上一页的 GPU/NPU 结果错误地沿用过来）。
        // 2026-09-19：只清**本页**的记录 —— 全局清空会让并发中的其他页被误标成 CPU OK。
        Waifu2x.markCpuFallback(pageIndex)
        val scale = preferences.lanczosScale.get() / 100f
        if (scale <= 1f) return null
        val argb = ensureArgb(input) ?: return null
        return nativeLanczosProcess(argb, scale).takeUnless { it === argb }
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
