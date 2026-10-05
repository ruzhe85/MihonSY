package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
// SY -->
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.ProgressClock
// SY <--
import exh.source.MERGED_SOURCE_ID
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.injectLazy

class SetReadStatus(
    private val downloadPreferences: DownloadPreferences,
    private val deleteDownload: DeleteDownload,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    // SY -->
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId,
    // SY <--
) {

    // SY -->
    /** Held lazily: only touched when a read status actually changed. */
    private val syncPreferences: SyncPreferences by injectLazy()
    // SY <--

    private val mapper = { chapter: Chapter, read: Boolean ->
        ChapterUpdate(
            read = read,
            lastPageRead = if (!read) 0 else null,
            id = chapter.id,
        )
    }

    suspend fun await(read: Boolean, vararg chapters: Chapter): Result = withNonCancellableContext {
        val chaptersToUpdate = chapters.filter {
            when (read) {
                true -> !it.read
                false -> it.read || it.lastPageRead > 0
            }
        }
        if (chaptersToUpdate.isEmpty()) {
            return@withNonCancellableContext Result.NoChapters
        }

        try {
            chapterRepository.updateAll(
                chaptersToUpdate.map { mapper(it, read) },
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        // SY -->
        // Read state only travels in the full sync, and marking a few hundred chapters at once would
        // overflow the progress channel's fixed size, so the change is flagged here: the full sync
        // runs once the user stops editing instead of once per edit.
        syncPreferences.syncPendingChange.set(true)
        // SY <--

        // SY -->
        // Marking chapters read (or unread) is a real progress change, so the reading clock is
        // advanced for the chapters that actually changed. The filter above is what keeps that
        // honest: re-marking an already read chapter must not make this device look newer than it is.
        chaptersToUpdate
            .groupBy { it.mangaId }
            .forEach { (mangaId, chapters) ->
                ProgressClock.stampProgressByMangaId(mangaId, chapters)
            }
        // SY <--

        if (read && downloadPreferences.removeAfterMarkedAsRead.get()) {
            chaptersToUpdate
                .groupBy { it.mangaId }
                .forEach { (mangaId, chapters) ->
                    deleteDownload.awaitAll(
                        manga = mangaRepository.getMangaById(mangaId),
                        chapters = chapters.toTypedArray(),
                    )
                }
        }

        Result.Success
    }

    suspend fun await(mangaId: Long, read: Boolean): Result = withNonCancellableContext {
        await(
            read = read,
            chapters = chapterRepository
                .getChapterByMangaId(mangaId)
                .toTypedArray(),
        )
    }

    // SY -->
    private suspend fun awaitMerged(mangaId: Long, read: Boolean) = withNonCancellableContext f@{
        return@f await(
            read = read,
            chapters = getMergedChaptersByMangaId
                .await(mangaId, dedupe = false)
                .toTypedArray(),
        )
    }

    suspend fun await(manga: Manga, read: Boolean) = if (manga.source == MERGED_SOURCE_ID) {
        awaitMerged(manga.id, read)
    } else {
        await(manga.id, read)
    }
    // SY <--

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
