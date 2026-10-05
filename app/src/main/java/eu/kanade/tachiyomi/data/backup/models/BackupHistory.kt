package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.history.model.History
import java.util.Date

@Serializable
data class BackupHistory(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastRead: Long,
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
