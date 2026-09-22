package tachiyomi.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.BookmarkItem
import tachiyomi.domain.chapter.repository.BookmarkRepository

// SY --> Komiho: 按页书签仓库实现。
class BookmarksRepositoryImpl(
    private val database: Database,
) : BookmarkRepository {

    override suspend fun getBookmarksBySource(sourceId: Long): List<BookmarkItem> {
        return database.bookmarksQueries
            .bookmarksBySource(sourceId) { id, chapterId, page, createdAt, mangaId, chapterName, chapterNumber, chapterUrl, mangaTitle, mangaUrl, thumbnailUrl, lastRead ->
                BookmarkItem(
                    id = id,
                    chapterId = chapterId,
                    mangaId = mangaId,
                    chapterName = chapterName,
                    chapterNumber = chapterNumber,
                    chapterUrl = chapterUrl,
                    page = page.toInt(),
                    createdAt = createdAt,
                    mangaTitle = mangaTitle,
                    mangaUrl = mangaUrl,
                    thumbnailUrl = thumbnailUrl,
                    lastRead = lastRead,
                )
            }
            .awaitAsList()
    }

    override suspend fun getBookmarksByChapter(chapterId: Long): List<BookmarkItem> {
        return database.bookmarksQueries
            .getByChapter(chapterId) { id, ch, page, createdAt ->
                // 阅读器内列表只需 id/chapterId/page，其余填空。
                BookmarkItem(
                    id = id,
                    chapterId = ch,
                    mangaId = -1,
                    chapterName = "",
                    chapterNumber = -1.0,
                    chapterUrl = "",
                    page = page.toInt(),
                    createdAt = createdAt,
                    mangaTitle = "",
                    mangaUrl = "",
                    thumbnailUrl = null,
                )
            }
            .awaitAsList()
    }

    override suspend fun addBookmark(chapterId: Long, page: Int) {
        if (isPageBookmarked(chapterId, page)) return
        database.bookmarksQueries.insert(chapterId, page.toLong(), System.currentTimeMillis())
    }

    override suspend fun removeBookmark(id: Long) {
        database.bookmarksQueries.deleteById(id)
    }

    override suspend fun removeBookmarkAtPage(chapterId: Long, page: Int) {
        database.bookmarksQueries.deleteByChapterAndPage(chapterId, page.toLong())
    }

    override suspend fun isPageBookmarked(chapterId: Long, page: Int): Boolean {
        return database.bookmarksQueries.countByChapterAndPage(chapterId, page.toLong()).awaitAsOne() > 0
    }
}
// SY <--
