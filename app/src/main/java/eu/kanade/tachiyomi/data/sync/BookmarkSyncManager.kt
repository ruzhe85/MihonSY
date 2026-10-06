package eu.kanade.tachiyomi.data.sync

import android.content.Context
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupBookmark
import eu.kanade.tachiyomi.data.backup.models.backupBookmarkMapper
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

// SY -->
/**
 * Page-bookmark channel.
 *
 * A bookmark is added or removed rarely, but going through the full sync for it means a whole backup
 * round trip before another device stops showing one the user deleted. This channel exchanges the
 * complete bookmark set in its own file instead.
 *
 * Complete is the important word: the merge decides that a bookmark missing from the other side was
 * deleted there, so the file must never be truncated to the most recent entries — doing that would
 * be read as a mass deletion on every other device. The same reason is why the file carries
 * tombstones, which the database cannot express because it deletes bookmark rows outright.
 */
class BookmarkSyncManager(
    private val context: Context,
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val database: Database = Injekt.get(),
    private val protoBuf: ProtoBuf = Injekt.get(),
) {

    companion object {
        private const val CACHE_FILE = "sync_bookmarks_cache.bin"

        /** Opening the bookmark screen pulls no more often than this. */
        private const val PULL_THROTTLE_MILLIS = 30_000L

        /** Editing a bookmark pushes no more often than this, unless forced. */
        private const val PUSH_THROTTLE_MILLIS = 30_000L
    }

    private val channel = LightSyncChannel(PULL_THROTTLE_MILLIS, PUSH_THROTTLE_MILLIS)

    /** Deletions this device decided on: the row is gone, so only a tombstone can carry them. */
    private var tombstones: MutableMap<String, BackupBookmark>? = null

    /** Set while an edit still has to reach the remote; cleared once an upload succeeded. */
    @Volatile
    private var dirty = false

    /** Only WebDAV carries the extra files for now; other services just skip this channel. */
    private val remote: WebDavSyncService? by lazy {
        val service = SyncManager.SyncService.fromInt(syncPreferences.syncService.get())
        if (service == SyncManager.SyncService.WEBDAV) WebDavSyncService(context) else null
    }

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    /**
     * The channel follows the bookmark toggle of the sync settings: a device that does not sync
     * bookmarks must not start exchanging them through the back door.
     */
    private fun isAvailable(): Boolean = remote != null &&
        syncPreferences.isSyncEnabled() &&
        syncPreferences.getSyncSettings().bookmarks

    /** Read per merge: the full sync is what writes it, and a stale copy would tombstone live rows. */
    private fun ledger(): Map<String, Long> = SyncLedger.load(context)

    // ---- local cache ---------------------------------------------------------------------------

    private fun tombstonesLocked(): MutableMap<String, BackupBookmark> {
        tombstones?.let { return it }

        val loaded = try {
            if (cacheFile.exists()) {
                protoBuf
                    .decodeFromByteArray(SyncBookmarks.serializer(), cacheFile.readBytes())
                    .entries
                    .filter { it.deletedAt > 0L }
                    .associateBy { MergeRules.bookmarkKeyOf(it) }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to read bookmark cache: ${e.message}" }
            mutableMapOf()
        }

        tombstones = loaded
        return loaded
    }

    private fun persistLocked() {
        val current = tombstones ?: return
        try {
            val payload = SyncBookmarks(
                deviceId = syncPreferences.uniqueDeviceID(),
                entries = current.values.toList(),
            )
            cacheFile.writeBytes(protoBuf.encodeToByteArray(SyncBookmarks.serializer(), payload))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to write bookmark cache: ${e.message}" }
        }
    }

    /** Keeps the tombstones of [merged] and forgets the ones it resolved back to a live bookmark. */
    private fun storeTombstonesLocked(merged: List<BackupBookmark>) {
        val current = tombstonesLocked()
        current.clear()
        merged.forEach {
            if (it.deletedAt > 0L) current[MergeRules.bookmarkKeyOf(it)] = it
        }
        persistLocked()
    }

    /**
     * What this device has: the bookmarks in the database plus the deletions it decided on. The
     * database is authoritative for values — a row that is present is a bookmark, whatever the cache
     * still remembers about it.
     */
    private suspend fun localViewLocked(): List<BackupBookmark> {
        val byKey = LinkedHashMap<String, BackupBookmark>()

        database.bookmarksQueries
            .bookmarksForBackup(backupBookmarkMapper)
            .awaitAsList()
            .forEach { byKey[MergeRules.bookmarkKeyOf(it)] = it }

        tombstonesLocked().forEach { (key, tombstone) ->
            if (key !in byKey) byKey[key] = tombstone
        }

        return byKey.values.toList()
    }

    private suspend fun pullQuietly(): LightSnapshot<SyncBookmarks>? = try {
        remote?.pullBookmarks()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.DEBUG) { "Bookmark pull failed: ${e.message}" }
        null
    }

    // ---- local events --------------------------------------------------------------------------

    /**
     * Publishes the bookmarks this device has now, after one was added. The snapshot is derived from
     * the database, so an addition needs nothing remembered about it.
     */
    suspend fun publishLocalChanges(force: Boolean = true) {
        if (!isAvailable()) return
        dirty = true
        push(force)
    }

    /**
     * Uploads only if something changed since the last successful upload.
     *
     * Called when the reader is left: an edit that a dead network swallowed would otherwise sit here
     * until the next full sync, while an ordinary exit has nothing to say and must not re-upload the
     * whole set.
     */
    suspend fun publishIfChanged() {
        if (!isAvailable()) return
        if (!dirty) return
        push(force = true)
    }

    /**
     * Records that this device deleted the bookmark at [page] of [chapterUrl]. Without the tombstone
     * the other devices would see only that the key is missing from here and hand it back.
     */
    suspend fun recordLocalDeletion(
        source: Long,
        mangaUrl: String,
        chapterUrl: String,
        page: Int,
        force: Boolean = true,
    ) {
        if (!isAvailable()) return

        val key = MergeRules.bookmarkKeyOf(
            BackupBookmark(source = source, mangaUrl = mangaUrl, chapterUrl = chapterUrl, page = page),
        )
        val stamp = SyncClock.next(
            context,
            SyncLedger.bookmarkKey(source, mangaUrl, chapterUrl, page),
        )

        channel.mutex.withLock {
            tombstonesLocked()[key] = BackupBookmark(
                source = source,
                mangaUrl = mangaUrl,
                chapterUrl = chapterUrl,
                page = page,
                // The tombstone only has to say when the bookmark was removed
                createdAt = 0L,
                deletedAt = stamp,
            )
            persistLocked()
        }

        dirty = true
        push(force)
    }

    /**
     * Records the deletion of the bookmark row [bookmarkId], for callers that only know the id — the
     * reader's bookmark list. Has to be called while the row still exists: once it is gone there is
     * nothing left to name in the tombstone.
     */
    suspend fun recordLocalDeletionById(bookmarkId: Long, force: Boolean = true) {
        if (!isAvailable()) return

        val row = database.bookmarksQueries.getById(bookmarkId).awaitAsOneOrNull() ?: return
        val chapter = database.chaptersQueries.getChapterById(row.chapter_id).awaitAsOneOrNull() ?: return
        val manga = database.mangasQueries
            .getMangaById(chapter.manga_id, ::mapManga)
            .awaitAsOneOrNull() ?: return

        recordLocalDeletion(
            source = manga.source,
            mangaUrl = manga.url,
            chapterUrl = chapter.url,
            page = row.page.toInt(),
            force = force,
        )
    }

    // ---- reading the remote bookmarks ----------------------------------------------------------

    /**
     * Pulls the remote bookmark set and applies it, so a bookmark added or removed on another device
     * shows up here without waiting for a full sync.
     */
    suspend fun pullAndApply(force: Boolean = false) {
        if (!isAvailable()) return
        if (!channel.takePullSlot(force)) return

        val fetched = pullQuietly() ?: return
        applyToDatabase(mergeWith(fetched.payload.entries, fetched.etag))
    }

    /** Merges [remoteEntries] into this device's view, remembers the result and returns it. */
    private suspend fun mergeWith(remoteEntries: List<BackupBookmark>, etag: String?): List<BackupBookmark> =
        channel.mutex.withLock {
            channel.etag = etag
            val merged = MergeRules.mergeBookmarks(
                localBookmarks = localViewLocked(),
                remoteBookmarks = remoteEntries,
                localSyncs = true,
                remoteSyncs = true,
                ledger = ledger(),
                context = context,
            )
            storeTombstonesLocked(merged)
            merged
        }

    /**
     * Applies a merge result to the database.
     *
     * Only entries that differ from what is already there are looked up and written: opening the
     * bookmark screen would otherwise cost a handful of queries per bookmark.
     */
    private suspend fun applyToDatabase(merged: List<BackupBookmark>) {
        try {
            val present = database.bookmarksQueries
                .bookmarksForBackup(backupBookmarkMapper)
                .awaitAsList()
                .associateBy { MergeRules.bookmarkKeyOf(it) }

            merged.forEach { entry ->
                val key = MergeRules.bookmarkKeyOf(entry)
                if (entry.deletedAt > 0L) {
                    if (key !in present) return@forEach
                    resolveChapterId(entry)?.let {
                        database.bookmarksQueries.deleteByChapterAndPage(it, entry.page.toLong())
                    }
                } else {
                    if (key in present) return@forEach
                    resolveChapterId(entry)?.let {
                        database.bookmarksQueries.insert(it, entry.page.toLong(), entry.createdAt)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to apply remote bookmarks: ${e.message}" }
        }
    }

    /** Local chapter id of an entry, or null when this device does not have that chapter. */
    private suspend fun resolveChapterId(entry: BackupBookmark): Long? {
        val manga = database.mangasQueries
            .getMangaByUrlAndSource(url = entry.mangaUrl, source = entry.source, mapper = ::mapManga)
            .awaitAsOneOrNull() ?: return null
        return database.chaptersQueries
            .getChapterByUrlAndMangaId(chapterUrl = entry.chapterUrl, mangaId = manga.id, mapper = ::mapChapter)
            .awaitAsOneOrNull()
            ?.id
    }

    // ---- transport -----------------------------------------------------------------------------

    /** Uploads the current set; on a lost race it merges once and retries. */
    private suspend fun push(force: Boolean) {
        val service = remote ?: return
        if (!channel.takePushSlot(force)) return

        val (payload, etag) = channel.mutex.withLock {
            SyncBookmarks(syncPreferences.uniqueDeviceID(), localViewLocked()) to channel.etag
        }

        val uploaded = try {
            service.pushBookmarks(payload, etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Bookmark push failed: ${e.message}" }
            false
        }

        if (uploaded) {
            // What was just published is also the state the next merge has to start from
            channel.mutex.withLock { storeTombstonesLocked(payload.entries) }
            dirty = false
            return
        }

        // Someone wrote in the meantime. Merge with what is there now and retry once, so a busy
        // remote does not silently swallow this device's bookmarks.
        val latest = pullQuietly() ?: return
        val merged = mergeWith(latest.payload.entries, latest.etag)

        val retried = try {
            service.pushBookmarks(SyncBookmarks(syncPreferences.uniqueDeviceID(), merged), latest.etag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Bookmark retry push failed: ${e.message}" }
            return
        }
        if (retried) dirty = false

        // Whatever the retry merged in also has to reach the database, or the values this device just
        // agreed to would never be applied locally.
        applyToDatabase(merged)
    }

    // ---- integration with the full sync --------------------------------------------------------

    /**
     * Folds the bookmark channel into the full payload and returns the payload to push.
     *
     * The full sync is what makes a device that never opens the bookmark screen converge, so the file
     * has to hand its view to the payload: the payload then carries the same values and, more
     * importantly, the same tombstones, so the two paths cannot disagree about what was deleted.
     */
    suspend fun mergeWithFullBackup(backup: Backup): Backup {
        if (!isAvailable()) return backup

        val fetched = pullQuietly()

        // A file that is absent, unreachable or unreadable is NOT an empty remote. Merging against an
        // empty list would read every bookmark this device owns as deleted on the other side — the
        // ledger makes that a tombstone — and the restore would then delete all of them. That is
        // exactly what the first full sync after this feature ships would look like, because the file
        // does not exist yet.
        val withFile = if (fetched != null) {
            mergeWith(fetched.payload.entries, fetched.etag)
        } else {
            logcat(LogPriority.INFO) { "No bookmark file to merge with; keeping this device's set" }
            channel.mutex.withLock { localViewLocked() }
        }

        val merged = MergeRules.mergeBookmarks(
            localBookmarks = withFile,
            remoteBookmarks = backup.backupBookmarks,
            localSyncs = true,
            remoteSyncs = true,
            ledger = ledger(),
            context = context,
        )

        channel.mutex.withLock { storeTombstonesLocked(merged) }
        backup.backupBookmarks = merged

        // The payload carries the same set now, so comparing it against what the merge brings back
        // would report "nothing changed" and skip the restore. Applying the result here is what makes
        // the full sync settle this device's bookmarks as well, entries that arrived through the file
        // included.
        applyToDatabase(merged)

        val uploaded = try {
            remote?.pushBookmarks(
                SyncBookmarks(syncPreferences.uniqueDeviceID(), merged),
                channel.etag,
            ) ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "Bookmark push during full sync failed: ${e.message}" }
            false
        }
        if (uploaded) dirty = false

        return backup
    }
}
// SY <--
