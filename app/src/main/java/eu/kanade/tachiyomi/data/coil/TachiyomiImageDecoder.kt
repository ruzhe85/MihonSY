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
        val decoder = resources.sourceOrNull()?.use {
            coverStream.use { coverStream ->
                ImageDecoder.newInstance(coverStream ?: it.inputStream(), options.cropBorders, displayProfile)
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

        return decodeWith(decoder!!)
    }

    /**
     * 拿到已初始化的 [decoder] 后，完成采样 / 解码 / 增强 / 硬件位图转换，返回结果。
     * 封面归档特例与普通页共用此逻辑。
     */
    private fun decodeWith(decoder: ImageDecoder): DecodeResult {
        // Komiho:「从 0 开始解码」的计时起点 —— 角标显示的实际计算耗时从这里算起，
        // 等锁与线程排队会被剔除（见 EnhanceTimings）。
        val decodeStart = android.os.SystemClock.uptimeMillis()
        val srcWidth = decoder.width
        val srcHeight = decoder.height

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
        val (targetW, targetH) = if (options.enhanced) {
            if (isTallStrip) {
                enhanceTarget(dstWidth) to srcHeight
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

        var bitmap = decoder.decode(sampleSize = sampleSize)
        decoder.recycle()

        check(bitmap != null) { "Failed to decode image" }

        // Komiho: 把「长边 ≤ MAX_ENHANCE_SOURCE_DIMENSION」这个意图真正落实。
        // 采样率只能取 2 的幂，所以实际解出的图可能比目标大最多一倍：源 5780×4096、
        // 目标 2048 → calculateInSampleSize 只能给 2 → 解出 2890×2048，面积翻倍，
        // AI 推理耗时与显存随之翻倍（实测单页 2.1s，本应约 1.05s）。
        // 这里对非长条页按目标尺寸等比缩一次。长条（isTallStrip）绝不参与：它只按宽度
        // 采样、高度是全高，宽度才是显示关键维度，按尺寸缩会把画面压成窄条
        // （800×9927 若按长边 2048 缩会变成 165×2048）。几何判断，与阅读模式无关。
        if (options.enhanced && !isTallStrip) {
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
                val preferences = Injekt.get<ReaderPreferences>()
                if (preferences.enhancementMode.get() != 0) {
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
                    val enhanced = MihonSyEnhancer.enhance(
                        bitmap,
                        preferences,
                        onComplete = { ok, _, wait ->
                            enhanceOk = ok
                            gpuWaitMs = wait
                        },
                        sourceTag = sourceTag,
                        // Komiho: 页号透传给增强器 —— 角标按页登记引擎，别让并发页互相覆盖。
                        pageIndex = options.pageIndex,
                        // Komiho: 把「适应屏幕」的目标尺寸传进去，AI 2x 后由软件层 Lanczos3 缩回，
                        // 避免 SSIV 双线性把网点糊掉。
                        targetWidth = targetW,
                        targetHeight = targetH,
                    )
                    if (enhanceOk) {
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
            } catch (e: Throwable) {
                // Fall back to the original on ANY failure (incl. OutOfMemoryError,
                // which Exception does not catch — an OOM here used to crash the
                // whole reader on large downloaded pages).
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
