package mihon.core.common.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import me.zhanghai.android.libarchive.ArchiveException
import tachiyomi.core.common.storage.openFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.InputStream

// SY --> Phase3: 实现 ArchiveHandle 窄接口，与 RemoteZipReader（远程 ZIP 直读路径）共用 ArchivePageLoader
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

    // 回调式构造器（Local / WebDAV / SMB 走这条，真正随机访问，不整本 mmap）
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

    override fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T =
        newStream(encrypted).use { block(generateSequence { it.getNextEntry() }) }

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
        val archive = newStream(false)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
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
    // SY --> Phase2: 真实文件（uri.scheme == "file"）走 LocalRandomAccessSource 回调路径，
    // 真机验证 libarchive seek 架构；content uri（SAF / 系统文件选择器）一律走原 mmap 路径。
    // 关键：SAF 在 scoped storage 下只能经 content resolver 访问，filePath 虽能解码出真实路径，
    // 但直接 RandomAccessFile(File) 打开会 EACCES；不可仅凭 filePath 非空就走回调路径。
    if (uri?.scheme == "file" && !path.isNullOrEmpty()) {
        return ArchiveReader(LocalRandomAccessSource(File(path)))
    }
    return openFileDescriptor(context, "r").use { ArchiveReader(it) }
}

// SY -->
/**
 * 加密归档缺少密码 / 密码错误时抛出，上层据此在阅读器内弹密码输入对话框。
 * [wrongPassword]=true 表示已输入过但密码不正确。
 */
class ArchivePasswordException(val wrongPassword: Boolean = false) :
    Exception(if (wrongPassword) "密码错误，请重新输入" else "此压缩包已加密，请输入密码")
// SY <--
