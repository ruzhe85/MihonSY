package mihon.core.common.archive

import java.io.Closeable
import java.io.InputStream

// SY --> Komiho Phase3: 从 ArchivePageLoader 对 ArchiveReader 的实际依赖面提取的窄接口。
// ArchivePageLoader 只依赖接口，页面构建 / 解码 / 加密弹窗逻辑与具体归档实现解耦。
interface ArchiveHandle : Closeable {
    /** 每实例唯一（ArchivePageLoader 建临时目录用）。 */
    val archiveHashCode: Int

    /** 是否存在加密条目（缺密码 / 密码错误时由阅读器弹密码输入框）。 */
    val encrypted: Boolean

    /** null=未校验过密码；true=密码错误；false=密码正确。 */
    val wrongPassword: Boolean?

    fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T

    /** 返回条目内容的解压流；条目不存在返回 null。流独立，可并发开多条。 */
    fun getInputStream(entryName: String): InputStream?

    /**
     * 条目**解压后**的字节数，未知返回 -1。
     *
     * 只用于读取时精确预分配，避免 `readAllBytesCompact` 走分块累加（多一份峰值内存）。
     * 提示不准确也不会读错：实际更短会按实长收敛、更长会继续读完。
     */
    fun entrySize(entryName: String): Long = -1L

    /**
     * 读取条目开头至多 [maxBytes] 字节，供格式嗅探。
     *
     * 默认实现走完整 [getInputStream]：libarchive 路径是流式解压，读几十字节只会解出这么多，代价很低。
     *
     * 索引实现（[RemoteZipReader]）必须覆盖：它的 [getInputStream] 会先把整条压缩数据读进内存，
     * 用它做嗅探会让「按条目嗅探」退化成「把整本图读一遍」。
     */
    fun peek(entryName: String, maxBytes: Int): ByteArray? {
        if (maxBytes <= 0) return null
        val stream = getInputStream(entryName) ?: return null
        return stream.use { input ->
            val out = ByteArray(maxBytes)
            var offset = 0
            while (offset < maxBytes) {
                val n = input.read(out, offset, maxBytes - offset)
                if (n < 0) break
                if (n == 0) continue
                offset += n
            }
            out.copyOf(offset)
        }
    }
}
// SY <--
