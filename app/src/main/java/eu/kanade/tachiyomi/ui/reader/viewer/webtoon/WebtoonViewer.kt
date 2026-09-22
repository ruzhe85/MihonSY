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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
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
     */
    private fun applyWebtoonPrefetch() {
        layoutManager.extraLayoutSpace = scrollDistance * config.webtoonPrefetchDepth
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
            refreshAdapter()
        }

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
        super.destroy()
        scope.cancel()
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
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private val RECYCLER_VIEW_CACHE_SIZE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 4 else 2

// MihonSY: bottom peek margin (dp) left un-scrolled for the "full screen" tap-scroll
// preset, mirroring ComicScreen's set_menu_pagekey_offset default (23dp). A sliver of
// the next page stays visible so each tap feels like one full screen changed.
private const val TAP_SCROLL_PEEK_MARGIN_DP = 23f

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
