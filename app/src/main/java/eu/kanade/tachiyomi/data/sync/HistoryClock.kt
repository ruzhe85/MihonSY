package eu.kanade.tachiyomi.data.sync

import android.app.Application
import android.content.Context
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// SY -->
/**
 * Stamps the reading-history actions of this device on [SyncClock].
 *
 * The full sync resolves a reading date with the clock of the *chapter* it belongs to, because the
 * payload carries one entry per chapter. This channel carries one entry per manga instead — the form
 * the history screen shows — and a cleared reading date has no chapter left to be keyed by, so its
 * timestamps live in their own per-manga namespace.
 *
 * The two namespaces are comparable: both are seconds taken from the same monotonic-per-key clock,
 * which is what lets the full sync fold one into the other.
 */
object HistoryClock {

    /** Held lazily: this object is only touched long after Injekt has been set up. */
    private val context: Context by lazy { Injekt.get<Application>() }

    private fun key(source: Long, mangaUrl: String) = "hm:$source|$mangaUrl"

    /**
     * Call only after the history of this manga actually changed: reading it further, or clearing
     * its reading date. Stamping a no-op would make this device look like the most recent reader.
     */
    fun stampHistory(source: Long, mangaUrl: String): Long =
        SyncClock.next(context, key(source, mangaUrl))

    /** Timestamp in use for this manga, 0 when it was never stamped by this device. */
    fun historyAt(source: Long, mangaUrl: String): Long =
        SyncClock.peek(context, key(source, mangaUrl))

    /**
     * Records a timestamp that was decided elsewhere (a value pulled from another device, or one the
     * full sync already resolved), so this device's next write lands after it.
     */
    fun observeHistory(source: Long, mangaUrl: String, timestamp: Long) =
        SyncClock.observe(context, key(source, mangaUrl), timestamp)

    /** Persists the clock, for callers that are about to lose their coroutine scope. */
    fun flush() = SyncClock.flush(context)
}
// SY <--
