package tachiyomi.domain.chapter.model

import java.util.Date

// SY --> Komiho: 按页书签的一条记录。一本书（一个 chapter）可有多条，page 为 0 基页索引。
// lastRead = 章节（history 表）最后阅读时间，书签行展示用；无阅读记录为 null。
data class BookmarkItem(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val chapterName: String,
    val chapterNumber: Double,
    val chapterUrl: String,
    val page: Int,
    val createdAt: Long,
    val mangaTitle: String,
    val mangaUrl: String,
    val thumbnailUrl: String?,
    val lastRead: Date? = null,
)
// SY <--
