package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import exh.md.utils.FollowStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {

    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (
                    service == null ||
                    !service.isLoggedIn ||
                    chapterNumber <= track.lastChapterRead /* SY --> */ ||
                    (service is MdList && track.status == FollowStatus.UNFOLLOWED.long)/* SY <-- */
                ) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val updatedTrack = service.refresh(track.toDbTrack())
                                .toDomainTrack(idRequired = true)!!
                                .copy(lastChapterRead = chapterNumber)
                            service.update(updatedTrack.toDbTrack(), true)
                            insertTrack.await(updatedTrack)
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }

    /**
     * MihonSY: syncs partial page progress to Komga while reading, so Komga records
     * "read to page N" even before the chapter is completed. Only Komga supports
     * page-level granularity; other trackers are updated on chapter completion via [await].
     */
    suspend fun updateKomgaPageProgress(mangaId: Long, chapterNumber: Double, page: Int) {
        withNonCancellableContext {
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.forEach { track ->
                val service = trackerManager.get(track.trackerId)
                if (service is Komga && service.isLoggedIn) {
                    runCatching {
                        service.updatePageProgress(track.toDbTrack(), chapterNumber, page)
                    }.onFailure { logcat(LogPriority.WARN, it) { "Failed to sync Komga page progress" } }
                }
            }
        }
    }
}
