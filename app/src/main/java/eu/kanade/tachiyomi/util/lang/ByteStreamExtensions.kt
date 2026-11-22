package eu.kanade.tachiyomi.util.lang

import java.io.IOException
import java.io.InputStream

/**
 * 阅读器页面字节读取的统一入口。
 *
 * Kotlin 的 `InputStream.readBytes()` 内部走 `ByteArrayOutputStream`：
 * 每次扩容 `Arrays.copyOf` 会同时持有旧数组与新数组，读完后 `toByteArray()`
 * 再复制一次，峰值可达文件大小的 3～4 倍。漫画单页 32～64MB 时，多页预取
 * 叠加足以撑爆 512MB heap。
 *
 * - 有 [sizeHint]（本地文件长度 / Content-Length / 归档条目大小）时一次精确分配，
 *   峰值 1 倍；读到末尾会再确认一次是否还有剩余数据，避免文件在查询大小后被换成
 *   更大的文件时静默截断；
 * - 无长度信息（归档条目 / 缩略图）时按 64KB 分块收集、最后精确组装，峰值 2 倍
 *   且不会产生反复申请大块连续内存的扩容抖动。
 */
fun InputStream.readAllBytesCompact(sizeHint: Long = -1L): ByteArray {
    if (sizeHint > 0 && sizeHint <= Int.MAX_VALUE) {
        val expected = sizeHint.toInt()
        val bytes = ByteArray(expected)
        var offset = 0
        while (offset < expected) {
            val n = read(bytes, offset, expected - offset)
            if (n < 0) break
            if (n == 0) continue
            offset += n
        }

        // 读满预估长度后必须再探一次 EOF：文件可能在两次读取之间被换成更大的文件，
        // 静默截断会让解码器报 "Failed to initialize decoder"，反而更难定位。
        val extra = read()
        if (extra < 0) {
            return when (offset) {
                expected -> bytes
                0 -> ByteArray(0)
                // 文件比预估短（被截短/替换）：按实际长度收敛，不把尾部零字节交给解码器。
                else -> bytes.copyOf(offset)
            }
        }

        // 罕见：实际内容比预估更大。正确性优先，退化为分块拼接。
        // 注意：read() 返回 0 表示读到真实的 0x00 字节（不是 EOF，EOF 为 -1），
        // 所以这里必须无条件保留该字节，写成 extra > 0 会丢字节、损坏图片。
        val chunks = ArrayList<ByteArray>(8)
        if (offset > 0) chunks.add(bytes.copyOf(offset))
        chunks.add(byteArrayOf(extra.toByte()))
        return collectRemaining(chunks, offset + 1)
    }

    return collectRemaining(ArrayList(8), 0)
}

/** 按 64KB 分块读完剩余内容，并一次性精确组装。峰值 = 数据 1 倍 + 结果 1 倍。 */
private fun InputStream.collectRemaining(chunks: MutableList<ByteArray>, initialTotal: Int): ByteArray {
    val buffer = ByteArray(CHUNK_SIZE)
    var total = initialTotal
    while (true) {
        val n = read(buffer, 0, buffer.size)
        if (n < 0) break
        if (n == 0) continue
        // 单页字节不可能接近 2GB；超限说明流异常，明确失败而不是让 Int 溢出成负长度。
        if (n > Int.MAX_VALUE - total) {
            throw IOException("页面字节超过 2GB 上限，疑似流异常")
        }
        chunks.add(buffer.copyOf(n))
        total += n
    }
    if (total == 0) return ByteArray(0)

    val bytes = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(bytes, offset)
        offset += chunk.size
    }
    return bytes
}

private const val CHUNK_SIZE = 64 * 1024
