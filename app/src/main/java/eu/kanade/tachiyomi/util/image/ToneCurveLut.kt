package eu.kanade.tachiyomi.util.image

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Komiho: 图像伽马 —— Android 12 以下的兜底实现。
 *
 * 12+ 由显示层的 `RuntimeShader` 做（`ReaderActivity` 里那条 `RenderEffect`），即改即见；
 * 12 以下没有公开 API 能在绘制阶段做幂函数，只能在**解码完成后**对位图跑一遍 256 项查找表。
 * 代价是「改完关掉设置页才生效」（当前页会重新解码一次），换来全版本一致的效果。
 *
 * 位置要求：必须在灰阶量化**之后**（否则会被量化吸掉）、硬件位图转换**之前**
 * （硬件位图禁止 getPixels/setPixels）。
 *
 * 内存：分块处理，每块按 [CHUNK_TARGET_BYTES] 收敛（宽图自动减少行数），
 * 不申请整页 IntArray —— 长条页 1080×8000 整页一份就 33MB。
 */
object ToneCurveLut {

    /** 单块的目标内存（像素数组），宽图会自动缩小行数。 */
    private const val CHUNK_TARGET_BYTES = 4 * 1024 * 1024

    /** 单块最大行数。 */
    private const val CHUNK_ROWS = 512

    /** 与 UI 一致：0.40…2.50，1.00 为中性（调用方负责跳过中性值）。 */
    private const val GAMMA_MIN = 0.4f
    private const val GAMMA_MAX = 2.5f

    /**
     * 最近一次的查表缓存（γ 与表打包成一个不可变对象，整体替换）。
     *
     * 早先分成两个 `@Volatile` 字段，理论上会出现「读到新 γ、拿到旧表」的错配窗口 ——
     * 并发解码两页时正好命中就是一张错色的图。打包后一次读就是一个一致快照。
     */
    private class Entry(val gamma: Float, val lut: IntArray)

    @Volatile
    private var cached: Entry? = null

    /**
     * 就地把 [bitmap] 的 RGB 通道按 gamma 重映射（alpha 原样保留）。
     *
     * @return true 表示已改写；false 表示跳过（硬件位图 / 已回收 / 尺寸非法 / 中性值）。
     */
    fun apply(bitmap: Bitmap, gamma: Float): Boolean {
        if (bitmap.isRecycled) return false
        if (bitmap.config == Bitmap.Config.HARDWARE) return false
        // NaN / 无穷会把整页算成纯黑，宁可不动。
        if (!gamma.isFinite()) return false
        val g = gamma.coerceIn(GAMMA_MIN, GAMMA_MAX)
        if (g == 1f) return false
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false

        val lut = lutFor(g)
        // 每块目标 ~4MB：宽图（比如 4000px 的跨页扫描）自动降到更少的行，避免一次性申请十几 MB。
        val rowsBudget = (CHUNK_TARGET_BYTES / 4 / width).coerceAtLeast(1)
        val chunk = IntArray(width * min(min(CHUNK_ROWS, rowsBudget), height))
        val rowsPerChunk = chunk.size / width

        var y0 = 0
        while (y0 < height) {
            val rows = min(rowsPerChunk, height - y0)
            bitmap.getPixels(chunk, 0, width, 0, y0, width, rows)
            val end = rows * width
            for (i in 0 until end) {
                val p = chunk[i]
                val r = lut[(p shr 16) and 0xFF]
                val gr = lut[(p shr 8) and 0xFF]
                val b = lut[p and 0xFF]
                chunk[i] = (p and -0x1000000) or (r shl 16) or (gr shl 8) or b
            }
            bitmap.setPixels(chunk, 0, width, 0, y0, width, rows)
            y0 += rows
        }
        return true
    }

    /** 取（或构建）gamma 对应的 256 项查表：`out = round(255 * (v/255)^gamma)`。 */
    private fun lutFor(gamma: Float): IntArray {
        cached?.let { if (it.gamma == gamma) return it.lut }
        val table = IntArray(256) { v ->
            ((v / 255.0).pow(gamma.toDouble()) * 255.0).roundToInt().coerceIn(0, 255)
        }
        cached = Entry(gamma, table)
        return table
    }
}
