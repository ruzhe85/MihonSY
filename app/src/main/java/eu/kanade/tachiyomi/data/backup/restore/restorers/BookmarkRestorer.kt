package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.tachiyomi.data.backup.models.BackupBookmark
import logcat.LogPriority
import logcat.logcat
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterMapper.mapChapter
import tachiyomi.data.manga.MangaMapper.mapManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BookmarkRestorer(
    private val database: Database = Injekt.get(),
) {

    /**
     * Restores page bookmarks. Entries are located by (source, mangaUrl, chapterUrl) since
     * chapter ids are local autoincrement values that differ across devices.
     * Bookmarks whose manga or chapter cannot be resolved are skipped.
     */
    suspend fun restoreBookmarks(backupBookmarks: List<BackupBookmark>) {
        if (backupBookmarks.isEmpty()) return

        val resolved = backupBookmarks.mapNotNull { bookmark ->
            val manga = database.mangasQueries.getMangaByUrlAndSource(
                url = bookmark.mangaUrl,
                source = bookmark.source,
                mapper = ::mapManga,
            ).awaitAsOneOrNull() ?: return@mapNotNull null

            val chapter = database.chaptersQueries.getChapterByUrlAndMangaId(
                chapterUrl = bookmark.chapterUrl,
                mangaId = manga.id,
                mapper = ::mapChapter,
            ).awaitAsOneOrNull() ?: return@mapNotNull null

            bookmark to chapter.id
        }

        database.transaction {
            resolved.forEach { (bookmark, chapterId) ->
                val count = database.bookmarksQueries.countByChapterAndPage(
                    chapterId = chapterId,
                    page = bookmark.page.toLong(),
                ).awaitAsOneOrNull() ?: 0L
                if (count == 0L) {
                    database.bookmarksQueries.insert(
                        chapterId = chapterId,
                        page = bookmark.page.toLong(),
                        createdAt = bookmark.createdAt,
                    )
                }
            }
        }

        logcat(LogPriority.DEBUG) { "Restored ${resolved.size} bookmarks" }
    }
}
