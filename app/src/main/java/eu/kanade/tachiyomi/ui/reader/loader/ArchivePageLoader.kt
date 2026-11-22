package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.lang.readAllBytesCompact
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

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
                    // Komiho: 嗅探只读条目开头几十 KB。索引实现（RemoteZipReader）的
                    // getInputStream 会先把整条压缩数据读进内存，拿它做嗅探等于把整本图读一遍。
                    .filter { it.isFile && ImageUtil.isImage(it.name) { sniffStream(it.name) } }
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
            // Komiho: 嗅探只读条目开头几十 KB。索引实现（RemoteZipReader）的
            // getInputStream 会先把整条压缩数据读进内存，拿它做嗅探等于把整本图读一遍。
            .filter { it.isFile && ImageUtil.isImage(it.name) { sniffStream(it.name) } }
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
                    // Komiho: 不再 copyOf() —— 这里返回的都是**本次刚分配**的字节数组，
                    // 没人会改写它，多复制一份只是把单页内存翻倍（64MB 页 × 预取页数）。
                    stream = { (imageBytes ?: readEntryBytes(entry)).inputStream() }
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
    /**
     * Komiho: 条目格式嗅探用的流。
     *
     * `ImageUtil.isImage` 的 lambda 形参是 `() -> InputStream`（**非空**），而
     * [ArchiveHandle.peek] 返回 `ByteArray?` —— 直接 `?.inputStream()` 会推断成
     * `() -> InputStream?` 而编译不过。这里把「peek 不到」收敛成空流：读不到字节时
     * 判型必然失败，语义与「不是图片」一致。
     */
    private fun sniffStream(name: String): InputStream =
        reader.peek(name, SNIFF_BYTES)?.inputStream() ?: ByteArrayInputStream(ByteArray(0))

    private fun readEntryBytes(entry: ArchiveEntry): ByteArray {
        check(!isRecycled) { "页面读取时章节已被回收（翻页/换章竞态）——重载即恢复" }
        val input = reader.getInputStream(entry.name)
            ?: throw IllegalStateException("归档内取不到条目流：${entry.name}")
        // Komiho: readBytes() 的 ByteArrayOutputStream 扩容峰值可达 3～4 倍，
        // 大页（32～64MB）多页预取会直接 OOM；有大小线索时按实长精确预分配（峰值 1 倍），
        // 没有线索（libarchive 路径恒返回 -1）时按 64KB 分块组装（峰值 2 倍）。
        val sizeHint = reader.entrySize(entry.name)
        val bytes = input.buffered().use { it.readAllBytesCompact(sizeHint) }
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

/** Komiho: 格式嗅探读取的字节上限 —— 图片魔数在最前面，几十 KB 绰绰有余。 */
private const val SNIFF_BYTES = 64 * 1024
