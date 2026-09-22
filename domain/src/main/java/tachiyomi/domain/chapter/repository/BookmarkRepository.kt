package tachiyomi.domain.chapter.repository

import tachiyomi.domain.chapter.model.BookmarkItem

// SY --> Komiho: 按页书签存储（一本书可多条）。与 chapters.bookmark 章节级标记相互独立。
interface BookmarkRepository {
    /** 取某来源下所有按页书签（联表，含书名/章节/封面）。 */
    suspend fun getBookmarksBySource(sourceId: Long): List<BookmarkItem>

    /** 取某章节下的所有按页书签（按页升序），用于阅读器内列表。 */
    suspend fun getBookmarksByChapter(chapterId: Long): List<BookmarkItem>

    /** 在章节的指定页新增一条书签（已存在则忽略）。 */
    suspend fun addBookmark(chapterId: Long, page: Int)

    /** 按 id 删除一条书签。 */
    suspend fun removeBookmark(id: Long)

    /** 删除章节指定页的书签（用于「再点一次取消」）。 */
    suspend fun removeBookmarkAtPage(chapterId: Long, page: Int)

    /** 该章节的指定页是否已加书签。 */
    suspend fun isPageBookmarked(chapterId: Long, page: Int): Boolean
}
// SY <--
