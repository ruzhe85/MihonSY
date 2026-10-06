package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /**
     * Komiho: 渲染期发现章节缓存文件丢失时调用（由 holder 在读图片流抛出
     * `FileNotFoundException` 时发起）。
     *
     * 返回 true = 已接管：会重新入队重新下载，上层**不要**再显示错误页，等
     * [ReaderPage.statusFlow] 驱动 `Queue → DownloadImage → Ready` 自动重渲染；
     * 返回 false = 不接管（非在线源 / 自动重下次数用尽），上层按原逻辑报错。
     */
    open fun healMissingCache(page: ReaderPage): Boolean = false

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
