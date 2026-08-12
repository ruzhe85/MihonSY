package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.komga.Komga
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.time.OffsetDateTime
import kotlin.math.max

class SyncChapterProgressWithTrack(
    private val updateChapter: UpdateChapter,
    private val insertTrack: InsertTrack,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    suspend fun await(
        mangaId: Long,
        remoteTrack: Track,
        tracker: Tracker,
    ) {
        if (tracker !is EnhancedTracker) {
            return
        }

        val sortedChapters = getChaptersByMangaId.await(mangaId)
            .sortedBy { it.chapterNumber }
            .filter { it.isRecognizedNumber }

        // MihonSY: Komga keeps per-book read progress (which books are completed and on which
        // page each in-progress book is), so sync per-book instead of the "continuous prefix"
        // model used by other trackers. "Newer wins" decides the direction per chapter/book.
        if (tracker is Komga) {
            syncFromKomga(tracker, remoteTrack, sortedChapters)
        } else {
            syncFromContinuousRead(remoteTrack, tracker, sortedChapters)
        }
    }

    /**
     * Original behaviour for non-Komga trackers: mark the continuous prefix as read and
     * push back the max of local/remote last chapter read.
     */
    private suspend fun syncFromContinuousRead(
        remoteTrack: Track,
        tracker: Tracker,
        sortedChapters: List<Chapter>,
    ) {
        val chapterUpdates = sortedChapters
            .filter { chapter -> chapter.chapterNumber <= remoteTrack.lastChapterRead && !chapter.read }
            .map { it.copy(read = true).toChapterUpdate() }

        // only take into account continuous reading
        val localLastRead = sortedChapters.takeWhile { it.read }.lastOrNull()?.chapterNumber ?: 0F
        val lastRead = max(remoteTrack.lastChapterRead, localLastRead.toDouble())
        val updatedTrack = remoteTrack.copy(lastChapterRead = lastRead)

        try {
            tracker.update(updatedTrack.toDbTrack())
            updateChapter.awaitAll(chapterUpdates)
            insertTrack.await(updatedTrack)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e)
        }
    }

    /**
     * MihonSY: per-book bidirectional sync with Komga.
     *
     * For every local chapter matched to a Komga book (by numberSort), the newer state wins:
     * - Komga newer -> pull: completed books become read; the in-progress book's page is
     *   written back to the local chapter (lastPageRead), so "continue reading" resumes at
     *   the exact position.
     * - SY newer -> push: locally read chapters are marked completed in Komga; chapters with
     *   page progress are pushed as page progress.
     */
    private suspend fun syncFromKomga(
        komga: Komga,
        remoteTrack: Track,
        sortedChapters: List<Chapter>,
    ) {
        val books = try {
            komga.getSeriesBooks(remoteTrack.toDbTrack())
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Failed to fetch Komga books for reverse sync" }
            return
        }
        val bookByNumber = books.associateBy { it.metadata.numberSort.toDouble() }

        val chapterUpdates = mutableListOf<ChapterUpdate>()
        for (chapter in sortedChapters) {
            val book = bookByNumber[chapter.chapterNumber] ?: continue
            val progress = book.readProgress
            val komgaCompleted = progress?.completed == true
            val komgaPage = progress?.page ?: 0
            val komgaTs = parseKomgaTime(progress?.lastModified)
            val syTs = chapter.lastModifiedAt
            val komgaNewer = komgaTs != null && (syTs <= 0 || komgaTs > syTs)

            if (komgaNewer) {
                // Pull Komga state into the local chapter.
                when {
                    komgaCompleted -> if (!chapter.read) {
                        chapterUpdates += chapter.copy(read = true).toChapterUpdate()
                    }

                    komgaPage > 0 -> if (chapter.read || chapter.lastPageRead != komgaPage.toLong()) {
                        chapterUpdates += chapter.copy(
                            read = false,
                            lastPageRead = komgaPage.toLong(),
                        ).toChapterUpdate()
                    }

                    else -> if (chapter.read || chapter.lastPageRead != 0L) {
                        chapterUpdates += chapter.copy(read = false, lastPageRead = 0).toChapterUpdate()
                    }
                }
            } else {
                // Local state is newer (or unknown): push it to Komga per-book.
                runCatching {
                    when {
                        chapter.read && !komgaCompleted ->
                            komga.markBookCompleted(remoteTrack.toDbTrack(), chapter.chapterNumber)

                        !chapter.read && chapter.lastPageRead > 0 &&
                            !komgaCompleted && komgaPage != chapter.lastPageRead.toInt() ->
                            komga.updatePageProgress(remoteTrack.toDbTrack(), chapter.chapterNumber, chapter.lastPageRead.toInt())
                    }
                }.onFailure { logcat(LogPriority.WARN, it) { "Failed to push chapter state to Komga" } }
            }
        }

        try {
            if (chapterUpdates.isNotEmpty()) {
                updateChapter.awaitAll(chapterUpdates)
            }
            // Keep the local track in sync with what Komga now reports.
            insertTrack.await(remoteTrack)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e)
        }
    }

    private fun parseKomgaTime(s: String?): Long? = try {
        s?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (e: Exception) {
        null
    }
}
