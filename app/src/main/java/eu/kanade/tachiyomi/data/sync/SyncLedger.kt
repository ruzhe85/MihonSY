package eu.kanade.tachiyomi.data.sync

import android.content.Context
import java.io.File
import logcat.LogPriority
import logcat.logcat

// SY -->
/**
 * Ledger of the keys this device had in its library at the time of the last successful sync.
 *
 * The merge step uses it to distinguish "I deleted this locally" (key present in the ledger,
 * absent from the local DB -> propagate the deletion) from "I never had this" (key absent from
 * the ledger -> a remote entry to adopt, regardless of its lastModifiedAt). Without it, a device
 * can never adopt another device's old, unmodified library entries.
 */
object SyncLedger {

    private const val FILE_NAME = "sync_ledger.txt"
    private const val MANGA_PREFIX = "m:"
    private const val CATEGORY_PREFIX = "c:"

    fun mangaKey(source: Long, url: String) = "$MANGA_PREFIX$source|$url"

    fun categoryKey(name: String) = "$CATEGORY_PREFIX$name"

    fun load(context: Context): Set<String> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptySet()
        return try {
            file.readLines().filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to load sync ledger: ${e.message}" }
            emptySet()
        }
    }

    fun write(context: Context, keys: Set<String>) {
        try {
            File(context.filesDir, FILE_NAME).writeText(keys.sorted().joinToString("\n"))
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to write sync ledger: ${e.message}" }
        }
    }
}
// SY <--
