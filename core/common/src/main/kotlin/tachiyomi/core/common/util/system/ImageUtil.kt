package tachiyomi.core.common.util.system

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.blue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.exifinterface.media.ExifInterface
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.system.GLUtil
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageUtil {

    fun isImage(name: String?, openStream: (() -> InputStream)? = null): Boolean {
        if (name == null) return false
        // SY -->
        if (File(name).extension.equals("cbi", ignoreCase = true)) return true
        // SY <--

        // SY: 大小写归一 + jpeg 别名 —— ImageType.JPEG.extension 只有 "jpg"，此前
        // ".jpeg"（以及一切大写扩展名如 ".JPG"）会被判成非图片，导致封面提取把条目
        // 过滤光（本地来源封面受影响；阅读器不走此过滤
        // 所以能正常阅读）。
        val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
            .let { if (it == "jpeg") "jpg" else it }
        return ImageType.entries.any { it.extension == extension } || openStream?.let { findImageType(it) } != null
    }

    fun findImageType(openStream: () -> InputStream): ImageType? {
        return openStream().use { findImageType(it) }
    }

    fun findImageType(stream: InputStream): ImageType? {
        return try {
            when (getImageType(stream)?.format) {
                Format.Avif -> ImageType.AVIF
                Format.Gif -> ImageType.GIF
                Format.Heif -> ImageType.HEIF
                Format.Jpeg -> ImageType.JPEG
                Format.Jxl -> ImageType.JXL
                Format.Png -> ImageType.PNG
                Format.Webp -> ImageType.WEBP
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getExtensionFromMimeType(mime: String?, openStream: () -> InputStream): String {
        val type = mime?.let { ImageType.entries.find { it.mime == mime } } ?: findImageType(openStream)
        return type?.extension ?: "jpg"
    }

    fun isAnimatedAndSupported(source: BufferedSource): Boolean {
        return try {
            val type = getImageType(source.peek().inputStream()) ?: return false
            // https://coil-kt.github.io/coil/getting_started/#supported-image-formats
            when (type.format) {
                Format.Gif -> true
                // Animated WebP on Android 9+
                Format.Webp -> type.isAnimated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                // Animated Heif on Android 11+
                Format.Heif -> type.isAnimated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getImageType(stream: InputStream): tachiyomi.decoder.ImageType? {
        val bytes = ByteArray(32)

        val length = if (stream.markSupported()) {
            stream.mark(bytes.size)
            stream.read(bytes, 0, bytes.size).also { stream.reset() }
        } else {
            stream.read(bytes, 0, bytes.size)
        }

        if (length == -1) {
            return null
        }

        return ImageDecoder.findType(bytes)
    }

    enum class ImageType(val mime: String, val extension: String) {
        AVIF("image/avif", "avif"),
        GIF("image/gif", "gif"),
        HEIF("image/heif", "heif"),
        JPEG("image/jpeg", "jpg"),
        JXL("image/jxl", "jxl"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
    }

    // SY --> Komiho: 封面/缩略图的统一采样解码入口。
    // 不能只用 BitmapFactory：AVIF 的系统解码走厂商实现，部分机型完全解不出来 ——
    // inJustDecodeBounds 给 0×0、decode 返回 null，表现为「封面/缩略图不显示，但点开阅读器
    // 又能正常看图」（阅读器走 fork 的 ImageDecoder，与系统解码无关）。
    // 这里按格式分派：AVIF/JXL 直接交给 ImageDecoder（与阅读器同一引擎、同一口径），
    // 其余先走系统解码（更快、支持 EXIF），失败再兜底 ImageDecoder。
    // [open] 必须可重复打开（本函数最多读两次/三次同一张图）。
    fun decodeSampled(open: () -> InputStream?, maxPx: Int): Bitmap? {
        val type = runCatching {
            open()?.use { findImageType(BufferedInputStream(it)) }
        }.getOrNull()
        return when (type) {
            ImageType.AVIF, ImageType.JXL -> decodeWithImageDecoder(open, maxPx)
            else -> decodeWithBitmapFactory(open, maxPx) ?: decodeWithImageDecoder(open, maxPx)
        }
    }

    // SY --> Komiho: 只读宽高（不解像素）。系统 bounds 在部分机型上对 AVIF 恒给 0×0，
    // 此时改用 fork 的 ImageDecoder 取容器元数据 —— 仍然不解像素，只多一次流打开与头部解析。
    // 返回 null = 两条路都读不出尺寸。
    //
    // ⚠️ 绝对不能把 Java 流交给 BitmapFactory.decodeStream（原因见下方常量区的说明）。
    fun readImageSize(open: () -> InputStream?): Pair<Int, Int>? {
        val bounds = runCatching {
            val head = readHead(open, BOUNDS_HEAD_BYTES) ?: return@runCatching null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(head, 0, head.size, options)
            options.outWidth to options.outHeight
        }.getOrNull()
        if (bounds != null && bounds.first > 0 && bounds.second > 0) return bounds
        return runCatching {
            val decoder = open()?.use { ImageDecoder.newInstance(it) } ?: return@runCatching null
            try {
                if (decoder.width > 0 && decoder.height > 0) decoder.width to decoder.height else null
            } finally {
                runCatching { decoder.recycle() }
            }
        }.getOrNull()
    }

    /*
     * Komiho (2026-11-03): 为什么这里宁可多读一次字节，也不直接 BitmapFactory.decodeStream(stream)。
     *
     * 现场：OPPO PKJ110 / Android 17 上打开一本 EPUB，进程必崩 SIGSEGV（开不开图像增强都一样）。
     * 系统给的 native 栈：
     * ```
     * __memmove_aarch64_nt
     * art::JNI<false>::GetByteArrayRegion
     * JavaInputStreamAdaptor::read          (libhwui.so)
     * FrontBufferedStream::peek / SkCodec::MakeFromStream
     * BitmapFactory.nativeDecodeStream
     * android.graphics.BitmapFactory.decodeStream
     * ImageUtil.readImageSize
     * ReaderViewModel.measurePageAspectRatio   ← 自动条漫检测
     * ```
     * 崩点在框架「Java 流 → native」的适配层（GetByteArrayRegion 的 memmove 越界），与图片数据
     * 本身无关；decodeByteArray 直接吃字节数组，完全不走那条适配层。
     *
     * 代价：只读尺寸时多读一次头部（BOUNDS_HEAD_BYTES，远小于一页图），换掉一处必崩的系统调用。
     */

    /** 探测尺寸只读这么多字节：JPEG 的 SOF、PNG 的 IHDR、WebP 的 VP8X 都在这个范围内。 */
    private const val BOUNDS_HEAD_BYTES = 1024 * 1024

    /** 整图解码的字节上限：超过按「解不了」处理，绝不为了解一张图把内存吃穿。 */
    private const val MAX_DECODE_BYTES = 64 * 1024 * 1024

    /** 读流的前 [maxBytes] 字节（读到 EOF 就提前停）。 */
    private fun readHead(open: () -> InputStream?, maxBytes: Int): ByteArray? = runCatching {
        open()?.use { stream ->
            val buf = ByteArray(maxBytes)
            var n = 0
            while (n < maxBytes) {
                val read = stream.read(buf, n, maxBytes - n)
                if (read <= 0) break
                n += read
            }
            if (n == 0) null else buf.copyOf(n)
        }
    }.getOrNull()

    /** 读入整段图片字节（超过 [MAX_DECODE_BYTES] 直接放弃，返回 null）。 */
    private fun readAllBytes(open: () -> InputStream?): ByteArray? = runCatching {
        open()?.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read <= 0) break
                if (out.size() + read > MAX_DECODE_BYTES) return@use null
                out.write(chunk, 0, read)
            }
            out.toByteArray()
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 系统解码：先探尺寸，再按 inSampleSize 采样解码（省内存）。走字节路径，不用 decodeStream。 */
    private fun decodeWithBitmapFactory(open: () -> InputStream?, maxPx: Int): Bitmap? = runCatching {
        val bytes = readAllBytes(open) ?: return@runCatching null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
        val sample = max(1, max(opts.outWidth, opts.outHeight) / maxPx)
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts2)
    }.getOrNull()

    /** fork 的 ImageDecoder 解码（libavif/libjxl/libheif）：按长边算采样比，解不出返回 null。 */
    private fun decodeWithImageDecoder(open: () -> InputStream?, maxPx: Int): Bitmap? = runCatching {
        val decoder = open()?.use { ImageDecoder.newInstance(it) } ?: return@runCatching null
        try {
            if (decoder.width <= 0 || decoder.height <= 0) return@runCatching null
            // 采样比取 2 的幂（与 Coil calculateInSampleSize 同口径），避免把非 2 的幂直塞 native。
            val longEdge = max(decoder.width, decoder.height)
            var sample = 1
            while (longEdge / (sample * 2) >= maxPx) sample *= 2
            decoder.decode(sampleSize = sample)
        } finally {
            runCatching { decoder.recycle() }
        }
    }.getOrNull()

    /**
     * Check whether the image is wide (which we consider a double-page spread).
     *
     * @return true if the width is greater than the height
     */
    fun isWideImage(imageSource: BufferedSource): Boolean {
        // Komiho: 尺寸读取带 JNI 兜底（部分机型系统 bounds 对 AVIF 恒给 0×0，会把宽页误判成单页）。
        val (width, height) = readImageSize { imageSource.peek().inputStream() } ?: return false
        return width > height
    }

    // SY --> Komiho: 整图解出（保持原始像素，不采样）：系统解码优先，失败再走 fork 的
    // ImageDecoder。用于双页拆分 / 旋转 / 合并这类「必须按原图几何操作」的场景 ——
    // 这些地方原先直接用 BitmapFactory，AVIF 在部分机型上解出来是 null，一拆双页就崩页。
    fun decodeFullBitmap(open: () -> InputStream?): Bitmap? {
        // 同样走字节路径，不用 decodeStream（原因见常量区说明）。
        val fast = runCatching {
            val bytes = readAllBytes(open) ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        return fast ?: decodeWithImageDecoder(open, Int.MAX_VALUE)
    }

    /** 从 [imageSource] 整图解出（不消耗源，取的是 peek 视图）。 */
    fun decodeFullBitmap(imageSource: BufferedSource): Bitmap? =
        decodeFullBitmap { imageSource.peek().inputStream() }
    // SY <--

    /**
     * Extract the 'side' part from [BufferedSource] and return it as [BufferedSource].
     */
    fun splitInHalf(imageSource: BufferedSource, side: Side, sidePadding: Int): BufferedSource {
        // Komiho: 解不出就原样返回（宁可显示整页，也不要抛异常把这一页变成错误页）。
        val imageBitmap = decodeFullBitmap(imageSource) ?: return imageSource
        val height = imageBitmap.height
        val width = imageBitmap.width

        val singlePage = Rect(0, 0, width / 2 + sidePadding, height)

        val half = createBitmap(width / 2 + sidePadding, height)
        val part = when (side) {
            Side.RIGHT -> Rect(width - width / 2, 0, width, height)
            Side.LEFT -> Rect(0, 0, width / 2, height)
        }
        half.applyCanvas {
            // Komiho (2026-11-04): 先铺纯白。输出是 JPEG（无 alpha），不铺的话
            // 带透明底的图（调色板 PNG 的 tRNS、普通 RGBA）整片会变成不透明黑。
            drawColor(Color.WHITE)
            drawBitmap(imageBitmap, part, singlePage, null)
        }
        val output = Buffer()
        half.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())

        return output
    }

    fun rotateImage(imageSource: BufferedSource, degrees: Float): BufferedSource {
        // Komiho: 解不出就原样返回，旋转失败不该变成错误页。
        val imageBitmap = decodeFullBitmap(imageSource) ?: return imageSource
        val rotated = rotateBitMap(imageBitmap, degrees)

        // 输出 JPEG，同样先压白底（透明区否则变黑）。
        val flattened = flattenOnWhite(rotated) ?: rotated
        val output = Buffer()
        flattened.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())

        return output
    }

    private fun rotateBitMap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Split the image into left and right parts, then merge them into a
     * new vertically-aligned image.
     */
    fun splitAndMerge(imageSource: BufferedSource, upperSide: Side): BufferedSource {
        // Komiho: 解不出就原样返回（与 splitInHalf 同口径）。
        val imageBitmap = decodeFullBitmap(imageSource) ?: return imageSource
        val height = imageBitmap.height
        val width = imageBitmap.width

        val result = createBitmap(width / 2, height * 2)
        result.applyCanvas {
            // Komiho (2026-11-04): 输出 JPEG，先铺纯白，避免透明区变黑。
            drawColor(Color.WHITE)
            // right -> upper
            val rightPart = when (upperSide) {
                Side.RIGHT -> Rect(width - width / 2, 0, width, height)
                Side.LEFT -> Rect(0, 0, width / 2, height)
            }
            val upperPart = Rect(0, 0, width / 2, height)
            drawBitmap(imageBitmap, rightPart, upperPart, null)
            // left -> bottom
            val leftPart = when (upperSide) {
                Side.LEFT -> Rect(width - width / 2, 0, width, height)
                Side.RIGHT -> Rect(0, 0, width / 2, height)
            }
            val bottomPart = Rect(0, height, width / 2, height * 2)
            drawBitmap(imageBitmap, leftPart, bottomPart, null)
        }

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())
        return output
    }

    enum class Side {
        RIGHT,
        LEFT,
    }
    // SY -->

    /**
     * Split the image into left and right parts, then merge them into a
     * new image with added center padding scaled relative to the height of the display view
     * to compensate for scaling.
     *
     * [canvasColor] 是阅读页的底色（`PagerConfig.pageCanvasColor`）：画布先铺这个颜色再画两半，
     * 于是透明像素与中间留白都落成页面底色。输出是 JPEG（无 alpha 通道），不铺的话 PNG 的透明区
     * 会被压成黑色。
     */
    fun addHorizontalCenterMargin(
        imageSource: BufferedSource,
        viewHeight: Int,
        canvasColor: Int = Color.WHITE,
    ): BufferedSource {
        // Komiho: 原来这里是 `?.decode()!!` —— 解不出直接 NPE 崩页；改为原样返回。
        val imageBitmap = decodeFullBitmap(imageSource) ?: return imageSource
        val height = imageBitmap.height
        val width = imageBitmap.width

        val centerPadding = 96 / (max(1, viewHeight) / height).coerceAtLeast(1)

        val leftSourcePart = Rect(0, 0, width / 2, height)
        val rightSourcePart = Rect(width / 2, 0, width, height)
        val leftTargetPart = Rect(0, 0, width / 2, height)
        val rightTargetPart = Rect(width / 2 + centerPadding, 0, width + centerPadding, height)

        val result = createBitmap(width + centerPadding, height)

        result.applyCanvas {
            // Komiho: 先铺页面底色，再画两半 —— 透明像素自然合成为底色，不再变黑。
            // 中间那条留白也由这次填充决定：它和页面四周同色，接缝看不出来。
            // （旧实现用 chooseBackground 采样边角上色，但此时流已被 decodeFullBitmap 读空，
            //   实际永远拿到白色 —— 深色页上反而会有一条突兀的白缝。）
            drawColor(canvasColor)
            drawBitmap(imageBitmap, leftSourcePart, leftTargetPart, null)
            drawBitmap(imageBitmap, rightSourcePart, rightTargetPart, null)
        }

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())
        return output
    }
    // SY <--

    /**
     * Check whether the image is considered a tall image.
     *
     * @return true if the height:width ratio is greater than 3.
     */
    private fun isTallImage(imageSource: BufferedSource): Boolean {
        val options = extractImageOptions(imageSource)
        return TallImageSplitCalculator.shouldSplit(
            imageWidth = options.outWidth,
            imageHeight = options.outHeight,
            optimalImageHeight = optimalImageHeight,
        )
    }

    /**
     * Splits tall images to improve performance of reader
     */
    fun splitTallImage(
        tmpDir: UniFile,
        imageFile: UniFile,
        filenamePrefix: String,
    ): Boolean {
        val imageSource = imageFile.openInputStream().use { Buffer().readFrom(it) }
        if (isAnimatedAndSupported(imageSource) || !isTallImage(imageSource)) {
            return true
        }

        val bitmapRegionDecoder = getBitmapRegionDecoder(imageSource.peek().inputStream())
        if (bitmapRegionDecoder == null) {
            logcat { "Failed to create new instance of BitmapRegionDecoder" }
            return false
        }

        val options = extractImageOptions(imageSource).apply {
            inJustDecodeBounds = false
        }
        val splitDataList = options.splitData

        return try {
            splitDataList.forEach { splitData ->
                val splitImageName = splitImageName(filenamePrefix, splitData.index)
                // Remove pre-existing split if exists (this split shouldn't exist under normal circumstances)
                tmpDir.findFile(splitImageName)?.delete()

                val splitFile = tmpDir.createFile(splitImageName)!!

                val region = Rect(0, splitData.topOffset, splitData.splitWidth, splitData.bottomOffset)

                splitFile.openOutputStream().use { outputStream ->
                    val splitBitmap = bitmapRegionDecoder.decodeRegion(region, options)
                    splitBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    splitBitmap.recycle()
                }
                logcat {
                    "Success: Split #${splitData.index + 1} with topOffset=${splitData.topOffset} " +
                        "height=${splitData.splitHeight} bottomOffset=${splitData.bottomOffset}"
                }
            }
            imageFile.delete()
            true
        } catch (e: Exception) {
            // Image splits were not successfully saved so delete them and keep the original image
            splitDataList
                .map { splitImageName(filenamePrefix, it.index) }
                .forEach { tmpDir.findFile(it)?.delete() }
            logcat(LogPriority.ERROR, e)
            false
        } finally {
            bitmapRegionDecoder.recycle()
        }
    }

    private fun splitImageName(filenamePrefix: String, index: Int) = "${filenamePrefix}__${"%03d".format(
        Locale.ENGLISH,
        index + 1,
    )}.jpg"

    private val BitmapFactory.Options.splitData
        get(): List<SplitData> {
            val imageHeight = outHeight
            val imageWidth = outWidth

            val partCount = TallImageSplitCalculator.calculatePartCount(imageHeight, optimalImageHeight)
            val optimalSplitHeight = imageHeight / partCount

            logcat {
                "Generating SplitData for image (height: $imageHeight): " +
                    "$partCount parts @ ${optimalSplitHeight}px height per part"
            }

            return buildList {
                val range = 0..<partCount
                for (index in range) {
                    // Only continue if the list is empty or there is image remaining
                    if (isNotEmpty() && imageHeight <= last().bottomOffset) break

                    val topOffset = index * optimalSplitHeight
                    var splitHeight = min(optimalSplitHeight, imageHeight - topOffset)

                    if (index == range.last) {
                        val remainingHeight = imageHeight - (topOffset + splitHeight)
                        splitHeight += remainingHeight
                    }

                    add(SplitData(index, topOffset, splitHeight, imageWidth))
                }
            }
        }

    data class SplitData(
        val index: Int,
        val topOffset: Int,
        val splitHeight: Int,
        val splitWidth: Int,
    ) {
        val bottomOffset = topOffset + splitHeight
    }

    fun canUseHardwareBitmap(bitmap: Bitmap): Boolean {
        return canUseHardwareBitmap(bitmap.width, bitmap.height)
    }

    fun canUseHardwareBitmap(imageSource: BufferedSource): Boolean {
        return with(extractImageOptions(imageSource)) {
            canUseHardwareBitmap(outWidth, outHeight)
        }
    }

    var hardwareBitmapThreshold: Int = GLUtil.SAFE_TEXTURE_LIMIT

    private fun canUseHardwareBitmap(width: Int, height: Int): Boolean {
        if (HARDWARE_BITMAP_UNSUPPORTED) return false
        return maxOf(width, height) <= hardwareBitmapThreshold
    }

    /**
     * Komiho (2026-11-04): 页面背景的统一入口。
     *
     * - 开「自动背景」（阅读主题=自动）：按图片内容挑白/黑，与原行为一致；
     * - 关自动背景：只要图片格式**可能带透明通道**（PNG/WebP/GIF/AVIF/HEIF…，JPEG 不可能），
     *   就给一个纯白底。这样深色主题下「黑字 + 透明底」的素材图（logo、标题卡）也不会整页看不见。
     *   不透明像素会被图片自己盖住，所以对普通 PNG 页没有可见副作用。
     */
    fun pageBackground(
        context: Context,
        imageSource: BufferedSource,
        automaticBackground: Boolean,
    ): Drawable? = if (automaticBackground) {
        chooseBackground(context, imageSource)
    } else {
        transparentPageBackground(imageSource)
    }

    /** 图片格式是否可能带透明通道；是则返回纯白底，JPEG 返回 null（保持主题底色）。 */
    private fun transparentPageBackground(imageSource: BufferedSource): Drawable? {
        val type = runCatching { findImageType { imageSource.peek().inputStream() } }.getOrNull()
            ?: return null
        return if (type == ImageType.JPEG) null else Color.WHITE.toDrawable()
    }

    /**
     * Komiho (2026-11-04): 位图里是否存在**真正透明**的像素。
     *
     * 只看 `hasAlpha()` 没用 —— ARGB_8888 恒为 true。这里按网格采样（外加四角），
     * 命中一次 `alpha < 255` 就返回。用于「解码后、增强前」判断这张图要不要压白底。
     */
    fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.config != Bitmap.Config.ARGB_8888) return false
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        // 四角最容易是透明的（logo/标题卡），先看
        val corners = intArrayOf(0, 0, w - 1, 0, 0, h - 1, w - 1, h - 1)
        for (i in corners.indices step 2) {
            if (Color.alpha(bitmap[corners[i], corners[i + 1]]) < 255) return true
        }
        val stepX = (w / 24).coerceAtLeast(1)
        val stepY = (h / 24).coerceAtLeast(1)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (Color.alpha(bitmap[x, y]) < 255) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    /**
     * Komiho (2026-11-04): 把带透明通道的位图压到**纯白**底上，返回新位图（不回收入参）。
     *
     * 透明图（logo、标题卡、透明插画）在增强 / 编码 / 缩放链路里很容易丢掉 alpha
     * （RGB 内核会把 alpha 写成 255，透明区随即变成不透明黑），提前压白就一劳永逸。
     */
    fun flattenOnWhite(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled) return null
        return runCatching {
            val out = createBitmap(bitmap.width, bitmap.height)
            out.applyCanvas {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            out
        }.getOrNull()
    }

    /** 四角采样里是否含透明像素（带透明底的素材图，不能拿角上的颜色当页面背景）。 */
    private fun hasTransparentCorner(image: Bitmap): Boolean {
        if (!image.hasAlpha()) return false
        val xs = intArrayOf(
            (image.width * 0.03f).toInt().coerceIn(0, image.width - 1),
            (image.width * 0.97f).toInt().coerceIn(0, image.width - 1),
        )
        val ys = intArrayOf(
            (image.height * 0.02f).toInt().coerceIn(0, image.height - 1),
            (image.height * 0.98f).toInt().coerceIn(0, image.height - 1),
        )
        for (x in xs) {
            for (y in ys) {
                if (Color.alpha(image[x, y]) == 0) return true
            }
        }
        return false
    }

    /**
     * Algorithm for determining what background to accompany a comic/manga page
     */
    fun chooseBackground(context: Context, imageSource: BufferedSource): Drawable {
        val decoder = ImageDecoder.newInstance(imageSource.inputStream())
        val image = decoder?.decode()
        decoder?.recycle()

        val whiteColor = Color.WHITE
        if (image == null) return whiteColor.toDrawable()
        if (image.width < 50 || image.height < 50) {
            return whiteColor.toDrawable()
        }

        // Komiho (2026-11-04): 带透明底的图不能按「四角颜色」判页面背景。
        //
        // 透明像素读出来是 (0,0,0,0)，isDark() 会判成纯黑 —— 于是整页背景被设成黑色。
        // 放大时透明区跟着铺满屏幕，看起来「整屏全黑」，连点击有没有生效都看不出来
        // （典型就是带透明底的 logo、标题卡）。
        // 带透明底的图按「常规阅读器 / PDF 查看器」的做法：一律铺纯白底，
        // 不去猜它该配黑还是配白（透明的角会被 isDark() 判成黑，之前整页发黑就是这条）。
        if (hasTransparentCorner(image)) {
            return whiteColor.toDrawable()
        }

        val top = 5
        val bot = image.height - 5
        val left = (image.width * 0.0275).toInt()
        val right = image.width - left
        val midX = image.width / 2
        val midY = image.height / 2
        val offsetX = (image.width * 0.01).toInt()
        val leftOffsetX = left - offsetX
        val rightOffsetX = right + offsetX

        val topLeftPixel = image[left, top]
        val topRightPixel = image[right, top]
        val midLeftPixel = image[left, midY]
        val midRightPixel = image[right, midY]
        val topCenterPixel = image[midX, top]
        val botLeftPixel = image[left, bot]
        val bottomCenterPixel = image[midX, bot]
        val botRightPixel = image[right, bot]

        val topLeftIsDark = topLeftPixel.isDark()
        val topRightIsDark = topRightPixel.isDark()
        val midLeftIsDark = midLeftPixel.isDark()
        val midRightIsDark = midRightPixel.isDark()
        val topMidIsDark = topCenterPixel.isDark()
        val botLeftIsDark = botLeftPixel.isDark()
        val botRightIsDark = botRightPixel.isDark()

        var darkBG =
            (topLeftIsDark && (botLeftIsDark || botRightIsDark || topRightIsDark || midLeftIsDark || topMidIsDark)) ||
                (topRightIsDark && (botRightIsDark || botLeftIsDark || midRightIsDark || topMidIsDark))

        val topAndBotPixels =
            listOf(topLeftPixel, topCenterPixel, topRightPixel, botRightPixel, bottomCenterPixel, botLeftPixel)
        val isNotWhiteAndCloseTo = topAndBotPixels.mapIndexed { index, color ->
            val other = topAndBotPixels[(index + 1) % topAndBotPixels.size]
            !color.isWhite() && color.isCloseTo(other)
        }
        if (isNotWhiteAndCloseTo.all { it }) {
            return topLeftPixel.toDrawable()
        }

        val cornerPixels = listOf(topLeftPixel, topRightPixel, botLeftPixel, botRightPixel)
        val numberOfWhiteCorners = cornerPixels.map { cornerPixel -> cornerPixel.isWhite() }
            .filter { it }
            .size
        if (numberOfWhiteCorners > 2) {
            darkBG = false
        }

        var blackColor = when {
            topLeftIsDark -> topLeftPixel
            topRightIsDark -> topRightPixel
            botLeftIsDark -> botLeftPixel
            botRightIsDark -> botRightPixel
            else -> whiteColor
        }

        var overallWhitePixels = 0
        var overallBlackPixels = 0
        var topBlackStreak = 0
        var topWhiteStreak = 0
        var botBlackStreak = 0
        var botWhiteStreak = 0
        outer@ for (x in intArrayOf(left, right, leftOffsetX, rightOffsetX)) {
            var whitePixelsStreak = 0
            var whitePixels = 0
            var blackPixelsStreak = 0
            var blackPixels = 0
            var blackStreak = false
            var whiteStreak = false
            val notOffset = x == left || x == right
            inner@ for ((index, y) in (0..<image.height step image.height / 25).withIndex()) {
                val pixel = image[x, y]
                val pixelOff = image[x + (if (x < image.width / 2) -offsetX else offsetX), y]
                if (pixel.isWhite()) {
                    whitePixelsStreak++
                    whitePixels++
                    if (notOffset) {
                        overallWhitePixels++
                    }
                    if (whitePixelsStreak > 14) {
                        whiteStreak = true
                    }
                    if (whitePixelsStreak > 6 && whitePixelsStreak >= index - 1) {
                        topWhiteStreak = whitePixelsStreak
                    }
                } else {
                    whitePixelsStreak = 0
                    if (pixel.isDark() && pixelOff.isDark()) {
                        blackPixels++
                        if (notOffset) {
                            overallBlackPixels++
                        }
                        blackPixelsStreak++
                        if (blackPixelsStreak >= 14) {
                            blackStreak = true
                        }
                        continue@inner
                    }
                }
                if (blackPixelsStreak > 6 && blackPixelsStreak >= index - 1) {
                    topBlackStreak = blackPixelsStreak
                }
                blackPixelsStreak = 0
            }
            if (blackPixelsStreak > 6) {
                botBlackStreak = blackPixelsStreak
            } else if (whitePixelsStreak > 6) {
                botWhiteStreak = whitePixelsStreak
            }
            when {
                blackPixels > 22 -> {
                    if (x == right || x == rightOffsetX) {
                        blackColor = when {
                            topRightIsDark -> topRightPixel
                            botRightIsDark -> botRightPixel
                            else -> blackColor
                        }
                    }
                    darkBG = true
                    overallWhitePixels = 0
                    break@outer
                }
                blackStreak -> {
                    darkBG = true
                    if (x == right || x == rightOffsetX) {
                        blackColor = when {
                            topRightIsDark -> topRightPixel
                            botRightIsDark -> botRightPixel
                            else -> blackColor
                        }
                    }
                    if (blackPixels > 18) {
                        overallWhitePixels = 0
                        break@outer
                    }
                }
                whiteStreak || whitePixels > 22 -> darkBG = false
            }
        }

        val topIsBlackStreak = topBlackStreak > topWhiteStreak
        val bottomIsBlackStreak = botBlackStreak > botWhiteStreak
        if (overallWhitePixels > 9 && overallWhitePixels > overallBlackPixels) {
            darkBG = false
        }
        if (topIsBlackStreak && bottomIsBlackStreak) {
            darkBG = true
        }

        val isLandscape = context.resources.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            return when {
                darkBG -> blackColor.toDrawable()
                else -> whiteColor.toDrawable()
            }
        }

        val botCornersIsWhite = botLeftPixel.isWhite() && botRightPixel.isWhite()
        val topCornersIsWhite = topLeftPixel.isWhite() && topRightPixel.isWhite()

        val topCornersIsDark = topLeftIsDark && topRightIsDark
        val botCornersIsDark = botLeftIsDark && botRightIsDark

        val topOffsetCornersIsDark = image[leftOffsetX, top].isDark() && image[rightOffsetX, top].isDark()
        val botOffsetCornersIsDark = image[leftOffsetX, bot].isDark() && image[rightOffsetX, bot].isDark()

        val gradient = when {
            darkBG && botCornersIsWhite -> {
                intArrayOf(blackColor, blackColor, whiteColor, whiteColor)
            }
            darkBG && topCornersIsWhite -> {
                intArrayOf(whiteColor, whiteColor, blackColor, blackColor)
            }
            darkBG -> {
                return blackColor.toDrawable()
            }
            topIsBlackStreak ||
                (
                    topCornersIsDark &&
                        topOffsetCornersIsDark &&
                        (topMidIsDark || overallBlackPixels > 9)
                    ) -> {
                intArrayOf(blackColor, blackColor, whiteColor, whiteColor)
            }
            bottomIsBlackStreak ||
                (
                    botCornersIsDark &&
                        botOffsetCornersIsDark &&
                        (bottomCenterPixel.isDark() || overallBlackPixels > 9)
                    ) -> {
                intArrayOf(whiteColor, whiteColor, blackColor, blackColor)
            }
            else -> {
                return whiteColor.toDrawable()
            }
        }

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            gradient,
        )
    }

    private fun @receiver:ColorInt Int.isDark(): Boolean =
        red < 40 && blue < 40 && green < 40 && alpha > 200

    private fun @receiver:ColorInt Int.isCloseTo(other: Int): Boolean =
        abs(red - other.red) < 30 && abs(green - other.green) < 30 && abs(blue - other.blue) < 30

    private fun @receiver:ColorInt Int.isWhite(): Boolean =
        red + blue + green > 740

    /**
     * Used to check an image's dimensions without loading it in the memory.
     */
    private fun extractImageOptions(imageSource: BufferedSource): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 不把 Java 流塞进 BitmapFactory（原因见常量区说明），尺寸读取统一走 readImageSize。
        val size = readImageSize { imageSource.peek().inputStream() }
        if (size != null) {
            options.outWidth = size.first
            options.outHeight = size.second
        }
        return options
    }

    /**
     * Creates random exif metadata used as padding to make
     * the size of files inside  CBZ archives unique
     */
    fun addPaddingToImageExif(imageFile: File) {
        try {
            val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
            val padding = List(SecureRandom().nextInt(16384) + 16384) { charPool.random() }.joinToString("")
            val exif = ExifInterface(imageFile.absolutePath)
            exif.setAttribute("UserComment", padding)
            exif.saveAttributes()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
    // SY <--

    private fun getBitmapRegionDecoder(imageStream: InputStream): BitmapRegionDecoder? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(imageStream)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(imageStream, false)
        }
    }

    private val optimalImageHeight = getDisplayMaxHeightInPx * 2

    /**
     * Taken from Coil
     * (https://github.com/coil-kt/coil/blob/1674d3516f061aeacbe749a435b1924f9648fd41/coil-core/src/androidMain/kotlin/coil3/util/hardwareBitmaps.kt)
     * ---
     * Maintains a list of devices with broken/incomplete/unstable hardware bitmap implementations.
     *
     * Model names are retrieved from
     * [Google's official device list](https://support.google.com/googleplay/answer/1727131?hl=en).
     *
     */
    val HARDWARE_BITMAP_UNSUPPORTED = when (Build.VERSION.SDK_INT) {
        26 -> run {
            val model = Build.MODEL ?: return@run false

            // Samsung Galaxy (ALL)
            if (model.removePrefix("SAMSUNG-").startsWith("SM-")) return@run true

            val device = Build.DEVICE ?: return@run false

            return@run device in arrayOf(
                "nora", "nora_8917", "nora_8917_n", // Moto E5
                "james", "rjames_f", "rjames_go", "pettyl", // Moto E5 Play
                "hannah", "ahannah", "rhannah", // Moto E5 Plus

                "ali", "ali_n", // Moto G6
                "aljeter", "aljeter_n", "jeter", // Moto G6 Play
                "evert", "evert_n", "evert_nt", // Moto G6 Plus

                "G3112", "G3116", "G3121", "G3123", "G3125", // Xperia XA1
                "G3412", "G3416", "G3421", "G3423", "G3426", // Xperia XA1 Plus
                "G3212", "G3221", "G3223", "G3226", // Xperia XA1 Ultra

                "BV6800Pro", // BlackView BV6800Pro
                "CatS41", // Cat S41
                "Hi9Pro", // CHUWI Hi9 Pro
                "manning", // Lenovo K8 Note
                "N5702L", // NUU Mobile G3
            )
        }

        27 -> run {
            val device = Build.DEVICE ?: return@run false

            return@run device in arrayOf(
                "mcv1s", // LG Tribute Empire
                "mcv3", // LG K11
                "mcv5a", // LG Q7
                "mcv7a", // LG Stylo 4

                "A30ATMO", // T-Mobile REVVL 2
                "A70AXLTMO", // T-Mobile REVVL 2 PLUS

                "A3A_8_4G_TMO", // Alcatel 9027W
                "Edison_CKT", // Alcatel ONYX
                "EDISON_TF", // Alcatel TCL XL2
                "FERMI_TF", // Alcatel A501DL
                "U50A_ATT", // Alcatel TETRA
                "U50A_PLUS_ATT", // Alcatel 5059R
                "U50A_PLUS_TF", // Alcatel TCL LX
                "U50APLUSTMO", // Alcatel 5059Z
                "U5A_PLUS_4G", // Alcatel 1X

                "RCT6513W87DK5e", // RCA Galileo Pro
                "RCT6873W42BMF9A", // RCA Voyager
                "RCT6A03W13", // RCA 10 Viking
                "RCT6B03W12", // RCA Atlas 10 Pro
                "RCT6B03W13", // RCA Atlas 10 Pro+
                "RCT6T06E13", // RCA Artemis 10

                "A3_Pro", // Umidigi A3 Pro
                "One", // Umidigi One
                "One_Max", // Umidigi One Max
                "One_Pro", // Umidigi One Pro
                "Z2", // Umidigi Z2
                "Z2_PRO", // Umidigi Z2 Pro

                "Armor_3", // Ulefone Armor 3
                "Armor_6", // Ulefone Armor 6

                "Blackview", // Blackview BV6000
                "BV9500", // Blackview BV9500
                "BV9500Pro", // Blackview BV9500Pro

                "A6L-C", // Nuu A6L-C
                "N5002LA", // Nuu A7L
                "N5501LA", // Nuu A5L

                "Power_2_Pro", // Leagoo Power 2 Pro
                "Power_5", // Leagoo Power 5
                "Z9", // Leagoo Z9

                "V0310WW", // Blu VIVO VI+
                "V0330WW", // Blu VIVO XI

                "A3", // BenQ A3
                "ASUS_X018_4", // Asus ZenFone Max Plus M1 (ZB570TL)
                "C210AE", // Wiko Life
                "fireball", // DROID Incredible 4G LTE
                "ILA_X1", // iLA X1
                "Infinix-X605_sprout", // Infinix NOTE 5 Stylus
                "j7maxlte", // Samsung Galaxy J7 Max
                "KING_KONG_3", // Cubot King Kong 3
                "M10500", // Packard Bell M10500
                "S70", // Altice ALTICE S70
                "S80Lite", // Doogee S80Lite
                "SGINO6", // SGiNO 6
                "st18c10bnn", // Barnes and Noble BNTV650
                "TECNO-CA8", // Tecno CAMON X Pro,
                "SHIFT6m", // SHIFT 6m
            )
        }

        else -> false
    }

    // SY -->
    fun mergeBitmaps(
        imageBitmap: Bitmap,
        imageBitmap2: Bitmap,
        isLTR: Boolean,
        centerMargin: Int,
        @ColorInt background: Int = Color.WHITE,
        progressCallback: ((Int) -> Unit)? = null,
    ): BufferedSource {
        val height = imageBitmap.height
        val width = imageBitmap.width
        val height2 = imageBitmap2.height
        val width2 = imageBitmap2.width

        val maxHeight = max(height, height2)

        val result = Bitmap.createBitmap(width + width2 + centerMargin, max(height, height2), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(background)
        val upperPart = Rect(
            if (isLTR) 0 else width2 + centerMargin,
            (maxHeight - height) / 2,
            (if (isLTR) 0 else width2 + centerMargin) + width,
            height + (maxHeight - height) / 2,
        )

        canvas.drawBitmap(imageBitmap, imageBitmap.rect, upperPart, null)
        progressCallback?.invoke(98)
        val bottomPart = Rect(
            if (!isLTR) 0 else width + centerMargin,
            (maxHeight - height2) / 2,
            (if (!isLTR) 0 else width + centerMargin) + width2,
            height2 + (maxHeight - height2) / 2,
        )

        canvas.drawBitmap(imageBitmap2, imageBitmap2.rect, bottomPart, null)
        progressCallback?.invoke(99)

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())
        progressCallback?.invoke(100)
        return output
    }

    private val Bitmap.rect: Rect
        get() = Rect(0, 0, width, height)
    // SY <--
}

val getDisplayMaxHeightInPx: Int
    get() = Resources.getSystem().displayMetrics.let { max(it.heightPixels, it.widthPixels) }
