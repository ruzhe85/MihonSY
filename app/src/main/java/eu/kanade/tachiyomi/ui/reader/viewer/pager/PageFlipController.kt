package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.animation.ValueAnimator
import android.view.ViewPager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.animation.doOnEnd
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderTheme

/** 诊断 TAG：翻书动画。 */
private const val FLIP_TAG = "PageFlip"

/**
 * 双页（一跨两页）时的时长倍数。
 *
 * 一屏承载两页内容，相同毫秒数下信息量翻倍、观感更快，所以按此放慢。
 */
private const val DOUBLE_PAGE_FACTOR = 1.2f

/**
 * 翻书（折线）模式的时长倍数。
 *
 * 三种模式共用 400ms，但翻书的视觉位移从第一帧就铺满整屏，主观上明显更快；
 * 1.4 之后单页 560ms、双页 672ms，节奏与平移/覆盖接近。
 */
private const val FOLD_DURATION_FACTOR = 1.4f

/** 翻页方式（与 `ReaderPreferences.pageTurnStyle` 一致）。 */
private const val STYLE_SLIDE = 0
private const val STYLE_COVER = 1
private const val STYLE_FOLD = 2

/**
 * 页漫翻页动画的驱动。
 *
 * ## 它做三件事
 *
 * 1. **钉住当前页**：把正在露出的那一页的 `translationX` 抵消掉，让它停在原位不动
 *    （这就是「覆盖」与「滑动」的分界 —— 滑动是两页一起走）；
 * 2. **藏起新页**：它改由 [PageFlipView] 画在最上层，保证两个方向的层级都正确；
 * 3. 每帧把新页的**屏幕位置**交给叠加层，由它画新页并在交界处压一道投影。
 *
 * 翻页的判定、速度、阈值、取消回弹仍然完全归 `ViewPager`，这里不碰。
 *
 * ## 两个方向完全对称
 *
 * 全程**不判断「前进 / 后退」**：每帧直接比两页的 `left - scrollX`（它们在屏幕上的真实
 * 位置）—— 贴在视口原点的那一页是当前页（钉住），另一页是新页（按它自己的位置绘制）。
 */
internal class PageFlipController(private val viewer: PagerViewer) {

    /** 叠在 pager 之上的绘制层。由 `PagerViewer` 放进容器。 */
    val overlay = PageFlipView(viewer.activity)

    private var session: Session? = null

    private class Session(
        /**
         * 被钉在原位的那一页。
         *
         * - 覆盖 / 平移：当前页（新页在动）；
         * - 翻书：**下一页**（它是被揭开的那张，必须静止）。
         */
        val pinnedView: PagerPageHolder,
        /**
         * 被隐藏、改由叠加层绘制的那一页。
         *
         * - 覆盖 / 平移：新页；
         * - 翻书：**当前页**（正在被折走的那张）。
         */
        val sheetView: PagerPageHolder,
        /** 翻书模式下需要额外钉住的另一页（当前页）；其它模式为 null。 */
        val foldPinned: PagerPageHolder? = null,
        /** 翻书模式：折线从右侧自由边开始。 */
        val foldFromRight: Boolean = true,
        /** 翻书模式：会话开始时的 `scrollX`（进度与方向都按它算，见 applyFrame）。 */
        val startScrollX: Int = 0,
    )

    /** 当前滚动状态。只在真正滚动的帧里接管（瞬间跳页没有滚动过程）。 */
    private var scrollState = ViewPager.SCROLL_STATE_IDLE

    /** 本轮滚动是否已经打过启动日志（避免逐帧刷屏）。 */
    private var loggedThisScroll = false

    /** 点击翻页动画（见 [startTapFlip]）。 */
    private var tapAnimator: ValueAnimator? = null

    /** 手指按下位置（0..1，相对 pager 高度），决定翻书折线的倾角。 */
    private var grabFraction = 0.5f

    init {
        // 跨 View 绘制失败（极少数机型/页面）→ 立刻收尾，把页 View 复原，
        // 用户看到的就是一次普通滑动，而不是「页不见了」。
        overlay.onDrawFailed = { end() }
    }

