package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.TextView
import android.widget.FrameLayout
import eu.kanade.tachiyomi.R
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.EnhanceTimings
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    // MihonSY: the alwaysDecodeLongStripWithSSIV preference no longer gates the decode
    // path — every mode decodes through Coil into a software bitmap so image
    // enhancement applies everywhere.

    private var pageView: View? = null

    private var config: Config? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * MihonSY: the pixel height of the loaded image, or 0 if unknown. Exposed so the
     * webtoon holder can match the item height to the real 1:1 image height when
     * "original resolution" is enabled (avoids the black gap below each strip).
     */
    val imageSHeight: Int
        get() = (pageView as? SubsamplingScaleImageView)?.sHeight ?: 0

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    /**
     * Komiho 诊断：当前页序号（-1 = 未知），由 holder 在 setImage 前写入。
     * 只用于增强日志区分请求来源（`prewarm#N` / `holder#N`），不参与任何渲染逻辑。
     */
    var pageIndex: Int = -1

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        val config = config
        if (config != null &&
            config.landscapeZoom &&
            config.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                (animateScaleAndCenter(targetScale, point) ?: return@postDelayed)
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        // MihonSY: hide any leftover enhancement status from the previous image.
        enhanceStatusView?.visibility = View.GONE
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        // MihonSY: hide any leftover enhancement status from the previous image.
        enhanceStatusView?.visibility = View.GONE
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() = pageView?.let {
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
    }

    // MihonSY -->
    /**
     * MihonSY: small overlay at the bottom-left showing the image enhancement state.
     * Shows the real outcome (elapsed time on success, 跳过 on failure).
     * Created lazily and only when the "show enhancement status" toggle is on.
     */
    private var enhanceStatusView: TextView? = null

    private fun ensureEnhanceStatusView(): TextView {
        enhanceStatusView?.let { return it }
        val tv = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt()) // white text
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            alpha = 1f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x99000000.toInt()) // dark translucent background for contrast on white pages
                cornerRadius = dpToPx(4f).toFloat()
            }
            setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
            visibility = View.GONE
        }
        val lp = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            marginStart = dpToPx(8f)
            bottomMargin = dpToPx(8f)
        }
        addView(tv, lp)
        enhanceStatusView = tv
        return tv
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * MihonSY: shows the enhancement outcome badge. Success shows which engine actually ran
     * plus the real elapsed time; failure/skip shows 跳过. No-op unless enhancement is on
     * AND the status toggle is on. Enhancement itself runs synchronously inside the Coil
     * decoder, so this is only called from the Coil success/error listeners — plus
     * PagerPageHolder for the pre-decoded bitmap path (SY: Page 优化，耗时来自预处理阶段).
     *
     * Komiho (2026-09-17): the success label reports the **engine that really produced the
     * image** — `NPU OK` / `GPU OK` / `CPU OK` — read from [Waifu2x.engineFor] rather than
     * from the selected model. A silent NPU→Vulkan fallback used to render identically to a
     * genuine NPU run, which made the cross-HTP experiment unreadable from the screen.
     *
     * Komiho (2026-09-19): it is looked up **by page index**. Reading the single global slot
     * meant a concurrent page's fallback could mislabel this page (`CPU OK` on a page that ran
     * on the NPU); see [Waifu2x.engineFor].
     */
    internal fun showEnhancementOutcome(success: Boolean, elapsedMillis: Long) {
        val preferences = Injekt.get<ReaderPreferences>()
        if (preferences.enhancementMode.get() == 0 || !preferences.showEnhancementStatus.get()) return
        val tv = ensureEnhanceStatusView()
        tv.text = if (success) {
            String.format(
                java.util.Locale.US,
                "%s %.1fs",
                engineLabel(pageIndex),
                elapsedMillis / 1000f,
            )
        } else {
            context.getString(R.string.reader_enhancement_skipped)
        }
        tv.visibility = View.VISIBLE
    }

    /**
     * Komiho: engine tag for the badge. CPU 档（Lanczos3 / Catmull-Rom）本身就是 CPU，直接标
     * `CPU OK`；AI 档要看原生侧真正跑的是哪台引擎 —— 见 [Waifu2x.engineFor]。
     *
     * 2026-09-19：按**页号**取引擎，而不是读全局 `lastEngine`。增强是并发跑的（预取 + 当前页），
     * 全局槽会被别的页覆盖 —— 曾导致「明明跑的是 NPU，角标却显示 CPU OK」。
     */
    private fun engineLabel(pageIndex: Int): String =
        when (Injekt.get<ReaderPreferences>().enhancementMode.get()) {
            // CPU resampler modes (2 = Lanczos3, 3 = Catmull-Rom) never touch the native engines.
            2, 3 -> "CPU OK"
            else -> when (Waifu2x.engineFor(pageIndex)) {
                Waifu2x.EngineKind.QNN_HTP -> "NPU OK"
                Waifu2x.EngineKind.NCNN_VULKAN -> "GPU OK"
                // NONE = 引擎没出结果，本页由 CPU 重采样兜底（或首个引擎结果尚未登记）。
                Waifu2x.EngineKind.NONE -> "CPU OK"
            }
        }
    // MihonSY <--

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            // MihonSY: when enhancement is on, decode through Coil so the enhancer
            // runs inside the decoder (on a background thread, at higher resolution).
            // When enhancement is off, keep the original SSIV direct-decode path.
            // Enhancement now applies to every reading mode (webtoon/strip included).
            is BufferedSource -> {
                val preferences = Injekt.get<ReaderPreferences>()
                // MihonSY: only enhance streams that actually look like a standard
                // image (JPEG/PNG/WebP/GIF magic). Downloaded chapters packed as CBZ
                // (encrypted or raw archives) can yield non-image streams; feeding
                // those to the enhancement decoder crashed the reader. Non-standard
                // streams fall back to the original SSIV direct-decode path.
                val enhancementOn = preferences.enhancementMode.get() != 0 && isStandardImageStream(data)
                if (!enhancementOn) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                val startTime = android.os.SystemClock.uptimeMillis()
                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .enhanced(true)
                    .customDecoder(true)
                    // Komiho 诊断：带上页号（prewarm 保持默认 false → 日志里显示 holder#N）。
                    .pageIndex(this@ReaderPageImageView.pageIndex)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                            // Komiho: webtoon 条目是 WRAP_CONTENT，NPU 增强是同步解码，
                            // 常在 holder 还离屏（高度已量到 0）时就跑完。若此刻不把视图高度
                            // 定下来，增强图会落进 0 高度 SSIV，要等下次 RV 布局（滚进可视区）
                            // 才撑开 —— 表现就是「滚到才显示」。这里按 bitmap 宽高比预置真实
                            // 高度并立即 requestLayout，让增强图一算完就显示。
                            if (this@ReaderPageImageView.isWebtoon) {
                                val bmp = image.bitmap
                                if (bmp.width > 0) {
                                    val viewWidth = this@ReaderPageImageView.width.takeIf { it > 0 }
                                        ?: this@ReaderPageImageView.context.resources.displayMetrics.widthPixels
                                    val computedHeight = (bmp.height * viewWidth / bmp.width.toFloat()).toInt()
                                    this@ReaderPageImageView.layoutParams?.height = computedHeight
                                    this@ReaderPageImageView.requestLayout()
                                }
                            }
                            showEnhancementOutcome(
                                success = true,
                                // Komiho: 优先用解码器登记的实际计算耗时（解码 + 增强，已剔除等锁）；
                                // 取不到才回退 Coil 外层墙钟（含排队会虚高）。
                                elapsedMillis = EnhanceTimings.take(pageIndex)
                                    ?: (android.os.SystemClock.uptimeMillis() - startTime),
                            )
                        },
                    )
                    .listener(
                        onError = { _, _ ->
                            onImageLoadError(null)
                            showEnhancementOutcome(
                                success = false,
                                elapsedMillis = android.os.SystemClock.uptimeMillis() - startTime,
                            )
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F

/**
 * MihonSY: true when the stream begins with a known image magic number
 * (JPEG/PNG/WebP/GIF). Downloaded CBZ-packed chapters can hand the reader a
 * non-image stream (encrypted/raw archive data); the enhancement decoder
 * crashes on those, so they must skip enhancement and use the standard path.
 * [BufferedSource.peek] does not consume the stream.
 */
internal fun isStandardImageStream(source: BufferedSource): Boolean {
    return try {
        source.peek().use { peek ->
            val head = peek.readByteArray(16)
            (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) || // JPEG
                (head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() && head[4] == 0x0D.toByte() && head[5] == 0x0A.toByte() && head[6] == 0x1A.toByte() && head[7] == 0x0A.toByte()) || // PNG
                (head.size >= 12 && head[0] == 0x52.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x46.toByte() && head[8] == 0x57.toByte() && head[9] == 0x45.toByte() && head[10] == 0x42.toByte() && head[11] == 0x50.toByte()) || // WEBP
                (head.size >= 6 && head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() && head[3] == 0x38.toByte()) // GIF
        }
    } catch (e: Exception) {
        false
    }
}
