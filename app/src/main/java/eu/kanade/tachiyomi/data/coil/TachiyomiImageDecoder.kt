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
import eu.kanade.tachiyomi.util.MihonSyEnhancer
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import eu.kanade.tachiyomi.util.storage.CbzCrypto.getCoverStream
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
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
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

        check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }

        val srcWidth = decoder.width
        val srcHeight = decoder.height

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        // MihonSY: when enhancement is enabled, decode at a higher resolution than the
        // target view size (at most 2x the target, or full size if the image is small)
        // so the enhancement works on more source detail — enhancing an already
        // view-size-sampled bitmap yields no visible quality gain. Memory is capped by
        // the 2x bound; long-strip webtoons are still downsampled from absurd sizes.
        val sampleSize = if (options.enhanced && dstWidth > 0 && dstHeight > 0) {
            val enhanceDstWidth = min(dstWidth * 2, MAX_ENHANCE_SOURCE_DIMENSION)
            val enhanceDstHeight = min(dstHeight * 2, MAX_ENHANCE_SOURCE_DIMENSION)
            DecodeUtils.calculateInSampleSize(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = enhanceDstWidth,
                dstHeight = enhanceDstHeight,
                scale = options.scale,
            )
        } else {
            DecodeUtils.calculateInSampleSize(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = dstWidth,
                dstHeight = dstHeight,
                scale = options.scale,
            )
        }

        var bitmap = decoder.decode(sampleSize = sampleSize)
        decoder.recycle()

        check(bitmap != null) { "Failed to decode image" }

        // MihonSY: on-the-fly image enhancement. Runs at the higher decoded resolution
        // so enhancement quality is not degraded by Coil's view-size sampling. The
        // enhanced bitmap replaces the original and is cached by Coil like any other
        // decoded image, which also makes scrolling back to a page cheap.
        if (options.enhanced && bitmap.isRecycled.not()) {
            val enhanced = try {
                val prefs = Injekt.get<ReaderPreferences>()
                if (prefs.enhancementMode.get() != 0) {
                    MihonSyEnhancer.enhance(bitmap, prefs)
                } else {
                    null
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "On-the-fly enhancement failed" }
                null
            }
            if (enhanced != null && enhanced !== bitmap) {
                bitmap.recycle()
                bitmap = enhanced
            }
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            options.bitmapConfig == Bitmap.Config.HARDWARE &&
            ImageUtil.canUseHardwareBitmap(bitmap)
        ) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
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

        // MihonSY: cap the resolution used for on-the-fly enhancement (long edge).
        // Keeps memory bounded while still providing ~2x the view size of source detail.
        const val MAX_ENHANCE_SOURCE_DIMENSION = 2048
    }
}
