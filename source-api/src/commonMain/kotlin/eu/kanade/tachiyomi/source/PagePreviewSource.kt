package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import okhttp3.CacheControl
import okhttp3.Response

interface PagePreviewSource : Source {

    suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage

    suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl? = null): Response
}

@Serializable
data class PagePreviewPage(
    val page: Int,
    val pagePreviews: List<PagePreviewInfo>,
    val hasNextPage: Boolean,
    val pagePreviewPages: Int?,
)

@Serializable
data class PagePreviewInfo(
    val index: Int,
    val imageUrl: String,
    /**
     * Komiho（对齐上游 TachiyomiSY 90f9bfcb64）: 磁盘缓存用的 key，默认等于 [imageUrl]。
     *
     * 有些源的缩略图 URL 里带了会变的部分（签名/token/临时域名），拿它当 key 会让缓存永远
     * 命中不了；占位缩略图更糟 —— 多页共用同一个 URL，互相覆盖。这类源应当提供一个**稳定**
     * 的 key（如 EH 用 gid + 页序号 + 尺寸几何）。
     */
    val cacheKey: String = imageUrl,
    @Transient
    private val _progress: MutableStateFlow<Int> = MutableStateFlow(-1),
) : ProgressListener {
    @Transient
    val progress = _progress.asStateFlow()

    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
        _progress.value = if (contentLength > 0) {
            (100 * bytesRead / contentLength).toInt()
        } else {
            -1
        }
    }
}
