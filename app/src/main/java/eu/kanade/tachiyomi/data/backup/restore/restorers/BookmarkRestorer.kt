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
    suspend fun restoreBookmarks(backupBookmarks: List<BackupBookmark>, applyDeletions: Boolean = false) {
        if (backupBookmarks.isEmpty()) return

        // SY -->
        // Tombstoned bookmarks are removals and must be applied before inserting, otherwise a
        // deletion would be undone by the very same sync that carried it.
        val tombstones = if (applyDeletions) {
            backupBookmarks.filter { it.deletedAt > 0L }
        } else {
            emptyList()
        }
        val removedKeys = tombstones
            .map { "${it.source}|${it.mangaUrl}|${it.chapterUrl}|${it.page}" }
            .toSet()

        if (tombstones.isNotEmpty()) {
            val toDelete = tombstones.mapNotNull { tombstone ->
                val manga = database.mangasQueries.getMangaByUrlAndSource(
                    url = tombstone.mangaUrl,
                    source = tombstone.source,
                    mapper = ::mapManga,
                ).awaitAsOneOrNull() ?: return@mapNotNull null

                val chapterId = database.chaptersQueries.getChapterByUrlAndMangaId(
                    chapterUrl = tombstone.chapterUrl,
                    mangaId = manga.id,
                    mapper = ::mapChapter,
                ).awaitAsOneOrNull()?.id ?: return@mapNotNull null

                chapterId to tombstone.page
            }

            if (toDelete.isNotEmpty()) {
                database.transaction {
                    toDelete.forEach { (chapterId, page) ->
                        database.bookmarksQueries.deleteByChapterAndPage(chapterId, page.toLong())
                    }
                }
            }
            logcat(LogPriority.INFO) { "Sync removed ${toDelete.size} bookmarks deleted on another device" }
        }
        // SY <--

        val resolved = backupBookmarks
            .filterNot { "${it.source}|${it.mangaUrl}|${it.chapterUrl}|${it.page}" in removedKeys }
            .mapNotNull { bookmark ->
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

        // Deduplicate outside the transaction so its body stays await-free
        // (same pattern as SavedSearchRestorer)
        val toInsert = resolved.filter { (bookmark, chapterId) ->
            val count = database.bookmarksQueries.countByChapterAndPage(
                chapterId = chapterId,
                page = bookmark.page.toLong(),
            ).awaitAsOneOrNull() ?: 0L
            count == 0L
        }

        database.transaction {
            toInsert.forEach { (bookmark, chapterId) ->
                database.bookmarksQueries.insert(
                    chapterId = chapterId,
                    page = bookmark.page.toLong(),
                    createdAt = bookmark.createdAt,
                )
            }
        }

        logcat(LogPriority.DEBUG) { "Restored ${toInsert.size} bookmarks" }
    }
}
