package tachiyomi.domain.history.model

import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.injectLazy
import java.util.Date

data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    // SY -->
    val ogTitle: String,
    // SY <--
    val chapterNumber: Double,
    val readAt: Date?,
    val readDuration: Long,
    val coverData: MangaCover,
    // SY -->
    /** 0-based index of the page this chapter was last left at. */
    val lastPageRead: Long = 0,
    /** Total pages recorded by the reader, null for chapters it never opened here. */
    val totalPages: Int? = null,
    /** Chapter is marked as read, so its progress is complete regardless of the page index. */
    val chapterRead: Boolean = false,
    // SY <--
) {
    // SY -->
    val title: String = customMangaManager.get(mangaId)?.title ?: ogTitle

    companion object {
        private val customMangaManager: GetCustomMangaInfo by injectLazy()
    }
    // SY <--
}
