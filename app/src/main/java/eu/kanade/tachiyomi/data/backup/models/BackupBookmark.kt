package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
* SY page bookmarks class
 */
@Serializable
data class BackupBookmark(
    // SY --> The other fields carry a default for the same reason: a missing field has to fall back
    // instead of failing the whole decode — on the SyncYomi path a failed decode reads as "bad remote
    // content" and the remote copy gets overwritten.
    @ProtoNumber(1) val source: Long = 0L,
    // SY <--
    @ProtoNumber(2) val mangaUrl: String = "",
    @ProtoNumber(3) val chapterUrl: String = "",
    @ProtoNumber(4) val page: Int = 0,
    @ProtoNumber(5) val createdAt: Long = 0,
    // SY --> Multi-device sync: tombstone for "bookmark deleted on this device", otherwise the
    // union merge would resurrect it on every sync
    @ProtoNumber(6) val deletedAt: Long = 0,
    // SY <--
)

val backupBookmarkMapper =
    { source: Long, mangaUrl: String, chapterUrl: String, page: Long, createdAt: Long ->
        BackupBookmark(
            source = source,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            page = page.toInt(),
            createdAt = createdAt,
        )
    }
