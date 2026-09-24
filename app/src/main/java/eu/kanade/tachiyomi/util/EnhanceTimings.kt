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
     */
    private val skipped = ConcurrentHashMap.newKeySet<Int>()

    /** [pageIndex] 为 -1（未标注页号的请求）时忽略。 */
    fun put(pageIndex: Int, pureMillis: Long) {
        if (pageIndex >= 0 && pureMillis >= 0) {
            map[pageIndex] = pureMillis
        }
    }

    /** 取走该页的耗时（取到即删）。取不到时调用方回退到旧口径。 */
    fun take(pageIndex: Int): Long? = map.remove(pageIndex)

    /** 登记「本页没有做增强」。[-1]（未标注页号）时忽略。 */
    fun markSkipped(pageIndex: Int) {
        if (pageIndex < 0) return
        // 兜底：正常路径都会 takeSkipped 消费掉，但页面可能根本没渲染到角标那一步
        // （解码失败、holder 提前回收）。不让它无限增长，也不会拿陈年标记去误标新页。
        if (skipped.size > MAX_SKIPPED) {
            skipped.clear()
        }
        skipped.add(pageIndex)
    }

    /** 取走该页的「跳过」标记（取到即删）。 */
    fun takeSkipped(pageIndex: Int): Boolean = skipped.remove(pageIndex)

    /** skip 标记的保留上限（见 [markSkipped] 的兜底清理）。 */
    private const val MAX_SKIPPED = 64
}
