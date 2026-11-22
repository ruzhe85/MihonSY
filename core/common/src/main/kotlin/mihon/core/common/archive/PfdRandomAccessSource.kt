package mihon.core.common.archive

import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * 基于 [ParcelFileDescriptor] 的随机读取实现（issue #15）。
 *
 * 本地真实文件走 [LocalRandomAccessSource]；SAF / content-uri 拿不到 File 路径，
 * 但 PFD 的文件描述符本身支持定位读——有了它，SAF 里的 zip 也能走中央目录索引，
 * 而不是退回 libarchive 的「每次找条目从头扫一遍」。
 *
 * 关闭由 [ParcelFileDescriptor.AutoCloseInputStream] 持有 PFD 所有权：一次 [close]
 * 同时关掉 channel 与 fd，不会出现双关。
 *
 * 实现用 [java.nio.channels.FileChannel] 的定位读（显式 position），不依赖通道游标，
 * 多页并发预取各自读各自的位置互不干扰。
 */
class PfdRandomAccessSource(pfd: ParcelFileDescriptor) : RandomAccessSource {

    private val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val channel = stream.channel

    /** 优先用 PFD 自带的 statSize；个别 provider 给不出时再问 channel（失败按 0 处理，交由上层回落）。 */
    override val size: Long = pfd.statSize.takeIf { it > 0 }
        ?: runCatching { channel.size() }.getOrDefault(0L)

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length <= 0) return EMPTY
        val available = (size - offset).coerceAtLeast(0L)
        val n = min(length.toLong(), available).toInt()
        if (n == 0) return EMPTY

        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = channel.read(ByteBuffer.wrap(buf, read, n - read), offset + read)
            if (r <= 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    override fun close() {
        runCatching { stream.close() }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
