package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import eu.kanade.tachiyomi.ui.reader.viewer.PaperPageTexture
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 页漫翻页动画的绘制层。
 *
 * ## 两种模式
 *
 * - **覆盖**（[drawCover]）：当前页原地不动，下一页从一侧滑上来盖住它；
 * - **翻书**（[drawFold]）：只画「正在翻动的那一张纸」——
 *   1. 正面：折线靠书脊一侧的剩余平放部分；
 *   2. 背面：页矩形关于折线的**镜像** ∩ 平放半平面，内容是同一页的镜像；
 *   3. 折痕：沿折线对称衰减的渐变，压在前后面之上。
 *
 * 下一页（翻过去后露出的那页）始终是**活 View**，由 `ViewPager` 绘制，所以永远是增强后的
 * 最新图；本层只负责把「纸」盖在上面。
 *
 * ## 成本
 *
 * 翻书每帧固定 2 次 `drawBitmap` + 1 次 `drawRect` + 1 次渐变：Path / RectF / Paint / Shader
 * 全是成员，逐帧只 `rewind()`，零分配；不做 `setShadowLayer` / `saveLayer` / `Path.op`。
 */
internal class PageFlipView(context: Context) : View(context) {

    // region 覆盖模式

    /** 正在盖上来那一页（真实页 View）。null = 空闲。 */
    var sheetView: View? = null

    /** 新页在屏幕上的左边界（像素）。 */
    var sheetOffsetX: Float = 0f

    /** 覆盖进度（仅用于投影淡入）。 */
    var progress: Float = 0f

    /** 是否绘制交界投影（平移不画）。 */
    var drawShadow: Boolean = true

    // endregion

    // region 翻书模式

    /**
     * 正在翻动的那一页（真实页 View，由本层画正面 + 镜像背面）。
     *
     * 不用快照：`PixelCopy` 是异步的，拿不到就得回落，行为不稳定；而实时 View 每帧都在，
     * 翻开过程中增强完成也能立刻反映。代价是每帧多一次同一页的绘制（正面 + 镜像）。
     */
    var foldView: View? = null

    /**
     * 翻动进度：0 = 整页平放，1 = 完全翻走。
     * 与 [progress] 分开：覆盖用它做投影淡入，翻书用它算折线行程。
     */
    var foldProgress: Float = 0f

    /** true = 书脊在左（自由边在右）；false = 书脊在右（日漫右→左）。 */
    var spineOnLeft: Boolean = true

    /**
     * 手指落点（0..1，相对屏幕高度），决定折线倾角。
     * 抓上方则折线绕上方旋转，抓中间（0.5）退化为竖直折线 —— 「捏住一角」的手感全靠它。
     */
    var grabFraction: Float = 0.5f

    /** 背面纸色（跟随阅读器页面底色）。 */
    var backTint: Int = Color.WHITE

    // endregion

    /** 页面底色（纸档隔离层里当页面之下的衬底用）。 */
    var canvasColor: Int = Color.WHITE

    private val shadowPaint = Paint()
    private val backdropPaint = Paint()
    private val frontPath = Path()
    private val mirroredPath = Path()
    private var shadowRadius = 0f

    /** 跨 View 绘制失败的记录标记（只记一次日志）。 */
    private var drawFailed = false

    /** 绘制失败时回调（控制器据此收尾）。 */
    var onDrawFailed: (() -> Unit)? = null

