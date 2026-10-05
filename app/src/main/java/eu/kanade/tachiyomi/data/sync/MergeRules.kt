package eu.kanade.tachiyomi.data.sync

import android.content.Context
import eu.kanade.tachiyomi.data.backup.models.BackupBookmark
import logcat.LogPriority
import logcat.logcat

// SY -->
/**
 * The conflict rules of the full sync, as plain functions.
 *
 * The small channels exchange a subset of the same payload types, and they have to answer the same
 * questions about them — is this key one this device owned and no longer has? does a clearer win over
 * a reader? Keeping the answers here is what lets three code paths share one set of rules instead of
 * growing copies that drift apart.
 */
object MergeRules {

    /** Identity of a page bookmark, the same one the payload and the ledger use. */
    fun bookmarkKeyOf(bookmark: BackupBookmark): String =
        "${bookmark.source}|${bookmark.mangaUrl}|${bookmark.chapterUrl}|${bookmark.page}"

    fun asBookmarkTombstone(bookmark: BackupBookmark, context: Context): BackupBookmark {
        return BackupBookmark(
            source = bookmark.source,
            mangaUrl = bookmark.mangaUrl,
            chapterUrl = bookmark.chapterUrl,
            page = bookmark.page,
            createdAt = bookmark.createdAt,
            deletedAt = bookmark.deletedAt.takeIf { it > 0L }
                ?: SyncClock.next(
                    context,
                    SyncLedger.bookmarkKey(bookmark.source, bookmark.mangaUrl, bookmark.chapterUrl, bookmark.page),
                ),
        )
    }

    /**
     * Removals travel as tombstones, so a bookmark is only dropped when the side that still has it
     * accepts that it was deleted: either the other side says so, or the ledger says this device
     * owned the key at its last sync, which makes its absence a deletion rather than a non-adoption.
     */
    fun mergeBookmarks(
        localBookmarks: List<BackupBookmark>?,
        remoteBookmarks: List<BackupBookmark>?,
        localSyncs: Boolean,
        remoteSyncs: Boolean,
        ledger: Map<String, Long>,
        context: Context,
    ): List<BackupBookmark> {
        val logTag = "MergeBookmarks"

        val localMap = localBookmarks.orEmpty().associateBy { bookmarkKeyOf(it) }
        val remoteMap = remoteBookmarks.orEmpty().associateBy { bookmarkKeyOf(it) }

        return (localMap.keys + remoteMap.keys).distinct().mapNotNull { key ->
            val local = localMap[key]
            val remote = remoteMap[key]
            when {
                local != null && remote != null -> {
                    if (maxOf(local.deletedAt, remote.deletedAt) <= 0L) {
                        local
                    } else {
                        val tombstone = if (local.deletedAt > 0L) local else remote
                        val live = if (local.deletedAt > 0L) remote else local
                        when {
                            // Both sides are tombstones: the later deletion wins
                            live.deletedAt > 0L ->
                                if (local.deletedAt >= remote.deletedAt) {
                                    asBookmarkTombstone(local, context)
                                } else {
                                    remote
                                }

                            // A bookmark created after the deletion was recorded is a new bookmark,
                            // not the one that was removed. Without this the device that adds one
                            // back would delete it again on the very next merge.
                            live.createdAt / 1000L > tombstone.deletedAt -> live

                            else -> asBookmarkTombstone(tombstone, context)
                        }
                    }
                }

                local != null -> {
                    // Still here but gone from the other side: another device deleted it
                    if (remoteSyncs &&
                        SyncLedger.bookmarkKey(local.source, local.mangaUrl, local.chapterUrl, local.page) in ledger
                    ) {
                        logcat(LogPriority.DEBUG, logTag) { "Tombstoning bookmark deleted remotely: ${local.mangaUrl}" }
                        asBookmarkTombstone(local, context)
                    } else {
                        local
                    }
                }

                else -> remote?.let {
                    // A bookmark this device no longer has, while the ledger says it owned one at the
                    // last sync, means it was deleted here. Without this the other side simply handed
                    // it back on every sync.
                    if (localSyncs &&
                        SyncLedger.bookmarkKey(it.source, it.mangaUrl, it.chapterUrl, it.page) in ledger
                    ) {
                        logcat(LogPriority.DEBUG, logTag) { "Tombstoning bookmark deleted locally: ${it.mangaUrl}" }
                        asBookmarkTombstone(it, context)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * True when the local side holds the newer statement about a reading date.
     *
     * Reading times are milliseconds and clearing times are seconds, exactly as the payload stores
     * them. A cleared side is timed by when it was cleared and a live side by when it was last read,
     * so the two are comparable; a tie between two live sides falls back to the reading duration.
     */
    fun historyLocalWins(
        localReadAtMillis: Long,
        localClearedAt: Long,
        localReadDuration: Long,
        remoteReadAtMillis: Long,
        remoteClearedAt: Long,
        remoteReadDuration: Long,
    ): Boolean {
        if (localClearedAt > 0L || remoteClearedAt > 0L) {
            val localAt = if (localClearedAt > 0L) localClearedAt else localReadAtMillis / 1000L
            val remoteAt = if (remoteClearedAt > 0L) remoteClearedAt else remoteReadAtMillis / 1000L
            return localAt >= remoteAt
        }

        return !(
            remoteReadAtMillis > localReadAtMillis ||
                (remoteReadAtMillis == localReadAtMillis && remoteReadDuration > localReadDuration)
            )
    }
}
// SY <--
