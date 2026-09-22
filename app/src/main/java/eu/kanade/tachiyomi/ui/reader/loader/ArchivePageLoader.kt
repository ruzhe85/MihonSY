package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mihon.core.common.archive.ArchiveHandle
import mihon.core.common.archive.ArchiveEntry
import mihon.core.common.archive.ArchivePasswordException
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(private val reader: ArchiveHandle) : PageLoader() {
    // SY -->
    // 移除全局 Mutex：LocalRandomAccessSource 已改用 FileChannel 定位读（线程安全），
    // 每个 ArchiveInputStream 自带独立 libarchive handle + callback state，并发读同一 zip
    // 不再踩共享游标崩溃。串行化只会让当前页排在队列尾、拖慢启动/跳转，且是「大跳页
    // 解码失败」的诱因（等待期间页面被回收→流被提前关闭→native 解析头失败）。
    private val context: Application by injectLazy()
    private val readerPreferences: ReaderPreferences by injectLazy()
    private val tmpDir = File(context.externalCacheDir, "reader_${reader.archiveHashCode}").also {
        it.deleteRecursively()
    }

    init {
        // SY --> 加密本：缺密码 / 密码错误时抛异常，交由阅读器弹密码输入框
        if (reader.encrypted) {
            if (reader.wrongPassword == true) {
                throw ArchivePasswordException(wrongPassword = true)
            }
            if (reader.wrongPassword == null) {
                throw ArchivePasswordException(wrongPassword = false)
            }
        }
        // SY <--
        if (readerPreferences.archiveReaderMode.get() == ReaderPreferences.ArchiveReaderMode.CACHE_TO_DISK) {
            tmpDir.mkdirs()
            reader.useEntries { entries ->
                entries
                    .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .forEach { entry ->
                        File(tmpDir, entry.name.substringAfterLast("/"))
                            .also { it.createNewFile() }
                            .outputStream()
                            .use { output ->
                                reader.getInputStream(entry.name)?.use { input ->
                                    input.copyTo(output)
                                }
                            }
                    }
            }
        }
    }
    // SY <--

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> =
        if (readerPreferences.archiveReaderMode.get() == ReaderPreferences.ArchiveReaderMode.CACHE_TO_DISK) {
            // SY --> CACHE_TO_DISK 模式直接走磁盘临时目录，无需打开归档（也避免非局部 return 依赖 inline）
            DirectoryPageLoader(UniFile.fromFile(tmpDir)!!).getPages()
        } else reader.useEntries { entries ->
        // SY <--
        entries
            .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .mapIndexed { i, entry ->
                // SY -->
                val imageBytesDeferred: Deferred<ByteArray>? =
                    when (readerPreferences.archiveReaderMode.get()) {
                        ReaderPreferences.ArchiveReaderMode.LOAD_INTO_MEMORY -> {
                            CoroutineScope(Dispatchers.IO).async {
                                readEntryBytes(entry)
                            }
                        }

                        else -> null
                    }
                val imageBytes by lazy { runBlocking { imageBytesDeferred?.await() } }
                // SY <--
                ReaderPage(i).apply {
                    // MihonSY fix (Phase2): 每页把条目字节读进独立 ByteArray 再交给解码器，
                    // 解码器拿到的是独立内存流（不被页面回收关闭），彻底规避「大跳页时页面被回收
                    // → 底层 archive 流被关闭 → native 解析头失败闪错误行」；也避免把实时
                    // ArchiveInputStream 直接交给 native 解码（多页并发读同一 zip handle 曾导致
                    // native 崩溃）。去全局 Mutex 后，并发 getInputStream+readBytes 由 FileChannel
                    // 定位读保证线程安全，不再排队，启动/跳转回到正常速度。
                    // 读取统一走 [readEntryBytes]：LOAD_INTO_MEMORY 用后台预读好的字节
                    //（deferred 内部同样走防御），其余模式实时读；回收/空流/截断在读取层
                    // 快速失败，不再把空字节交给解码器伪装成 "Failed to initialize decoder"。
                    stream = { (imageBytes ?: readEntryBytes(entry)).copyOf().inputStream() }
                    // SY <--
                    status = Page.State.Ready
                }
            }
            .toList()
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    /**
     * SY: 读取单个条目的完整字节。竞态防御（治「Failed to initialize decoder」伪装案）：
     * recycle() 会直接 close ArchiveHandle 且与页面 stream() 无互斥——快速翻页/大跳页/
     * 换章时，读到一半流被关闭会得到截断字节、关闭后打开得到空流，InputStream.readBytes()
     * 对两者都「合法返回」不抛异常，空/截断字节一路走到解码器才炸出失真的
     * "Failed to initialize decoder"。这里在每个环节快速失败并抛出真实原因：
     *  - loader 已回收 → check(!isRecycled)
     *  - 条目取不到流 → 明确报条目名
     *  - 读出空字节 → 明确报「流被回收关闭」
     * 错误行显示真实原因后自动刷新（重绑定→重读）即恢复正常。
     */
    private fun readEntryBytes(entry: ArchiveEntry): ByteArray {
        check(!isRecycled) { "页面读取时章节已被回收（翻页/换章竞态）——重载即恢复" }
        val input = reader.getInputStream(entry.name)
            ?: throw IllegalStateException("归档内取不到条目流：${entry.name}")
        val bytes = input.buffered().use { it.readBytes() }
        check(bytes.isNotEmpty()) { "条目读取为空（章节流已被回收关闭）：${entry.name}——重载即恢复" }
        return bytes
    }

    override fun recycle() {
        super.recycle()
        reader.close()
        // SY -->
        tmpDir.deleteRecursively()
        // SY <--
    }
}