    init {
        // 纯绘制层：不拦截触摸（点击/拖动必须继续落到下面的 Pager 上）。
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 阴影视口只在尺寸变化时重建：渐变坐标固定在本地系 ±radius，逐帧靠 translate+rotate
        // 挪到折线上，因此拖动时不需要重新分配 Shader。
        shadowRadius = SHADOW_DP * resources.displayMetrics.density
        shadowPaint.shader = LinearGradient(
            0f,
            -shadowRadius,
            0f,
            shadowRadius,
            intArrayOf(Color.TRANSPARENT, SHADOW_COLOR, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** 清空状态（会话结束 / 视图销毁）。位图由控制器复用，这里只解除引用。 */
    fun reset() {
        val dirty = sheetView != null || foldView != null
        sheetView = null
        foldView = null
        progress = 0f
        foldProgress = 0f
        if (dirty) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 这里会把**别人的** View 画到自己的画布上。万一抛异常会落进绘制遍历 → 崩溃，
        // 所以兜住：失败就退化成不画（页面照常可见）。
        try {
            if (foldView != null) drawFold(canvas) else drawCover(canvas)
        } catch (e: Throwable) {
            if (!drawFailed) {
                drawFailed = true
                android.util.Log.w(
                    "PageFlip",
                    "draw failed, skip animation: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
            reset()
            onDrawFailed?.invoke()
        }
    }

    // region 覆盖

    private fun drawCover(canvas: Canvas) {
        val sheet = sheetView ?: return
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return

        val left = sheetOffsetX
        val fromRight = left >= 0f
        val edgeX = if (fromRight) left else left + w
        val shadowDir = if (fromRight) -1f else 1f

        if (drawShadow) {
            val fade = (progress / START_FADE).coerceAtMost(1f)
            shadowPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val sc1 = canvas.save()
            canvas.translate(edgeX, 0f)
            canvas.scale(shadowDir, 1f)
            canvas.drawRect(0f, 0f, w * COVER_SHADOW_FRACTION, h, shadowPaint)
            canvas.restoreToCount(sc1)
        }

        val sc2 = canvas.save()
        canvas.translate(left, 0f)
        drawPage(canvas, sheet)
        canvas.restoreToCount(sc2)
    }

    // endregion

    // region 翻书

    private fun drawFold(canvas: Canvas) {
        val page = foldView ?: return
        // 进度 0 时**也要画**：此时几何退化为「整页平放」，与真实页一模一样。
        // 只在 0 附近跳过会导致「当前页已隐藏、叠加层又没画」的那一帧 —— 就是点击/按住
        // 先闪一下的原因。只有到 1（纸完全翻走）才停画。
        val p = foldProgress.coerceIn(0f, 1f)
        if (p >= MAX_PROGRESS) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val freeX = if (spineOnLeft) w else 0f
        val spineX = if (spineOnLeft) 0f else w

        // 折线中点沿「自由边 → 书脊」推进。
        //
        // **必须线性**（t = p）：ViewPager 的落位判据是 offset ≥ 0.5，如果这里前 34% 就走完
        // 62% 的行程，拖动到 1/3 时画面已经翻了 2/3，而 ViewPager 仍按位置判"回去/前进"，
        // 观感就是「只翻了不到 1/3 就自己翻过去/弹回」。
        val t = p
        val cx = freeX + (spineX - freeX) * t

        // 折线绕手指落点旋转；张开量在两端收敛为 0（p→0 必须竖直贴住自由边）。
        val gy = grabFraction.coerceIn(0.12f, 0.88f) * h
        val env = sin(PI.toFloat() * min(p / TILT_FADE_END, 1f))
        val ahead = if (spineOnLeft) 1f else -1f
        val drift = ahead * (0.5f - grabFraction) * 2f * TILT_MAX * w * env
        val topX = cx - drift * (gy / h)
        val botX = cx + drift * ((h - gy) / h)

        val dx = botX - topX
        val lineLen = hypot(dx, h)
        if (lineLen < 1f) return
        val ux = dx / lineLen
        val uy = h / lineLen

        // 指向「平放侧」（书脊一侧）的单位法线。
        val nx = if (spineOnLeft) -uy else uy
        val ny = if (spineOnLeft) ux else -ux
        val ext = (w + h) * 2f

        frontPath.rewind()
        frontPath.moveTo(topX, 0f)
        frontPath.lineTo(botX, h)
        frontPath.lineTo(botX + nx * ext, h + ny * ext)
        frontPath.lineTo(topX + nx * ext, ny * ext)
        frontPath.close()

        buildMirroredRect(topX, ux, uy, w, h)

        val deg = atan2(uy, ux) * 180f / PI.toFloat()

        // ① 正面：折线靠书脊一侧的剩余平放部分。
        val sc1 = canvas.save()
        canvas.clipPath(frontPath)
        drawPage(canvas, page)
        canvas.restoreToCount(sc1)

        // ② 背面：平放半平面 ∩ 镜像后的页矩形。裁好之后把画布镜像过去再画同一张页。
        val sc2 = canvas.save()
        canvas.clipPath(frontPath)
        canvas.clipPath(mirroredPath)
        canvas.translate(topX, 0f)
        canvas.rotate(deg)
        canvas.scale(1f, -1f)
        canvas.rotate(-deg)
        canvas.translate(-topX, 0f)
        // 背面画的是**同一页的实时内容**（镜像），不叠任何纸色 —— 纸色由页面自己带。
        drawPage(canvas, page)
        canvas.restoreToCount(sc2)

        // ③ 折痕阴影：本地系里折线就是 x 轴，渐变沿 y 向对称衰减。
        //    两端各淡出一段，否则折线贴到屏幕边缘时阴影会突然消失。
        if (shadowPaint.shader != null) {
            val fade = min(1f, p / SHADOW_FADE_IN) * min(1f, (1f - p) / SHADOW_FADE_OUT)
            shadowPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            val sc3 = canvas.save()
            canvas.translate(topX, 0f)
            canvas.rotate(deg)
            canvas.drawRect(-ext, -ext, ext, ext, shadowPaint)
            canvas.restoreToCount(sc3)
        }
    }

    /**
     * 生成「页矩形关于折线镜像」后的四边形。
     *
     * 背面之所以还要裁它：折过去的那一块在几何上只可能来自页面本身，镜像矩形之外对应的是
     * 「纸外」，不裁就会画出越出页面边缘的残影。
     */
    private fun buildMirroredRect(topX: Float, ux: Float, uy: Float, w: Float, h: Float) {
        mirroredPath.rewind()
        for (i in 0..3) {
            val px = if (i == 0 || i == 3) 0f else w
            val py = if (i < 2) 0f else h
            val vx = px - topX
            val vy = py
            val dot = vx * ux + vy * uy
            val rx = 2f * (topX + ux * dot) - px
            val ry = 2f * (uy * dot) - py
            if (i == 0) mirroredPath.moveTo(rx, ry) else mirroredPath.lineTo(rx, ry)
        }
        mirroredPath.close()
    }

    // endregion

    /** 把一个页 View 画到原点（纸档下先铺页面底色，避免纸纹乘到下面那张页上）。 */
    private fun drawPage(canvas: Canvas, view: View) {
        if (PaperPageTexture.enabled) {
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            backdropPaint.color = canvasColor
            backdropPaint.alpha = 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)
            view.draw(canvas)
            canvas.restoreToCount(layer)
        } else {
            view.draw(canvas)
        }
    }

    internal companion object {
        /** 高于此进度不绘制（纸已经翻出屏幕）。 */
        const val MAX_PROGRESS = 0.998f

        /** 折线倾角衰减终点：超过它折线必须已经竖直，否则贴近书脊时会撕裂。 */
        const val TILT_FADE_END = 0.75f

        /** 折线两端相对中点的最大水平张开（占页宽比例）。 */
        const val TILT_MAX = 0.35f

        /** 折痕阴影半径（dp）。 */
        const val SHADOW_DP = 18f

        /** 阴影在起点 / 终点附近的淡出区间。 */
        const val SHADOW_FADE_IN = 0.06f
        const val SHADOW_FADE_OUT = 0.12f

        /** 折痕阴影峰值色（约 24% 黑）。 */
        const val SHADOW_COLOR = 0x3D000000

        /** 覆盖模式投影宽度（占页宽比例）。 */
        const val COVER_SHADOW_FRACTION = 0.045f

        /** 覆盖模式投影淡入区间。 */
        const val START_FADE = 0.05f
    }
}
