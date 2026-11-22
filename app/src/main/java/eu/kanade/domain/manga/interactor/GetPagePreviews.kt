package eu.kanade.domain.manga.interactor

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.PagePreview
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.Source
import exh.source.getMainSource
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class GetPagePreviews(
    private val pagePreviewCache: PagePreviewCache,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    /**
     * @param refresh Komiho（对齐上游 TachiyomiSY 90f9bfcb64）: 跳过磁盘化的列表缓存，强制
     * 重新向源要一次预览列表。默认 false（走缓存）。
     */
    suspend fun await(manga: Manga, source: Source, page: Int, refresh: Boolean = false): Result {
        @Suppress("NAME_SHADOWING")
        val source = source.getMainSource<PagePreviewSource>() ?: return Result.Unused
        val chapters = getChaptersByMangaId.await(manga.id).sortedByDescending { it.sourceOrder }
        val chapterIds = chapters.map { it.id }
        return try {
            val pagePreviews = if (refresh) {
                fetchAndCache(manga, source, chapters, chapterIds, page)
            } else {
                try {
                    pagePreviewCache.getPageListFromCache(manga, chapterIds, page)
                } catch (_: Exception) {
                    fetchAndCache(manga, source, chapters, chapterIds, page)
                }
            }
            Result.Success(
                pagePreviews.pagePreviews.map {
                    PagePreview(it.index, it.imageUrl, source.id, it.cacheKey)
                },
                pagePreviews.hasNextPage,
                pagePreviews.pagePreviewPages,
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /** 向源要一次预览列表并写入磁盘缓存（缓存命中与 refresh 两条路共用）。 */
    private suspend fun fetchAndCache(
        manga: Manga,
        source: PagePreviewSource,
        chapters: List<Chapter>,
        chapterIds: List<Long>,
        page: Int,
    ): PagePreviewPage {
        return source.getPagePreviewList(manga.toSManga(), chapters.map { it.toSChapter() }, page).also {
            pagePreviewCache.putPageListToCache(manga, chapterIds, it)
        }
    }

    sealed class Result {
        data object Unused : Result()
        data class Success(
            val pagePreviews: List<PagePreview>,
            val hasNextPage: Boolean,
            val pageCount: Int?,
        ) : Result()
        data class Error(val error: Throwable) : Result()
    }
}
