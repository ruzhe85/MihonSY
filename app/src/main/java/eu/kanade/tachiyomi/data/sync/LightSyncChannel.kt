package eu.kanade.tachiyomi.data.sync

import kotlinx.coroutines.sync.Mutex

// SY -->
/**
 * Shared plumbing for the small side channels (progress, history, bookmarks).
 *
 * Each channel keeps its own remote file, but they all need the same three things: one lock so no
 * coroutine ever sees a half-updated cache, the revision the local copy was merged against, and a
 * pair of throttle slots so ordinary reading does not turn into a request per page.
 *
 * The slots deliberately do not use [mutex]: callers hold the cache lock while they build a push,
 * and a slot check that waited for that same lock would deadlock against them.
 */
internal class LightSyncChannel(
    private val pullThrottleMillis: Long,
    private val pushThrottleMillis: Long,
) {
    /** Guards the channel cache and the fields below it. */
    val mutex = Mutex()

    private val slotLock = Any()

    /** Revision of the remote file the local copy was merged against, null when it is new. */
    @Volatile
    var etag: String? = null

    private var lastPullAt = 0L
    private var lastPushAt = 0L

    /** False when a pull happened too recently; [force] is for screens the user just opened. */
    fun takePullSlot(force: Boolean): Boolean = synchronized(slotLock) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPullAt < pullThrottleMillis) {
            false
        } else {
            lastPullAt = now
            true
        }
    }

    /** False when a push happened too recently; [force] is for chapter switches and leaving. */
    fun takePushSlot(force: Boolean): Boolean = synchronized(slotLock) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPushAt < pushThrottleMillis) {
            false
        } else {
            lastPushAt = now
            true
        }
    }
}

/** A side-channel file together with the revision it was read at. */
data class LightSnapshot<T>(
    val payload: T,
    val etag: String?,
)
// SY <--
