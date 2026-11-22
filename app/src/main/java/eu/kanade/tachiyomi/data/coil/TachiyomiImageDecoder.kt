package eu.kanade.tachiyomi.data.coil

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.EnhanceTimings
import eu.kanade.tachiyomi.util.MihonSyEnhancer
import eu.kanade.tachiyomi.util.image.ToneCurveLut
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import eu.kanade.tachiyomi.util.storage.CbzCrypto.getCoverStream
import mihon.core.common.archive.archiveReader
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 *
 * MihonSY: when [Options.enhanced] is set (Lanczos3 enhancement enabled), this decoder is used for
 * ALL image formats, decodes at a higher resolution than the view size so the enhancer works on
 * real source detail, then runs the (pure-CPU) Lanczos3 resampler synchronously. No disk cache, no
 * threading — the simplest possible pipeline.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = Injekt.get<Application>()

    override suspend fun decode(): DecodeResult {
        // SY -->
        var coverStream: BufferedInputStream? = null
        if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
            if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                coverStream = UniFile.fromFile(resources.file().toFile())
                    ?.archiveReader(context = context)
                    ?.getCoverStream()
            }
        }
        // Komiho (2026-11-05): PNG 必须赶在 JNI 解码器**读流之前**截下来。
        //
        // 阅读页的 data 是页字节流，Coil 包成 SourceImageSource —— `source()` 背后只有
        // **一条** BufferedSource。JNI 解码器 newInstance 会把它读干并 close，之后
        // `resources.source()` 只能拿到 EOF/已关闭的流，PNG 判型就再也做不成了。
        // 更要紧的是：fork 的 image-decoder 不处理调色板 PNG 的 tRNS 透明表，透明索引
        // 对应的颜色（Komga/EPUB 里常是纯黑）会被原样画成不透明像素 —— 「黑字 + 透明底」
        // 的 logo 整块变黑，之后再怎么压白底也救不回来（alpha 在解码这一步就没了）。
        // BitmapFactory 对 tRNS/PLTE 处理正确，代价只是一次平台解码。
        if (coverStream == null) {
            readPngBytes()?.let { return decodePng(it) }
        }

        val decoder = resources.sourceOrNull()?.use {
            coverStream.use { coverStream ->
                // Komiho: 深度裁剪边缘 —— 只在裁边开启时把 aggressiveCrop 传给 fork 的
                // image-decoder（容差 0.125% → 10%，水印/页码不再挡住裁边）。
                ImageDecoder.newInstance(
                    coverStream ?: it.inputStream(),
                    options.cropBorders,
                    options.cropBorders && options.cropBordersAggressive,
                    displayProfile,
                )
            }
        }
        // SY <--
        // SY: newInstance 返回 null / 宽高 0 = 流为空或截断（0 字节流能合法通过 fetch 阶段）。
        // 报错附上来源大小与常见原因：阅读页空流多为「章节回收竞态」，读取层
        // （ArchivePageLoader.readEntryBytes）会抛出更明确的异常，这里通常是兜底；
        // 若来源是文件且大小正常，则更像图片数据本身损坏。
        check(decoder != null && decoder.width > 0 && decoder.height > 0) {
            val srcDesc = runCatching { "file(${resources.file().toFile().length()}B)" }
                .getOrElse { "stream" }
            "Failed to initialize decoder: source=$srcDesc, size=${decoder?.width}x${decoder?.height}. " +
                "流为空/截断或图片数据损坏（阅读页偶发+刷新即好=章节回收竞态，读取层会报明确原因）"
        }

        val dec = decoder!!
        return decodeWith(dec.width, dec.height) { sampleSize ->
            try {
                dec.decode(sampleSize = sampleSize)
            } finally {
                dec.recycle()
            }
        }
    }

    /** 若当前页是 PNG，返回整份字节（peek 判型不消耗流）；其它格式/读取失败返回 null。 */
    private fun readPngBytes(): ByteArray? {
        val source = runCatching { resources.sourceOrNull() }.getOrNull() ?: return null
        // 判型失败：此时流一个字节都没被消耗，回落 JNI 解码器完全安全。
        val type = try {
            source.peek().use { ImageUtil.findImageType(it.inputStream()) }
        } catch (e: Throwable) {
            null
        }
        if (type != ImageUtil.ImageType.PNG) return null
        // 确认是 PNG 之后整份读出。这里**不再兜异常**：读失败说明输入源本身有问题，
        // 而且流已经被消耗掉一部分，再硬塞给 JNI 只会得到「Failed to initialize decoder」
        // 这种与真实原因无关的报错。让原始异常向上抛，Coil 报的才是真原因。
        return source.readByteArray()
    }

    /**
     * PNG 走平台解码：先 inJustDecodeBounds 拿原始尺寸（[decodeWith] 要用它算采样率），
     * 再由 [decodeWith] 里的 lambda 按采样率真正解码。字节已整份在内存里，两趟都从内存读。
     */
    private fun decodePng(bytes: ByteArray): DecodeResult {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Failed to decode image: PNG 头解析失败（${bytes.size}B）"
        }
        return decodeWith(bounds.outWidth, bounds.outHeight, formatCarriesAlpha = true) { sampleSize ->
            decodePlatformPng(bytes, sampleSize)
        }
    }

    /** 用平台解码器解 PNG（支持调色板 tRNS 透明），失败返回 null（由 [decodeWith] 报错）。 */
    private fun decodePlatformPng(bytes: ByteArray, sampleSize: Int): Bitmap? = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    /**
     * 完成采样 / 解码 / 增强 / 硬件位图转换，返回结果。
     * 封面归档特例与普通页共用此逻辑。
     *
     * [decode] 在算好采样率之后才调用 —— PNG 交平台解码，其余交 JNI。
     */
    private fun decodeWith(
        srcWidth: Int,
        srcHeight: Int,
        formatCarriesAlpha: Boolean = false,
        decode: (sampleSize: Int) -> Bitmap?,
    ): DecodeResult {
        // Komiho:「从 0 开始解码」的计时起点 —— 角标显示的实际计算耗时从这里算起，
        // 等锁与线程排队会被剔除（见 EnhanceTimings）。
        val decodeStart = android.os.SystemClock.uptimeMillis()

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        // MihonSY: when enhancing, decode at up to 2x the view size (capped) so the
        // enhancer has real source detail to work on. dstWidth may be Int.MAX_VALUE
        // before the view lays out; clamp first to avoid overflow.
        // Webtoon strips (extreme aspect ratio): the height must NOT limit sampling —
        // sampling by height would crush the width (1080x8000 sampled to 2048-tall ->
        // 270px wide). For strips the width is the display-critical dimension, so we
        // keep the FULL source height (no height-based downsampling) and only sample
        // by width. A4K (GPU) handles the full-height texture; Ultra's 2x output is
        // then naturally rejected by the texture-size guard if it would exceed the
        // GPU limit.
        val isTallStrip = srcHeight > 0 && srcWidth > 0 &&
            srcHeight.toFloat() / srcWidth.toFloat() > 2.5f
        // Komiho: 增强时解码/预缩目标。
        // 默认 = 视图尺寸（capped 2048）：AI 只对 r>1（显示需放大）的源图跑（下方 skipAi 门），
        // 这类图 < 2×视图，inSampleSize 无损失。
        // 开启「大图强制 AI 增强」（aiBypassFitGate）后放宽到统一 2048 上限——大源图 AI 也能
        // 吃到 2048 内的真实细节；MP 输出门（enhance() 里）始终兜底防 OOM。
        // AI 2x 结果默认直接交 SSIV 缩放显示；aiAreaDownscale 开启时在增强后按视图尺寸
        // 面积回缩（见下方 areaDownscaleToDisplay 调用）。
        val preferences = Injekt.get<ReaderPreferences>()
        // Komiho: 「增强是否真的会跑」—— 12 以下的伽马也会带 enhanced(true) 打开这条解码链
        // （见 ReaderPageImageView / PagerPagePreparer），但它不改分辨率。若这里按
        // options.enhanced 选目标，单开伽马就会把解码目标从「视图尺寸」变成
        // min(视图, 2048)，宽屏（>2048px）上白白多降一档采样。按「真会跑增强」判断。
        val enhanceActive = options.enhanced &&
            (preferences.enhancementMode.get() != 0 || preferences.denoiseLevel.get() != 0)
        val (targetW, targetH) = if (enhanceActive) {
            if (isTallStrip) {
                // 采样高度仍用 srcHeight（高度不约束解码；负数不能流进 calculateInSampleSize
                // —— coil3 DecodeUtils 对它的语义未知）。
                (if (preferences.aiBypassFitGate.get()) MAX_ENHANCE_SOURCE_DIMENSION else enhanceTarget(dstWidth)) to srcHeight
            } else if (preferences.aiBypassFitGate.get()) {
                MAX_ENHANCE_SOURCE_DIMENSION to MAX_ENHANCE_SOURCE_DIMENSION
            } else {
                enhanceTarget(dstWidth) to enhanceTarget(dstHeight)
            }
        } else {
            dstWidth to dstHeight
        }

        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = targetW,
            dstHeight = targetH,
            scale = options.scale,
        )

        var bitmap = decode(sampleSize)
        check(bitmap != null) { "Failed to decode image" }

        // Komiho (2026-11-05): 透明图**必须在增强之前**压白底，这是整条链路上唯一有效的点。
        //
        // 增强（AI / Lanczos 的 RGB 内核）会把 alpha 一律写成 255，而透明像素的 RGB 是 0：
        // 透明区因此变成**不透明黑**。等增强跑完再压白底已经晚了 —— 那时画到白底上的仍是
        // 黑像素，白底被完全盖住。判断本身也必须在解码后、增强前做，之后 alpha 就没了。
        // 判据两条并集：像素抽样（任何格式）+「源格式带 alpha 通道」（PNG 独有）。
        // 抽样是网格，透明区很窄时可能漏检；PNG 这边 `hasAlpha()` 为 true 就说明文件本身
        // 带 alpha 通道（BitmapFactory 按 PNG 的 alpha 类型置位），这种情况一律压白底，
        // 代价只是一次位图拷贝，换来的是「不会再黑」。
        val srcTransparent = ImageUtil.hasTransparentPixels(bitmap) ||
            (formatCarriesAlpha && bitmap.hasAlpha())
        if (srcTransparent) {
            val flattened = ImageUtil.flattenOnWhite(bitmap)
            if (flattened != null && flattened !== bitmap) {
                bitmap.recycle()
                bitmap = flattened
            }
        }

        // Komiho (2026-09-26 定稿): AI 跳过门 —— 解码后、预缩前算 fit 比例 r。
        // 普通页 r = min(视图宽/图宽, 视图高/图高)：r ≤ 1 = 屏幕只会缩小显示，源图高频细节
        // 本来就够，AI 2x 再缩回视图是白做往返（真机实测 2890×4096 源图净画质不升反降），
        // 直接跳过，原图交 SSIV 缩小显示（双线性无锐化负瓣，不会锐化摩尔纹）。
        // 长条页 r 只看宽度（dstWidth/图宽）—— 高度是滚动维度，不参与 fit；判据仍是
        // 「解码宽度已 ≥ 视图宽度就跳过」。r > 1 才喂 AI：图 < 2×视图宽，2x 输出默认
        // 直接交 SSIV；aiAreaDownscale 开启时按视图尺寸面积回缩（防摩尔纹）。
        // ⚠️ 必须在预缩块之前算 —— 预缩会动 bitmap 尺寸，污染 r 的语义。
        val fitRatio = if (isTallStrip) {
            dstWidth / bitmap.width.toFloat()
        } else {
            minOf(
                dstWidth / bitmap.width.toFloat(),
                dstHeight / bitmap.height.toFloat(),
            )
        }
        val skipAi = options.enhanced &&
            preferences.enhancementMode.get() == 5 &&
            !preferences.aiBypassFitGate.get() &&
            !bitmap.isRecycled &&
            fitRatio <= 1f

        // Komiho: 把「长边 ≤ MAX_ENHANCE_SOURCE_DIMENSION」这个意图真正落实。
        // 采样率只能取 2 的幂，所以实际解出的图可能比目标大最多一倍：源 5780×4096、
        // 目标 2048 → calculateInSampleSize 只能给 2 → 解出 2890×2048，面积翻倍，
        // AI 推理耗时与显存随之翻倍（实测单页 2.1s，本应约 1.05s）。
        // 这里对非长条页按目标尺寸等比缩一次。长条（isTallStrip）绝不参与：它只按宽度
        // 采样、高度是全高，宽度才是显示关键维度，按尺寸缩会把画面压成窄条
        // （800×9927 若按长边 2048 缩会变成 165×2048）。几何判断，与阅读模式无关。
        if (enhanceActive && !isTallStrip) {
            try {
                val scaleDown = minOf(
                    1f,
                    targetW / bitmap.width.toFloat(),
                    targetH / bitmap.height.toFloat(),
                )
                if (scaleDown < 1f) {
                    val scaledWidth = max(1, (bitmap.width * scaleDown).roundToInt())
                    val scaledHeight = max(1, (bitmap.height * scaleDown).roundToInt())
                    val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                    if (scaled !== bitmap) {
                        bitmap.recycle()
                        bitmap = scaled
                    }
                }
            } catch (e: Throwable) {
                // 缩不下来就按原尺寸继续（只是慢一些），不影响正确性。
            }
        }

        // MihonSY: run Lanczos3 enhancement synchronously on the decoded bitmap. The
        // decoder is called on Coil's background thread, so this never blocks the UI.
        // Any failure leaves the original bitmap — a black frame can never appear.
        // Applies to every image size (strips included, sampled by width only).
        if (options.enhanced) {
            try {
                // Komiho: 降噪已独立于增强档位——mode 0 + 降噪开启也要进链路（只降噪不增强）。
                if (preferences.enhancementMode.get() != 0 || preferences.denoiseLevel.get() != 0) {
                    if (skipAi) {
                        // Komiho: AI 跳过门命中 —— 登记 skip，角标显示「跳过」，原图直出。
                        EnhanceTimings.markSkipped(options.pageIndex)
                    } else {
                        // Komiho 诊断：来源标识 = 谁发起的 + 第几页。
                        // 用来判断并发增强请求是「同一页被算了两遍」还是「相邻两页各一次」——
                        // 同包内取 top-level 扩展，无需 import。
                        val sourceTag = (if (options.prewarm) "prewarm" else "holder") +
                            "#${options.pageIndex}"
                        // Komiho: 角标口径 = 「解码 + 增强」的**实际计算**耗时（剔除等锁）。
                        // `gpuWaitMs` 是**两段**等锁之和（MihonSyEnhancer 给的 totalWaitMs）——
                        // 只剔第一段不够：nativeProcess 内部还有一次 g_lock 排队。
                        // 在同线程用局部变量收集回调值，避免并发页互相串号。
                        var enhanceOk = false
                        var gpuWaitMs = 0L
                        var enhanced = MihonSyEnhancer.enhance(
                            bitmap,
                            preferences,
                            onComplete = { ok, _, wait ->
                                enhanceOk = ok
                                gpuWaitMs = wait
                            },
                            sourceTag = sourceTag,
                            // Komiho: 页号透传给增强器 —— 角标按页登记引擎，别让并发页互相覆盖。
                            pageIndex = options.pageIndex,
                        )
                        // Komiho (2026-09-30): AI 面积回缩（防摩尔纹）。SSIV 对 bitmap 源是
                        // 整图双线性缩小、无低通，AI 2x 的高频网点与屏幕像素网格拍频出摩尔纹；
                        // 相对显示区缩比 < 0.85 时用面积核压回显示带通。放在
                        // EnhanceTimings.put 之前 —— 回缩是真实计算耗时，角标要计入。
                        // 只对 mode 5 生效（含引擎失败回落 Lanczos 的页）；webtoon 原始尺寸
                        // 模式 1:1 显示无缩小、回缩只会丢细节，跳过。
                        if (
                            enhanced != null &&
                            enhanced !== bitmap &&
                            preferences.enhancementMode.get() == 5 &&
                            preferences.aiAreaDownscale.get() &&
                            !options.originalSizeDisplay
                        ) {
                            val downscaled = MihonSyEnhancer.areaDownscaleToDisplay(
                                enhanced,
                                viewWidth = dstWidth,
                                viewHeight = dstHeight,
                                isTallStrip = isTallStrip,
                                strength = preferences.aiAreaDownscaleStrength.get() / 100f,
                            )
                            if (downscaled !== enhanced) {
                                enhanced.recycle()
                                enhanced = downscaled
                            }
                        }
                        // Komiho (2026-10-25): 只有**真出了 AI 结果**才登记计时。
                        // CPU 兜底（Lanczos）也会返回一张新位图，以前据此登记，于是上层把
                        // 「CPU 放大」当成「已增强」（timing != null ⇒ currentImageEnhanced
                        // = true）⇒ 停顿补算永远不触发 —— 用户看到的就是「停在 CPU OK
                        // 的那一页再也不会重建」。
                        val aiEngine = Waifu2x.engineFor(options.pageIndex)
                        if (enhanceOk && aiEngine != Waifu2x.EngineKind.NONE) {
                            EnhanceTimings.put(
                                options.pageIndex,
                                (android.os.SystemClock.uptimeMillis() - decodeStart) -
                                    gpuWaitMs.coerceAtLeast(0L),
                            )
                        }
                        if (enhanced != null && enhanced !== bitmap && !enhanced.isRecycled) {
                            bitmap.recycle()
                            bitmap = enhanced
                        }
                    }
                }
            } catch (e: Throwable) {
                // Fall back to the original on ANY failure (incl. OutOfMemoryError,
                // which Exception does not catch — an OOM here used to crash the
                // whole reader on large downloaded pages).
            }
        }

        // Komiho: 伽马（Android 12 以下）。12+ 由显示层 RuntimeShader 做（即改即见），
        // 这里只负责无法用 ColorMatrix 表达幂函数的旧系统。
        // 位置：灰阶量化**之后**（否则会被量化吸掉）、硬件位图转换**之前**（硬件位图禁止读写像素）。
        // 只作用于阅读器的页面请求（customDecoder / enhanced）：封面、导出图片都不带这两个标记，
        // 否则封面会被阅读器的伽马设置污染。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            (options.customDecoder || options.enhanced)
        ) {
            val gamma = preferences.gammaValueOrNull()
            if (gamma != null) {
                // 滤镜失败就原样输出，绝不因此让整页读不出来。
                runCatching { ToneCurveLut.apply(bitmap, gamma) }
            }
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            options.bitmapConfig == Bitmap.Config.HARDWARE &&
            ImageUtil.canUseHardwareBitmap(bitmap)
        ) {
            try {
                val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
                if (hwBitmap != null) {
                    bitmap.recycle()
                    bitmap = hwBitmap
                }
            } catch (e: Throwable) {
                // Keep the software bitmap on failure.
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            // MihonSY: when enhancement is enabled this decoder handles ALL formats so
            // JPG/PNG pages also get enhanced; otherwise only the system-unsupported ones.
            return if (options.enhanced || options.customDecoder || isApplicable(result.source.source())) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().buffered().use { stream ->
                ImageUtil.findImageType(stream)
            }
            // SY -->
            source.peek().inputStream().use { stream ->
                if (CbzCrypto.detectCoverImageArchive(stream)) return true
            }
            // SY <--
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL -> true
                ImageUtil.ImageType.HEIF -> Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null

        /**
         * MihonSY: enhanced-decode target for one dimension. The view size is clamped
         * (it can be Int.MAX_VALUE before layout) and used as-is — NO extra 2x here,
         * because Lanczos3 itself already scales the image up (1.5x/2x/3x). Decoding
         * at 2x AND scaling by Lanczos would multiply the output to 3-6x the view,
         * most of which gets scaled back down for display: a huge waste of CPU.
         * Keeping the decode at view size means the final enhanced image is exactly
         * the configured scale (e.g. 1.5x) — supersampled for display, ~4x faster.
         */
        private fun enhanceTarget(viewDimension: Int): Int {
            if (viewDimension <= 0 || viewDimension == Int.MAX_VALUE) return MAX_ENHANCE_SOURCE_DIMENSION
            return min(viewDimension, MAX_ENHANCE_SOURCE_DIMENSION)
        }

        const val MAX_ENHANCE_SOURCE_DIMENSION = 2048
    }
}
