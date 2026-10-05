package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.net.Uri
import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
// SY -->
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveSyncService
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.data.sync.service.SyncYomiSyncService
// SY <--

// SY -->
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncService
// SY <--
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaMapper.mapManga
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.util.Date
import kotlin.system.measureTimeMillis

/**
 * A manager to handle synchronization tasks in the app, such as updating
 * sync preferences and performing synchronization with a remote server.
 *
 * @property context The application context.
 */
class SyncManager(
    private val context: Context,
    private val database: Database = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private var json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
    private val getCategories: GetCategories = Injekt.get(),
) {
    private val backupCreator: BackupCreator = BackupCreator(context, false)
    private val notifier: SyncNotifier = SyncNotifier(context)
    private val mangaRestorer: MangaRestorer = MangaRestorer()

    enum class SyncService(val value: Int) {
        NONE(0),
        SYNCYOMI(1),
        GOOGLE_DRIVE(2),

        // SY -->
        WEBDAV(3),
        // SY <--
        ;

        companion object {
            fun fromInt(value: Int) = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    /**
     * Syncs data with a sync service.
     *
     * This function retrieves local data (favorites, manga, extensions, and categories)
     * from the database using the BackupManager, then synchronizes the data with a sync service.
     */
    suspend fun syncData() {
        // Reset isSyncing in case it was left over or failed syncing during restore.
        database.transaction {
            database.mangasQueries.resetIsSyncing()
            database.chaptersQueries.resetIsSyncing()
            database.categoriesQueries.resetIsSyncing()
        }

        val syncOptions = syncPreferences.getSyncSettings()
        val databaseManga = getAllMangaThatNeedsSync()

        val backupOptions = BackupOptions(
            libraryEntries = syncOptions.libraryEntries,
            categories = syncOptions.categories,
            chapters = syncOptions.chapters,
            tracking = syncOptions.tracking,
            history = syncOptions.history,
            extensionStores = syncOptions.extensionStores,
            appSettings = syncOptions.appSettings,
            sourceSettings = syncOptions.sourceSettings,
            privateSettings = syncOptions.privateSettings,

            // SY -->
            customInfo = syncOptions.customInfo,
            readEntries = syncOptions.readEntries,
            savedSearches = syncOptions.savedSearches,
            bookmarks = syncOptions.bookmarks,
            // SY <--
        )

        logcat(LogPriority.DEBUG) { "Begin create backup" }
        val backupManga = backupCreator.backupMangas(databaseManga, backupOptions)
        val backup = Backup(
            backupManga = backupManga,
            backupCategories = backupCreator.backupCategories(backupOptions),
            backupSources = backupCreator.backupSources(backupManga),
            backupPreferences = backupCreator.backupAppPreferences(backupOptions) +
                // SY -->
                // Publish which sections this device syncs so the others can detect a disagreement.
                // The entry is stripped again before it reaches the restore.
                BackupPreference(
                    SyncPreferences.SYNC_SETTINGS_KEY,
                    StringPreferenceValue(syncPreferences.encodeSyncSettings(syncOptions)),
                ),
            // SY <--

            backupSourcePreferences = backupCreator.backupSourcePreferences(backupOptions),
            backupExtensionStores = backupCreator.backupExtensionStores(backupOptions),

            // SY -->
            backupSavedSearches = backupCreator.backupSavedSearches(backupOptions),
            backupBookmarks = backupCreator.backupBookmarks(backupOptions),
            // SY <--
        )
        logcat(LogPriority.DEBUG) { "End create backup" }

        // SY -->
        // Fold the progress channel into this payload before it is sent: entries it knows to be
        // newer are applied here, and this payload's entries are written back into it, so the two
        // views converge no matter which one was used since the last full sync.
        ProgressSyncManager(context).mergeWithFullBackup(backup)
        // SY <--

        // Create the SyncData object
        // SY -->
        // The merge resolves entries in place, so it is handed a private copy. Without this the
        // local backup would come back already merged and every "did the remote change anything"
        // comparison below would compare the merged objects against themselves, silently reporting
        // "no changes" and skipping the restore.
        val syncBackup = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
        )
        val syncData = SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = syncBackup,
        )
        // SY <--

        // Handle sync based on the selected service
        val syncService = when (val syncService = SyncService.fromInt(syncPreferences.syncService.get())) {
            SyncService.SYNCYOMI -> {
                SyncYomiSyncService(
                    context,
                    json,
                    syncPreferences,
                    notifier,
                )
            }

            SyncService.GOOGLE_DRIVE -> {
                GoogleDriveSyncService(context, json, syncPreferences)
            }

            // SY -->
            SyncService.WEBDAV -> {
                WebDavSyncService(context, json, syncPreferences, notifier)
            }
            // SY <--

            else -> {
                logcat(LogPriority.ERROR) { "Invalid sync service type: $syncService" }
                null
            }
        }

        // SY -->
        syncService?.ledger = SyncLedger.load(context)
        // SY <--

        val remoteBackup = syncService?.doSync(syncData)

        if (remoteBackup == null) {
            logcat(LogPriority.DEBUG) { "Skip restore due to network issues" }
            // should we call showSyncError?
            return
        }

        // SY -->
        // The clock must survive a restart even when no restore follows, otherwise a device whose
        // wall clock is slow could produce timestamps that lose to values it has not seen yet.
        SyncClock.flush(context)
        // SY <--

        if (remoteBackup === syncData.backup) {
            // nothing changed
            logcat(LogPriority.DEBUG) { "Skip restore due to remote was overwrite from local" }
            syncPreferences.lastSyncTimestamp.set(Date().time)

            // SY -->
            writeSyncLedger()
            // SY <--

            notifier.showSyncSuccess("Sync completed successfully")
            return
        }

        // SY -->
        // Only bail out when every single enabled section came back empty. Checking just manga,
        // categories and sources reported "no data on remote" for users who sync only bookmarks or
        // searches, which also meant the baseline was never advanced for them.
        val enabledSections = listOf(
            syncOptions.libraryEntries to remoteBackup.backupManga,
            syncOptions.categories to remoteBackup.backupCategories,
            syncOptions.savedSearches to remoteBackup.backupSavedSearches,
            syncOptions.bookmarks to remoteBackup.backupBookmarks,
        )
        val hasAnyEnabledSection = enabledSections.any { (enabled, _) -> enabled }
        val hasAnyContent = enabledSections.any { (enabled, value) -> enabled && value.isNotEmpty() }
        if (hasAnyEnabledSection && !hasAnyContent) {
            notifier.showSyncError("No data found on remote server.")
            return
        }
        // SY <--

        // SY -->
        // Removed upstream "first sync skips restore" early return: it prevented a device
        // with an existing library from ever adopting the remote library on its first sync.
        // The merged data pushed by doSync is restored below in all cases.
        // SY <--

        val (filteredFavorites, nonFavorites) = filterFavoritesAndNonFavorites(remoteBackup)
        updateNonFavorites(nonFavorites)

        // SY -->
        // Tombstones are removals decided by the merge and must reach the restore job, otherwise the
        // deleted entries are recreated locally. Categories are excluded from the pre-delete below
        // because their tombstones are applied by the restorer itself.
        val deletionTombstones = remoteBackup.backupManga.filter { it.deletedAt > 0L }
        // SY <--

        // SY -->
        // Reading progress of entries outside the library never reaches the restore through the
        // favorites branch, so a device that read a book without adding it would keep that progress
        // to itself. Entries the remote has and this device has never seen are restored so progress,
        // history and bookmarks converge too.
        val readOnlyAdditions = filterReadOnlyAdditions(remoteBackup)
        // SY <--

        // SY -->
        reportSyncSettingsMismatch(syncService?.remoteSyncSettingsMismatch)
        // The marker is metadata for the sync settings screen, not an app preference, so it must
        // not be written into the shared preferences by the restore.
        val restorablePreferences = remoteBackup.backupPreferences
            .filterNot { it.key == SyncPreferences.SYNC_SETTINGS_KEY }
        // SY <--

        val newSyncData = backup.copy(
            backupManga = filteredFavorites + readOnlyAdditions + deletionTombstones,
            backupCategories = remoteBackup.backupCategories,
            backupSources = remoteBackup.backupSources,
            backupPreferences = restorablePreferences,
            backupSourcePreferences = remoteBackup.backupSourcePreferences,
            backupExtensionStores = remoteBackup.backupExtensionStores,

            // SY -->
            backupSavedSearches = remoteBackup.backupSavedSearches,
            backupBookmarks = remoteBackup.backupBookmarks,
            // SY <--
        )

        // SY -->
        val hasMangaChanges = filteredFavorites.isNotEmpty() ||
            readOnlyAdditions.isNotEmpty() ||
            deletionTombstones.isNotEmpty()
        // SY <--
        val hasCategoryChanges = remoteBackup.backupCategories != backup.backupCategories
        val hasSourceChanges = remoteBackup.backupSources != backup.backupSources
        val hasPreferenceChanges = remoteBackup.backupPreferences != backup.backupPreferences
        val hasSourcePreferenceChanges = remoteBackup.backupSourcePreferences != backup.backupSourcePreferences
        val hasExtensionRepoChanges = remoteBackup.backupExtensionStores != backup.backupExtensionStores
        val hasSavedSearchChanges = remoteBackup.backupSavedSearches != backup.backupSavedSearches

        // SY -->
        val hasBookmarkChanges = remoteBackup.backupBookmarks != backup.backupBookmarks
        // SY <--

        if (!hasMangaChanges && !hasCategoryChanges && !hasSourceChanges &&
            !hasPreferenceChanges && !hasSourcePreferenceChanges &&
            !hasExtensionRepoChanges && !hasSavedSearchChanges &&
            // SY -->
            !hasBookmarkChanges
            // SY <--
        ) {
            // update the sync timestamp
            syncPreferences.lastSyncTimestamp.set(Date().time)

            // SY -->
            writeSyncLedger()
            // SY <--

            notifier.showSyncSuccess("Sync completed successfully")
            return
        }

        // SY -->
        // Removals are decided by tombstones now, so a local category missing from the merged set
        // is no longer evidence of a deletion and must not be dropped here.
        // SY <--

        val backupUri = writeSyncDataToCache(context, newSyncData)
        logcat(LogPriority.DEBUG) { "Got Backup Uri: $backupUri" }
        if (backupUri != null) {
            BackupRestoreJob.start(
                context,
                backupUri,
                sync = true,
                options = RestoreOptions(
                    appSettings = syncOptions.appSettings,
                    sourceSettings = syncOptions.sourceSettings,
                    libraryEntries = syncOptions.libraryEntries,
                    categories = syncOptions.categories,
                    extensionStores = syncOptions.extensionStores,
                    // SY -->
                    savedSearches = syncOptions.savedSearches,
                    bookmarks = syncOptions.bookmarks,
                    // SY <--
                ),
            )

            // update the sync timestamp
            syncPreferences.lastSyncTimestamp.set(Date().time)
        } else {
            logcat(LogPriority.ERROR) { "Failed to write sync data to file" }
        }

        // SY -->
        // The push already happened. The ledger is deliberately NOT written here: the restore above
        // runs asynchronously, so the library at this instant does not yet contain what was just
        // pulled. It is written by the restorer once the database actually reflects the merge.
        // SY <--
    }

    // SY -->
    private suspend fun writeSyncLedger() {
        val entries = SyncLedger.capture(database, getCategories)
        SyncLedger.write(context, entries)
        logcat(LogPriority.DEBUG) { "Sync ledger updated with ${entries.size} keys" }
    }

    /**
     * Records whether the devices agree on which sections are synced. A device that syncs fewer
     * sections would otherwise silently truncate the others' data on every merge, so a mismatch is
     * surfaced in the sync settings instead of being resolved automatically.
     *
     * The value comes from the merge, which sees the remote's own marker before it is overwritten.
     */
    private fun reportSyncSettingsMismatch(mismatch: String?) {
        val encoded = mismatch.orEmpty()

        if (encoded.isNotEmpty()) {
            logcat(LogPriority.WARN) {
                "Sync section mismatch: remote=$encoded local=${syncPreferences.encodeSyncSettings(syncPreferences.getSyncSettings())}"
            }
        }

        syncPreferences.remoteSyncSettings.set(encoded)
    }
    // SY <--

    private fun writeSyncDataToCache(context: Context, backup: Backup): Uri? {
        val cacheFile = File(context.cacheDir, "tachiyomi_sync_data.proto.gz")
        return try {
            cacheFile.outputStream().use { output ->
                output.write(ProtoBuf.encodeToByteArray(Backup.serializer(), backup))
                Uri.fromFile(cacheFile)
            }
        } catch (e: IOException) {
            logcat(LogPriority.ERROR, throwable = e) { "Failed to write sync data to cache" }
            null
        }
    }

    /**
     * Retrieves all manga from the local database.
     *
     * @return a list of all manga stored in the database
     */
    private suspend fun getAllMangaFromDB(): List<Manga> {
        return database.mangasQueries
            .getAllManga(::mapManga)
            .awaitAsList()
    }

    private suspend fun getAllMangaThatNeedsSync(): List<Manga> {
        return database.mangasQueries
            .getMangasWithFavoriteTimestamp(::mapManga)
            .awaitAsList()
    }

    private suspend fun isMangaDifferent(localManga: Manga, remoteManga: BackupManga): Boolean {
        val localChapters = database.chaptersQueries.getChaptersByMangaId(localManga.id, 0).awaitAsList()
        val localCategories = getCategories.await(localManga.id).map { it.order }

        if (areChaptersDifferent(localChapters, remoteManga.chapters)) {
            return true
        }

        if (localManga.version != remoteManga.version) {
            return true
        }

        // SY -->
        // Being added to or removed from the library is a change in its own right. Without this the
        // restore was never triggered for it and the favorite flag stayed as it was locally.
        if (localManga.favorite != remoteManga.favorite &&
            (remoteManga.favoriteModifiedAt ?: 0L) >= (localManga.favoriteModifiedAt ?: 0L)
        ) {
            return true
        }
        // SY <--

        if (localCategories.toSet() != remoteManga.categories.toSet()) {
            return true
        }

        // SY -->
        // A cleared reading date changes neither the chapter nor the version, so without this check
        // the merge result would never trigger the restore that is supposed to apply it. Only pay
        // for the extra query when the merged entry actually asks for a clearing.
        val clearedInMerge = remoteManga.history.count { it.clearedAt > 0L }
        if (clearedInMerge > 0) {
            val clearedLocally = database.historyQueries
                .getHistoryByMangaId(localManga.id)
                .awaitAsList()
                .count { (it.last_read?.time ?: 0L) <= 0L }
            if (clearedLocally < clearedInMerge) return true
        }
        // SY <--

        return false
    }

    private fun areChaptersDifferent(localChapters: List<Chapters>, remoteChapters: List<BackupChapter>): Boolean {
        val localChapterMap = localChapters.associateBy { it.url }
        val remoteChapterMap = remoteChapters.associateBy { it.url }

        if (localChapterMap.size != remoteChapterMap.size) {
            return true
        }

        for ((url, localChapter) in localChapterMap) {
            val remoteChapter = remoteChapterMap[url]

            // If a matching remote chapter doesn't exist, or the version numbers are different, consider them different
            if (remoteChapter == null || localChapter.version != remoteChapter.version) {
                return true
            }
        }

        return false
    }

    /**
     * Filters the favorite and non-favorite manga from the backup and checks
     * if the favorite manga is different from the local database.
     * @param backup the Backup object containing the backup data.
     * @return a Pair of lists, where the first list contains different favorite manga
     * and the second list contains non-favorite manga.
     */
    private suspend fun filterFavoritesAndNonFavorites(backup: Backup): Pair<List<BackupManga>, List<BackupManga>> {
        val favorites = mutableListOf<BackupManga>()
        val nonFavorites = mutableListOf<BackupManga>()
        val logTag = "filterFavoritesAndNonFavorites"

        val elapsedTimeMillis = measureTimeMillis {
            val databaseManga = getAllMangaFromDB()
            val localMangaMap = databaseManga.associateBy {
                Pair(it.source, it.url)
            }

            logcat(LogPriority.DEBUG, logTag) { "Starting to filter favorites and non-favorites from backup data." }

            backup.backupManga.forEach { remoteManga ->
                // SY -->
                // Tombstones are removals, handled by the restore job. Letting them fall into the
                // non-favorite branch would only clear the favorite flag and leave the entry behind.
                if (remoteManga.deletedAt > 0L) return@forEach
                // SY <--
                val compositeKey = Pair(remoteManga.source, remoteManga.url)
                val localManga = localMangaMap[compositeKey]
                when {
                    // Checks if the manga is in favorites and needs updating or adding
                    remoteManga.favorite -> {
                        if (localManga == null || isMangaDifferent(localManga, remoteManga)) {
                            logcat(LogPriority.DEBUG, logTag) { "Adding to favorites: ${remoteManga.title}" }
                            favorites.add(remoteManga)
                        } else {
                            logcat(LogPriority.DEBUG, logTag) { "Already up-to-date favorite: ${remoteManga.title}" }
                        }
                    }
                    // Handle non-favorites
                    !remoteManga.favorite -> {
                        logcat(LogPriority.DEBUG, logTag) { "Adding to non-favorites: ${remoteManga.title}" }
                        nonFavorites.add(remoteManga)
                    }
                }
            }
        }

        val minutes = elapsedTimeMillis / 60000
        val seconds = (elapsedTimeMillis % 60000) / 1000
        logcat(LogPriority.DEBUG, logTag) {
            "Filtering completed in ${minutes}m ${seconds}s. Favorites found: ${favorites.size}, " +
                "Non-favorites found: ${nonFavorites.size}"
        }

        return Pair(favorites, nonFavorites)
    }

    // SY -->
    /**
     * Remote entries this device has never seen that carry reading state, so their progress, history
     * and bookmarks are restored as well. Entries the device already has are left to the favorites
     * branch, which only restores what actually differs.
     */
    private suspend fun filterReadOnlyAdditions(remoteBackup: Backup): List<BackupManga> {
        val localKeys = getAllMangaFromDB()
            .map { it.source to it.url }
            .toSet()

        return remoteBackup.backupManga.filter { manga ->
            !manga.favorite &&
                manga.deletedAt <= 0L &&
                (manga.source to manga.url) !in localKeys &&
                (
                    manga.chapters.any { it.read || it.lastPageRead > 0L || it.bookmark } ||
                        manga.history.isNotEmpty() ||
                        manga.tracking.isNotEmpty()
                    )
        }
    }
    // SY <--

    /**
     * Updates the non-favorite manga in the local database with their favorite status from the backup.
     * @param nonFavorites the list of non-favorite BackupManga objects from the backup.
     */
    private suspend fun updateNonFavorites(nonFavorites: List<BackupManga>) {
        val localMangaList = getAllMangaFromDB()

        val localMangaMap = localMangaList.associateBy { Pair(it.source, it.url) }

        nonFavorites.forEach { nonFavorite ->
            val key = Pair(nonFavorite.source, nonFavorite.url)
            localMangaMap[key]?.let { localManga ->
                // SY -->
                // Being removed from the library is a change like any other and is resolved by
                // timestamp, so an older removal on the remote cannot undo a newer local addition.
                if (localManga.favorite != nonFavorite.favorite &&
                    (nonFavorite.favoriteModifiedAt ?: 0L) >= (localManga.favoriteModifiedAt ?: 0L)
                ) {
                    val updatedManga = localManga.copy(favorite = nonFavorite.favorite)
                    mangaRestorer.updateManga(updatedManga)
                }
                // SY <--
            }
        }
    }
}
