package eu.kanade.tachiyomi.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Komiho: 「显示增强状态」角标用的**实际计算耗时**登记处。
 *
 * 为什么需要它：角标原先量的是「Coil 调用外层墙钟」，里面夹着两类**不属于计算**的时间 ——
 *  - 等原生引擎锁（`Waifu2x.Timing.waitMs`：等别人的推理跑完。实测可达 4.4s / 9.7s）；
 *  - 解码线程池排队。
 * 结果同一页在角标上会显示成 6.9s / 12.1s，而它真正「从 0 开始解码 + 增强」只花了 ~2.7s。
 *
 * 用户要的口径（2026-09-17 明确）：**从 0 开始解码进行图像增强的实际消耗**。
 * 所以由解码器（`TachiyomiImageDecoder`）在**同一线程内**量好
 * `总流程耗时 − 等锁` 后登记到这里，显示端按页号取走。
 *
 * 取走即删：条目只服务于「刚算完的那一页」，不会无限增长，也不会被陈旧值误用。
 */
object EnhanceTimings {

    private val map = ConcurrentHashMap<Int, Long>()

    /**
     * 「这一页什么都没做」的页号集合 —— 尺寸门（输入过大，2x 结果必被 cap 缩回）、HARDWARE 位图、
     * 输入已回收。这些页没有任何引擎参与，角标若落回 [Waifu2x.engineFor] 的默认值会显示
     * `CPU OK`（误导成「CPU 做过增强」），所以要单独记成「跳过」。
     *
     * Komiho (2026-10-16)：语义已明确为**永久跳过** —— 这批页**不该**再重算（重算只会再被同一道门
     * 挡回来），与可重试的 [interrupted] 是两个不同的集合。
     */
    private val skipped = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Komiho (2026-10-16): 「本页这次没出增强图，是**被抢占**造成的」页号集合（可重试的降级）。
     *
     * 为什么不能用 `Waifu2x.wasAborted(pageIndex)` 代替：原生那个槽位（`waifu2x_jni.cpp` 的
     * `g_last_aborted_id`）**只保留最近一次**打断，下一次 `nativeProcess` 一起步就被清成 -1；
     * 而调用方（停顿补算）是几百毫秒后才回头看 —— 那段时间里必然有别的页跑过推理，于是永远读到
     * false。后果是「停顿补算」这条兜底**从未生效过**（issue #8 日志里 `Waifu2xReEnhance` 出现
     * 0 次，而 `AI upscale preempted` 出现 119 次）。这里改成**当场登记、按页保留**，读它才代表
     * 「这一页真的被抢占过」。
     *
     * 三个消费点：
     *  - `PagerPreparedCache.finishLayout`：只读（[hasInterrupted]）——决定这份预处理结果是否
     *    **可重试**（可重试则不写入缓存，回翻不会被残留原图命中）；
     *  - `ReaderPageImageView` 角标：只读 —— 显示「跳过」而不是冒充引擎 OK + 墙钟假耗时；
     *  - `PagerPageHolder.maybeReEnhance`：其余闸门都过了、真要动手补算时才 [takeInterrupted]，
     *    避免标记被无谓消费掉。
     */
    private val interrupted = ConcurrentHashMap.newKeySet<Int>()

    /** [pageIndex] 为 -1（未标注页号的请求）时忽略。 */
    fun put(pageIndex: Int, pureMillis: Long) {
        if (pageIndex >= 0 && pureMillis >= 0) {
            map[pageIndex] = pureMillis
            // Komiho (2026-10-16): 这一页这次真增强成功了 ⇒ 之前那次「被抢占」的登记已过期，
            // 顺手作废 —— 否则停顿补算会为一张已经增强好的图再跑一次 NPU。
            // ⚠️ 只作废 interrupted：**不碰 skipped** —— skip 标记的消费方（角标 `takeSkipped`）
            // 与本次 put 可能属于同一页的两个并发请求，替它清掉会让那张「未增强」的图被标成
            // 引擎 OK（旧行为里 skip 标记只由消费方自己清）。
            interrupted.remove(pageIndex)
        }
    }

    /** 取走该页的耗时（取到即删）。取不到时调用方回退到旧口径。 */
    fun take(pageIndex: Int): Long? = map.remove(pageIndex)

    /** 登记「本页没有做增强，且这是永久性的（尺寸门 / HARDWARE / 输入已回收）」。[pageIndex] 为 -1 时忽略。 */
    fun markSkipped(pageIndex: Int) {
        if (pageIndex < 0) return
        // 兜底：正常路径都会 takeSkipped 消费掉，但页面可能根本没渲染到角标那一步
        // （解码失败、holder 提前回收）。不让它无限增长，也不会拿陈年标记去误标新页。
        if (skipped.size > MAX_PENDING) {
            skipped.clear()
        }
        skipped.add(pageIndex)
    }

    /** 取走该页的「跳过」标记（取到即删）。 */
    fun takeSkipped(pageIndex: Int): Boolean = skipped.remove(pageIndex)

    /** 该页是否有「永久跳过」标记（**只读、不消费**）——`finishLayout` 判「可重试」要用。 */
    fun hasSkipped(pageIndex: Int): Boolean = pageIndex >= 0 && skipped.contains(pageIndex)

    /**
     * Komiho (2026-10-16): 登记「本页这次被抢占打断了，图没增强」。[pageIndex] 为 -1 时忽略。
     * 调用点：`MihonSyEnhancer.enhanceWithGpu` 命中 `Waifu2x.wasAborted` 的分支。
     */
    fun markInterrupted(pageIndex: Int) {
        if (pageIndex < 0) return
        if (interrupted.size > MAX_PENDING) {
            interrupted.clear()
        }
        interrupted.add(pageIndex)
    }

    /** 该页是否「被抢占过」（**只读、不消费**）。 */
    fun hasInterrupted(pageIndex: Int): Boolean = pageIndex >= 0 && interrupted.contains(pageIndex)

    /** 取走该页的「被抢占」登记（取到即删）——补算真正要动手时才调。 */
    fun takeInterrupted(pageIndex: Int): Boolean = interrupted.remove(pageIndex)

    /** skip / interrupted 两类待处理标记各自的保留上限（见 [markSkipped] 的兜底清理）。 */
    private const val MAX_PENDING = 64
}
