package eu.kanade.tachiyomi.util

// Komiho: Application is used by the GPU AI upscaler (model asset extraction).
import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModelRegistry
import eu.kanade.tachiyomi.util.waifu2x.UpscaleModelSpec
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

    /**
     * Komiho: AI 面积回缩触发门。AI 2x 结果相对显示区的缩比 ≥ 此值时不动它 —— 轻度缩小
     * 交给 SSIV 双线性即可（无可感知拍频）；小于此值才用面积核回缩（双线性在 >1.2x 左右
     * 的缩小上开始欠采样，留余量取 0.85）。见 [areaDownscaleToDisplay]。
     */
    private const val AREA_DOWNSCALE_TRIGGER = 0.85f

    /**
     * Komiho: 灰度掩膜的通道发散度阈值（/255）。≤ 此值视为灰度像素（容忍 JPEG 色噪
     * ±2-3）；> 此值视为彩色像素，原样通过模型并保留输出色度。
     */
    private const val GRAY_CHROMA_THRESHOLD = 8

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

    /**
     * Komiho: AI 2x 回缩到显式目标尺寸（native kernel id 5 高斯，σ = 0.5×[strength]，
     * 支撑窗 3σ —— 缩比 r 下实际积分窗 ≈ 3σ/r 源像素，覆盖网点晶格周期才能积成均匀灰；
     * 上一版软边 box 的 ~1.5px 窗口在 0.655 缩比下只留云纹，已弃）。目标大于源时 native
     * 侧钳回源尺寸（绝不放大）；任何失败返回入参本身。
     */
    private external fun nativeAreaDownscaleTo(bitmap: Bitmap, dstWidth: Int, dstHeight: Int, strength: Float): Bitmap

    /**
     * Komiho: CPU Guided Filter 漫画降噪（亮度引导强度缩放：只平滑亮度、三通道同乘一因子，
     * 色相守恒永不色偏）。曾用 Fast NLM：真机实测 3.1MP 弱档单页 ~11s，换 O(1) guided filter 实测 ~100-200ms。
     * 成功返回新位图（尺寸不变）；失败返回入参本身。`argb` 必须是 ARGB_8888（调用方先过 [ensureArgb]）。
     */
    private external fun nativeGuidedDenoise(bitmap: Bitmap, eps: Float, radius: Int): Bitmap?

    /** 降噪进程级互斥：guided 虽快（~100ms），但 Coil 解码线程池会多页并发增强，
     *  不加锁会重新出现 NLM 时代「48 线程抢核 + 内存翻倍」的争抢。 */
    private val denoiseLock = Any()

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

        // Komiho: CPU Guided Filter 漫画降噪（默认关），与增强算法解耦 —— 只要增强模式
        // 非零（Lanczos3 / Catmull-Rom / AI 皆然）就先去噪再增强；mode 0（关增强）保持零处理。
        // 落点在解码采样之后、各增强分支之前：满足「进入增强前输入更干净」的本质目的
        // （解码采样不可避免），且 guided filter 代价与窗口无关，采样后尺寸
        // （~1-3MP）单页仅 ~100-200ms（Fast NLM 方案同尺寸实测 11s，已弃）。
        var src = input
        val denoiseLevel = preferences.denoiseLevel.get()
        if (denoiseLevel != 0) {
            src = denoiseForAi(src, denoiseLevel, sourceTag)
        }

        // Komiho (2026-10-01): AI 灰度掩膜 —— 电子漫画网点经 HTP 管线后通道发散，表现为网点
        // 边缘的彩色色块。推理前把「近灰度像素」钳位为中性（R=G=B=亮度），推理后把这些像素
        // 的输出色度中和掉；彩色像素（含彩色漫画整页、页内彩色插图）原样通过，零影响。
        // 掩膜随结果在 [neutralizeChroma] 消费。
        // ⚠️ 只对 QNN/HTP 建 —— Vulkan 从未出现色块，且原生 postproc 已整页强制中性，
        // 在 Vulkan 上这一步是纯开销（判据与依据见 [needsGrayMask]）。
        var grayMask: Pair<ByteArray, Int>? = null
        if (mode == 5 && needsGrayMask(preferences)) {
            val clamped = clampGrayForAi(src)
            src = clamped.first
            grayMask = clamped.second
        }

        val result = when (mode) {
            // Komiho: 降噪独立档——mode 0 只做降噪不增强（降噪已在上方完成，src 即结果）。
            0 -> src
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
            // (Spline36 (4) disabled; kernel id: 0 = Lanczos3, 1 = Catmull-Rom,
            //  3 = Mitchell-Netravali（GPU/AI 路线降采样用）native side.)
            in 2..3 -> {
                val scale = preferences.lanczosScale.get() / 100f
                val argb = ensureArgb(src) ?: run {
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
            5 -> {
                enhanceWithGpu(src, preferences, sourceTag, gpuTiming, pageIndex)
            }

            else -> null
        }
        // 降噪产物只被本次增强消费（下游返回的都是新位图，见 enhanceWithGpu / 重采样），
        // 及时回收，避免大图滞留到 GC。仅当 src 是新位图（≠ input）且不是最终返回对象时回收。
        // 掩膜在 src 回收前消费：输出色度中和按 src 尺寸缩放掩膜坐标。
        if (grayMask != null && result != null && result !== src) {
            neutralizeChroma(result, grayMask.first, grayMask.second)
        }
        if (src !== input && (result == null || result !== src)) src.recycle()
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

    /**
     * Komiho: 降噪输入像素量护栏。超大图（已超分页/长条漫全高图）跑降噪收益存疑且耗时
     * 随像素量线性增长，直接跳过（解码管线的采样已把常规页压到 1~3MP；
     * 6MP 是长条漫按宽度采样后的常见上限）。
     */
    private const val MAX_DENOISE_INPUT_PIXELS = 6_000_000L

    /**
     * Komiho: 漫画降噪前处理（Guided Filter，亮度引导强度缩放）。任何失败都静默回落原图
     * （或其 ARGB 副本），绝不阻断出图。对所有增强模式（Lanczos3 / Catmull-Rom / AI）生效。
     *
     * 开启即固定强档：radius=12, eps=64（eps 越大平滑越强，255 域）。统计引导 = 预平滑亮度 gI，
     * a = var(gI)/(var(gI)+eps)，平坦区 var(gI) ≈ σ²/49（7×7 box，σ²≈100 → ≈2），噪声残留 = a·噪声。
     * 耗时用 exh.log.DiagLog 而非项目 logcat()：release 构建下 XLog 级别是 WARN，
     * logcat() 的 DEBUG/INFO 会被整条吞掉（与 Waifu2x.process 同款口径）；DiagLog 同时写
     * android.util.Log 与进程内缓冲，所以 logcat 与「导出诊断日志」都能拿到。
     * 角标的「解码+增强」总耗时天然包含降噪（denoise 在 enhance() 内部跑），
     * 这里另拆出 denoiseMs 单项供评估。
     */
    private fun denoiseForAi(input: Bitmap, level: Int, sourceTag: String): Bitmap {
        if (input.width.toLong() * input.height.toLong() > MAX_DENOISE_INPUT_PIXELS) {
            android.util.Log.d(
                "Waifu2xTiming",
                "denoise=skipped(level=$level, ${input.width}x${input.height} exceeds cap) from=${sourceTag.ifEmpty { "?" }}",
            )
            return input
        }
        // 开启即用强档：radius=12, eps=64（预平滑后平坦区 a≈0.03，近全平但保线稿）。
        // level 仅 0/1，调用点已在 denoiseLevel!=0 时拦截，进入此处恒为 1。
        val radius = 12
        val eps = 64f
        val argb = ensureArgb(input) ?: return input
        val start = SystemClock.uptimeMillis()
        // 进程级互斥：多页并发增强时串行化降噪（见 [denoiseLock] 注释）。
        val out = synchronized(denoiseLock) {
            try {
                nativeGuidedDenoise(argb, eps, radius)
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "Guided denoise failed; using original" }
                null
            }
        }
        val ms = SystemClock.uptimeMillis() - start
        android.util.Log.d(
            "Waifu2xTiming",
            "denoise=${ms}ms level=$level r=$radius eps=$eps " +
                "src=${input.width}x${input.height} from=${sourceTag.ifEmpty { "?" }}",
        )
        return if (out != null && out !== argb) {
            // 降噪成功：ARGB 中间副本（若拷过）已无消费者，立刻回收。
            if (argb !== input) argb.recycle()
            out
        } else {
            // 失败：用（可能拷贝过的）原图继续，降噪跳过。
            argb
        }
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
     * Komiho (2026-10-01): 本次 AI 增强实际会使用的模型（含插件模型解析）。
     *
     * 单独抽出来是因为 [enhance] 在**推理之前**就要知道引擎类型 —— 灰度掩膜建不建取决于这
     * 一页会跑 Vulkan 还是 QNN（见 [needsGrayMask]），而这件事不能等 [Waifu2x.lastEngine]，
     * 那个值要推理结束才写。「一次扫描」的语义仍由 [UpscaleModelRegistry.ensureScanned] 保证。
     */
    private fun resolveAiModel(preferences: ReaderPreferences): UpscaleModelSpec {
        val app = Injekt.get<Application>()
        UpscaleModelRegistry.ensureScanned(app)
        return UpscaleModelRegistry.findById(preferences.aiModelId.get())
    }

    /**
     * Komiho (2026-10-01): 本次 AI 增强是否需要 Kotlin 侧的灰度掩膜。
     *
     * **只有 QNN/HTP（NPU）需要**。HTP 管线的 NHWC 包装与 I/O 量化会让灰度网点的边缘产生
     * 通道发散（彩色色块），**与权重精度无关** —— npu 的 fp16 与 int8 模型都需要（已确认）。
     *
     * **Vulkan 不需要**：实测从未出现色块（已确认）；而且原生 fused postproc 本来就会在
     * 检测到灰度输入时整页强制中性输出（`waifu2x.cpp` 的 is_grayscale 判定 →
     * postproc `constants[15]`），Kotlin 这套在 Vulkan 上只是双重冗余 —— 白白付出每页两次
     * 整图 getPixels/setPixels 加一块 47MB（pager）／109MB（条漫）的 IntArray。
     * 反过来说 QNN 侧**没有任何**原生灰度兜底（`qnn_backend.cpp` 里没有相关逻辑），
     * 所以掩膜在那里是唯一保障。
     *
     * 判据取**偏好**而非 [Waifu2x.lastEngine]：后者要等推理结束才写，而输入钳位必须在推理
     * **之前**完成。回落方向是安全的 —— QNN 初始化失败会静默回落 Vulkan，此时仍建掩膜只是
     * 白做一次；反方向（偏好 Vulkan 却跑了 QNN）不可能发生。
     */
    private fun needsGrayMask(preferences: ReaderPreferences): Boolean =
        resolveAiModel(preferences).backend == UpscaleModelSpec.Backend.QNN_HTP

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
    ): Bitmap? {
        if (Waifu2x.isSupported) {
            // Komiho: model and tile geometry are user preferences. Both are pushed before
            // the pass and applied lazily — only a real change reaches the native side
            // (the model rebuilds the engine, the tile size takes the engine lock).
            // 2026-09-19 模型插件化: the persisted id may belong to a plugin model package,
            // so resolve through the registry (which lazily scans plugin APKs once per
            // process) instead of the built-in enum alone — 解析见 [resolveAiModel]，
            // [enhance] 也要用它（灰度掩膜判定），所以不在这里重复一份。
            val app = Injekt.get<Application>()
            Waifu2x.setModel(resolveAiModel(preferences))
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
                // Komiho (2026-09-30): AI 2x 结果默认直接交 SSIV 缩放显示（2026-09-26 定稿）。
                // 可选开关 [ReaderPreferences.aiAreaDownscale] 开启后，由解码器按视图尺寸
                // 走 [areaDownscaleToDisplay] 面积回缩 —— SSIV 对 bitmap 源是整图双线性缩小、
                // 无低通，AI 2x 的高频网点会拍频出摩尔纹（回缩在解码器做，因为它持有视图尺寸）。
                return upscaled
            }
            // Komiho (2026-10-01): 被抢占打断的页不跑 CPU 兜底 —— 那一整幅重采样要占住
            // CPU 0.5~1 秒（还会和别的页的增强抢核），而这一页之所以被打断，正是因为可见页
            // 已经换人；它的原始解码图本来就在屏幕上，AI 结果稍后重算时自然会再来。
            // 返回 null 等于「保持原始解码图」。
            if (Waifu2x.wasAborted(pageIndex)) {
                logcat(LogPriority.DEBUG) { "AI upscale preempted; keeping original decode" }
                return null
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

    /**
     * Komiho: AI 面积回缩 —— AI 2x 输出相对显示尺寸的缩比 < [AREA_DOWNSCALE_TRIGGER] 时，
     * 用高斯核（σ = 0.5×[strength]，支撑窗 3σ 随缩比放大）压回显示带通再交 SSIV。
     *
     * 摩尔纹根因：SSIV 对 bitmap 源走非瓦片路径，`FilterBitmap` 双线性整图缩小没有低通
     * （每屏幕像素仅 2×2 tap），AI 2x 的高频网点与屏幕像素网格拍频。第一版软边 box 的
     * 积分窗只有 ~1/缩比 个源像素，盖不住 AI 2x 后 6~16px 的网点晶格周期，0.655 缩比
     * 实测残留低频云纹 —— 高斯的窗口随 [strength] 放大才能把晶格积成均匀灰（轻/中/强
     * ≈ 4.6/7/9px 窗口）；滚降平滑无振铃，线稿由 AI 2x 预先锐化、适度模糊可接受。
     *
     * 契约：不回收入参；返回新位图或入参本身。长条（[isTallStrip]）只按宽度算 fit
     * （与解码侧 fitRatio 同口径，高度是滚动维度）；视图尺寸未知（<=0 / 未布局 /
     * Int.MAX_VALUE）时原样返回。任何失败都原样返回，绝不阻断出图。
     */
    fun areaDownscaleToDisplay(
        bitmap: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
        isTallStrip: Boolean,
        strength: Float = 1f,
    ): Bitmap {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        if (viewWidth <= 0 || viewWidth == Int.MAX_VALUE) return bitmap
        if (!isTallStrip && (viewHeight <= 0 || viewHeight == Int.MAX_VALUE)) return bitmap

        val fit = if (isTallStrip) {
            viewWidth / bitmap.width.toFloat()
        } else {
            min(
                viewWidth / bitmap.width.toFloat(),
                viewHeight / bitmap.height.toFloat(),
            )
        }
        if (fit <= 0f || fit >= AREA_DOWNSCALE_TRIGGER) return bitmap

        val targetW = maxOf(1, (bitmap.width * fit).roundToInt()).coerceAtMost(bitmap.width)
        val targetH = maxOf(1, (bitmap.height * fit).roundToInt()).coerceAtMost(bitmap.height)
        if (targetW == bitmap.width && targetH == bitmap.height) return bitmap

        // native 只读输入像素（输出是它自建的位图），无需 mutable —— 与 [ensureArgb] 的
        // 可变要求不同，避免为一次降采样做整图拷贝。AI/重采样输出恒为 ARGB_8888，
        // copy 路径纯防御。
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (t: Throwable) {
                null
            }
        } ?: return bitmap

        val out: Bitmap? = try {
            nativeAreaDownscaleTo(argb, targetW, targetH, strength.coerceIn(0.5f, 3f)).takeIf { it !== argb }
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "Area downscale failed; keeping full size" }
            null
        }
        // 中间副本（若有）无消费者即回收；入参所有权归调用方。
        if (argb !== bitmap) argb.recycle()
        return out ?: bitmap
    }

    /**
     * Komiho (2026-10-01): AI 灰度掩膜（治电子漫画网点色块）。扫描 [src]，把通道发散度
     * ≤ [GRAY_CHROMA_THRESHOLD] 的像素钳位为中性（R=G=B=亮度），并返回灰度掩膜
     * (ByteArray, srcWidth) 供 [neutralizeChroma] 在推理输出上消费。彩色像素原样保留、
     * 掩膜记 0 —— 彩色漫画整页掩膜近似空集，行为与无掩膜完全一致。
     *
     * 原理：色块来自模型对各通道的独立卷积在灰度输入上产生通道发散；输入钳位 + 输出
     * 中和后灰度像素的色度被钳死在中性，色块从根上不可能产生。
     * 返回 (位图, (掩膜, 宽))：位图可能是入参本身（已可变 ARGB）也可能是副本；
     * 失败/无需处理返回入参本身且第二元为 null 掩膜（调用方按 null 跳过中和）。
     */
    private fun clampGrayForAi(src: Bitmap): Pair<Bitmap, Pair<ByteArray, Int>?> {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return src to null
        val argb = if (src.config == Bitmap.Config.ARGB_8888 && src.isMutable) {
            src
        } else {
            try {
                src.copy(Bitmap.Config.ARGB_8888, true) ?: return src to null
            } catch (t: Throwable) {
                return src to null
            }
        }
        val w = argb.width
        val h = argb.height
        val pixels = IntArray(w * h)
        argb.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = ByteArray(w * h)
        var grayCount = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val divergence = maxOf(
                kotlin.math.abs(r - g),
                kotlin.math.abs(g - b),
                kotlin.math.abs(r - b),
            )
            if (divergence <= GRAY_CHROMA_THRESHOLD) {
                val y = (r * 299 + g * 587 + b * 114) / 1000
                pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
                mask[i] = 1
                grayCount++
            }
        }
        if (grayCount == 0) {
            // 整页彩色：钳位无效果，掩膜也不必建。
            if (argb !== src) {
                src.recycle()
            }
            return src to null
        }
        argb.setPixels(pixels, 0, w, 0, 0, w, h)
        logcat(LogPriority.DEBUG) {
            "Gray mask: ${grayCount * 100 / (w * h)}% gray (${w}x$h)"
        }
        return argb to (mask to w)
    }

    /**
     * Komiho: 推理输出中和 —— 掩膜标记的灰度像素（按 [srcWidth] 缩放到输出坐标）把
     * 输出色度中和为输出亮度。任何失败静默跳过（绝不阻断出图）。
     */
    private fun neutralizeChroma(result: Bitmap, mask: ByteArray, srcWidth: Int) {
        if (result.isRecycled || result.config != Bitmap.Config.ARGB_8888 || !result.isMutable) {
            return
        }
        val scale = result.width / srcWidth
        if (scale <= 0) return
        try {
            val w = result.width
            val h = result.height
            val pixels = IntArray(w * h)
            result.getPixels(pixels, 0, w, 0, 0, w, h)
            for (y in 0 until h) {
                val srcRow = (y / scale) * srcWidth
                var idx = y * w
                for (x in 0 until w) {
                    if (mask[srcRow + x / scale].toInt() == 1) {
                        val p = pixels[idx]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val y8 = (r * 299 + g * 587 + b * 114) / 1000
                        pixels[idx] = (p and 0xFF000000.toInt()) or (y8 shl 16) or (y8 shl 8) or y8
                    }
                    idx++
                }
            }
            result.setPixels(pixels, 0, w, 0, 0, w, h)
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "Chroma neutralize failed; keeping output" }
        }
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
