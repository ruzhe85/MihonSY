package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.app.ActivityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(
    val activity: ReaderActivity,
    val isContinuous: Boolean = true,
    private val tapByPage: Boolean = false,
) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /** Komiho: 合并中的适配器重建任务（见 [refreshAdapter]），null = 没有待办。 */
    private var refreshJob: Job? = null

    /**
     * Komiho: 跨章预载窗口（下一章第一页）。见 [WebtoonCrossChapterPrewarm] —— 解决
     * 「开了增强时下一章第一页要滑到才开始解码」。
     */
    private val crossChapterPrewarm = WebtoonCrossChapterPrewarm()

    /**
     * Komiho: 跨章预载的看门狗 —— 目标页**已经进入预载区（已被布局、已开始解码 + 增强）**
     * 却迟迟没有渲染完时兜底关窗，避免长期占用额外预载空间（例如该页一直报错 / 引擎卡住）。
     * 只在目标被布局后才武装，所以不会误杀「还在远处、本来就还没开始跑」的窗口。
     */
    private var prewarmWatchdog: Job? = null

    /**
     * Komiho: 上一次「真正重建」时的成像指纹（见 `ViewerConfig.imageFingerprint()`）。
     * 构造时先按当前偏好记下基线，随后 config 里各 register 的首发回调就会因指纹相同而被跳过。
     */
    private var lastImageFingerprint: String? = null

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private var scrollDistance = computeTapScrollDistance()

    /**
     * MihonSY: computes the tap-scroll distance from the configured fraction.
     *
     * The "full screen" preset matches ComicScreen's behavior: scroll one screen
     * height minus a small peek margin, so a little of the next page peeks at the
     * bottom edge and every tap visually "changes one screen". Half/3/4 presets
     * keep their plain fraction-of-screen distance.
     */
    private fun computeTapScrollDistance(): Int {
        // MihonSY fix: use the ACTUAL reader container height, not the physical
        // screen. displayMetrics.heightPixels is the whole display — on tablets
        // (and with system bars / gesture hints / app bars) the visible reader
        // area is much smaller, so "one screen" scrolled only ~1/3. recycler.height
        // is the real laid-out height; fall back to screen height before layout.
        val heightPx = if (recycler.height > 0) recycler.height else activity.resources.displayMetrics.heightPixels
        val fraction = config.tapScrollDistanceFraction
        if (fraction >= 1.0f) {
            // One full screen minus a peek margin (23dp, like ComicScreen's
            // set_menu_pagekey_offset default) so the next page peeks at the bottom.
            val marginPx = (TAP_SCROLL_PEEK_MARGIN_DP * activity.resources.displayMetrics.density).toInt()
            return (heightPx - marginPx).coerceAtLeast(1)
        }
        return (heightPx * fraction).toInt()
    }

    /**
     * Komiho: apply the webtoon prefetch depth to the layout manager's extra
     * layout space. extraLayoutSpace = one tap-scroll distance multiplied by the
     * user's prefetch depth (1 = original ~1-screen behaviour, up to 3 screens).
     * Larger depth pre-binds more pages so NPU enhancement finishes before they
     * scroll into view, eliminating the black flash on arrival.
     *
     * Komiho: 在「接近章末」时再叠加跨章预载窗口的加成（见 [WebtoonCrossChapterPrewarm]），
     * 让下一章第一页也能落进预载区。幂等 —— 值没变就不动，避免滚动 / 布局回调每帧触发重排。
     */
    private fun applyWebtoonPrefetch() {
        // Komiho: 窗口一开/一关就把「准可见」名额同步给 Waifu2x（插队判定在推理侧，见 syncUrgentPage）。
        syncUrgentPage()
        val base = scrollDistance * config.webtoonPrefetchDepth
        val extra = crossChapterPrewarm.extraFor(base, scrollDistance, isEnhancementOn())
        if (layoutManager.extraLayoutSpace == extra) return
        layoutManager.extraLayoutSpace = extra
        recycler.requestLayout()
    }

    /**
     * Komiho: 增强链路是否会真的跑（含「只降噪、不放大」的 mode 0 + 降噪组合，
     * 见 TachiyomiImageDecoder）。纯解码不需要跨章额外提前量。
     */
    private fun isEnhancementOn(): Boolean {
        val preferences = Injekt.get<ReaderPreferences>()
        return preferences.enhancementMode.get() != 0 || preferences.denoiseLevel.get() != 0
    }

    /**
     * MihonSY: animator driving the tap-scroll. A ValueAnimator that steps the
     * recycler by a fixed per-frame delta gives a perfectly constant-speed scroll
     * (like ComicScreen) and avoids the janky ViewFlinger/OverScroller path of
     * RecyclerView.smoothScrollBy. A new tap cancels the previous animation.
     */
    private var scrollAnimator: ValueAnimator? = null

    // Komiho: 即时翻页的双击回滚——第一击翻页前记录第一可见项位置，第二击按下
    // （onDoubleTap → recycler.doubleTapUndo）时恢复到该位置再放大，观感即
    // 「直接放大」而不是先滚一屏。MENU 区点击不记录（见 tapListener）。
    private var flipRollback: (() -> Unit)? = null
    private var flipRollbackAt = 0L

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance * config.webtoonPrefetchDepth)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    /* [EXH] private */
    var currentPage: Any? = null

    private val threshold: Int =
        Injekt.get<ReaderPreferences>()
            .readerHideThreshold
            .get()
            .threshold

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                // Komiho (2026-10-01): 手指拖动期间让原生推理让位（见 Waifu2x.setUiBusy）。
                // 条漫只认 DRAGGING（手指真在动）：SETTLING 的惯性滚动也占位会把增强饿死
                // —— 单页推理是 8 秒级，而连续滚动时惯性期很长。
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    Waifu2x.setUiBusy(newState == RecyclerView.SCROLL_STATE_DRAGGING)
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> {
                    markFlipForRollback()
                    scrollDown()
                }
                NavigationRegion.PREV, NavigationRegion.LEFT -> {
                    markFlipForRollback()
                    scrollUp()
                }
            }
        }
        // Komiho: 双击缩放时撤销第一击的即时翻页——取消翻页动画并把列表瞬间
        // 恢复到翻页前位置，随后 ACTION_UP 的 onDoubleTapConfirmed 正常放大。
        recycler.doubleTapUndo = f@{
            val rollback = flipRollback
            flipRollback = null
            // 只回滚双击窗口内刚发生的翻页；陈旧快照（间隔过久、三击连按）直接丢弃
            if (rollback == null || SystemClock.uptimeMillis() - flipRollbackAt > DOUBLE_TAP_ROLLBACK_WINDOW_MS) {
                return@f
            }
            scrollAnimator?.cancel()
            rollback()
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            config.onImagePropertyChanged()
        }
        config.bindRefreshAdapter { refreshAdapter() }

        // 基线：此刻适配器就是按这些设置建的，所以紧接着的首发回调不该触发重建。
        lastImageFingerprint = config.imageFingerprint()

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        // MihonSY: keep tap-scroll distance and animation speed in sync with settings
        config.tapScrollChangedListener = {
            scrollDistance = computeTapScrollDistance()
            applyWebtoonPrefetch()
        }

        // Komiho: prefetch depth changed in settings -> recompute extra layout space now
        config.prefetchChangedListener = {
            applyWebtoonPrefetch()
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)

        // MihonSY fix: recompute the tap-scroll distance once the reader is actually
        // laid out — recycler.height is the real visible height (correct on tablets,
        // where the physical screen height is much larger than the reader area).
        // Settings changes also trigger this via tapScrollChangedListener below.
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val newDistance = computeTapScrollDistance()
            if (newDistance != scrollDistance) {
                scrollDistance = newDistance
                applyWebtoonPrefetch()
            }
        }
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        scrollAnimator?.cancel()
        cancelPrewarmWatchdog()
        crossChapterPrewarm.cancel("viewer-destroy")
        syncUrgentPage()
        super.destroy()
        scope.cancel()
    }

    /**
     * Komiho: holder 报告「这一页的图片（含增强）已经真正落地」（成功与失败终态都会调）。
     *
     * 三个用途：
     * 1. 记账跨章预载目标（日志 + 取消看门狗 + 撤销「准可见」）；
     * 2. 目标渲染完成后撤销 [Waifu2x.urgentPageIndex] —— 它已经出图，再占着插队名额只会让其它
     *    预取多等一轮；
     * 3. 预载窗口开启期间补一次布局 —— 章末页解码完成后高度会从「一屏」涨到真实高度，预载区的
     *    剩余空间随之重新分配，这里补一次 `requestLayout` 让新腾出的空间立刻用来让下一章第一页
     *    被 bind（否则要等用户下一次滚动才可能补上）。`requestLayout` 在同一帧内幂等
     *    （PFLAG_FORCE_LAYOUT），不需要额外的同帧合并标志。
     */
    fun onPageRendered(page: ReaderPage) {
        crossChapterPrewarm.onTargetRendered(page)
        if (crossChapterPrewarm.isActiveFor(page)) cancelPrewarmWatchdog()
        syncUrgentPage()
        if (crossChapterPrewarm.isOpen) recycler.requestLayout()
    }

    /**
     * Komiho: 把「跨章预载目标页」登记给 [Waifu2x.urgentPageIndex]，让它的 AI 推理**插队**到同批
     * 预载的中间页之前 —— 正向布局下目标页是最后入队的，不插队的话「提前几屏 bind」换来的提前量
     * 会被刚落进预载区的中间页吃光（它们先占住原生推理锁）。
     *
     * 撤销时机：窗口关闭 / 目标被替换 / 目标渲染完成 / 离开阅读器 —— 都在调用点触发。
     */
    private fun syncUrgentPage() {
        val prewarm = crossChapterPrewarm
        val target = prewarm.activeTarget
        Waifu2x.urgentPageIndex =
            if (prewarm.isOpen && target != null && !prewarm.isTargetRendered) target.index else -1
    }

    /**
     * Komiho: 供 [Waifu2x] 可见页优先闸门使用的「正在看的那一页」。
     *
     * 刻意不用 `currentPage`（= 底边可见的最后一页，往往只是屏幕最底部刚露头的那页），改用
     * **视口中线所在的那一页**：条漫连续阅读时视线在屏幕中部，闸门保护的对象才对得上。
     * 中线上没有 ReaderPage（过渡页 / 尚未布局）时退回 [currentPage]，再取不到就保留上一个值
     * （比清成 -1 更安全：清掉等于闸门失效）。
     *
     * 只服务于优先级判定，不参与任何功能逻辑。
     */
    private fun visiblePageIndexForGate(): Int {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first >= 0 && last >= first) {
            val centerY = recycler.paddingTop +
                (recycler.height - recycler.paddingTop - recycler.paddingBottom) / 2
            for (position in first..last) {
                val view = layoutManager.findViewByPosition(position) ?: continue
                if (view.top <= centerY && view.bottom >= centerY) {
                    (adapter.items.getOrNull(position) as? ReaderPage)?.let { return it.index }
                    break
                }
            }
        }
        return (currentPage as? ReaderPage)?.index ?: Waifu2x.visiblePageIndex
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        // Komiho: 刷新跨章预载目标（下一章第一页）。是否开窗由紧随其后的 recheck 决定 ——
        // 「当前读到本章第几页」这个判据只有那里才有。
        val nextFirstPage = chapters.nextChapter
            ?.takeIf { it !== chapters.currChapter }
            ?.pages
            ?.firstOrNull()
        if (crossChapterPrewarm.onChaptersChanged(nextFirstPage)) {
            cancelPrewarmWatchdog()
            applyWebtoonPrefetch()
        }
        // Komiho: 目标刚就位（或章节数据刚变）就把窗口决策做掉，不必等用户下一次滚动。
        // 这里只做与下标无关的部分 —— adapter 的更新还没落地，见 recheckCrossChapterPrewarm 的说明。
        updatePrewarmWindow()

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }

        // Komiho P3：把「正在看的那一页」登记给 Waifu2x 的可见页优先闸门。原先只有 PagerViewer 会写
        // （见 Waifu2x.visiblePageIndex 的说明），条漫一直是 -1 ⇒ 闸门对条漫完全失效；
        // 跨章预载会额外多布几屏页，登记之后预取才会给正在看的那一页让路。
        Waifu2x.visiblePageIndex = visiblePageIndexForGate()

        // Komiho: 跨章预载窗口复核（幂等）。放在最后 —— 此时 currentPage 才是最新的。
        recheckCrossChapterPrewarm()
    }

    /** 距章末还剩几页。判据取自 [currentPage] 这个**对象**，与 RecyclerView 下标无关；null = 未知。 */
    private fun distanceFromChapterEnd(): Int? {
        val page = currentPage as? ReaderPage ?: return null
        val pages = page.chapter.pages ?: return null
        return pages.size - page.number
    }

    /**
     * Komiho: 只做「开 / 关窗」决策（见 [WebtoonCrossChapterPrewarm.onDistanceFromEnd]）。
     *
     * 不碰任何 RecyclerView 下标，所以在 `setChapters` 之后**可以立刻调用** —— 目标(下一章首页)
     * 刚就位时就把窗口开起来，不必等用户下一次滚动。
     */
    private fun updatePrewarmWindow() {
        val distance = distanceFromChapterEnd() ?: return
        if (crossChapterPrewarm.onDistanceFromEnd(distance)) applyWebtoonPrefetch()
    }

    /**
     * Komiho: 复核跨章预载窗口（见 [WebtoonCrossChapterPrewarm]）。
     *
     * 1. 距章末足够近 → 开窗（把 extra space 抬到 3 屏，下一章第一页才有机会被提前 bind）；
     *    滑离章末 → 关窗（滞回）；
     * 2. 目标页进入视口 / 已成为当前页 → 关窗；
     * 3. 目标页已被布局（说明已落进预载区、已在解码 + 增强）却迟迟没渲染完 → 武装看门狗兜底。
     *
     * ⚠️ 2/3 依赖 `adapter.items` 与 child 的 **position 同源**：DiffUtil 的更新要到下一次布局
     * （`consumePendingUpdateOperations`）才真正落地，`setChapters` 之后立刻查会拿到旧下标。
     * 所以本方法只在布局完成后的滚动 / 布局回调里调用，`setChapters` 那边只用 [updatePrewarmWindow]。
     */
    private fun recheckCrossChapterPrewarm() {
        updatePrewarmWindow()

        val target = crossChapterPrewarm.activeTarget ?: return
        val targetPosition = adapter.items.indexOf(target)
        if (targetPosition < 0) {
            // 目标已经不在列表里（章节数据变了 / 不再是下一章）→ 关窗。
            if (crossChapterPrewarm.cancel("target-not-in-adapter")) applyWebtoonPrefetch()
            cancelPrewarmWatchdog()
            return
        }
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val targetOnScreen = firstVisible >= 0 &&
            targetPosition in firstVisible..layoutManager.findLastVisibleItemPosition()
        if (currentPage === target || targetOnScreen) {
            if (crossChapterPrewarm.onTargetArrived(target)) applyWebtoonPrefetch()
            cancelPrewarmWatchdog()
            return
        }
        if (crossChapterPrewarm.isOpen &&
            !crossChapterPrewarm.isTargetRendered &&
            layoutManager.findViewByPosition(targetPosition) != null
        ) {
            armPrewarmWatchdog(target)
        }
    }

    /**
     * Komiho: 武装跨章预载看门狗。只在**目标页真的被布局、且还没渲染完**之后才计时（否则会把
     * 「窗口开了但目标还在很远处、本来就还没轮到它跑」误判成超时，或把已经算好的页误杀），
     * 超时即关窗回落到用户设定深度并记一条 WARN。
     */
    private fun armPrewarmWatchdog(target: ReaderPage) {
        if (prewarmWatchdog != null) return
        prewarmWatchdog = scope.launch {
            delay(PREWARM_WATCHDOG_MS)
            prewarmWatchdog = null
            if (crossChapterPrewarm.isOpenFor(target) && crossChapterPrewarm.cancel("watchdog-timeout")) {
                android.util.Log.w(
                    KOMIHA_PREWARM_TAG,
                    "prewarm watchdog: page=${target.index} 布局后 ${PREWARM_WATCHDOG_MS}ms " +
                        "仍未渲染完成，关窗回落用户设定深度",
                )
                applyWebtoonPrefetch()
            }
        }
    }

    private fun cancelPrewarmWatchdog() {
        prewarmWatchdog?.cancel()
        prewarmWatchdog = null
    }

    private var lastAnimatedValue: Int = 0

    /**
     * MihonSY: performs a tap-scroll of [totalDistance] pixels with a constant
     * (linear) speed over [durationMillis]. Each animation frame scrolls the
     * recycler by an equal delta, which is what makes the motion feel smooth
     * and uniform instead of chunky. A previously running animation is
     * cancelled first so rapid taps never fight each other.
     *
     * @param totalDistance signed scroll distance in pixels (negative = scroll up)
     * @param durationMillis animation duration; <= 0 means jump instantly
     * @param easeOut true = 翻页动画 v2：三次方减速曲线（起步快、尾段短）
     */
    private fun animateScrollBy(totalDistance: Int, durationMillis: Int, easeOut: Boolean = false) {
        // Cancel any running animation first so rapid taps never overlap.
        scrollAnimator?.cancel()
        if (durationMillis <= 0 || totalDistance == 0) {
            recycler.scrollBy(0, totalDistance)
            return
        }

        val animator = ValueAnimator.ofInt(0, totalDistance).apply {
            this.duration = durationMillis.toLong()
            interpolator = if (easeOut) EASE_OUT_CUBIC else LinearInterpolator()

            addUpdateListener {
                val animated = it.animatedValue as Int
                // Scroll by the difference since the last frame: v1（线性）得到恒定
                // 每帧位移；v2 由插值器给出五次方减速的每帧位移（先快后慢）。
                val delta = animated - lastAnimatedValue
                lastAnimatedValue = animated
                if (delta != 0) {
                    recycler.scrollBy(0, delta)
                }
            }

            doOnEnd {
                lastAnimatedValue = 0
                // Only clear the field if we're still the active animator; a
                // newer animation may have replaced us after cancel().
                if (scrollAnimator === this) {
                    scrollAnimator = null
                }
            }
        }
        lastAnimatedValue = 0
        scrollAnimator = animator
        animator.start()
    }

    /**
     * Komiho 翻页动画 v2：时长按滚动距离算——
     * duration = (|距离| / 可视高度 + 1) × 速度档位（50/100/150/200ms），封顶 2000ms。
     * 以默认 100ms 档为例：整屏（屏高 − 23dp peek）约 197ms、3/4 屏约 175ms、
     * 半屏约 150ms。距离越长越慢，档位越小越快。
     */
    private fun computeEaseOutDuration(totalDistance: Int): Int {
        val heightPx = if (recycler.height > 0) {
            recycler.height
        } else {
            activity.resources.displayMetrics.heightPixels
        }.coerceAtLeast(1)
        val screens = kotlin.math.abs(totalDistance).toFloat() / heightPx
        return ((screens + 1f) * config.pageTransitionsV2SpeedMs)
            .toLong()
            .coerceAtMost(EASE_OUT_DURATION_MAX_MS)
            .toInt()
    }

    /**
     * Komiho: 翻页前记录第一可见项位置及偏移，供双击缩放时回滚（见 doubleTapUndo）。
     */
    private fun markFlipForRollback() {
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position < 0) return
        val offset = layoutManager.findViewByPosition(position)?.top ?: 0
        flipRollback = {
            recycler.stopScroll()
            layoutManager.scrollToPositionWithOffset(position, offset)
        }
        flipRollbackAt = SystemClock.uptimeMillis()
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        // Komiho: v1（匀速，固定时长）/ v2（五次方减速，时长按距离算）互斥，v2 优先。
        val useV2 = config.usePageTransitionsV2
        if (useV2 || config.usePageTransitions) {
            val duration = if (useV2) computeEaseOutDuration(-scrollDistance) else config.tapScrollDurationMillis
            animateScrollBy(-scrollDistance, duration, easeOut = useV2)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls one screen over a period of time
     */
    fun linearScroll(duration: Duration) {
        animateScrollBy(
            activity.resources.displayMetrics.heightPixels,
            duration.inWholeMilliseconds.toInt(),
        )
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    /* [EXH] private */
    fun scrollDown() {
        // SY -->
        if (!isContinuous && tapByPage) {
            val currentPage = currentPage
            if (currentPage is ReaderPage) {
                val position = adapter.items.indexOf(currentPage)
                val nextItem = adapter.items.getOrNull(position + 1)
                if (nextItem is ReaderPage) {
                    // v2 同样走平滑滚动（这条路径是整页对齐，曲线由系统 smooth scroll 决定）
                    if (config.usePageTransitions || config.usePageTransitionsV2) {
                        recycler.smoothScrollToPosition(position + 1)
                    } else {
                        recycler.scrollToPosition(position + 1)
                    }
                    return
                }
            }
        }
        scrollDownBy()
    }

    private fun scrollDownBy() {
        // SY <--
        val useV2 = config.usePageTransitionsV2
        if (useV2 || config.usePageTransitions) {
            val duration = if (useV2) computeEaseOutDuration(scrollDistance) else config.tapScrollDurationMillis
            animateScrollBy(scrollDistance, duration, easeOut = useV2)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        // Komiho (2026-09-24): 只有「成像设置真的变了」才重建。config 里每个 `register()` 在订阅时
        // 都会**首发一次当前值**（`AndroidPreference.changes()` 的 `onStart { emit(…) }`），
        // `distinctUntilChanged` 在赋值之后、挡不住这第一次 —— viewer 刚建好时十几个 register 各发
        // 一次，全部打到这个回调上。而每次重建都会销毁所有可见 holder、让它们**重跑解码 + 增强**
        // （请求 memory/disk 双 DISABLED，没有缓存兜底），于是刚打开一本书就会把可见页渲染两遍。
        // 指纹把「首发噪音」与「用户真改了设置」区分开；指纹相同直接跳过。
        val fingerprint = config.imageFingerprint()
        if (fingerprint == lastImageFingerprint) {
            android.util.Log.d(KOMIHA_REBUILD_TAG, "skip rebuild: image settings unchanged")
            return
        }
        lastImageFingerprint = fingerprint

        // 合并连续触发：一串真变更并成一次重建（理由同上，代价与上面一致）。
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(REFRESH_COALESCE_DELAY_MS)
            android.util.Log.d(KOMIHA_REBUILD_TAG, "rebuild adapter (coalesced)")
            rebuildAdapter()
        }
    }

    /** 真正重建适配器：销毁并重建所有可见 holder，按最新图像设置重解码。 */
    private fun rebuildAdapter() {
        // Komiho: 重建会让全部 holder 作废，先关掉跨章预载窗口（目标保留 —— 下一章第一页这个
        // 目标本身没变，重建结束后随 onScrolled 复核可以重新开窗）。
        cancelPrewarmWatchdog()
        if (crossChapterPrewarm.closeWindow("adapter-rebuild")) {
            // 窗口关了就回落用户设定深度；注意这里不 requestLayout —— 紧接着就要重建适配器。
            layoutManager.extraLayoutSpace = scrollDistance * config.webtoonPrefetchDepth
        }
        syncUrgentPage()
        // 强制重建适配器（与 pager 的 pager.adapter = adapter 同款）：销毁并重建所有可见
        // WebtoonPageHolder，重新走加载链并按最新增强设置重解码，保证切换增强实时生效。
        // 重设 adapter 会清空滚动位置，故先记下首可见项与像素偏移，重建后再还原，避免跳页。
        val lm = layoutManager as? LinearLayoutManager
        val firstPos = lm?.findFirstVisibleItemPosition() ?: 0
        val firstView = if (firstPos >= 0) lm?.findViewByPosition(firstPos) else null
        val offset = firstView?.let { it.top - recycler.paddingTop } ?: 0
            recycler.adapter = adapter
        if (firstPos >= 0) {
            lm?.scrollToPositionWithOffset(firstPos, offset)
        }
    }

    override fun deferImagePropertyRefresh() = config.deferImagePropertyRefresh()

    override fun flushImagePropertyRefresh() = config.flushImagePropertyRefresh()
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private val RECYCLER_VIEW_CACHE_SIZE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 4 else 2

// MihonSY: bottom peek margin (dp) left un-scrolled for the "full screen" tap-scroll
// preset, mirroring ComicScreen's set_menu_pagekey_offset default (23dp). A sliver of
// the next page stays visible so each tap feels like one full screen changed.
private const val TAP_SCROLL_PEEK_MARGIN_DP = 23f

/** Komiho 诊断：适配器重建日志（pager 侧同名 tag，便于一起 grep）。 */
private const val KOMIHA_REBUILD_TAG = "Waifu2xRebuild"

/** Komiho 诊断：跨章预载窗口日志（与 WebtoonPageHolder / WebtoonCrossChapterPrewarm 同值）。 */
private const val KOMIHA_PREWARM_TAG = "Waifu2xWebtoon"

/**
 * Komiho: 跨章预载看门狗超时 —— 目标页**已被布局**（即已落进预载区、已开始解码 + 增强）之后，
 * 还等不到渲染完成就兜底关窗。宽到足以覆盖最慢的 AI 单页，只拦「一直没跑出来」的异常情况。
 */
private const val PREWARM_WATCHDOG_MS = 20_000L

/** Komiho: 适配器重建的合并窗口 —— 把同一批 register 首发回调并成一次重建。 */
private const val REFRESH_COALESCE_DELAY_MS = 120L

// Komiho 翻页动画 v2 的曲线：三次方减速 (t-1)^3 + 1（等价于 1-(1-t)^3）。
// ComicScreen / RecyclerView 默认用的是五次方（(t-1)^5+1），但五次方在 50% 时间就
// 走完 97% 路程，后半程几乎看不见移动却在耗时间，主观很拖。三次方 50% 时间走完
// 87.5%，尾巴短得多，点起来更脆快，同时保留"快起慢停"的减速手感。
private val EASE_OUT_CUBIC = Interpolator { t ->
    val f = t - 1
    f * f * f + 1f
}

// v2 时长上限：无论距离多长都不超过 2000ms。
// 每屏基准时长由「翻页动画 v2 速度」设置项决定（50/100/150/200），默认 100。
private const val EASE_OUT_DURATION_MAX_MS = 2000L

// Komiho: 双击回滚窗口——GestureDetector 的双击判定窗口是 DOUBLE_TAP_TIMEOUT
// （300ms），双击的第二击按下必然落在此窗口内；取 350ms 留余量，超过即视为
// 陈旧快照丢弃（如间隔较久的后续双击，不再回滚第一次的翻页）。
private const val DOUBLE_TAP_ROLLBACK_WINDOW_MS = 350L