    // region 对外入口

    /**
     * 点击 / 按键翻页：由叠加层自己播完，最后一帧一次性落到目标页。
     *
     * ## 为什么不用 `setCurrentItem(…, true)` 或「假拖动」
     *
     * - `setCurrentItem(…, true)` 用 ViewPager 写死的 viscous-fluid 曲线，前 100ms 走完
     *   大半行程，观感很「窜」；而这个 fork 没有暴露 `setScroller`，换不了曲线。
     * - 「假拖动」绕过了手势的位移累积：首帧真正滚动的像素与我们给的值并不严格相等。
     *
     * 现在整段动画里 ViewPager **一动不动**（`currentItem` 也不变），叠加层把新页从一侧
     * 推到 0；结束后 `setCurrentItem(target, false)` 一次性落位。
     */
    fun startTapFlip(pages: Int) {
        // 先把上一次还没播完的落位（连点两次时 currentItem 会因此变化，target 必须在那之后算）。
        abortTapFlip()

        val pager = viewer.pager
        val target = pager.currentItem + pages
        // 越界判定走 `itemAt`（`adapter` 在 PagerViewer 里是 private）。
        if (target < 0 || viewer.itemAt(target) == null) return

        if (!prepareSession(pages)) {
            // 没有可用的两页（章节过渡页 / holder 缺失）→ 交回 ViewPager 的默认动画。
            pager.setCurrentItem(target, true)
            return
        }
        startTapAnimator(target)
    }

