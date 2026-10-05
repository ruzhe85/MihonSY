package eu.kanade.tachiyomi.data.sync

import android.content.Context
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterMapper.mapChapter
import tachiyomi.data.manga.MangaMapper.mapManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Date

// SY -->
/**
 * Reading-history channel.
 *
 * The history screen of another device only moved after a whole backup round trip, because history
 * rides inside the library entries. This channel gives it a file of its own: one entry per manga,
 * holding where it was last read, or the fact that its reading date was cleared.
 *
 * Two rules keep it from destroying data:
 *  - an entry that is missing from the other side is never read as a deletion. History is deleted by
 *    clearing the reading date, which travels inside the entry; a hard delete of the rows
 *    ("clear all history") is approximated by clearing every entry this device knows about.
 *  - what a remote entry is allowed to touch is decided against the database, not against the cache,
 *    so a cache that is ahead or behind cannot make an older value overwrite a newer one.
 */
class HistorySyncManager(
    private val context: Context,
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val database: Database = Injekt.get(),
    private val protoBuf: ProtoBuf = Injekt.get(),
) {

    companion object {
        private const val CACHE_FILE = "sync_history_cache.bin"

        /** Opening the history screen pulls no more often than this. */
        private const val PULL_THROTTLE_MILLIS = 30_000L

        /** Reading reports history no more often than this, unless forced. */
        private const val PUSH_THROTTLE_MILLIS = 30_000L
    }

    private val channel = LightSyncChannel(PULL_THROTTLE_MILLIS, PUSH_THROTTLE_MILLIS)

    private var entries: MutableMap<String, HistoryEntry>? = null

    /** Only WebDAV carries the extra files for now; other services just skip this channel. */
    private val remote: WebDavSyncService? by lazy {
        val service = SyncManager.SyncService.fromInt(syncPreferences.syncService.get())
        if (service == SyncManager.SyncService.WEBDAV) WebDavSyncService(context) else null
    }

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    /**
     * The channel follows the history toggle of the sync settings: a device that does not sync
     * history must not start exchanging it through the back door.
     */
    private fun isAvailable(): Boolean = remote != null &&
        syncPreferences.isSyncEnabled() &&
        syncPreferences.getSyncSettings().history

    // ---- local cache ---------------------------------------------------------------------------

    private fun cacheLocked(): MutableMap<String, HistoryEntry> {
        entries?.let { return it }

        val loaded = try {
            if (cacheFile.exists()) {
                protoBuf
                    .decodeFromByteArray(SyncHistory.serializer(), cacheFile.readBytes())
                    .entries
                    .associateBy { it.key }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to read history cache: ${e.message}" }
            mutableMapOf()
        }

        entries = loaded
        return loaded
    }

    /**
     * No truncation: one entry per manga the user ever read on one of the devices, so the file grows
     * with the library rather than with the number of reads, and a dropped entry could take a
     * clearing with it.
     */
    private fun persistLocked() {
        val current = entries ?: return
        try {
            val payload = SyncHistory(
                deviceId = syncPreferences.uniqueDeviceID(),
                entries = current.values.toList(),
            )
            cacheFile.writeBytes(protoBuf.encodeToByteArray(SyncHistory.serializer(), payload))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to write history cache: ${e.message}" }
        }
    }

    private suspend fun pullQuietly(): LightSnapshot<SyncHistory>? = try {
        remote?.pullHistory()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.DEBUG) { "History pull failed: ${e.message}" }
        null
    }

    // ---- reporting local history ---------------------------------------------------------------

    /**
     * Records that this device read [chapterUrl] of [mangaId], so the other devices move that manga
     * to the top of their history and show the same chapter.
     */
    suspend fun recordLocalRead(
        mangaId: Long,
        chapterUrl: String,
        readAtSeconds: Long,
        force: Boolean = false,
    ) {
        if (!isAvailable() || chapterUrl.isBlank()) return
        if (!channel.takePushSlot(force)) return

        val manga = database.mangasQueries.getMangaById(mangaId, ::mapManga).awaitAsOneOrNull() ?: return
        val entry = HistoryEntry(
            source = manga.source,
            mangaUrl = manga.url,
            chapterUrl = chapterUrl,
            lastRead = readAtSeconds,
            clearedAt = 0L,
            updatedAt = HistoryClock.stampHistory(manga.source, manga.url),
        )

        channel.mutex.withLock {
            cacheLocked()[entry.key] = entry
            persistLocked()
        }

        push()
    }

    /**
     * Records that this device cleared the reading date of [mangaId]. The whole manga travels as one
     * entry, so the other devices clear it the same way instead of needing every chapter it had.
     */
    suspend fun recordLocalClear(mangaId: Long, force: Boolean = true) {
        if (!isAvailable()) return
        if (!channel.takePushSlot(force)) return

        val manga = database.mangasQueries.getMangaById(mangaId, ::mapManga).awaitAsOneOrNull() ?: return
        val stamp = HistoryClock.stampHistory(manga.source, manga.url)

        channel.mutex.withLock {
            val current = cacheLocked()
            val key = "${manga.source}|${manga.url}"
            current[key] = HistoryEntry(
                source = manga.source,
                mangaUrl = manga.url,
                // Kept only so the other side has an address for the entry
                chapterUrl = current[key]?.chapterUrl.orEmpty(),
                lastRead = 0L,
                clearedAt = stamp,
                updatedAt = stamp,
            )
            persistLocked()
        }

        push()
    }

    /**
     * "Clear all history" deletes the rows outright, which leaves nothing to compare against. Marking
     * every entry this device knows as cleared is the same statement in a form the other devices act
     * on, and it stops the file from handing the deleted entries straight back on the next pull.
     */
    suspend fun recordLocalClearAll(force: Boolean = true) {
        if (!isAvailable()) return
        if (!channel.takePushSlot(force)) return

        channel.mutex.withLock {
            val current = cacheLocked()
            current.keys.toList().forEach { key ->
                val entry = current[key] ?: return@forEach
                val stamp = HistoryClock.stampHistory(entry.source, entry.mangaUrl)
                current[key] = entry.copy(lastRead = 0L, clearedAt = stamp, updatedAt = stamp)
            }
            persistLocked()
        }

        push()
    }

    // ---- reading the remote history ------------------------------------------------------------

    /**
     * Pulls the remote history and applies the entries that say something this device does not have.
     *
     * Never throws and never blocks for long: a missing or unreachable file simply means the device
     * keeps using its own history.
     */
    suspend fun pullAndApply(force: Boolean = false) {
        if (!isAvailable()) return
        if (!channel.takePullSlot(force)) return

        val fetched = pullQuietly() ?: return
        val winners = adopt(fetched)
        applyAll(winners)
    }

    /** Folds a fetched file into the cache and returns the entries that were newer than ours. */
    private suspend fun adopt(fetched: LightSnapshot<SyncHistory>): List<HistoryEntry> =
        channel.mutex.withLock {
            channel.etag = fetched.etag
            val current = cacheLocked()
            val newer = fetched.payload.entries.filter { incoming ->
                val mine = current[incoming.key]
                mine == null || incoming.updatedAt > mine.updatedAt
            }
            newer.forEach { current[it.key] = it }
            if (newer.isNotEmpty()) persistLocked()
            newer
        }

    private suspend fun applyAll(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        try {
            entries.forEach { applyEntry(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to apply remote history: ${e.message}" }
        }
    }

    /**
     * Applies one remote entry, but only where the database does not already hold something newer.
     * The comparison is deliberately made against the database: the cache is a record of what the
     * channel exchanged, not of what the user actually read.
     */
    private suspend fun applyEntry(entry: HistoryEntry) {
        val manga = database.mangasQueries
            .getMangaByUrlAndSource(url = entry.mangaUrl, source = entry.source, mapper = ::mapManga)
            .awaitAsOneOrNull() ?: return

        HistoryClock.observeHistory(entry.source, entry.mangaUrl, entry.updatedAt)

        if (entry.isCleared) {
            val latestLocal = database.historyQueries.getHistoryByMangaId(manga.id)
                .awaitAsList()
                .maxOfOrNull { (it.last_read?.time ?: 0L) / 1000L }
                ?: 0L
            // A reading date written after the clearing is a later statement than the one coming in
            if (latestLocal > entry.clearedAt) return
            database.historyQueries.resetHistoryByMangaId(manga.id)
            logcat(LogPriority.DEBUG) { "Applied remote history clearing for ${entry.mangaUrl}" }
            return
        }

        val chapter = database.chaptersQueries
            .getChapterByUrlAndMangaId(chapterUrl = entry.chapterUrl, mangaId = manga.id, mapper = ::mapChapter)
            .awaitAsOneOrNull() ?: return

        val local = database.historyQueries
            .getHistoryByChapterUrl(manga.id, entry.chapterUrl)
            .awaitAsOneOrNull()
        val localSeconds = (local?.last_read?.time ?: 0L) / 1000L
        if (localSeconds > entry.lastRead) return

        // history.upsert accumulates time_read, so a value read from another device has to
        // contribute nothing to the local reading duration
        database.historyQueries.upsert(
            chapterId = chapter.id,
            readAt = Date(entry.lastRead * 1000L),
            time_read = 0L,
        )
    }

    // ---- transport -----------------------------------------------------------------------------

    /** Uploads the current list; on a lost race it merges once and retries. */
    private suspend fun push() {
        val service = remote ?: return

        val (payload, etag) = channel.mutex.withLock {
            SyncHistory(
                deviceId = syncPreferences.uniqueDeviceID(),
                entries = cacheLocked().values.toList(),
            ) to channel.etag
        }

        val uploaded = try {
            service.pushHistory(payload, etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "History push failed: ${e.message}" }
            false
        }

        if (uploaded) return

        // Someone wrote in the meantime. Merge with what is there now and retry once, so a busy
        // remote does not silently swallow this device's history.
        val latest = pullQuietly() ?: return
        val adopted = adopt(latest)
        val merged = channel.mutex.withLock {
            SyncHistory(syncPreferences.uniqueDeviceID(), cacheLocked().values.toList())
        }

        try {
            service.pushHistory(merged, latest.etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "History retry push failed: ${e.message}" }
            return
        }

        // Whatever the retry merged in also has to reach the database, or the values this device
        // just agreed to would never be applied locally.
        applyAll(adopted)
    }

    // ---- integration with the full sync --------------------------------------------------------

    /**
     * Folds the history channel into the full payload and returns the payload to push.
     *
     * The full sync is still what makes a device that never opens the history screen converge, so the
     * newest value has to exist on both sides: entries this file is ahead on are written into the
     * payload, and entries the payload is ahead on are written back into this file.
     */
    suspend fun mergeWithFullBackup(backup: Backup): Backup {
        if (!isAvailable()) return backup

        val fetched = pullQuietly()

        val fromFile = channel.mutex.withLock {
            if (fetched != null) channel.etag = fetched.etag

            val current = cacheLocked()

            // Entries the file is ahead on, by key, so the ones the payload outbids are dropped again
            val fileWinners = LinkedHashMap<String, HistoryEntry>()
            fetched?.payload?.entries?.forEach { incoming ->
                val mine = current[incoming.key]
                if (mine == null || incoming.updatedAt > mine.updatedAt) {
                    current[incoming.key] = incoming
                    fileWinners[incoming.key] = incoming
                }
            }

            var touched = fetched != null

            backup.backupManga.forEach { manga ->
                val key = "${manga.source}|${manga.url}"
                val mine = current[key]
                val theirs = manga.latestHistoryEntry()

                if (mine == null) {
                    if (theirs == null) return@forEach
                    // Learn the payload's value in the cache and in the clock: the clock is what the
                    // next local read is stamped against, and it has to land after this.
                    current[key] = theirs.toEntry(manga)
                    HistoryClock.observeHistory(manga.source, manga.url, theirs.at)
                    touched = true
                    return@forEach
                }

                if (theirs != null && theirs.at > mine.updatedAt) {
                    current[key] = theirs.toEntry(manga)
                    HistoryClock.observeHistory(manga.source, manga.url, theirs.at)
                    fileWinners.remove(key)
                    touched = true
                } else {
                    manga.writeHistoryEntry(mine)
                }
            }

            if (touched) persistLocked()
            fileWinners.values.toList()
        }

        // What the file contributed has to reach the database as well. The payload carries it onward,
        // but a restore only runs for entries it considers different, and a newer reading time does
        // not change a chapter, so this device would stay behind its own file.
        applyAll(fromFile)

        push()
        return backup
    }

    /**
     * The payload's most recent statement about a manga, as one comparable timestamp. History travels
     * per chapter there, so "the latest" is the entry with the largest reading or clearing time.
     */
    private class PayloadHistory(
        val url: String,
        val lastReadSeconds: Long,
        val clearedAt: Long,
        val at: Long,
    ) {
        fun toEntry(manga: BackupManga) = HistoryEntry(
            source = manga.source,
            mangaUrl = manga.url,
            chapterUrl = url,
            lastRead = lastReadSeconds,
            clearedAt = clearedAt,
            updatedAt = at,
        )
    }

    private fun BackupManga.latestHistoryEntry(): PayloadHistory? =
        history.mapNotNull { entry ->
            val at = if (entry.clearedAt > 0L) entry.clearedAt else entry.lastRead / 1000L
            if (at <= 0L) null else PayloadHistory(entry.url, entry.lastRead / 1000L, entry.clearedAt, at)
        }.maxByOrNull { it.at }

    private fun BackupManga.writeHistoryEntry(entry: HistoryEntry) {
        if (entry.isCleared) {
            // This file keeps one entry per manga; the payload keeps every chapter of it, and the
            // action that produced the clearing cleared all of them.
            history.forEach {
                it.lastRead = 0L
                it.clearedAt = entry.clearedAt
            }
            if (history.isEmpty() && entry.chapterUrl.isNotEmpty()) {
                history = history + BackupHistory(entry.chapterUrl, 0L, clearedAt = entry.clearedAt)
            }
            return
        }

        if (entry.chapterUrl.isEmpty()) return
        val existing = history.firstOrNull { it.url == entry.chapterUrl }
        if (existing != null) {
            existing.lastRead = entry.lastRead * 1000L
            existing.clearedAt = 0L
        } else {
            history = history + BackupHistory(entry.chapterUrl, entry.lastRead * 1000L)
        }
    }
}
// SY <--
