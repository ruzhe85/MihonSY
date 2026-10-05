package eu.kanade.tachiyomi.data.sync

import android.content.Context
import java.io.File
import logcat.LogPriority
import logcat.logcat

// SY -->
/**
 * Per-key hybrid logical clock for multi-device sync.
 *
 * Every mutation gets a timestamp from [next], which is guaranteed to be strictly greater than
 * both the local wall clock and any timestamp previously seen for that key. That single property
 * is what makes four devices converge: a device whose clock is minutes fast cannot permanently
 * suppress the changes of the other three, because the next write it produces still lands after
 * the remote value it observed through [observe].
 *
 * Without this, plain wall-clock timestamps let a single device with a skewed clock win every
 * conflict forever, which is exactly the "one device stops updating" symptom.
 *
 * All timestamps are in seconds, matching the database triggers that stamp `last_modified_at` and
 * `favorite_modified_at` with `strftime('%s','now')`.
 */
object SyncClock {

    private const val FILE_NAME = "sync_clock.txt"
    private const val MAX_ENTRIES = 20_000

    private val lock = Any()

    /** Highest timestamp seen or produced per key. Guarded by [lock]. */
    private val seen = mutableMapOf<String, Long>()
    private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                try {
                    file.readLines().forEach { line ->
                        if (line.isBlank()) return@forEach
                        val tab = line.lastIndexOf('\t')
                        if (tab <= 0) return@forEach
                        val key = line.substring(0, tab)
                        val ts = line.substring(tab + 1).toLongOrNull() ?: return@forEach
                        val current = seen[key]
                        if (current == null || ts > current) seen[key] = ts
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Failed to load sync clock: ${e.message}" }
                }
            }
            loaded = true
        }
    }

    /** Timestamp for a new local mutation of [key]; always strictly increasing for that key. */
    fun next(context: Context, key: String): Long {
        ensureLoaded(context)
        synchronized(lock) {
            // Seconds, because that is the unit the database stamps its own timestamps with
            // (strftime('%s','now') in the manga/chapter/category triggers). Tombstones have to be
            // comparable with those entry timestamps, so millisecond precision here would make every
            // tombstone look infinitely newer than the entry it is meant to remove.
            val now = System.currentTimeMillis() / 1000L
            val previous = seen[key] ?: 0L
            val ts = maxOf(now, previous + 1)
            seen[key] = ts
            return ts
        }
    }

    /**
     * Stable timestamp for [key]: the existing one when there is one, otherwise a fresh one.
     *
     * [next] deliberately keeps incrementing, which is wrong for values that must survive unchanged
     * across syncs — re-stamping a tombstone on every run would rewrite the remote file forever and
     * make the conditional write fail with 412 every time.
     */
    fun stamp(context: Context, key: String): Long {
        ensureLoaded(context)
        synchronized(lock) {
            val existing = seen[key]
            if (existing != null && existing > 0L) return existing

            val ts = maxOf(System.currentTimeMillis() / 1000L, (existing ?: 0L) + 1)
            seen[key] = ts
            return ts
        }
    }

    /** Records a timestamp produced elsewhere so later local writes still land after it. */
    fun observe(context: Context, key: String, ts: Long) {
        if (ts <= 0L) return
        ensureLoaded(context)
        synchronized(lock) {
            val current = seen[key]
            if (current == null || ts > current) seen[key] = ts
        }
    }

    /**
     * Persists the clock. The file only exists to survive process death; a lost clock degrades to
     * plain wall-clock timestamps, which is correct on devices whose clocks agree.
     */
    fun flush(context: Context) {
        ensureLoaded(context)
        val snapshot = synchronized(lock) { seen.toMap() }
        try {
            val file = File(context.filesDir, FILE_NAME)
            val lines = snapshot.entries
                .sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .map { "${it.key}\t${it.value}" }
            // Write to a temp file first so a kill mid-write cannot truncate the clock.
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(lines.joinToString("\n"))
            if (!tmp.renameTo(file)) {
                file.writeText(lines.joinToString("\n"))
                tmp.delete()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to write sync clock: ${e.message}" }
        }
    }
}
// SY <--
