package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import exh.source.isEhBasedSource
import exh.util.DataSaver
import exh.util.DataSaver.Companion.getImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.min

/**
 * Loader used to load chapters from an online source.
 */
@OptIn(DelicateCoroutinesApi::class)
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
    // SY -->
    private val readerPreferences: ReaderPreferences = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    // SY <--
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    private val preloadSize = /* SY --> */ readerPreferences.preloadSize.get() // SY <--

    // SY -->
    private val dataSaver = DataSaver(source, sourcePreferences)
    // SY <--

    /**
     * Komiho: 「缓存文件丢失 → 自动重下」的按 URL 计数。必须线程安全 —— 队列由多个消费协程并发取。
     * 用途见 [healMissingCache]。
     */
    private val healCounts = ConcurrentHashMap<String, Int>()

    /**
     * Komiho: 同一页的**单飞**标记（按 `ReaderPage` 对象同一性，未覆写 equals/hashCode）。
     *
     * 为什么需要：队列消费端的 `take()` 与 `.filter { status == Queue }` 之间没有锁，`readerThreads > 1`
     * 时同一页的两条队列记录可能被两个消费协程几乎同时取到并都通过过滤 —— 而「同一页被入队两次」
     * 的来源不止一个（`loadPage()` 的「Ready 但缓存丢了」分支、用户重试、[healMissingCache] 自愈）。
     * 两边同时 `source.getImage` + `putImageToCache`，就会撞上 DiskLruCache 同一个 key 的 editor
     * （`edit()` 返回 null → 静默不写），白下两遍还平添一次失败。这里后到的那次直接跳过，由先到的
     * 那次把页面推到终态（`internalLoadPage` 的 try/catch 保证一定会落到 Ready 或 Error）。
     */
    private val inFlightPages = ConcurrentHashMap.newKeySet<ReaderPage>()

    init {
        // EXH -->
        repeat(readerPreferences.readerThreads.get()) {
            // EXH <--
            scope.launchIO {
                flow {
                    while (true) {
                        emit(runInterruptible { queue.take() })
                    }
                }
                    .filter { it.page.status == Page.State.Queue }
                    .collect {
                        internalLoadPage(
                            page = it.page,
                            force = it.priority == PriorityPage.RETRY,
                        )
                    }
            }
            // EXH -->
        }
        // EXH <--
    }

    override var isLocal: Boolean = false

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            chapterCache.getPageListFromCache(chapter.chapter.toDomainChapter()!!)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            source.getPageList(chapter.chapter)
        }
        // SY -->
        val rp = pages.mapIndexed { index, page ->
            // Don't trust sources and use our own indexing
            ReaderPage(index, page.url, page.imageUrl)
        }
        if (readerPreferences.aggressivePageLoading.get()) {
            rp.forEach {
                if (it.status == Page.State.Queue) {
                    queue.offer(PriorityPage(it, 0))
                }
            }
        }
        return rp
        // SY <--
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val queuedPages = mutableListOf<PriorityPage>()
        if (page.status == Page.State.Queue) {
            queuedPages += PriorityPage(page, PriorityPage.DEFAULT).also { queue.offer(it) }
        }
        queuedPages += preloadNextPages(page, preloadSize)

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queuedPages.forEach {
                    if (it.page.status == Page.State.Queue) {
                        queue.remove(it)
                    }
                }
            }
        }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        // Komiho: 原来只在 `status is Error` 时才置 Queue，于是「渲染期才暴露的失败」根本重试不了：
        // 章节缓存文件被清掉后，异常抛在 holder 读 stream 的时候，`page.status` 仍是 Ready，
        // 这里跳过置 Queue ⇒ 入队的 PriorityPage 又被消费端
        // `.filter { it.page.status == Page.State.Queue }` 丢掉 —— 用户看到的「点重试不生效」。
        //
        // 现在：在途状态（LoadPage / DownloadImage）直接返回，避免同一页被并发下两遍（那会撞上
        // DiskLruCache 同一 key 的 editor 静默失败，见 downloadToCache）；其余一律重新入队。
        if (page.status == Page.State.LoadPage || page.status == Page.State.DownloadImage) {
            return
        }
        page.status = Page.State.Queue
        // EXH -->
        // Grab a new image URL on EXH sources
        if (source.isEhBasedSource()) {
            page.imageUrl = null
        }

        if (readerPreferences.readerInstantRetry.get()) {
            // 顺序不能反：boostPage 内部以 `status == Queue` 为前提
            boostPage(page)
        } else {
            // EXH <--
            queue.offer(PriorityPage(page, PriorityPage.RETRY))
        }
    }

    /**
     * Komiho: 渲染期发现章节缓存文件丢失时的自动愈合（语义见 [PageLoader.healMissingCache]）。
     *
     * 典型场景：App 退后台期间系统清掉了 `chapter_disk_cache` 目录，回前台 holder 重绑时
     * `statusFlow` 重放 Ready ⇒ 立刻重渲染 ⇒ 去读一个已经不存在的文件。这里把它当「未缓存」处理，
     * 重新入队下载，用户不必看到错误页就能继续读。
     *
     * 上限 [MAX_AUTO_HEAL_PER_URL]：缓存容量被设得极小时可能「刚下完就被淘汰」，无上限会变成
     * 下载循环；超限后交回上层显示错误页（手动重试已被修好，必定能恢复）。计数按 URL 记在 loader
     * 上 —— loader 与章节同生命周期，天然按章复位。
     */
    override fun healMissingCache(page: ReaderPage): Boolean {
        val url = page.imageUrl ?: return false
        val used = healCounts.merge(url, 1) { old, _ -> old + 1 } ?: 1
        if (used > MAX_AUTO_HEAL_PER_URL) return false

        logcat(LogPriority.WARN) {
            "章节缓存文件丢失，自动重下（第 $used 次）：$url"
        }
        retryPage(page)
        return true
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()

        // Cache current page list progress for online chapters to allow a faster reopen
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter.toDomainChapter()!!, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Preloads the given [amount] of pages after the [currentPage] with a lower priority.
     *
     * @return a list of [PriorityPage] that were added to the [queue]
     */
    private fun preloadNextPages(currentPage: ReaderPage, amount: Int): List<PriorityPage> {
        val pageIndex = currentPage.index
        val pages = currentPage.chapter.pages ?: return emptyList()
        if (pageIndex == pages.lastIndex) return emptyList()

        return pages
            .subList(pageIndex + 1, min(pageIndex + 1 + amount, pages.size))
            .mapNotNull {
                if (it.status == Page.State.Queue) {
                    PriorityPage(it, PriorityPage.ADJACENT).apply { queue.offer(this) }
                } else {
                    null
                }
            }
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun internalLoadPage(page: ReaderPage, force: Boolean) {
        // Komiho: 同一页单飞（见 inFlightPages）—— 后到的重复入队直接让路。
        if (!inFlightPages.add(page)) return
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl!!

            if (force || !chapterCache.isImageInCache(imageUrl)) {
                page.status = Page.State.DownloadImage
                downloadToCache(imageUrl, page)
            }

            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            page.status = Page.State.Error(e)
            if (e is CancellationException) {
                throw e
            }
        } finally {
            inFlightPages.remove(page)
        }
    }

    /**
     * Komiho: 下载一张图并写入章节缓存，**写入后校验文件真的落地**。
     *
     * 为什么必须校验：`ChapterCache.putImageToCache` 存在静默失败路径 —— DiskLruCache 在同一个 key
     * 上已有未提交的 editor 时 `edit()` 返回 null，那个函数直接 return，一个字节都不写。旧代码不校验
     * 就把页标成 Ready，`page.stream` 于是指向一个不存在的文件，要等渲染期才抛 ENOENT —— 而那时状态
     * 已经是 Ready，重试/重载都进不了队列（详见 [retryPage]）。这里重下重写（上限
     * [MAX_CACHE_WRITE_ATTEMPTS] 次），仍写不进去就抛 IOException，让状态老实落到 Error。
     */
    private suspend fun downloadToCache(imageUrl: String, page: ReaderPage) {
        var attempt = 0
        while (true) {
            attempt++
            val imageResponse = source.getImage(page, dataSaver = dataSaver)
            chapterCache.putImageToCache(imageUrl, imageResponse)
            if (chapterCache.isImageInCache(imageUrl)) return
            if (attempt >= MAX_CACHE_WRITE_ATTEMPTS) {
                throw IOException("图片未能写入章节缓存（已尝试 $attempt 次）：$imageUrl")
            }
        }
    }

    // EXH -->
    fun boostPage(page: ReaderPage) {
        if (page.status == Page.State.Queue) {
            scope.launchIO {
                loadPage(page)
            }
        }
    }
    // EXH <--
}

/**
 * Data class used to keep ordering of pages in order to maintain priority.
 */
@OptIn(ExperimentalAtomicApi::class)
private class PriorityPage(
    val page: ReaderPage,
    val priority: Int,
) : Comparable<PriorityPage> {
    companion object {
        private val idGenerator = AtomicInt(0)

        const val RETRY = 2
        const val DEFAULT = 1
        const val ADJACENT = 0
    }

    private val identifier = idGenerator.incrementAndFetch()

    override fun compareTo(other: PriorityPage): Int {
        val p = other.priority.compareTo(priority)
        return if (p != 0) p else identifier.compareTo(other.identifier)
    }
}

/** Komiho: 单张图写入缓存失败时的「重下 + 重写」次数上限，见 [HttpPageLoader.downloadToCache]。 */
private const val MAX_CACHE_WRITE_ATTEMPTS = 2

/**
 * Komiho: 同一个 URL 允许自动愈合（缓存文件丢失后静默重下）的次数上限，见
 * [HttpPageLoader.healMissingCache]。超过就交回上层显示错误页，避免「下完立刻被淘汰」时无限重下。
 */
private const val MAX_AUTO_HEAL_PER_URL = 2
