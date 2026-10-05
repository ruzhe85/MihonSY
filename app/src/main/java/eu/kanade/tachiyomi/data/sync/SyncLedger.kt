package eu.kanade.tachiyomi.data.sync

import android.content.Context
import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.data.backup.models.backupBookmarkMapper
import java.io.File
import logcat.LogPriority
import logcat.logcat
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaMapper.mapManga
import tachiyomi.domain.category.interactor.GetCategories

// SY -->
/**
 * Record of which entries this device owned at its last successful sync, together with the version
 * timestamp each one had then.
 *
 * This is what lets a removal become a tombstone. A key that is in the ledger but no longer present
 * in the local library was *deleted* here, as opposed to a key that was never in the ledger, which
 * was simply never owned and must be adopted from the remote. The previous approach inferred this
 * from a single global `lastSyncTimestamp` compared against entry timestamps, which could not
 * represent a deletion at all.
 *
 * Lines are `key\ttimestamp`; files written before the timestamp was added are still readable and
 * are interpreted as timestamp 0.
 */
object SyncLedger {

    private const val FILE_NAME = "sync_ledger.txt"
    private const val MANGA_PREFIX = "m:"
    private const val CATEGORY_PREFIX = "c:"
    private const val CHAPTER_PREFIX = "s:"
    private const val BOOKMARK_PREFIX = "b:"
    private const val HISTORY_PREFIX = "h:"

    fun mangaKey(source: Long, url: String) = "$MANGA_PREFIX$source|$url"

    fun categoryKey(name: String) = "$CATEGORY_PREFIX$name"

    fun chapterKey(source: Long, mangaUrl: String, chapterUrl: String) =
        "$CHAPTER_PREFIX$source|$mangaUrl|$chapterUrl"

    fun bookmarkKey(source: Long, mangaUrl: String, chapterUrl: String, page: Int) =
        "$BOOKMARK_PREFIX$source|$mangaUrl|$chapterUrl|$page"

    fun historyKey(source: Long, mangaUrl: String, chapterUrl: String) =
        "$HISTORY_PREFIX$source|$mangaUrl|$chapterUrl"

    fun load(context: Context): Map<String, Long> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyMap()
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val tab = line.lastIndexOf('\t')
                    if (tab <= 0) {
                        // Legacy format: bare key, no timestamp
                        line to 0L
                    } else {
                        val ts = line.substring(tab + 1).toLongOrNull() ?: return@mapNotNull null
                        line.substring(0, tab) to ts
                    }
                }
                .toMap()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to load sync ledger: ${e.message}" }
            emptyMap()
        }
    }

    fun write(context: Context, entries: Map<String, Long>) {
        try {
            File(context.filesDir, FILE_NAME).writeText(
                entries.entries
                    .sortedBy { it.key }
                    .joinToString("\n") { "${it.key}\t${it.value}" },
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to write sync ledger: ${e.message}" }
        }
    }

    /**
     * Snapshots what this device currently owns, so the next merge can tell a local removal from an
     * entry that was never here. Must be called once the restore has actually been applied.
     *
     * Chapters are deliberately absent: covering them would need one query per library entry, and
     * chapter rows disappear on their own when a source updates. Chapter tombstones arriving from
     * the remote are still applied; this device just never originates one.
     */
    suspend fun capture(database: Database, getCategories: GetCategories): Map<String, Long> {
        val favorites = database.mangasQueries
            .getAllManga(::mapManga)
            .awaitAsList()
            .filter { it.favorite }

        return buildMap<String, Long> {
            favorites.forEach {
                put(mangaKey(it.source, it.url), maxOf(it.lastModifiedAt, it.favoriteModifiedAt ?: 0L))
            }

            getCategories.await()
                .filter { it.id != 0L }
                .forEach { put(categoryKey(it.name), it.lastModifiedAt) }

            database.bookmarksQueries.bookmarksForBackup(backupBookmarkMapper)
                .awaitAsList()
                .forEach { put(bookmarkKey(it.source, it.mangaUrl, it.chapterUrl, it.page), it.createdAt) }

            // History, keyed by the chapter it belongs to. The stored value is the reading time as
            // of this sync; watching it fall from non-zero to zero is how the next merge recognises
            // a reading date that was cleared in the meantime.
            favorites.forEach mangaLoop@{ manga ->
                val historyRows = database.historyQueries.getHistoryByMangaId(manga.id).awaitAsList()
                if (historyRows.isEmpty()) return@mangaLoop

                val chapterUrls = database.chaptersQueries
                    .getChaptersByMangaId(mangaId = manga.id, applyScanlatorFilter = 0)
                    .awaitAsList()
                    .associate { it._id to it.url }

                historyRows.forEach rowLoop@{ row ->
                    val url = chapterUrls[row.chapter_id] ?: return@rowLoop
                    put(historyKey(manga.source, manga.url, url), row.last_read?.time ?: 0L)
                }
            }
        }
    }
}
// SY <--
