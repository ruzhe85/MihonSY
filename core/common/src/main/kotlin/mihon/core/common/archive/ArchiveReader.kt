package mihon.core.common.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import logcat.LogPriority
import me.zhanghai.android.libarchive.ArchiveException
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.openFileDescriptor
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File
import java.io.InputStream

// SY --> Phase3: 实现 ArchiveHandle 窄接口，供 ArchivePageLoader 使用
class ArchiveReader : ArchiveHandle {

    val size: Long
    private val address: Long?
    private val source: RandomAccessSource?

    // 本地 mmap 构造器（SAF / content uri 走这条，保持原有行为）
    constructor(pfd: ParcelFileDescriptor) {
        size = pfd.statSize
        address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)
        source = null
        checkEncryptionStatus()
    }

    // 回调式构造器（本地真实文件走这条，按需定位读，不整本 mmap）
    constructor(source: RandomAccessSource) {
        this.source = source
        size = source.size
        address = null
        checkEncryptionStatus()
    }

    // SY -->
    override var encrypted: Boolean = false
        private set
    override var wrongPassword: Boolean? = null
        private set
    // 每 reader 实例唯一（用于 ArchivePageLoader 建临时目录）；mmap 路径取 mmap 地址，回调路径取 source 实例哈希
    override val archiveHashCode: Int
        get() = address?.hashCode() ?: source!!.hashCode()
    // SY <--

    private fun newStream(encrypted: Boolean): ArchiveInputStream =
        if (address != null) ArchiveInputStream(address, size, encrypted)
        else ArchiveInputStream(source!!, encrypted)

    /**
     * 构造期扫描（[checkEncryptionStatus]）顺带收集的条目列表。
     *
     * 未加密归档会把全部条目扫完 → 完整缓存，[useEntries] 直接复用，省掉 `getPages()`
     * 的第二次全量扫描（rar/7z 这类 libarchive-only 格式打开停顿的主要来源之一）；
     * 加密归档在首个加密条目处提前 break（只为尽快弹密码框），此时列表不完整、不缓存。
     */
    @Volatile
    private var cachedEntries: List<ArchiveEntry>? = null

    override fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T {
        cachedEntries?.let { return block(it.asSequence()) }
        return newStream(encrypted).use { block(generateSequence { it.getNextEntry() }) }
    }

    override fun getInputStream(entryName: String): InputStream? {
        val archive = newStream(encrypted)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    // SY -->
    private fun checkEncryptionStatus() {
        // SY: 顺带收集条目列表，供 useEntries 复用（省掉第二次全量扫描）
        val collected = ArrayList<ArchiveEntry>()
        val archive = newStream(false)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                collected.add(entry)
                if (entry.isEncrypted) {
                    encrypted = true
                    // SY: 仅在已设置全局密码时才校验对错，否则交由上层弹密码框（避免无密码时空抛）
                    if (CbzCrypto.isPasswordSet()) {
                        isPasswordIncorrect(entry.name)
                    }
                    break
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        // 未加密 = 已扫完全部条目，列表完整可复用；加密时上面提前 break，不缓存。
        if (!encrypted) cachedEntries = collected
    }

    private fun isPasswordIncorrect(entryName: String) {
        try {
            getInputStream(entryName).use { stream ->
                stream!!.read()
            }
        } catch (e: ArchiveException) {
            if (e.message == "Incorrect passphrase") {
                wrongPassword = true
                return
            }
            throw e
        }
        wrongPassword = false
    }
    // SY <--

    override fun close() {
        if (address != null) Os.munmap(address, size)
        source?.close()
    }
}

fun UniFile.archiveReader(context: Context): ArchiveReader {
    val path = filePath
    // SY --> Phase2: 真实文件（uri.scheme == "file"）走 LocalRandomAccessSource 按需定位读，
    // content uri（SAF / 系统文件选择器）一律走原 mmap 路径。
    // 关键：SAF 在 scoped storage 下只能经 content resolver 访问，filePath 虽能解码出真实路径，
    // 但直接 RandomAccessFile(File) 打开会 EACCES；不可仅凭 filePath 非空就走回调路径。
    if (uri?.scheme == "file" && !path.isNullOrEmpty()) {
        return ArchiveReader(LocalRandomAccessSource(File(path)))
    }
    return openFileDescriptor(context, "r").use { ArchiveReader(it) }
}

// SY --> Komiho: 本地 ZIP 中央目录索引路径
/** [RemoteZipReader] 只支持 ZIP 家族扩展名；rar/7z/tar 直接走 libarchive。EPUB 本质也是 zip。 */
private val ZIP_FAMILY_EXTENSIONS = setOf("zip", "cbz", "epub")

/**
 * 本地归档句柄：zip/cbz/epub 优先进中央目录索引（[RemoteZipReader]）。
 *
 * 打开时只读尾部 EOCD + 中央目录建索引，之后每页直接定位读取（O(1)）；不再像 libarchive
 * 路径那样「每次找条目从头扫一遍、打开时还要扫两次全包」。
 *
 * 其余格式（rar/cbr/7z/cb7/tar/cbt）、索引不支持或解析失败，一律回落 libarchive，行为不变。
 * SAF / content-uri 没有真实路径，但可通过 [PfdRandomAccessSource] 同样走索引。
 */
fun UniFile.indexedArchiveHandle(context: Context): ArchiveHandle {
    // 非 zip 家族保持原行为：真实文件走定位读、SAF 走 mmap。
    if (extension?.lowercase() !in ZIP_FAMILY_EXTENSIONS) return archiveReader(context)

    // 真实文件（file:// 且有路径）→ 定位读；SAF / content-uri → PFD 定位读。
    // 直接拿 File? 做分支，而不是「先算个布尔、再指望编译器 smart-cast 回非空」。
    val realFile = filePath?.takeIf { uri?.scheme == "file" && it.isNotEmpty() }?.let(::File)
    val source: RandomAccessSource = if (realFile != null) {
        LocalRandomAccessSource(realFile)
    } else {
        // SAF / content-uri：用 PFD 做定位读；所有权转交 source，close() 时一并关闭。
        PfdRandomAccessSource(openFileDescriptor(context, "r"))
    }

    try {
        val indexed = RemoteZipReader(source)
        val count = indexed.useEntries { it.count() }
        // 条目名一律按 UTF-8 解码：传统编码（GBK / Shift-JIS 等且未置 UTF-8 标志）会解成
        // U+FFFD，不同条目可能撞成同一个 key → 丢页。这种情况坚决回落 libarchive。
        val badNames = count > 0 && indexed.useEntries { seq -> seq.any { it.name.contains('\uFFFD') } }
        if (count > 0 && !badNames) return indexed
    } catch (e: Exception) {
        // 尾部偶然命中 EOCD、加密算法不支持、zip64 越界等：回落 libarchive。
        // 必须走 logcat()：它经 XLogLogcatLogger 落到「导出诊断日志」的缓冲里，
        // 直接 android.util.Log 只写系统 logcat，用户导出的文件里完全看不到。
        logcat(LogPriority.WARN, e) { "[ArchiveOpen] 索引化打开失败，回落 libarchive：${e.message}" }
    }

    // 真实文件复用同一 source（定位读无游标）；SAF 关掉索引用的 PFD 后走原来的 mmap 路径。
    if (realFile != null) return ArchiveReader(source)
    runCatching { source.close() }
    return openFileDescriptor(context, "r").use { ArchiveReader(it) }
}
// SY <--

// SY -->
/**
 * 加密归档缺少密码 / 密码错误时抛出，上层据此在阅读器内弹密码输入对话框。
 * [wrongPassword]=true 表示已输入过但密码不正确。
 */
class ArchivePasswordException(val wrongPassword: Boolean = false) :
    Exception(if (wrongPassword) "密码错误，请重新输入" else "此压缩包已加密，请输入密码")
// SY <--
