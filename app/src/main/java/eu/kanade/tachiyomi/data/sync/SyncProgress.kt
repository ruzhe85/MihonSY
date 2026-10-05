package eu.kanade.tachiyomi.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// SY -->
/**
 * Lightweight chapter-progress payload.
 *
 * It lives in its own remote file so that opening a chapter costs one small round trip instead of a
 * full backup + upload + restore. Only the most recent entries are kept (see [MAX_ENTRIES]);
 * anything older is covered by the full sync, which merges this file back in so the two views
 * cannot drift apart.
 *
 * Scope is deliberately narrow: the page a chapter was left at and whether it is read. Browsing
 * history keeps travelling through the full sync.
 */
@Serializable
data class SyncProgress(
    @ProtoNumber(1) val deviceId: String = "",
    @ProtoNumber(2) val entries: List<ProgressEntry> = emptyList(),
) {
    companion object {
        /** Hard cap, so the file cannot quietly grow into a second full payload. */
        const val MAX_ENTRIES = 200
    }
}

/**
 * Progress of a single chapter, addressed by identity rather than by local ids so it means the same
 * thing on every device.
 */
@Serializable
data class ProgressEntry(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val mangaUrl: String,
    @ProtoNumber(3) val chapterUrl: String,
    @ProtoNumber(4) val lastPageRead: Long = 0,
    @ProtoNumber(5) val read: Boolean = false,
    /** Seconds; the last-writer-wins timestamp, taken from `chapters.last_modified_at`. */
    @ProtoNumber(6) val updatedAt: Long = 0,
) {
    val key: String get() = "$source|$mangaUrl|$chapterUrl"
}

/** A fetched progress file together with the revision it was read at. */
data class ProgressSnapshot(
    val progress: SyncProgress,
    val etag: String?,
)
// SY <--
