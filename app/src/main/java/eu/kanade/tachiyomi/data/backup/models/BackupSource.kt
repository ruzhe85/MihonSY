package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupSource(
    @ProtoNumber(1) var name: String = "",
    // Komiho: 见 Backup.kt —— 等于默认值时也必须写出。
    @EncodeDefault
    @ProtoNumber(2) var sourceId: Long = 0,
)
