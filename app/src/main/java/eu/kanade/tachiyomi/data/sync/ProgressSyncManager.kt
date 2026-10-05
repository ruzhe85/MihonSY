package eu.kanade.tachiyomi.data.sync

import android.content.Context
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.chapter.ChapterMapper.mapChapter
import tachiyomi.data.manga.MangaMapper.mapManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

// SY -->
/**
 * Reading-progress channel.
 *
 * Opening a chapter needs one thing from the other devices: the page it was left at. Going through
 * the full sync for that means building a whole backup, uploading it and running a full restore, so
 * the reader waits for work unrelated to the page it wants.
 *
 * This channel keeps that single concern in its own small remote file: a bounded, most-recent-first
 * list of chapter progress. The full sync still owns everything else and merges this file back into
 * its payload, so the two cannot drift apart.
 *
 * The bounded list itself is cached in [CACHE_FILE]; it is never derived from a query, so no schema
 * change is needed. All timestamps here are in seconds, matching `chapters.last_modified_at`.
 */
class ProgressSyncManager(
    private val context: Context,
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val database: Database = Injekt.get(),
    private val protoBuf: ProtoBuf = Injekt.get(),
) {

    companion object {
        private const val CACHE_FILE = "sync_progress_cache.bin"

        /** Opening a chapter re-pulls no more often than this. */
        private const val PULL_THROTTLE_MILLIS = 30_000L

        /** Reading reports progress no more often than this, unless forced. */
        private const val PUSH_THROTTLE_MILLIS = 30_000L
    }

    private val mutex = Mutex()

    private var entries: MutableMap<String, ProgressEntry>? = null
    private var remoteEtag: String? = null
    private var lastPullAt = 0L
    private var lastPushAt = 0L

    /** Only WebDAV carries the second file for now; other services just skip this channel. */
    private val remote: WebDavSyncService? by lazy {
        val service = SyncManager.SyncService.fromInt(syncPreferences.syncService.get())
        if (service == SyncManager.SyncService.WEBDAV) WebDavSyncService(context) else null
    }

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    // ---- local cache ---------------------------------------------------------------------------

    private fun cacheLocked(): MutableMap<String, ProgressEntry> {
        entries?.let { return it }

        val loaded = try {
            if (cacheFile.exists()) {
                protoBuf
                    .decodeFromByteArray(SyncProgress.serializer(), cacheFile.readBytes())
                    .entries
                    .associateBy { it.key }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to read progress cache: ${e.message}" }
            mutableMapOf()
        }

        entries = loaded
        return loaded
    }

    private fun persistLocked() {
        val current = entries ?: return
        try {
            val payload = SyncProgress(
                deviceId = syncPreferences.uniqueDeviceID(),
                entries = trimmed(current.values),
            )
            cacheFile.writeBytes(protoBuf.encodeToByteArray(SyncProgress.serializer(), payload))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to write progress cache: ${e.message}" }
        }
    }

    /** Most recent first, capped so the remote file cannot grow into a second full payload. */
    private fun trimmed(source: Collection<ProgressEntry>): List<ProgressEntry> =
        source.sortedByDescending { it.updatedAt }.take(SyncProgress.MAX_ENTRIES)

    /**
     * Progress is chapter state, so it follows the chapter toggle: a device that does not sync
     * chapters must not start exchanging progress through the back door.
     */
    private fun isAvailable(): Boolean = remote != null &&
        syncPreferences.isSyncEnabled() &&
        syncPreferences.getSyncSettings().chapters

    // ---- opening a chapter ---------------------------------------------------------------------

    /**
     * Pulls remote progress for [chapterUrl] and applies it locally when it is newer than what this
     * device has, so the reader opens where another device left off.
     *
     * Returns the applied entry, or null when nothing changed. Never throws and never blocks for
     * long: a missing or unreachable progress file simply means the reader uses local data.
     */
    suspend fun applyRemoteProgress(mangaId: Long, chapterUrl: String, force: Boolean = false): ProgressEntry? {
        if (!isAvailable() || chapterUrl.isBlank()) return null
        // Coming back to the foreground has to see the newest page right away, so the reader is
        // allowed to skip the throttle that keeps ordinary chapter opens cheap.
        if (!force && !takePullSlot()) return null

        val fetched = pullQuietly() ?: return null
        val remoteEntry = fetched.progress.entries.firstOrNull { it.chapterUrl == chapterUrl }
            ?: return null

        remember(fetched)

        val chapter = database.chaptersQueries
            .getChapterByUrlAndMangaId(chapterUrl = chapterUrl, mangaId = mangaId, mapper = ::mapChapter)
            .awaitAsOneOrNull() ?: return null
        val manga = database.mangasQueries
            .getMangaById(mangaId, ::mapManga)
            .awaitAsOneOrNull() ?: return null

        // Both sides are in seconds here. The local side is the reading clock, not the chapter row's
        // timestamp: bookmark edits and restores refresh that one as well.
        val localUpdatedAt = ProgressClock.progressAt(manga, chapter).takeIf { it > 0L }
            ?: chapter.lastModifiedAt
        if (remoteEntry.updatedAt <= localUpdatedAt) return null

        val page = remoteEntry.lastPageRead
        val read = remoteEntry.read || chapter.read
        if (page == chapter.lastPageRead && read == chapter.read) {
            // Nothing to write, but the clock still has to learn the newer timestamp
            ProgressClock.observeProgress(manga, chapter, remoteEntry.updatedAt)
            return null
        }

        writeChapterProgress(chapter.id, read = read, lastPageRead = page)
        ProgressClock.observeProgress(manga, chapter, remoteEntry.updatedAt)
        logcat(LogPriority.DEBUG) { "Applied remote progress for $chapterUrl (page=$page, read=$read)" }

        // Hand back exactly what landed so the caller can refresh its in-memory copy
        return remoteEntry.copy(read = read, lastPageRead = page)
    }

    // ---- reporting local progress --------------------------------------------------------------

    /**
     * Records this device's progress for [chapterId] and uploads it when the throttle allows, or
     * immediately when [force] is set (chapter switch / leaving the reader).
     */
    suspend fun recordLocalProgress(chapterId: Long, force: Boolean = false) {
        if (!isAvailable()) return
        if (!takePushSlot(force)) return

        val chapter = database.chaptersQueries.getChapterById(chapterId).awaitAsOneOrNull() ?: return
        val manga = database.mangasQueries
            .getMangaById(chapter.manga_id, ::mapManga)
            .awaitAsOneOrNull() ?: return

        val entry = ProgressEntry(
            source = manga.source,
            mangaUrl = manga.url,
            chapterUrl = chapter.url,
            lastPageRead = chapter.last_page_read,
            read = chapter.read,
            // The reading clock only moves when the progress itself changed; the chapter row's own
            // timestamp also moves for bookmark edits and restores. Falling back to it keeps the
            // behaviour for chapters this change has never stamped.
            updatedAt = ProgressClock.progressAt(manga.source, manga.url, chapter.url)
                .takeIf { it > 0L }
                ?: chapter.last_modified_at,
        )

        mutex.withLock {
            cacheLocked()[entry.key] = entry
            persistLocked()
        }

        push()
    }

    // ---- transport -----------------------------------------------------------------------------

    private suspend fun takePullSlot(): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now - lastPullAt < PULL_THROTTLE_MILLIS) {
            false
        } else {
            lastPullAt = now
            true
        }
    }

    private suspend fun takePushSlot(force: Boolean): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastPushAt < PUSH_THROTTLE_MILLIS) {
            false
        } else {
            lastPushAt = now
            true
        }
    }

    private suspend fun pullQuietly(): ProgressSnapshot? = try {
        remote?.pullProgress()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.DEBUG) { "Progress pull failed: ${e.message}" }
        null
    }

    /** Folds a fetched file into the local list; the newer side wins per key. */
    private suspend fun remember(snapshot: ProgressSnapshot) {
        mutex.withLock {
            remoteEtag = snapshot.etag
            val current = cacheLocked()
            snapshot.progress.entries.forEach { incoming ->
                val mine = current[incoming.key]
                if (mine == null || incoming.updatedAt > mine.updatedAt) current[incoming.key] = incoming
            }
            persistLocked()
        }
    }

    /** Uploads the current list; on a lost race it merges once and retries. */
    private suspend fun push() {
        val service = remote ?: return

        val (payload, etag) = mutex.withLock {
            SyncProgress(
                deviceId = syncPreferences.uniqueDeviceID(),
                entries = trimmed(cacheLocked().values),
            ) to remoteEtag
        }

        val uploaded = try {
            service.pushProgress(payload, etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Progress push failed: ${e.message}" }
            false
        }

        if (uploaded) return

        // Someone wrote in the meantime. Merge with what is there now and retry once, so a busy
        // remote does not silently swallow this device's progress.
        val latest = pullQuietly() ?: return

        val merged = mutex.withLock {
            remoteEtag = latest.etag
            val current = cacheLocked()
            latest.progress.entries.forEach { incoming ->
                val mine = current[incoming.key]
                if (mine == null || incoming.updatedAt > mine.updatedAt) current[incoming.key] = incoming
            }
            persistLocked()
            SyncProgress(syncPreferences.uniqueDeviceID(), trimmed(current.values))
        }

        try {
            service.pushProgress(merged, latest.etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Progress retry push failed: ${e.message}" }
        }
    }

    // ---- integration with the full sync --------------------------------------------------------

    /**
     * Folds the progress channel into the full payload and returns the payload to push.
     *
     * Entries the progress file knows to be newer are applied to the payload; entries the payload
     * has that are newer are written back into the progress file. Running this on every full sync is
     * what keeps the two views from drifting when only one of them is used.
     */
    suspend fun mergeWithFullBackup(backup: Backup): Backup {
        if (!isAvailable()) return backup

        val fetched = pullQuietly()

        // Index the payload the same way the progress file keys its entries
        val payloadKeys = mutableMapOf<String, Pair<Long, String>>()
        backup.backupManga.forEach { manga ->
            manga.chapters.forEach { chapter ->
                val key = "${manga.source}|${manga.url}|${chapter.url}"
                payloadKeys[key] = manga.source to manga.url
            }
        }

        val remoteEntries = fetched?.progress?.entries.orEmpty()
        val winners = mutableMapOf<String, ProgressEntry>()

        backup.backupManga.forEach { manga ->
            manga.chapters.forEach { chapter ->
                val key = "${manga.source}|${manga.url}|${chapter.url}"
                winners[key] = ProgressEntry(
                    source = manga.source,
                    mangaUrl = manga.url,
                    chapterUrl = chapter.url,
                    lastPageRead = chapter.lastPageRead,
                    read = chapter.read,
                    // The payload was stamped by the export from the same reading clock
                    updatedAt = chapter.progressAt,
                )
            }
        }

        var contributed = false
        remoteEntries.forEach { theirs ->
            if (theirs.key !in payloadKeys) return@forEach
            val mine = winners[theirs.key] ?: return@forEach
            if (theirs.updatedAt > mine.updatedAt) {
                winners[theirs.key] = theirs
                contributed = true
            }
        }

        if (contributed) {
            backup.backupManga.forEach { manga ->
                manga.chapters.forEach { chapter ->
                    val winner = winners["${manga.source}|${manga.url}|${chapter.url}"] ?: return@forEach
                    // The payload has to carry the timestamp of whichever side won, otherwise the
                    // value it just adopted would look stale to the very next merge.
                    chapter.progressAt = winner.updatedAt
                    if (winner.lastPageRead != chapter.lastPageRead || winner.read != chapter.read) {
                        chapter.lastPageRead = winner.lastPageRead
                        chapter.read = winner.read
                    }
                }
            }
        }

        val toUpload = mutex.withLock {
            val current = cacheLocked()
            winners.values.forEach { current[it.key] = it }
            persistLocked()
            SyncProgress(syncPreferences.uniqueDeviceID(), trimmed(current.values))
        }

        try {
            remote?.pushProgress(toUpload, fetched?.etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Progress push during full sync failed: ${e.message}" }
        }

        return backup
    }

    // ---- helpers -------------------------------------------------------------------------------

    private suspend fun writeChapterProgress(chapterId: Long, read: Boolean, lastPageRead: Long) {
        val row = database.chaptersQueries.getChapterById(chapterId).awaitAsOneOrNull() ?: return
        database.chaptersQueries.update(
            mangaId = null,
            url = null,
            name = null,
            scanlator = null,
            read = read,
            bookmark = row.bookmark,
            lastPageRead = lastPageRead,
            bookmarkPage = row.bookmark_page,
            chapterNumber = null,
            sourceOrder = null,
            dateFetch = null,
            dateUpload = null,
            // Applying another device's progress is not a local edit, so the row keeps its timestamp
            lastModifiedAt = null,
            chapterId = chapterId,
            version = row.version,
            isSyncing = 1,
            memo = row.memo.let(MemoColumnAdapter::encode),
        )
    }
}
// SY <--
