package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
* SY page bookmarks class
 */
@Serializable
data class BackupBookmark(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val mangaUrl: String = "",
    @ProtoNumber(3) val chapterUrl: String = "",
    @ProtoNumber(4) val page: Int = 0,
    @ProtoNumber(5) val createdAt: Long = 0,
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
