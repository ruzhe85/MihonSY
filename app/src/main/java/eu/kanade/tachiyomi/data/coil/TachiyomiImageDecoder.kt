package eu.kanade.tachiyomi.data.coil

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import eu.kanade.tachiyomi.util.MihonSyEnhancer
import eu.kanade.tachiyomi.util.enhancer.ImageEnhancementCache
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import eu.kanade.tachiyomi.util.storage.CbzCrypto.getCoverStream
import eu.kanade.tachiyomi.util.system.GLUtil
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import mihon.core.common.archive.archiveReader
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import kotlin.math.min

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system,
 * and applies on-the-fly image enhancement (Anime4K / Lanczos3) with a disk cache.
 *
 * Architecture ported from mihon_img_upscale (HaoweiLi97/mihon_img_upscale): decoding and
 * enhancement run under a [Semaphore] so the GPU/CPU enhancement work is never contended
 * across Coil's thread pool, enhanced results are cached on disk per (manga, chapter, page,
 * config) and the original bitmap is always returned on any failure — a black frame can
 * never reach the UI.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = Injekt.get<Application>()

    override suspend fun decode(): DecodeResult? {
        return resources.source().use { source ->
            decodeSemaphore.withPermit {
                try {
                    var bitmap: Bitmap? = null
                    var sampleSize = 1

                    // SY --> encrypted cover archive support (CbzCrypto)
                    var coverStream: BufferedInputStream? = null
                    if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
                        if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                            coverStream = UniFile.fromFile(resources.file().toFile())
                                ?.archiveReader(context = context)
                                ?.getCoverStream()
                        }
                    }
                    val stream = coverStream ?: source.inputStream()
                    // SY <--

                    // 1. Attempt decoding with native ImageDecoder (AVIF/JXL/HEIF and others)
                    val nativeDecoder = try {
                        ImageDecoder.newInstance(stream, options.cropBorders, displayProfile)
                    } catch (e: Exception) {
                        null
                    }

                    if (nativeDecoder != null && nativeDecoder.width > 0 && nativeDecoder.height > 0) {
                        try {
                            val srcWidth = nativeDecoder.width
                            val srcHeight = nativeDecoder.height
                            val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                            val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                            // MihonSY: when enhancement is enabled, decode at a higher
                            // resolution than the view size (2x, capped) so the enhancer
                            // works on real source detail — enhancing an already
                            // view-size-sampled bitmap yields no visible quality gain.
                            val (targetW, targetH) = if (options.enhanced) {
                                min(dstWidth * 2, MAX_ENHANCE_SOURCE_DIMENSION) to
                                    min(dstHeight * 2, MAX_ENHANCE_SOURCE_DIMENSION)
                            } else {
                                dstWidth to dstHeight
                            }

                            sampleSize = DecodeUtils.calculateInSampleSize(
                                srcWidth = srcWidth,
                                srcHeight = srcHeight,
                                dstWidth = targetW,
                                dstHeight = targetH,
                                scale = options.scale,
                            )
                            bitmap = nativeDecoder.decode(sampleSize = sampleSize)
                        } finally {
                            nativeDecoder.recycle()
                        }
                    }

                    // 2. Fallback to BitmapFactory for system-supported formats (JPG, PNG, WEBP, etc.)
                    if (bitmap == null) {
                        try {
                            val byteBuf = source.peek().readByteArray()
                            val ops = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, ops)

                            if (ops.outWidth > 0 && ops.outHeight > 0) {
                                val srcWidth = ops.outWidth
                                val srcHeight = ops.outHeight
                                val dstWidth = options.size.widthPx(options.scale) { srcWidth }
                                val dstHeight = options.size.heightPx(options.scale) { srcHeight }

                                // MihonSY: same higher-resolution decode when enhancing.
                                val (targetW, targetH) = if (options.enhanced) {
                                    min(dstWidth * 2, MAX_ENHANCE_SOURCE_DIMENSION) to
                                        min(dstHeight * 2, MAX_ENHANCE_SOURCE_DIMENSION)
                                } else {
                                    dstWidth to dstHeight
                                }

                                sampleSize = DecodeUtils.calculateInSampleSize(
                                    srcWidth = srcWidth,
                                    srcHeight = srcHeight,
                                    dstWidth = targetW,
                                    dstHeight = targetH,
                                    scale = options.scale,
                                )

                                val decodeOps = BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize
                                    inPreferredConfig = if (options.bitmapConfig == Bitmap.Config.HARDWARE) {
                                        Bitmap.Config.ARGB_8888 // Decode to software first
                                    } else {
                                        options.bitmapConfig
                                    }
                                }
                                bitmap = BitmapFactory.decodeByteArray(byteBuf, 0, byteBuf.size, decodeOps)
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: BitmapFactory fallback failed" }
                        }
                    }

                    if (bitmap == null) {
                        logcat(LogPriority.ERROR) { "TachiyomiImageDecoder: Failed to decode bitmap via all methods" }
                        return@withPermit null
                    }

                    // --- Enhancement Integration (ported from mihon_img_upscale) ---
                    if (options.enhanced) {
                        val preferences = Injekt.get<ReaderPreferences>()
                        val enhancementMode = preferences.enhancementMode.get()
                        if (enhancementMode != 0) {
                            val mangaId = options.mangaId
                            val chapterId = options.chapterId
                            val pageIndex = options.pageIndex
                            val pageVariant = options.pageVariant

                            if (mangaId != -1L && chapterId != -1L && pageIndex != -1) {
                                ImageEnhancementCache.init(context)

                                val configHash = ImageEnhancementCache.getConfigHash(
                                    enhancementMode = enhancementMode,
                                    anime4kMode = preferences.anime4kMode.get(),
                                    lanczosScale = preferences.lanczosScale.get(),
                                )

                                // Check cache first
                                var usedCache = false
                                val cachedFile = ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
                                if (cachedFile != null) {
                                    try {
                                        val cachedBitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                                        if (cachedBitmap != null && ImageEnhancementCache.isDisplayable(cachedBitmap)) {
                                            bitmap.recycle()
                                            bitmap = cachedBitmap
                                            usedCache = true
                                        } else {
                                            cachedBitmap?.recycle()
                                            ImageEnhancementCache.removeCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
                                            logcat(LogPriority.WARN) { "TachiyomiImageDecoder: Removed invalid enhanced cache for page $pageIndex" }
                                        }
                                    } catch (e: Exception) {
                                        logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to decode cached enhanced image" }
                                    }
                                }

                                if (!usedCache && !ImageEnhancementCache.isSkipped(mangaId, chapterId, pageIndex, configHash, pageVariant)) {
                                    try {
                                        val enhanced = MihonSyEnhancer.enhance(bitmap, preferences)
                                        if (enhanced != null && enhanced !== bitmap) {
                                            val finalBitmap = enforceTextureLimit(enhanced)
                                            if (ImageEnhancementCache.isDisplayable(finalBitmap)) {
                                                ImageEnhancementCache.saveToCache(
                                                    mangaId, chapterId, pageIndex, configHash, finalBitmap, pageVariant,
                                                )
                                                bitmap.recycle()
                                                bitmap = finalBitmap
                                            } else {
                                                logcat(LogPriority.ERROR) { "TachiyomiImageDecoder: Page $pageIndex produced a nearly transparent result, keeping original" }
                                                if (finalBitmap !== bitmap) finalBitmap.recycle()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Failed to enhance image on-the-fly" }
                                    }
                                }
                            }
                        }
                    }
                    // --- End Enhancement Integration ---

                    if (options.bitmapConfig == Bitmap.Config.HARDWARE && ImageUtil.canUseHardwareBitmap(bitmap)) {
                        val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
                        if (hwBitmap != null) {
                            bitmap.recycle()
                            bitmap = hwBitmap
                        }
                    }

                    DecodeResult(
                        image = bitmap.asImage(),
                        isSampled = sampleSize > 1,
                    )
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "TachiyomiImageDecoder: Critical failure during decode" }
                    null
                }
            }
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.customDecoder || isApplicable(result.source.source())) {
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

        /** Serialises decode + enhancement so GPU/CPU enhancement work never contends. */
        private val decodeSemaphore = Semaphore(1)

        /**
         * MihonSY: cap on the long edge used for on-the-fly enhancement decoding.
         * Keeps memory bounded while still providing ~2x the view size of source detail.
         */
        const val MAX_ENHANCE_SOURCE_DIMENSION = 2048
    }

    /**
     * MihonSY: scales an enhanced bitmap down if it exceeds the device's GL texture
     * limit, so it stays displayable by SubsamplingScaleImageView. Returns the input
     * if no scaling was needed (caller keeps ownership).
     */
    private fun enforceTextureLimit(input: Bitmap): Bitmap {
        val limit = GLUtil.DEVICE_TEXTURE_LIMIT
        if (limit <= 0 || (input.width <= limit && input.height <= limit)) return input
        val ratio = min(limit.toFloat() / input.width, limit.toFloat() / input.height)
        val scaled = Bitmap.createScaledBitmap(
            input,
            (input.width * ratio).toInt().coerceAtLeast(1),
            (input.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== input) input.recycle()
        return scaled
    }
}
