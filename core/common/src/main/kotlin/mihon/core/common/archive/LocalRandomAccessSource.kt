package mihon.core.common.archive

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * 基于本地真实文件的随机读取实现。
 *
 * 本地真实文件（已下载章节、本地源、本地 EPUB）走这条路径：libarchive 经
 * `SeekCallback`（SEEK_SET / SEEK_CUR / SEEK_END）按需定位读，不整本 mmap。
 *
 * 实现用 [java.nio.channels.FileChannel] 的**定位读** `read(buf, position)`：每次读都显式带
 * 文件偏移、不依赖通道的共享游标，因此多个页面并发预取（大跳页时）各自读各自的位置互不干扰，
 * 避免 `RandomAccessFile.seek()+read()` 在多线程下游标被踩导致的偶发读数错乱。
 */
class LocalRandomAccessSource(file: File) : RandomAccessSource {

    private val channel = RandomAccessFile(file, "r").channel

    override val size: Long
        get() = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return EMPTY
        val available = (channel.size() - offset).coerceAtLeast(0L)
        val n = min(length.toLong(), available).toInt()
        if (n == 0) return EMPTY
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            // 定位读：从文件 offset+read 处读入 buf[read..n)，不触碰通道游标，线程安全
            val r = channel.read(ByteBuffer.wrap(buf, read, n - read), offset + read)
            if (r <= 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    override fun close() = channel.close()

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
