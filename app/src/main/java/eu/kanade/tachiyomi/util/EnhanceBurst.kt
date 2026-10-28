package eu.kanade.tachiyomi.util

import android.os.SystemClock

/** 诊断 TAG：连点判定。 */
private const val TAG = "EnhanceBurst"

/**
 * 「快速连点」判定 —— 连点期间整段跳过增强（含 CPU 兜底）。
 *
 * ## 为什么需要
 *
 * 增强单页 0.5~0.8 秒，而连点翻页最快每 0.2~0.3 秒一页。这段时间里发起的推理几乎必然
 * 被下一次翻页打断（实测一次会话 `completed 49 / aborted 118`）—— 七成算力白烧，而且
 * 每次失败还会顺带跑一遍 0.6~1 秒的 CPU Lanczos 兜底，和下一页的推理抢核，形成连锁。
 *
 * 判据只用**换页间隔**（[ENTER_MS]）：点击、音量键、翻页键、拖动都只在“换页”这一刻调用
 * [onTurn]，所以三类输入共用一套判定，不需要额外的速度追踪。
 *
 * ## 三个时间常数
 *
 * | 常数 | 值 | 含义 |
 * |---|---|---|
 * | [ENTER_MS] | 400ms | 两次换页间隔小于它 ⇒ 进入 burst |
 * | [EXIT_MS] | 200ms | 距最后一次换页超过它 ⇒ 自动解除 |
 * | [RESUME_WINDOW_MS] | 1500ms | 停稳后为**当前页**开的放行窗口（够跑完一次推理） |
 *
 * ## 放行规则（[allows]）
 *
 * - 非 burst：一律放行；
 * - burst 中：只有「当前可见页 + 处于放行窗口内」的请求能过，其余（预载、邻居页、
 *   刚翻过去的那页）全部跳过；
 * - 被跳过的请求由调用方登记 `EnhanceTimings.markInterrupted`，停稳后由
 *   `PagerPageHolder.maybeReEnhance` 补算 —— 与“被抢占”走同一条兜底链路。
 *
 * 全部是内存里的几个 `Long`，无锁、无定时器、无分配。
 */
object EnhanceBurst {

    /** 两次换页间隔小于它 ⇒ 进入 burst。 */
    private const val ENTER_MS = 400L

    /**
     * 距最后一次换页超过它 ⇒ 自动解除（用户已经停下来）。
     *
     * 取 200ms 而不是更长：停稳后 150ms 就要补当前页（见 `PagerPageHolder.reEnhanceDelayMs`），
     * 解除得太晚会把这次补算也挡在门外。取太短又会在连点间隙（间隔约 200~300ms）漏放预载，
     * 200ms 是这两者之间能同时成立的最小值。
     */
    private const val EXIT_MS = 200L

    /** 停稳后给当前页的放行窗口：够跑完一次推理即可，超时自动收回。 */
    private const val RESUME_WINDOW_MS = 1500L

    /** 最近一次换页的时刻。 */
    @Volatile
    private var lastTurnAt = 0L

    /**
     * 是否已经进入连点状态。
     *
     * 置位条件：**两次换页的间隔 ≤ [ENTER_MS]**；清除条件：距今最后一次换页超过 [EXIT_MS]
     * （在 [isBursting] 里惰性清除）。
     *
     * 为什么不用“计数 ≥ 2”：点击翻页会先后产生两个事件 —— 请求与落地（`onPageSelected`），
     * 两者相隔约一个动画时长。用计数会在落地时把计数打回 1，刚建立的 burst 状态立刻失效。
     * 用「置位 + 静默超时」就没有这个自相矛盾。
     */
    @Volatile
    private var burstActive = false

    /** 放行窗口的截止时刻。 */
    @Volatile
    private var resumeUntil = 0L

    /** 「被挡住」诊断日志的限流时刻。 */
    @Volatile
    private var lastBlockLogAt = 0L

    /**
     * 记录一次换页。
     *
     * 调用点：`PagerViewer.moveRight/moveLeft`（点击 / 音量键 / 翻页键）与
     * `PagerViewer` 的 `onPageSelected`（拖动落地）。同一页重复上报无副作用。
     */
    fun onTurn() {
        val now = SystemClock.uptimeMillis()
        // 与上一次换页挨得很近 ⇒ 连续翻页。首次调用不置位（间隔视为无穷大）。
        if (lastTurnAt != 0L && now - lastTurnAt <= ENTER_MS) {
            if (!burstActive) {
                android.util.Log.d(TAG, "burst enter gap=${now - lastTurnAt}ms")
            }
            burstActive = true
        }
        lastTurnAt = now
    }

    /** 当前是否处于连点状态（距最后一次换页 [EXIT_MS] 以内）。 */
    val isBursting: Boolean
        get() {
            if (!burstActive) return false
            val idle = SystemClock.uptimeMillis() - lastTurnAt
            if (idle > EXIT_MS) {
                burstActive = false
                android.util.Log.d(TAG, "burst exit idle=${idle}ms")
                return false
            }
            return true
        }

    /**
     * 刚停下（翻页停稳）—— 为当前页开一个放行窗口，好让补算立刻能跑。
     * 由 `PagerPageHolder.maybeReEnhance` 在发起补算前调用。
     */
    fun openResumeWindow() {
        resumeUntil = SystemClock.uptimeMillis() + RESUME_WINDOW_MS
    }

    /** 清空状态（退出阅读器 / 关闭增强时调用，避免把上一本书的节奏带过来）。 */
    fun reset() {
        lastTurnAt = 0L
        burstActive = false
        resumeUntil = 0L
    }

    /**
     * 这次请求要不要真的跑增强。
     *
     * @param visible 请求的是不是用户此刻正在看的那一页（`Waifu2x.visiblePageIndex`）。
     */
    fun allows(visible: Boolean): Boolean {
        if (!isBursting) return true
        val inWindow = SystemClock.uptimeMillis() <= resumeUntil
        if (visible && inWindow) return true
        // 诊断：谁被挡了、当时是不是可见页、放行窗口开没开。限流 500ms，避免刷屏。
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockLogAt >= 500L) {
            lastBlockLogAt = now
            android.util.Log.d(TAG, "block visible=$visible window=$inWindow")
        }
        return false
    }
}
