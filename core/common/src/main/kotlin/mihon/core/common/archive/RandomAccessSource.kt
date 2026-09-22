package mihon.core.common.archive

import java.io.Closeable

/**
 * 任意偏移随机读取的字节源抽象。
 *
 * Local / WebDAV / SMB 各自实现这一接口，[ArchiveReader] 只依赖它，
 * 不关心底层协议。Reader / PageLoader / 缓存全部不感知数据来源。
 *
 * 约定：
 * - [read] 从 [offset] 开始读取，返回数据可以少于 [length]（接近文件末尾时）。
 * - [offset] 越界时返回空数组，禁止抛异常（libarchive 据此判定 EOF）。
 * - 一个 [ArchiveReader] 持有长期存在的 [RandomAccessSource]，退出时由调用方 close。
 */
interface RandomAccessSource : Closeable {
    val size: Long

    fun read(offset: Long, length: Int): ByteArray
}
