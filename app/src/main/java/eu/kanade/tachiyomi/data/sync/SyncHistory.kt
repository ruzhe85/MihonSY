package eu.kanade.tachiyomi.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// SY -->
/**
 * Lightweight reading-history payload.
 *
 * The full sync carries every history row of every library entry, which is why a device only sees
 * another device's reading history after a whole backup round trip. This file carries the part that
 * is actually shown: one entry per manga, holding the chapter it was last read at — or the fact
 * that its reading date was cleared.
 *
 * Only the most recent action per manga travels here; older rows stay the full sync's business, and
 * that sync merges this file back into its payload so the two views cannot drift apart.
 *
 * Where the reader left off rides along as well: the page it stopped at, the page count the reader
 * recorded for that chapter, and the reading-clock stamp of that position. Without them the other
 * device can show the chapter but not its progress until a whole payload round trip happens. They
 * are hints, not authority — the receiving side writes them only after the same comparison the
 * progress channel makes, against the same reading clock.
 *
 * All timestamps are seconds, matching [SyncClock] and the history clock keys in [SyncLedger].
 */
@Serializable
data class SyncHistory(
    @ProtoNumber(1) val deviceId: String = "",
    @ProtoNumber(2) val entries: List<HistoryEntry> = emptyList(),
)

/**
 * One manga's reading history, addressed by identity rather than by local ids so it means the same
 * thing on every device.
 */
@Serializable
data class HistoryEntry(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val mangaUrl: String,
    @ProtoNumber(3) val chapterUrl: String = "",
    /** Seconds; 0 when the entry was cleared. */
    @ProtoNumber(4) val lastRead: Long = 0,
    /** Seconds; when the reading date was cleared, 0 when it still holds one. */
    @ProtoNumber(5) val clearedAt: Long = 0,
    /** Seconds; the last-writer-wins timestamp, taken from the history clock of the device. */
    @ProtoNumber(6) val updatedAt: Long = 0,

    // SY -->
    /** 0-based page the reader stopped at; only meaningful while [progressAt] says it is known. */
    @ProtoNumber(7) val lastPageRead: Long = 0,
    /** Page count the reader recorded; 0 means "not known", never "no pages". */
    @ProtoNumber(8) val totalPages: Int = 0,
    /**
     * Reading-clock stamp of the position above, 0 when this device never recorded one.
     *
     * It is the clock the progress channel uses, which is what keeps that channel from undoing this
     * one: the receiver writes the pages and then teaches its clock this timestamp, so an older
     * progress entry can no longer claim to be newer. An entry from a release that predates this
     * field carries 0, and the receiver leaves the chapter row alone.
     */
    @ProtoNumber(9) val progressAt: Long = 0,
    // SY <--
) {
    /** One entry per manga: a newer reading position replaces the older one. */
    val key: String get() = "$source|$mangaUrl"

    val isCleared: Boolean get() = clearedAt > 0L && lastRead <= 0L
}
// SY <--
