package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.history.model.History
import java.util.Date

@Serializable
data class BackupHistory(
    // Komiho: 见 Backup.kt —— 等于默认值时也必须写出。
    @EncodeDefault
    @ProtoNumber(1) var url: String = "",
    @EncodeDefault
    @ProtoNumber(2) var lastRead: Long = 0,
    @ProtoNumber(3) var readDuration: Long = 0,
    // SY -->
    /**
     * Seconds; when this entry's reading date was cleared, 0 when it was not.
     *
     * Clearing a reading date is a soft delete in mihon (`last_read = 0`, row kept), so a bare 0 in
     * [lastRead] carries no timing information and the merge could not tell "cleared later" from
     * "read earlier". This is the timestamp that makes the comparison possible.
     */
    @ProtoNumber(4) var clearedAt: Long = 0,
    // SY <--
) {
    fun getHistoryImpl(): History {
        return History.create().copy(
            readAt = Date(lastRead),
            readDuration = readDuration,
        )
    }
}
