package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.util.Log
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * Komiho: 跨章预载窗口 —— 让「下一章第一页」也能像本章内的页一样被提前 bind、提前解码 + 增强。
 *
 * 为什么需要（根因，已读码确认）：
 * 条漫的预载**只有** [WebtoonLayoutManager.extraLayoutSpace] 一条通路，而 `LinearLayoutManager`
 * 的 extra space **只在列表尾部**消费、且必须**逐项完整布局完前一页**才会继续下一页；条漫页在
 * 未解码时高度恰好是**一整屏**（[WebtoonPageHolder] 用 `parentHeight` 撑起进度容器）。
 * 于是「本章最后一页」一进入预载区就把那一屏吃光，**下一章第一页落不进预载区**，直到用户把它
 * 滑进视口才第一次 bind ⇒ 解码 + AI 增强才开始 —— 表现即「拉到了才开始工作」。
 *
 * 做法：接近章末时把 extra space 抬到 [maxExtraScreens] 屏。这个值**等于**用户手动能把
 * 「条漫预载深度」调到的上限，所以不引入新的内存 / GPU 峰值档位；而 extra space 的语义是
 * 「相对当前视口再往下多布 N 屏」，本身自带钳制 —— 目标还在更远处时提前开窗也不会多布局任何东西。
 *
 * 关闭时机刻意**不含**「目标渲染完成」：目标渲染完后如果立刻把 extra 降回去，下一次布局会把它
 * 移出预载区 → [WebtoonPageHolder.recycle] 清掉 `renderedPage` 守卫 → 那份解码 + 增强白丢，
 * 用户滑到它时又要从零重算。所以窗口一直开到用户真正滑到该页（或滑离章末 / 目标失效 /
 * 阅读器重建），保证「预载好的那一页」在用户到达前不会被回收。
 */
internal class WebtoonCrossChapterPrewarm(
    private val maxExtraScreens: Int = MAX_EXTRA_SCREENS,
    private val openPagesFromEnd: Int = OPEN_PAGES_FROM_END,
    private val closePagesFromEnd: Int = CLOSE_PAGES_FROM_END,
) {

    /** 目标 = 下一章第一页；null = 没有可预载的下一章。 */
    var activeTarget: ReaderPage? = null
        private set

    /** 窗口是否开启（开启时 extra space 抬到 [maxExtraScreens] 屏）。 */
    var isOpen: Boolean = false
        private set

    /**
     * 当前目标是否已经渲染完成（成功或失败都算）。渲染完了就不再需要看门狗 ——
     * 否则目标明明已经算好，20 秒后还会被看门狗当成「卡住」关窗，反而把它挤出预载区、白丢结果。
     */
    var isTargetRendered: Boolean = false
        private set

    /**
     * 章节数据变化（[WebtoonViewer.setChapters]）：刷新目标。
     * 返回 true = 目标变了，调用方需要重算 extra space。
     *
     * 目标换了说明旧的预载窗口已无意义，直接关掉；是否重开交给随后的
     * [onDistanceFromEnd] 复核（此时 currentPage 已经是最新的）。
     */
    fun onChaptersChanged(nextFirstPage: ReaderPage?): Boolean {
        if (activeTarget === nextFirstPage) return false
        activeTarget = nextFirstPage
        val wasOpen = isOpen
        isOpen = false
        isTargetRendered = false
        log("target=${nextFirstPage?.index ?: -1} wasOpen=$wasOpen reason=chapters-changed")
        return true
    }

    /**
     * 每次滚动 / 选页复核「距章末还剩 [distanceFromEnd] 页」。返回 true = 开 / 关状态发生变化，
     * 调用方需要重算 extra space。
     *
     * 带滞回（[openPagesFromEnd] 开、[closePagesFromEnd] 关）：用户停在阈值附近时不会反复开合。
     */
    fun onDistanceFromEnd(distanceFromEnd: Int): Boolean {
        if (isOpen) {
            if (distanceFromEnd <= closePagesFromEnd) return false
            isOpen = false
            log("close reason=scrolled-away distanceFromEnd=$distanceFromEnd")
            return true
        }
        if (activeTarget == null || distanceFromEnd > openPagesFromEnd) return false
        isOpen = true
        log(
            "open target=${activeTarget?.index} distanceFromEnd=$distanceFromEnd " +
                "extra=${maxExtraScreens}screens",
        )
        return true
    }

    /**
     * 目标页渲染完成（成功或失败都会走到）。只记账 + 让调用方取消看门狗，**不关窗**（理由见类注释）。
     */
    fun onTargetRendered(page: ReaderPage) {
        if (!isActiveFor(page)) return
        isTargetRendered = true
        if (isOpen) log("rendered target=${page.index}")
    }

    /** 目标页已进入视口 / 已成为当前页 → 关窗（此后不再需要额外预留预载空间）。 */
    fun onTargetArrived(page: ReaderPage): Boolean {
        if (!isOpenFor(page)) return false
        isOpen = false
        log("close reason=arrived target=${page.index}")
        return true
    }

    /** 关窗但保留目标（适配器重建：旧 holder 作废，但「下一章第一页」这个目标仍然有效）。 */
    fun closeWindow(reason: String): Boolean {
        if (!isOpen) return false
        isOpen = false
        // 适配器重建会销毁全部 holder，之前渲染好的目标也需要重来 —— 复位渲染标记。
        isTargetRendered = false
        log("close reason=$reason target=${activeTarget?.index}")
        return true
    }

    /** 强制关窗并丢弃目标（退出阅读器 / 看门狗超时）。返回 true = 需要重算 extra space。 */
    fun cancel(reason: String): Boolean {
        val wasOpen = isOpen
        val target = activeTarget
        isOpen = false
        activeTarget = null
        isTargetRendered = false
        if (wasOpen || target != null) {
            log("cancel reason=$reason target=${target?.index ?: -1} wasOpen=$wasOpen")
        }
        return wasOpen
    }

    /**
     * 当前应该使用的 extra space。窗口关闭时等于用户设定值 [baseExtra]；开启且增强档位非 0 时
     * 抬到 [maxExtraScreens] 屏上限（[baseExtra] 已经到上限时不再变化）。
     */
    fun extraFor(baseExtra: Int, scrollDistance: Int, enhancementOn: Boolean): Int {
        if (!isOpen || !enhancementOn || scrollDistance <= 0) return baseExtra
        return (baseExtra + WINDOW_SCREENS * scrollDistance)
            .coerceAtMost(maxExtraScreens * scrollDistance)
    }

    /** [page] 是否就是当前预载窗口的目标页（不管窗口开着还是已被关掉）。 */
    fun isActiveFor(page: ReaderPage): Boolean = activeTarget === page

    /** [page] 是否正好是「开启中」窗口的目标页。 */
    fun isOpenFor(page: ReaderPage): Boolean = isOpen && activeTarget === page

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}

// Komiho: 诊断 tag —— 与 WebtoonPageHolder 的绑定 / 渲染日志同 tag，方便一起 grep。
private const val TAG = "Waifu2xWebtoon"

/** 开窗时额外多布的屏数（相对用户设定值叠加）。 */
private const val WINDOW_SCREENS = 2

/** 绝对上限：等于「条漫预载深度」的可调上限，保证峰值不超出现有档位。 */
private const val MAX_EXTRA_SCREENS = 3

/** 距章末多少页以内开窗。与 requestPreloadChapter 的 <5 页同量级，略早一点。 */
private const val OPEN_PAGES_FROM_END = 8

/** 滑离章末多少页以外关窗（滞回，避免阈值附近反复开合）。 */
private const val CLOSE_PAGES_FROM_END = 12
