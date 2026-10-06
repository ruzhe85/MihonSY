package eu.kanade.domain.manga.interactor

import android.app.Application
import eu.kanade.tachiyomi.data.sync.SyncClock
import eu.kanade.tachiyomi.data.sync.SyncLedger
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import java.time.ZonedDateTime

class UpdateManga(
    private val mangaRepository: MangaRepository,
    private val fetchInterval: FetchInterval,
) {

    // SY -->
    /** Held lazily: only touched when library membership actually changes. */
    private val context: Application by injectLazy()
    // SY <--

    suspend fun await(mangaUpdate: MangaUpdate): Boolean {
        val updated = mangaRepository.update(mangaUpdate)

        // SY -->
        // A removal has to stay recognisable as one for the next merge. Being in the library travels
        // in the full sync alone, so this device notes that it owned the entry instead of syncing
        // right away; an addition needs no note, because that sync reads the library as it is then.
        if (updated && mangaUpdate.favorite == false) recordOwned(mangaUpdate.id)
        // SY <--
        return updated
    }

    suspend fun awaitAll(mangaUpdates: List<MangaUpdate>): Boolean {
        val updated = mangaRepository.updateAll(mangaUpdates)

        // SY -->
        // A batch that moves entries out of the library is noted the same way
        if (updated) {
            mangaUpdates
                .filter { it.favorite == false }
                .forEach { recordOwned(it.id) }
        }
        // SY <--
        return updated
    }

    suspend fun awaitUpdateFetchInterval(
        manga: Manga,
        dateTime: ZonedDateTime = ZonedDateTime.now(),
        window: Pair<Long, Long> = fetchInterval.getWindow(dateTime),
    ): Boolean {
        return mangaRepository.update(
            fetchInterval.toMangaUpdate(manga, dateTime, window),
        )
    }

    suspend fun awaitUpdateLastUpdate(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, lastUpdate = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateCoverLastModified(mangaId: Long): Boolean {
        return mangaRepository.update(MangaUpdate(id = mangaId, coverLastModified = Instant.now().toEpochMilli()))
    }

    suspend fun awaitUpdateFavorite(mangaId: Long, favorite: Boolean): Boolean {
        val dateAdded = when (favorite) {
            true -> Instant.now().toEpochMilli()
            false -> 0
        }
        return await(MangaUpdate(id = mangaId, favorite = favorite, dateAdded = dateAdded))
    }

    // SY -->
    /**
     * Records that this device owns the entry, right before it is removed from the library.
     *
     * A removal has to stay recognisable as one. The ledger is what lets a later merge tell "this
     * device owned it and no longer does" from "never had it", and it is only captured by a full
     * sync — so without this entry, an entry added and removed between two full syncs would let the
     * other devices' copy bring it back.
     */
    private suspend fun recordOwned(mangaId: Long) {
        val manga = runCatching { mangaRepository.getMangaById(mangaId) }.getOrNull() ?: return
        val key = SyncLedger.mangaKey(manga.source, manga.url)
        SyncLedger.markOwned(context, key, SyncClock.next(context, key))
    }
    // SY <--
}