    /** 会话已备好，真正开始播放。 */
    private fun startTapAnimator(target: Int) {
        if (viewer.config.pageTurnStyle == STYLE_FOLD) {
            // 翻书：两个页 View 都静止，只推进折线；落位时页面早已被折到书脊。
            tapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = tapFlipDuration()
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    overlay.foldProgress = it.animatedValue as Float
                    overlay.invalidate()
                }
                doOnEnd {
                    tapAnimator = null
                    commitTapFlip(target)
                }
                start()
            }
            return
        }
        // 新页的起点就是它在布局里的真实位置（±页宽），方向不用额外判断。
        val startOffset = overlay.sheetOffsetX
        // 覆盖 = 当前页钉住不动、新页压上来；平移 = 两页并排滑动（当前页反向让开）。
        // 平移不画交界投影 —— 两页没有层级关系。
        val slide = viewer.config.pageTurnStyle == STYLE_SLIDE
        overlay.drawShadow = !slide
        val pinnedView = session?.pinnedView
        tapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = tapFlipDuration()
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                overlay.sheetOffsetX = startOffset * (1f - t)
                overlay.progress = t
                // 平移：当前页仍由 ViewPager 绘制，这里只补它的位移（覆盖模式恒为 0）。
                if (slide && pinnedView != null) pinnedView.translationX = -startOffset * t
                overlay.invalidate()
            }
            doOnEnd {
                tapAnimator = null
                commitTapFlip(target)
            }
            start()
        }
    }

    /** 动画途中用户触摸 → 立即落位，把控制权交回手势。 */
    fun abortTapFlip() {
        tapAnimator?.cancel() // cancel 也会走 doOnEnd → commitTapFlip
    }

    /** 点击翻页动画是否正在进行（用于连点时忽略新点击，见 `PagerViewer.moveRight`）。 */
    val isBusy: Boolean
        get() = tapAnimator != null

    private fun commitTapFlip(target: Int) {
        val current = session
        session = null
        overlay.reset()
        // 先落位再复原：落位后新页就是 currentItem，恢复 alpha 不会闪。
        viewer.pager.setCurrentItem(target, false)
        current?.pinnedView?.translationX = 0f
        current?.sheetView?.alpha = 1f
    }

    /**
     * 点击翻页的时长。
     *
     * 没有「页数」参数：`joinedItems` 的一项就是一屏（单页或合并后的整跨），
     * 翻页永远是换 1 项；会跳多项的路径（页码滑杆 / 打开章节 / 恢复进度）都是
     * `setCurrentItem(…, false)` 瞬时到位，不经过动画。
     */
    private fun tapFlipDuration(): Long {
        val base = viewer.config.pageFlipDurationMs
        var factor = if (viewer.isDoubleSpread(viewer.pager.currentItem)) DOUBLE_PAGE_FACTOR else 1f
        // 翻书观感明显比平移/覆盖快 —— 它的画面从第一帧就开始整页位移，
        // 同样的 400ms 视觉变化量最大。这里给它单独放慢一点（400 → 560ms）。
        if (viewer.config.pageTurnStyle == STYLE_FOLD) factor *= FOLD_DURATION_FACTOR
        return (base * factor).toLong()
    }

    /**
     * 预置会话：钉住当前页、藏起新页。
     *
     * 必须在动画开始**之前**做：钉住是按 `left - scrollX` 算的，静止状态下它就是 0，
     * 所以没有任何位移跳变。
     *
     * @return 是否成功备好一张可用的「纸」。
     */
    private fun prepareSession(pages: Int): Boolean {
        // 注意这里判的是「翻页动画是否开着」而不是 [isSupported]（那条要求开了覆盖翻页）：
        // 点击翻页不论平移还是覆盖都由叠加层播，拖动才只有覆盖需要接管。
        if (!canDriveTapFlip()) return false
        val pager = viewer.pager
        val leavingItem = viewer.itemAt(pager.currentItem)
        val enteringItem = viewer.itemAt(pager.currentItem + pages)
        if (leavingItem !is ReaderPage || enteringItem !is ReaderPage) return false
        val leaving = findHolder(leavingItem) ?: return false
        // 新页还没画出来（解码未完成）时**也照样接管**：叠加层画的就是这页现在的样子
        // （可能只有纸色），与普通滑动看到的一致。
        val entering = findHolder(enteringItem) ?: return false

        end()
        val prepared = if (viewer.config.pageTurnStyle == STYLE_FOLD) {
            // 翻书：被折走的是**当前页**（藏起来由叠加层画），下一页保持静止。
            // 折线方向与拖动路径同一条规则（看两页在布局里的左右关系），不再用 `pages` 猜。
            // ⚠️ 必须在 `alpha = 0` **之前**确定绘制源：先藏再取有可能拿到一张空白。
            leaving.alpha = 0f
            // 点击时 ViewPager 完全不滚，下一页还停在 ±页宽（屏幕外）——必须把它**平移进视口**，
            // 否则被揭开的那片区域没有任何东西可画，只剩底色。
            entering.translationX = -(entering.left - pager.scrollX).toFloat()
            Session(
                pinnedView = entering,
                sheetView = leaving,
                // 按**位置序号**判方向：新页在右（pages > 0）⇒ 自由边在右、书脊在左。
                // 不能用 `leaving.left <= entering.left`：相邻位置有时解析到同一个 holder
                // （双页 / 邻居未附着），left 相等时 `<=` 恒为真 ⇒ 前进后退都从右侧折起。
                foldFromRight = pages > 0,
            )
        } else {
            // 覆盖 / 平移：新页藏起来，由叠加层按屏幕位置绘制。
            entering.alpha = 0f
            Session(pinnedView = leaving, sheetView = entering)
        }
        session = prepared
        if (viewer.config.pageTurnStyle == STYLE_FOLD) {
            overlay.foldView = prepared.sheetView
            overlay.spineOnLeft = prepared.foldFromRight
            overlay.grabFraction = grabFraction
            overlay.foldProgress = 0f
            overlay.backTint = ReaderTheme.paperStyleOf(viewer.config.theme)?.whiteColor
                ?: viewer.config.pageCanvasColor
            overlay.invalidate()
        } else {
            applyFrame(prepared, pager.scrollX, 0f)
        }
        return true
    }

    /** `ViewPager` 的滚动回调，唯一的驱动源：拖动与 `setCurrentItem(…, true)` 都走这里。 */
    fun onPageScrolled(position: Int, offset: Float) {
        if (!isSupported() || scrollState == ViewPager.SCROLL_STATE_IDLE) {
            end()
            return
        }

        val pager = viewer.pager
        val scrollX = pager.scrollX
        val current = session ?: startSession(position, scrollX) ?: return
        applyFrame(current, scrollX, offset)
    }

    private fun applyFrame(current: Session, scrollX: Int, offset: Float) {
        if (viewer.config.pageTurnStyle == STYLE_FOLD) {
            // 翻书：两页都钉住（画面完全冻结），折线完全由 offset 驱动 —— 这就是「跟手翻书」。
            current.pinnedView.translationX = -(current.pinnedView.left - scrollX).toFloat()
            current.foldPinned?.let { it.translationX = -(it.left - scrollX).toFloat() }
            // 拖动：实时 View 直接画，不需要快照 —— 折线一跟手就有内容。
            // 进度与方向都按**实际滚动位移**算（相对会话起点），而不是 position/offset：
            // 松手回弹时 offset 会继续朝 1 走，但页面其实在往回，两者一旦反向就会闪。
            val w = viewer.pager.width.coerceAtLeast(1)
            val delta = (scrollX - current.startScrollX).toFloat() / w
            overlay.foldView = current.sheetView
            overlay.spineOnLeft = delta > 0f
            overlay.grabFraction = grabFraction
            overlay.foldProgress = kotlin.math.abs(delta).coerceIn(0f, 1f)
            overlay.canvasColor = ReaderTheme.paperStyleOf(viewer.config.theme)?.whiteColor
                ?: viewer.config.pageCanvasColor
            overlay.invalidate()
            return
        }
        // 钉住当前页：让它相对视口停在 0（抵消 ViewPager 给它的位移）。
        current.pinnedView.translationX = -(current.pinnedView.left - scrollX).toFloat()
        // 新页按它自己的真实屏幕位置绘制，方向由位置本身决定，不需要额外判断。
        overlay.sheetView = current.sheetView
        overlay.sheetOffsetX = (current.sheetView.left - scrollX).toFloat()
        // 纸档的衬底要用纸色（不是纯白）：静态时页面之外的空白处是纸纹底，
        // 纸色是它的均值，乘上同一张纸纹后两者才对得上。
        overlay.canvasColor = ReaderTheme.paperStyleOf(viewer.config.theme)?.whiteColor
            ?: viewer.config.pageCanvasColor
        overlay.progress = offset
        overlay.invalidate()
    }

    /** 滚动状态变化：一旦停稳就复位。 */
    fun onScrollStateChanged(state: Int) {
        scrollState = state
        if (state == ViewPager.SCROLL_STATE_IDLE) {
            loggedThisScroll = false
            end()
        }
    }

    /**
     * 立即结束并复位所有副作用（幂等）。
     *
     * 若点击动画正在进行，这里是**放弃**它（不跳页）；主动落位走 [commitTapFlip]。
     */
    fun end() {
        // 先摘监听再取消：否则 cancel 会走 doOnEnd → commitTapFlip，在收尾途中又去跳页。
        tapAnimator?.also {
            it.removeAllListeners()
            it.cancel()
        }
        tapAnimator = null
        val current = session ?: return
        session = null
        current.pinnedView.translationX = 0f
        current.foldPinned?.translationX = 0f
        current.sheetView.alpha = 1f
        overlay.reset()
    }

    /** 释放（退出阅读器 / 切换阅读模式）。 */
    fun release() {
        end()
        scrollState = ViewPager.SCROLL_STATE_IDLE
    }

    // endregion

    /**
     * 会话启动：把参与这次滚动的两页找出来。
     *
     * 不做方向判断 —— 直接比 `left - scrollX`：滚动刚开始时，当前页几乎贴在视口原点
     * （偏移≈0），另一页还在一屏之外（偏移≈±w）。近的那个是当前页，另一个是新页。
     */
    private fun startSession(position: Int, scrollX: Int): Session? {
        val a = viewer.itemAt(position)
        val b = viewer.itemAt(position + 1)
        // 章节过渡页（PagerTransitionHolder）没有「一整张纸」的语义，保持普通滑动。
        if (a !is ReaderPage || b !is ReaderPage) return null

        val holderA = findHolder(a) ?: return null
        val holderB = findHolder(b) ?: return null
        val distA = kotlin.math.abs(holderA.left - scrollX)
        val distB = kotlin.math.abs(holderB.left - scrollX)
        val pinned = if (distA <= distB) holderA else holderB
        val sheet = if (pinned === holderA) holderB else holderA

        if (viewer.config.pageTurnStyle == STYLE_FOLD) {
            // 翻书：当前页（离原点近的那张）藏起来由叠加层折走，下一页留在下面当底。
            // 两页都要钉住 —— 画面冻结，折线完全由滚动 offset 驱动，才有「跟手」。
            val current = pinned
            val incoming = sheet
            current.alpha = 0f
            current.translationX = 0f
            incoming.translationX = 0f
            if (!loggedThisScroll) {
                loggedThisScroll = true
                android.util.Log.d(
                    FLIP_TAG,
                    "fold start current=${current.item.first.index} " +
                        "incoming=${incoming.item.first.index}",
                )
            }
            // 方向按位置序号：当前页是 `position`（左边那项）⇒ 新页在右 ⇒ 从右侧折起。
            return Session(
                pinnedView = incoming,
                sheetView = current,
                foldPinned = current,
                foldFromRight = pinned === holderA,
                startScrollX = scrollX,
            ).also { session = it }
        }

        // 新页藏起来，改由叠加层画在最上层 —— 否则后退翻页时它会被钉住的当前页盖住。
        sheet.alpha = 0f
        if (!loggedThisScroll) {
            loggedThisScroll = true
            android.util.Log.d(
                FLIP_TAG,
                "cover start pinnedLeft=${pinned.item.first.index} " +
                    "sheetLeft=${sheet.item.first.index} sheetX=${sheet.left - scrollX}",
            )
        }
        return Session(pinnedView = pinned, sheetView = sheet).also { session = it }
    }

    /**
     * 点击翻页是否可以由叠加层接管。
     *
     * 只要「翻页动画」开着就行 —— 平移与覆盖都由 [startTapFlip] 播放（这样时长才可控，
     * `ViewPager` 的 Scroller 在这个 fork 里换不掉）。竖向翻页没有横向的语义，不接管。
     */
    private fun canDriveTapFlip(): Boolean {
        if (viewer is VerticalPagerViewer) return false
        if (viewer !is L2RPagerViewer && viewer !is R2LPagerViewer) return false
        if (viewer.activity.isScrollingThroughPages) return false
        return viewer.config.pageSlideAnimationsEnabled
    }

    /**
     * 这个 viewer 是否支持**拖动**时的接管（与「此刻是否在滚动」无关 —— 点击翻页
     * 需要在滚动开始之前就预置会话，见 [startTapFlip]）。
     *
     * 「覆盖」与「翻书」都接管拖动：前者只钉住当前页，后者把两页都钉住、让折线跟着
     * 手指走（见 [startSession] 与 [applyFrame]）。平移不接管 —— 它本来就是两页并排
     * 滑动，ViewPager 原生就够。
     */
    private fun isSupported(): Boolean {
        // 只有横向的 L2R / R2L 页漫才翻书；竖向翻页没有「纸」的语义。
        if (viewer is VerticalPagerViewer) return false
        if (viewer !is L2RPagerViewer && viewer !is R2LPagerViewer) return false
        // 页码滑杆连续跳页时不接管（跟着手指刷几十页会很吵）。
        if (viewer.activity.isScrollingThroughPages) return false
        return viewer.config.pageFlipActive
    }

    /**
     * 按页找 holder。必须同时比 `item.first` 与 `item.second`：双页模式下 holder 的
     * `item` 是 `page to extraPage` 这一对整个 Pair。
     */
    private fun findHolder(page: ReaderPage): PagerPageHolder? {
        val pager = viewer.pager
        for (i in 0 until pager.childCount) {
            val child = pager.getChildAt(i) as? PagerPageHolder ?: continue
            if (child.item.first == page || child.item.second == page) return child
        }
        return null
    }
}
