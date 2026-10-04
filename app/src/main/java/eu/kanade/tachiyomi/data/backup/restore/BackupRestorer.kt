package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupBookmark
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.BookmarkRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
// SY -->
import eu.kanade.tachiyomi.data.sync.SyncLedger
// SY <--
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
// SY -->
import tachiyomi.domain.category.interactor.GetCategories
// SY <--
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val database: Database = Injekt.get(),
    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    // SY -->
    private val getCategories: GetCategories = Injekt.get(),
    // SY <--
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(isSync),
    // SY -->
    private val savedSearchRestorer: SavedSearchRestorer = SavedSearchRestorer(),
    private val bookmarkRestorer: BookmarkRestorer = BookmarkRestorer(),
    // SY <--
) {

    private var restoreAmount = 0
    private val restoreProgress = AtomicInt(0)
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        // SY -->
        try {
            restoreFromFile(uri, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Record fatal restore failures into the error log instead of failing with only
            // a transient notification that hides the cause
            logcat(LogPriority.ERROR, e)
            errors.add(
                Date() to "Fatal: " +
                    e.stackTraceToString().lineSequence().take(15).joinToString("\n"),
            )
        }
        // SY <--

        // Invalidate download cache to ensure UI reflects any restored downloads
        if (options.libraryEntries) {
            try {
                Injekt.get<DownloadCache>().invalidateCache()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to invalidate download cache after restore" }
            }
        }

        val time = System.currentTimeMillis() - startTime

        // SY -->
        // The ledger records what this device owns once the merge has actually landed. Writing it
        // when the sync merely queued the restore recorded the pre-restore library, which made
        // every freshly pulled entry look "never owned" and let deletions be undone.
        if (isSync) {
            writeSyncLedger()
        }
        // SY <--

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val backup = BackupDecoder(context).decode(uri)

        // Store source mapping for error messages
        val backupMaps = backup.backupSources
        sourceMapping = backupMaps.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size
        }
        if (options.categories) {
            restoreAmount += 1
        }
        // SY -->
        if (options.savedSearches) {
            restoreAmount += 1
        }
        if (options.bookmarks) {
            restoreAmount += 1
        }
        // SY <--
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += backup.backupExtensionStores.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }

        // SY -->
        // Removals first, and outside the concurrent block below: a tombstoned entry has to be gone
        // before the restore that carries its own deletion gets a chance to re-insert it.
        if (isSync && options.libraryEntries) {
            applyMangaDeletions(backup.backupManga)
        }
        // SY <--

        coroutineScope {
            if (options.categories) {
                // SY -->
                restoreCategories(backup.backupCategories, isSync)
                // SY <--
            }
            // SY -->
            if (options.savedSearches) {
                restoreSavedSearches(backup.backupSavedSearches)
            }
            // SY <--
            if (options.appSettings) {
                restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories })
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(backup.backupSourcePreferences)
            }
            if (options.extensionStores) {
                restoreExtensionStores(backup.backupExtensionStores)
            }
        }

        // SY -->
        // Library entries run after the removals, and bookmarks after the library entries because a
        // bookmark is located through its chapter. Both used to sit in the concurrent block above,
        // where a bookmark could be resolved before its chapter existed and be silently dropped.
        if (options.libraryEntries) {
            coroutineScope {
                restoreManga(
                    backup.backupManga.filterNot { it.deletedAt > 0L },
                    if (options.categories) backup.backupCategories else emptyList(),
                )
            }
        }
        if (options.bookmarks) {
            coroutineScope {
                restoreBookmarks(backup.backupBookmarks, isSync)
            }
        }
        // SY <--

        // TODO: optionally trigger online library + tracker update
    }

    // SY -->
    private suspend fun writeSyncLedger() {
        val entries = SyncLedger.capture(database, getCategories)
        SyncLedger.write(context, entries)
    }
    // SY <--

    // SY -->
    private suspend fun applyMangaDeletions(backupMangas: List<BackupManga>) {
        val tombstones = backupMangas.filter { it.deletedAt > 0L }
        if (tombstones.isEmpty()) return

        try {
            mangaRestorer.restoreMangaDeletions(tombstones)
        } catch (e: Exception) {
            errors.add(Date() to "Sync deletions: ${e.message}")
        }
    }
    // SY <--

    // SY -->
    private fun CoroutineScope.restoreCategories(
        backupCategories: List<BackupCategory>,
        applyDeletions: Boolean,
    ) = launch {
        ensureActive()
        categoriesRestorer(backupCategories, applyDeletions)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            progress,
            restoreAmount,
            isSync,
        )
    }

    // SY -->
    private fun CoroutineScope.restoreSavedSearches(backupSavedSearches: List<BackupSavedSearch>) = launch {
        ensureActive()
        savedSearchRestorer.restoreSavedSearches(backupSavedSearches)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(SYMR.strings.saved_searches),
            progress,
            restoreAmount,
            isSync,
        )
    }
    // SY <--

    // SY -->
    private fun CoroutineScope.restoreBookmarks(
        backupBookmarks: List<BackupBookmark>,
        applyDeletions: Boolean,
    ) = launch {
        ensureActive()
        bookmarkRestorer.restoreBookmarks(backupBookmarks, applyDeletions)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.label_bookmarks),
            progress,
            restoreAmount,
            isSync,
        )
    }
    // SY <--

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        mangaRestorer.sortByNew(backupMangas)
            /* SY --> */.sortedBy { it.source == MERGED_SOURCE_ID } /* SY <-- */
            .chunked(100)
            .forEach { chunk ->
                database.transaction {
                    chunk.forEach {
                        ensureActive()

                        try {
                            mangaRestorer.restore(it, backupCategories)
                        } catch (e: Exception) {
                            val sourceName = sourceMapping[it.source] ?: it.source.toString()
                            errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                        }

                        restoreProgress.incrementAndFetch()
                    }
                }
                notifier.showRestoreProgress(chunk.last().title, restoreProgress.load(), restoreAmount, isSync)
            }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        val progress = restoreProgress.incrementAndFetch()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            progress,
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        backupExtensionStores
            .chunked(100)
            .forEach { chunk ->
                database.transaction {
                    chunk.forEach {
                        ensureActive()

                        try {
                            extensionStoreRestorer(it)
                        } catch (e: Exception) {
                            errors.add(Date() to "Error Adding Repo: ${it.name} : ${e.message}")
                        }

                        restoreProgress.incrementAndFetch()
                    }
                }
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionStores),
                    restoreProgress.load(),
                    restoreAmount,
                    isSync,
                )
            }
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("mihon_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}
