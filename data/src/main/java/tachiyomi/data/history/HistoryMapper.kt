package tachiyomi.data.history

import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.ChapterMemo
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

object HistoryMapper {
    fun mapHistory(
        id: Long,
        chapterId: Long,
        readAt: Date?,
        readDuration: Long,
    ): History = History(
        id = id,
        chapterId = chapterId,
        readAt = readAt,
        readDuration = readDuration,
    )

    fun mapHistoryWithRelations(
        historyId: Long,
        mangaId: Long,
        chapterId: Long,
        title: String,
        thumbnailUrl: String?,
        sourceId: Long,
        isFavorite: Boolean,
        coverLastModified: Long,
        chapterNumber: Double,
        readAt: Date?,
        readDuration: Long,
        // SY -->
        lastPageRead: Long,
        chapterMemo: JsonObject,
        chapterRead: Boolean,
        // SY <--
    ): HistoryWithRelations = HistoryWithRelations(
        id = historyId,
        chapterId = chapterId,
        mangaId = mangaId,
        // SY -->
        ogTitle = title,
        // SY <--
        chapterNumber = chapterNumber,
        readAt = readAt,
        readDuration = readDuration,
        // SY -->
        lastPageRead = lastPageRead,
        totalPages = ChapterMemo.pages(chapterMemo),
        chapterRead = chapterRead,
        // SY <--
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = isFavorite,
            ogUrl = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
