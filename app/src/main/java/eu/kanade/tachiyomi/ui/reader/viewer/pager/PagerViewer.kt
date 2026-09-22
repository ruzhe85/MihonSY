package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.math.abs
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(val activity: ReaderActivity) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    val scope = MainScope()

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    val pager = createPager()

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = PagerConfig(this, scope)

    // SY（A+B，Page 流畅度优化）：预处理结果缓存 + 相邻页预热。
    // 借鉴 webtoon「解码好了等你滑」：翻页选定后，后台提前把相邻页的
    // stream 物化/处理/增强预解码做完，holder 实例化时直接命中缓存。
    val preparedCache = PagerPreparedCache()

    /** 预热串行锁：避免前后两页同时跑 Lanczos 预解码打满 CPU（教训同 offscreen 调高）。 */
    private val prewarmMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Komiho P1：预热「意图代数」。每次翻页自增；排队等锁的预热任务若发现代数已变，
     * 说明用户又翻了页、目标已过期，直接放弃（避免白跑一次 1–3 秒的 GPU 增强）。
     */
    private val prewarmGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Komiho P1：正在执行预热的页位置（-1 = 空闲）。翻页后若它离当前页太远，
     * 说明 GPU 正在为一个已经没人要的页面做推理 → 主动 abort（C++ 在 tile 边界生效，很快）。
     */
    @Volatile
    private var runningPrewarmPosition = -1

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    /* [EXH] private */
    var currentPage: ReaderItem? = null

    /**
     * Viewer chapters to set when the pager enters idle mode. Otherwise, if the view was settling
     * or dragging, there'd be a noticeable and annoying jump.
     */
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Whether the view pager is currently in idle mode. It sets the awaiting chapters if setting
     * this field to true.
     */
    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersDoubleShift(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
                    }
                }
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            // SY -->
            if (pager.isRestoring) return
            // SY <--
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            onPageChange(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        // SY: 离屏缓冲 = 1（前后各 1 页）。流畅度由「并发解码密度」决定而非 Mutex：
        // offscreen 越高，ViewPager 同时撑起的页面越多，5000×2400 整页解码+Lanczos 增强
        // 并发打满 CPU/GC 导致掉帧。降回 1 恢复最早顺滑感；独立的 ByteArray 内存流已
        // 根治大跳页「解码失败」错误与活 archive 流崩溃，不会随 offscreen 变化复发。
        // 代价：极快连翻可能偶现黑屏（同最早，但无 Mutex 拖慢会比最早轻）。条页模式不变。
        // Komiho：现在这个值由「阅读设置 → 预载页数」控制（1/2/3，默认 1），见 PagerConfig；
        // 抬高仍是上面那笔账：每档多 2 页已解码的增强位图 + 每页一次推测性 GPU 推理。
        pager.offscreenPageLimit = config.offscreenPageLimit
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
            }
        }
        pager.longTapListener = f@{
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val item = adapter.joinedItems.getOrNull(pager.currentItem)
                val firstPage = item?.first as? ReaderPage
                val secondPage = item?.second as? ReaderPage
                if (firstPage is ReaderPage) {
                    activity.onPageLongTap(firstPage, secondPage)
                    return@f true
                }
            }
            false
        }

        config.dualPageSplitChangedListener = { enabled ->
            if (!enabled) {
                cleanupPageSplit()
            }
        }

        config.reloadChapterListener = {
            preparedCache.clear() // SY: 章节重载（如加密包换密码）后旧预处理结果一律作废
            activity.reloadChapters(it)
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
    }

    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return pager
    }

    /**
     * Returns the PagerPageHolder for the provided page
     */
    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance<PagerPageHolder>()
            .firstOrNull { it.item.first == page || it.item.second == page }

    /**
     * [item] 是否为当前显示项（Komiho P3 判「可见页」用；双页时 item = page to extraPage）。
     */
    fun isCurrentItem(item: Any): Boolean = adapter.joinedItems.getOrNull(pager.currentItem) == item

    /**
     * Komiho P3：可见页优先。
     *
     * 记下「最近一份开始跑的重活是第几页」，并在**可见页**开始跑时，若上一份是别的页，
     * 调 [Waifu2x.abortProcessing] 把它打断 —— 原生在 tile 边界返回（几十 ms），把 GPU 让给当前页。
     *
     * 为什么需要：GPU 只有一个引擎，且原生 `g_lock` 覆盖**整次推理**（双页一次 ≈2.5s）。
     * GPU 上没有优先级，谁先进 `nativeProcess` 谁先跑 ⇒ 快速连翻或单↔双页切换时，
     * 新可见页会被上一页/邻居的推理拖着排队（实测 5 次推理 wait 累积到 9.7s、total 12.1s，
     * 可见页排在第 3）。
     *
     * 安全性：[Waifu2x.process] 每次进入都会先 `nativeClearAbortProcessing()`，所以「打断」
     * 只对**当前正在跑的那一次**推理生效；对排队中的、以及后续任务都是空操作。
     */
    fun onPrepareStart(pageIndex: Int, visible: Boolean) {
        val previous = lastPreparePage
        lastPreparePage = pageIndex
        if (!visible || previous < 0 || previous == pageIndex) return
        prewarmLog("preempt page=$previous by-page=$pageIndex")
        Waifu2x.abortProcessing()
    }

    /** 最近一份开始跑的重活是第几页（-1 = 还没跑过）。见 [onPrepareStart]。 */
    @Volatile
    private var lastPreparePage = -1

    /**
     * Called when a new page (either a [ReaderPage] or [ChapterTransition]) is marked as active
     */
    fun onPageChange(position: Int) {
        val pagePair = adapter.joinedItems.getOrNull(position)
        val page = pagePair?.first
        if (page != null && currentPage != page) {
            val allowPreload = checkAllowPreload(page as? ReaderPage)
            val forward = when {
                currentPage is ReaderPage && page is ReaderPage -> {
                    // if both pages have the same number, it's a split page with an InsertPage
                    if (page.number == (currentPage as ReaderPage).number) {
                        // the InsertPage is always the second in the reading direction
                        page is InsertPage
                    } else {
                        page.number > (currentPage as ReaderPage).number
                    }
                }
                currentPage is ChapterTransition.Prev && page is ReaderPage ->
                    false
                else -> true
            }
            currentPage = page
            when (page) {
                is ReaderPage -> onReaderPageSelected(page, allowPreload, forward, pagePair.second != null)
                is ChapterTransition -> onTransitionSelected(page)
            }
        }
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        // Allow preload for
        // 1. Going to next chapter from chapter transition
        // 2. Going between pages of same chapter
        // 3. Next chapter page
        return when (page.chapter) {
            (currentPage as? ChapterTransition.Next)?.to -> true
            (currentPage as? ReaderPage)?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    /**
     * Called when a [ReaderPage] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onReaderPageSelected(page: ReaderPage, allowPreload: Boolean, forward: Boolean, hasExtraPage: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onReaderPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page, hasExtraPage)

        // Notify holder of page change
        getPageHolder(page)?.onPageSelected(forward)

        // Skip preload on inserts it causes unwanted page jumping
        if (page is InsertPage) {
            return
        }

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
        }

        // SY（A）：相邻页预热（前+后各一页），解码好等用户翻
        prewarmAdjacentPages(forward)
    }

    /**
     * 预热当前页相邻页（offscreen=1 之外的"第 2 页"由此获得与 webtoon 同级的提前量）。
     * 双页合并配置（pair.second != null）会命中副作用分支，预热无意义，跳过。
     */
    // SY（OOM 降峰）：增强开启时预热解码含全流程，峰值更高——只预热 1 页。
    // Komiho P1：该页的**方向跟随阅读方向**（原先固定「下一页」，导致回翻永远无预热）；
    // 另加「意图代数 + 运行位置」两个状态：翻页后排队中的过期预热直接放弃、
    // GPU 上正在为已跑远的目标做的推理主动 abort，避免新目标被旧任务拖住。
    private val readerPrefs: ReaderPreferences by injectLazy()

    private fun prewarmAdjacentPages(forward: Boolean) {
        val enhancementOn = readerPrefs.enhancementMode.get() != 0
        val generation = prewarmGeneration.incrementAndGet()

        if (enhancementOn) {
            val running = runningPrewarmPosition
            if (running >= 0 && abs(running - pager.currentItem) > 1) {
                Waifu2x.abortProcessing()
            }
        }

        val positions = if (enhancementOn) {
            listOf(if (forward) pager.currentItem + 1 else pager.currentItem - 1)
        } else {
            listOf(pager.currentItem + 1, pager.currentItem - 1)
        }
        for (position in positions) {
            val pair = adapter.joinedItems.getOrNull(position)
            if (pair == null) {
                prewarmLog("skip pos=$position reason=no-item")
                continue
            }
            if (pair.second != null) {
                prewarmLog("skip pos=$position reason=dual-page-pair")
                continue // 双页合并模式：纯管线放弃，预热无收益
            }
            val next = pair.first as? ReaderPage
            if (next == null) {
                prewarmLog("skip pos=$position reason=not-ReaderPage(${pair.first?.javaClass?.simpleName})")
                continue
            }
            if (next is InsertPage) {
                prewarmLog("skip pos=$position reason=InsertPage")
                continue // 插入页没有自己的流，预热无意义
            }
            val key = preparedCache.key(next, pair.second as? ReaderPage)
            if (preparedCache.get(key) != null) {
                prewarmLog("skip pos=$position page=${next.index} reason=cache-hit")
                continue
            }
            prewarmLog("launch pos=$position page=${next.index} gen=$generation")
            scope.launchIO {
                prewarmMutex.withLock {
                    // Komiho P1：等锁期间用户可能又翻了页 —— 目标过期就放弃，否则会白跑
                    // 一次昂贵的增强（GPU 1–3 秒），把真正需要的页面继续往后推。
                    if (generation != prewarmGeneration.get()) {
                        prewarmLog(
                            "abort page=${next.index} reason=stale-gen " +
                                "gen=$generation now=${prewarmGeneration.get()}",
                        )
                        return@withLock
                    }
                    // 拿到锁后复查：可能已被相邻预热或 holder 计算填入
                    if (preparedCache.get(key) != null) {
                        prewarmLog("abort page=${next.index} reason=cache-filled-while-waiting")
                        return@withLock
                    }
                    runningPrewarmPosition = position
                    // Komiho P3：把预热也登记进「谁在跑」，这样可见页 holder 开始跑时能把它打断
                    // （否则新可见页会排在一条已无人要的预热推理后面，实测可拖到 +2.5s）。
                    onPrepareStart(pageIndex = next.index, visible = false)
                    try {
                        val prepared = PagerPagePreparer.preparePure(
                            viewer = this@PagerViewer,
                            page = next,
                            extraPage = pair.second as? ReaderPage,
                            viewHeight = pager.height,
                        )
                        prewarmLog(
                            "done page=${next.index} ok=${prepared != null} " +
                                "enhanceMs=${prepared?.enhanceElapsedMillis} " +
                                "layoutApplied=${prepared?.layoutApplied}",
                        )
                        prepared?.let { preparedCache.put(key, it) }
                    } finally {
                        runningPrewarmPosition = -1
                    }
                }
            }
        }
    }

    /**
     * Called when a [ChapterTransition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        } else if (transition is ChapterTransition.Next) {
            // No more chapters, show menu because the user is probably going to close the reader
            activity.showMenu()
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersDoubleShift(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        // Remove listener so the change in item doesn't trigger it
        pager.removeOnPageChangeListener(pagerListener)

        val forceTransition =
            config.alwaysShowChapterTransition ||
                adapter.joinedItems.getOrNull(pager.currentItem)?.first is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        // Layout the pager once a chapter is being set
        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            pager.isVisible = true
        }

        pager.addOnPageChangeListener(pagerListener)
        // Manually call onPageChange to update the UI
        onPageChange(pager.currentItem)
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.joinedItems.indexOfFirst { it.first == page || it.second == page }
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, true)
            // manually call onPageChange since ViewPager listener is not triggered in this case
            if (currentPosition == position) {
                onPageChange(position)
            } else {
                // Call this since with double shift onPageChange wont get called (it shouldn't)
                // Instead just update the page count in ui
                val joinedItem = adapter.joinedItems.firstOrNull { it.first == page || it.second == page }
                activity.onPageSelected(
                    joinedItem?.first as? ReaderPage ?: page,
                    joinedItem?.second != null,
                )
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    /**
     * Moves to the next page.
     */
    open fun moveToNext() {
        moveRight()
    }

    /**
     * Moves to the previous page.
     */
    open fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanRight()) {
                holder.panRight()
            } else {
                pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        if (pager.currentItem != 0) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanLeft()) {
                holder.panLeft()
            } else {
                pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected open fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected open fun moveDown() {
        moveToNext()
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        preparedCache.clear() // SY: 图像配置变更（分割/裁剪/背景等）后旧结果全部失效
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) {
                    if (ctrlPressed) moveToNext() else moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) {
                    if (ctrlPressed) moveToPrevious() else moveLeft()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        activity.runOnUiThread {
            // Need to insert on UI thread else images will go blank
            adapter.onPageSplit(currentPage, newPage)
        }
    }

    private fun cleanupPageSplit() {
        adapter.cleanupPageSplit()
    }

    // SY -->
    fun setChaptersDoubleShift(chapters: ViewerChapters) {
        setChaptersInternal(chapters)
    }

    fun updateShifting(page: ReaderPage? = null) {
        adapter.pageToShift = page ?: adapter.joinedItems.getOrNull(pager.currentItem)?.first as? ReaderPage
    }

    fun splitDoublePages(currentPage: ReaderPage) {
        adapter.splitDoublePages(currentPage)
    }

    fun getShiftedPage(): ReaderPage? = adapter.pageToShift
    // SY <--
}

/**
 * Komiho 诊断（临时，排查完可删）：记录预载路径的每一次决策，用来回答
 * 「为什么预载从来没有产出过增强结果」。release 下 logcat() 的 DEBUG 会被
 * XLog 的 WARN 级别吞掉，所以这里直接用 android.util.Log。
 */
private fun prewarmLog(msg: String) = android.util.Log.d("Waifu2xPrewarm", msg)
