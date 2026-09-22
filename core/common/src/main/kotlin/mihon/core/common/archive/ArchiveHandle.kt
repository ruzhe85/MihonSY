package mihon.core.common.archive

import java.io.Closeable
import java.io.InputStream

// SY --> Komiho Phase3: 从 ArchivePageLoader 对 ArchiveReader 的实际依赖面提取的窄接口。
// ArchiveReader（libarchive 路径）与 RemoteZipReader（远程 ZIP 中央目录直读路径）同实现，
// ArchivePageLoader 只依赖接口，两条路径共用同一套页面构建 / 解码 / 加密弹窗逻辑。
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
}
// SY <--
