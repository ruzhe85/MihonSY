package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.BackupBookmark
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// SY -->
/**
 * Lightweight page-bookmark payload.
 *
 * Unlike the progress and history channels this one is a *complete* snapshot of the device's page
 * bookmarks, tombstones included, and it is deliberately not truncated: the merge decides that a
 * bookmark missing from another device's snapshot was deleted there, so dropping entries to bound
 * the file would be read as a deletion and wipe the bookmarks on every other device.
 *
 * Entries reuse [BackupBookmark] so this channel and the full sync resolve a conflict with exactly
 * the same rules and the same `SyncLedger` keys.
 */
@Serializable
data class SyncBookmarks(
    @ProtoNumber(1) val deviceId: String = "",
    @ProtoNumber(2) val entries: List<BackupBookmark> = emptyList(),
)
// SY <--
