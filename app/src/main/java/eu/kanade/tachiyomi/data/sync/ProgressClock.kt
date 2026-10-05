package eu.kanade.tachiyomi.data.sync

import android.app.Application
import android.content.Context
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// SY -->
/**
 * Stamps the two reading actions a chapter carries on the device-local [SyncClock].
 *
 * The database keeps a single `last_modified_at` per chapter and refreshes it on *any* update, so it
 * cannot tell "the user read up to page 30" from "a bookmark was toggled" or "a sync restore wrote
 * the row". Progress merging used that column as its timestamp, which let a device that was merely
 * touched overwrite another device's real progress. The clock is advanced only where the value
 * actually changes, which is what makes the comparison trustworthy.
 *
 * Keys use the identity the sync payload uses (source + manga url + chapter url), so the backup
 * export can look them up from the mapped rows without a second database query.
 */
object ProgressClock {

    /** Held lazily: this object is only touched long after Injekt has been set up. */
    private val context: Context by lazy { Injekt.get<Application>() }

    /** Only the id-based helpers need this; the reader and export paths pass the models directly. */
    private val getManga: GetManga by lazy { Injekt.get() }

    fun progressKey(manga: Manga, chapter: Chapter): String =
        SyncClock.progressKey(manga.source, manga.url, chapter.url)

    fun bookmarkKey(manga: Manga, chapter: Chapter): String =
        SyncClock.bookmarkKey(manga.source, manga.url, chapter.url)

    /**
     * Call only after a write that actually changed `read` or `lastPageRead`. Stamping a no-op write
     * would make this device look like the most recent reader and undo another device's progress.
     */
    fun stampProgress(manga: Manga, chapter: Chapter): Long =
        SyncClock.next(context, progressKey(manga, chapter))

    fun stampProgress(manga: Manga, chapters: Collection<Chapter>): Long =
        chapters.maxOfOrNull { stampProgress(manga, it) } ?: 0L

    /** Call only after a write that actually changed the chapter bookmark flag. */
    fun stampBookmark(manga: Manga, chapter: Chapter): Long =
        SyncClock.next(context, bookmarkKey(manga, chapter))

    fun stampBookmark(manga: Manga, chapters: Collection<Chapter>): Long =
        chapters.maxOfOrNull { stampBookmark(manga, it) } ?: 0L

    // Overloads taking the identity directly: the reader works with the legacy chapter model and
    // the progress channel reads raw rows, so neither can hand over the domain model.
    fun stampProgress(source: Long, mangaUrl: String, chapterUrl: String): Long =
        SyncClock.next(context, SyncClock.progressKey(source, mangaUrl, chapterUrl))

    fun stampBookmark(source: Long, mangaUrl: String, chapterUrl: String): Long =
        SyncClock.next(context, SyncClock.bookmarkKey(source, mangaUrl, chapterUrl))

    fun progressAt(source: Long, mangaUrl: String, chapterUrl: String): Long =
        SyncClock.peek(context, SyncClock.progressKey(source, mangaUrl, chapterUrl))

    /**
     * Records a timestamp that was decided elsewhere (a progress pulled from another device, or a
     * merged value written by a restore), so this device's next write lands after it and the merged
     * value is not immediately reverted by an older local stamp.
     */
    fun observeProgress(manga: Manga, chapter: Chapter, timestamp: Long) =
        SyncClock.observe(context, progressKey(manga, chapter), timestamp)

    fun observeProgress(source: Long, mangaUrl: String, chapterUrl: String, timestamp: Long) =
        SyncClock.observe(context, SyncClock.progressKey(source, mangaUrl, chapterUrl), timestamp)

    fun observeBookmark(source: Long, mangaUrl: String, chapterUrl: String, timestamp: Long) =
        SyncClock.observe(context, SyncClock.bookmarkKey(source, mangaUrl, chapterUrl), timestamp)

    /**
     * Convenience for call sites that only know the manga id (library-wide read toggles, tracker
     * pulls). The id is device-local, so the manga is looked up once to build the shared key.
     */
    suspend fun stampProgressByMangaId(mangaId: Long, chapters: Collection<Chapter>): Long {
        if (chapters.isEmpty()) return 0L
        val manga = getManga.await(mangaId) ?: return 0L
        return stampProgress(manga, chapters)
    }

    suspend fun stampBookmarkByMangaId(mangaId: Long, chapters: Collection<Chapter>): Long {
        if (chapters.isEmpty()) return 0L
        val manga = getManga.await(mangaId) ?: return 0L
        return stampBookmark(manga, chapters)
    }

    /** Persists the clock, for callers that are about to lose their coroutine scope. */
    fun flush() = SyncClock.flush(context)

    /** Timestamp in use for this chapter's progress, 0 when it was never stamped by this device. */
    fun progressAt(manga: Manga, chapter: Chapter): Long =
        SyncClock.peek(context, progressKey(manga, chapter))

    fun bookmarkAt(manga: Manga, chapter: Chapter): Long =
        SyncClock.peek(context, bookmarkKey(manga, chapter))

    /**
     * Timestamp to export for a chapter, adopting the chapter's own `last_modified_at` the first
     * time it is seen. Without that seed the change would make every existing chapter look like it
     * was read just now on the first sync after upgrading.
     */
    fun exportedProgressAt(source: Long, mangaUrl: String, chapterUrl: String, seed: Long): Long =
        SyncClock.adopt(context, SyncClock.progressKey(source, mangaUrl, chapterUrl), seed)

    fun exportedBookmarkAt(source: Long, mangaUrl: String, chapterUrl: String, seed: Long): Long =
        SyncClock.adopt(context, SyncClock.bookmarkKey(source, mangaUrl, chapterUrl), seed)
}
// SY <--
