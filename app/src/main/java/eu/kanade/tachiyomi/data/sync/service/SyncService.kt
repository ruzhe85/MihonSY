package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupBookmark
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.sync.MergeRules
import eu.kanade.tachiyomi.data.sync.SyncClock
import eu.kanade.tachiyomi.data.sync.SyncLedger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat

@Serializable
data class SyncData(
    val deviceId: String = "",
    val backup: Backup? = null,
)

/**
 * Merges the local and remote snapshots into one.
 *
 * Convergence across several devices rests on three rules, all of them per key:
 *  - a removal leaves a [deletedAt] tombstone instead of vanishing, so the intent can propagate;
 *  - a key that the ledger says we owned but that is now missing locally becomes a tombstone,
 *    which is the only way to tell "deleted here" from "never had it";
 *  - mutable fields carry their own timestamp and are resolved by last-writer-wins per field, so
 *    two devices that progressed through the same book differently keep both contributions.
 */
abstract class SyncService(
    val context: Context,
    val json: Json,
    val syncPreferences: SyncPreferences,
) {
    abstract suspend fun doSync(syncData: SyncData): Backup?

    // SY -->
    /** Entries this device owned at its last successful sync, mapped to their version timestamp. */
    var ledger: Map<String, Long> = emptyMap()

    /**
     * Encoded section selection of the other device when it disagrees with this one, empty
     * otherwise. Captured here rather than read back from the merged payload, because merging keeps
     * the local marker and would always compare it against itself.
     */
    var remoteSyncSettingsMismatch: String = ""

    /**
     * Remote-only entries the merge of this run had to apply locally. Together with the completion
     * time this is what the settings screen reports as the sync result: 0 means the two sides
     * already agreed, which is the only meaningful way to confirm several devices converged.
     */
    var lastAppliedCount: Int = 0
    // SY <--

    protected fun mergeSyncData(localSyncData: SyncData, remoteSyncData: SyncData): SyncData {
        val localBackup = localSyncData.backup
        val remoteBackup = remoteSyncData.backup

        // SY -->
        // Measured before merging: the merge folds the remote side into the local entries in place,
        // so afterwards there would be no difference left to count.
        lastAppliedCount = countRemoteOnlyEntries(localBackup, remoteBackup)
        // SY <--

        // SY -->
        // Which sections each side is actually syncing. A section that is switched off is left out
        // of the backup completely, so its absence must never be read as "the other device deleted
        // everything" — that would let one device with a different selection wipe the rest.
        // An older remote carries no marker, in which case actually sending entries is the signal.
        val localSettings = syncPreferences.getSyncSettings()
        val remoteSettings = parseSyncSettings(remoteBackup?.backupPreferences)

        // Library sections are unioned so a device that missed a checkbox cannot silently drop data
        // the others still keep. The widened selection is persisted and takes effect from the next
        // sync on, because the payload for this run has already been built.
        if (remoteSettings != null) {
            val mergedSections = syncPreferences.mergeSyncSections(localSettings, remoteSettings)
            if (mergedSections != localSettings) {
                logcat(LogPriority.INFO, "SyncService") {
                    "Library sections widened to match the other device"
                }
                syncPreferences.setSyncSettings(mergedSections)
            }
        }

        // Only settings sections are worth reporting: library ones are reconciled automatically
        remoteSyncSettingsMismatch = if (remoteSettings != null &&
            syncPreferences.settingsSectionsDiffer(localSettings, remoteSettings)
        ) {
            syncPreferences.encodeSyncSettings(remoteSettings)
        } else {
            ""
        }

        val localSyncsCategories = localSettings.categories
        val remoteSyncsCategories = remoteSettings?.categories
            ?: !remoteBackup?.backupCategories.isNullOrEmpty()
        val localSyncsManga = localSettings.libraryEntries
        val remoteSyncsManga = remoteSettings?.libraryEntries
            ?: !remoteBackup?.backupManga.isNullOrEmpty()
        val localSyncsBookmarks = localSettings.bookmarks
        val remoteSyncsBookmarks = remoteSettings?.bookmarks
            ?: !remoteBackup?.backupBookmarks.isNullOrEmpty()
        // History rides inside the manga entries, so an older remote is judged by whether it sent
        // any manga at all
        val localSyncsHistory = localSettings.history
        val remoteSyncsHistory = remoteSettings?.history
            ?: !remoteBackup?.backupManga.isNullOrEmpty()
        // SY <--

        val mergedCategoriesList = mergeCategoriesLists(
            localBackup?.backupCategories,
            remoteBackup?.backupCategories,
            localSyncs = localSyncsCategories,
            remoteSyncs = remoteSyncsCategories,
        )

        val mergedMangaList = mergeMangaLists(
            localBackup?.backupManga,
            remoteBackup?.backupManga,
            localBackup?.backupCategories ?: emptyList(),
            remoteBackup?.backupCategories ?: emptyList(),
            mergedCategoriesList,
            localSyncs = localSyncsManga,
            remoteSyncs = remoteSyncsManga,
            localSyncsHistory = localSyncsHistory,
            remoteSyncsHistory = remoteSyncsHistory,
        )

        val mergedSourcesList = mergeSourcesLists(localBackup?.backupSources, remoteBackup?.backupSources)
        val mergedPreferencesList = mergePreferencesLists(localBackup?.backupPreferences, remoteBackup?.backupPreferences)
        val mergedSourcePreferencesList = mergeSourcePreferencesLists(
            localBackup?.backupSourcePreferences,
            remoteBackup?.backupSourcePreferences,
        )

        // SY -->
        val mergedSavedSearchesList = mergeSavedSearchesLists(
            localBackup?.backupSavedSearches,
            remoteBackup?.backupSavedSearches,
        )
        val mergedBookmarksList = mergeBookmarksLists(
            localBackup?.backupBookmarks,
            remoteBackup?.backupBookmarks,
            localSyncs = localSyncsBookmarks,
            remoteSyncs = remoteSyncsBookmarks,
        )
        // SY <--

        // SY -->
        val mergedBackup = Backup(
            backupManga = mergedMangaList,
            backupCategories = mergedCategoriesList,
            backupSources = mergedSourcesList,
            backupPreferences = mergedPreferencesList,
            backupSourcePreferences = mergedSourcePreferencesList,
            backupSavedSearches = mergedSavedSearchesList,
            backupBookmarks = mergedBookmarksList,
        )
        // SY <--

        return SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = mergedBackup,
        )
    }

    // SY -->
    /**
     * How much of the remote payload this device did not have yet, across the sections that
     * represent actual content. Used purely for the sync result shown in the settings screen.
     */
    private fun countRemoteOnlyEntries(local: Backup?, remote: Backup?): Int {
        if (remote == null) return 0

        val localManga = local?.backupManga.orEmpty().map { "${it.source}|${it.url}" }.toSet()
        val localCategories = local?.backupCategories.orEmpty().map { it.name }.toSet()
        val localBookmarks = local?.backupBookmarks.orEmpty()
            .map { "${it.source}|${it.mangaUrl}|${it.chapterUrl}|${it.page}" }
            .toSet()

        val manga = remote.backupManga.count { "${it.source}|${it.url}" !in localManga }
        val categories = remote.backupCategories.count { it.name !in localCategories }
        val bookmarks = remote.backupBookmarks.count {
            "${it.source}|${it.mangaUrl}|${it.chapterUrl}|${it.page}" !in localBookmarks
        }

        return manga + categories + bookmarks
    }
    // SY <--

    // SY -->

    private fun mergeMangaLists(
        localMangaList: List<BackupManga>?,
        remoteMangaList: List<BackupManga>?,
        localCategories: List<BackupCategory>,
        remoteCategories: List<BackupCategory>,
        mergedCategories: List<BackupCategory>,
        localSyncs: Boolean,
        remoteSyncs: Boolean,
        localSyncsHistory: Boolean,
        remoteSyncsHistory: Boolean,
    ): List<BackupManga> {
        val logTag = "MergeMangaLists"

        val localMap = localMangaList.orEmpty().associateBy { mangaKey(it) }
        val remoteMap = remoteMangaList.orEmpty().associateBy { mangaKey(it) }

        val localCategoriesByOrder = localCategories.associateBy { it.order }
        val remoteCategoriesByOrder = remoteCategories.associateBy { it.order }
        val mergedCategoriesByName = mergedCategories.associateBy { it.name }

        val syncChapters = syncPreferences.getSyncSettings().chapters

        logcat(LogPriority.DEBUG, logTag) {
            "Merging ${localMap.size} local and ${remoteMap.size} remote entries"
        }

        val merged = (localMap.keys + remoteMap.keys).distinct().mapNotNull { key ->
            val local = localMap[key]
            val remote = remoteMap[key]

            when {
                local != null && remote != null -> resolveManga(
                    local = local,
                    remote = remote,
                    syncChapters = syncChapters,
                    localCategories = localCategoriesByOrder,
                    remoteCategories = remoteCategoriesByOrder,
                    mergedCategories = mergedCategoriesByName,
                    localSyncsHistory = localSyncsHistory,
                    remoteSyncsHistory = remoteSyncsHistory,
                )

                local != null -> {
                    // SY -->
                    // Still here but gone from the remote, and this device had it at the last sync:
                    // another device deleted it, so the removal is recorded and applied locally too.
                    if (remoteSyncs && SyncLedger.mangaKey(local.source, local.url) in ledger) {
                        logcat(LogPriority.DEBUG, logTag) { "Tombstoning manga deleted remotely: ${local.title}" }
                        local.asTombstone()
                    } else {
                        local.apply { remapCategories(this, localCategoriesByOrder, mergedCategoriesByName) }
                    }
                    // SY <--
                }

                // SY -->
                else -> {
                    remote?.let {
                        SyncClock.observe(context, mangaLedgerKey(it), it.lastModifiedAt)
                        // Its key is in the ledger yet it is no longer in the library, so this
                        // device deleted it. Record the removal instead of pulling it back.
                        if (localSyncs && SyncLedger.mangaKey(it.source, it.url) in ledger) {
                            logcat(LogPriority.DEBUG, logTag) { "Tombstoning manga deleted locally: ${it.title}" }
                            it.asTombstone()
                        } else {
                            it.apply { remapCategories(this, remoteCategoriesByOrder, mergedCategoriesByName) }
                        }
                    }
                }
                // SY <--
            }
        }

        logcat(LogPriority.DEBUG, logTag) {
            "Merged ${merged.size} entries (${merged.count { it.deletedAt > 0 }} tombstones)"
        }

        return merged
    }

    /**
     * Resolves one entry present on both sides. Whichever side is deleted loses, unless the other
     * side was modified after that deletion, in which case the newer edit wins and the entry comes
     * back. Surviving entries merge field by field.
     */
    private fun resolveManga(
        local: BackupManga,
        remote: BackupManga,
        syncChapters: Boolean,
        localCategories: Map<Long, BackupCategory>,
        remoteCategories: Map<Long, BackupCategory>,
        mergedCategories: Map<String, BackupCategory>,
        localSyncsHistory: Boolean,
        remoteSyncsHistory: Boolean,
    ): BackupManga {
        if (local.deletedAt > 0L || remote.deletedAt > 0L) {
            return when {
                local.deletedAt > 0L && remote.deletedAt > 0L ->
                    if (local.deletedAt >= remote.deletedAt) local.asTombstone() else remote.asTombstone()

                local.deletedAt > 0L ->
                    if (remote.lastModifiedAt > local.deletedAt) remote else local.asTombstone()

                else ->
                    if (local.lastModifiedAt > remote.deletedAt) local else remote.asTombstone()
            }
        }

        val preferLocal = local.version >= remote.version
        val base = if (preferLocal) local else remote
        val baseCategories = if (preferLocal) localCategories else remoteCategories

        // Favorite is the one field a user toggles directly, so it gets its own last-writer-wins
        // instead of following the whole-entry version. Legacy entries without a timestamp fall
        // back to the version, and an exact tie keeps the entry favorited.
        val localFavoriteAt = local.favoriteModifiedAt ?: local.version
        val remoteFavoriteAt = remote.favoriteModifiedAt ?: remote.version
        base.favorite = when {
            localFavoriteAt > remoteFavoriteAt -> local.favorite
            remoteFavoriteAt > localFavoriteAt -> remote.favorite
            else -> local.favorite || remote.favorite
        }
        base.favoriteModifiedAt = maxOf(localFavoriteAt, remoteFavoriteAt).takeIf { it > 0L }

        base.lastModifiedAt = maxOf(local.lastModifiedAt, remote.lastModifiedAt)
        base.version = maxOf(local.version, remote.version)
        base.chapters = if (syncChapters) {
            mergeChapters(local, remote)
        } else {
            remote.chapters
        }
        base.history = mergeHistoryLists(
            manga = base,
            localHistory = local.history,
            remoteHistory = remote.history,
            localSyncs = localSyncsHistory,
            remoteSyncs = remoteSyncsHistory,
        )

        remapCategories(base, baseCategories, mergedCategories)
        return base
    }

    private fun remapCategories(
        manga: BackupManga,
        from: Map<Long, BackupCategory>,
        mergedByName: Map<String, BackupCategory>,
    ) {
        manga.categories = manga.categories
            .mapNotNull { order -> from[order]?.let { mergedByName[it.name]?.order } }
            .distinct()
    }

    /**
     * Strips an entry down to its identity plus a tombstone, so removed data stops bloating the file.
     * An existing tombstone keeps its original timestamp: re-stamping it on every sync would make
     * the remote file change forever and leave the etag permanently unstable.
     */
    private fun BackupManga.asTombstone(): BackupManga {
        val tombstone = BackupManga(source = source, url = url, title = title)
        tombstone.deletedAt = deletedAt.takeIf { it > 0L } ?: SyncClock.next(context, mangaLedgerKey(this))
        tombstone.favorite = false
        return tombstone
    }

    private fun mangaKey(manga: BackupManga) = "${manga.source}|${manga.url}"

    // SY -->
    /** Section selection advertised by another device, or null when it predates the marker. */
    private fun parseSyncSettings(preferences: List<BackupPreference>?): SyncSettings? {
        val encoded = preferences
            ?.firstOrNull { it.key == SyncPreferences.SYNC_SETTINGS_KEY }
            ?.let { (it.value as? StringPreferenceValue)?.value }
            ?: return null
        return syncPreferences.decodeSyncSettings(encoded)
    }
    // SY <--

    private fun mangaLedgerKey(manga: BackupManga) = SyncLedger.mangaKey(manga.source, manga.url)

    /**
     * Chapters carry their own timestamps for the three fields a reader touches continuously
     * (read, bookmark, last page), so two devices reading different amounts of the same chapter
     * keep the further progress and the bookmark instead of one device's snapshot overwriting the
     * other's.
     */
    private fun mergeChapters(manga: BackupManga, remote: BackupManga): List<BackupChapter> {
        val localChapters = manga.chapters
        val remoteChapters = remote.chapters

        val localMap = localChapters.associateBy { it.url }
        val remoteMap = remoteChapters.associateBy { it.url }

        fun ledgerKeyOf(url: String) = SyncLedger.chapterKey(manga.source, manga.url, url)

        return (localMap.keys + remoteMap.keys).distinct().mapNotNull { url ->
            val local = localMap[url]
            val remoteChapter = remoteMap[url]

            when {
                local != null && remoteChapter != null -> {
                    if (local.deletedAt > 0L || remoteChapter.deletedAt > 0L) {
                        val winner = when {
                            local.deletedAt > 0L && remoteChapter.deletedAt > 0L ->
                                if (local.deletedAt >= remoteChapter.deletedAt) local else remoteChapter
                            local.deletedAt > 0L ->
                                if (remoteChapter.lastModifiedAt > local.deletedAt) remoteChapter else local
                            else ->
                                if (local.lastModifiedAt > remoteChapter.deletedAt) local else remoteChapter
                        }
                        winner.asChapterTombstone(ledgerKeyOf(url))
                    } else {
                        mergeChapterPair(
                            local = local,
                            remote = remoteChapter,
                            progressKey = SyncClock.progressKey(manga.source, manga.url, url),
                            bookmarkKey = SyncClock.bookmarkKey(manga.source, manga.url, url),
                        )
                    }
                }

                local != null -> {
                    // A chapter missing from the remote is not treated as a removal here: chapters
                    // disappear on their own when a source updates, and a tombstone is only
                    // propagated when the remote explicitly carries one.
                    local
                }

                else -> remoteChapter?.also {
                    SyncClock.observe(context, ledgerKeyOf(it.url), it.lastModifiedAt)
                }
            }
        }
    }

    private fun mergeChapterPair(
        local: BackupChapter,
        remote: BackupChapter,
        progressKey: String,
        bookmarkKey: String,
    ): BackupChapter {
        // SY -->
        // Absorb the timestamps this merge resolved against, so a later local write on either field
        // is guaranteed to land after them even when this device's wall clock is behind.
        SyncClock.observe(context, progressKey, remote.progressAt)
        SyncClock.observe(context, bookmarkKey, remote.bookmarkAt)
        // SY <--
        val base = if (local.version >= remote.version) local else remote

        base.read = when {
            local.readAt > remote.readAt -> local.read
            remote.readAt > local.readAt -> remote.read
            else -> local.read || remote.read
        }
        base.readAt = maxOf(local.readAt, remote.readAt)

        base.bookmark = when {
            local.bookmarkAt > remote.bookmarkAt -> local.bookmark
            remote.bookmarkAt > local.bookmarkAt -> remote.bookmark
            else -> local.bookmark || remote.bookmark
        }
        base.bookmarkAt = maxOf(local.bookmarkAt, remote.bookmarkAt)

        base.lastPageRead = when {
            local.progressAt > remote.progressAt -> local.lastPageRead
            remote.progressAt > local.progressAt -> remote.lastPageRead
            else -> maxOf(local.lastPageRead, remote.lastPageRead)
        }
        base.progressAt = maxOf(local.progressAt, remote.progressAt)

        base.dateFetch = maxOf(local.dateFetch, remote.dateFetch)
        base.lastModifiedAt = maxOf(local.lastModifiedAt, remote.lastModifiedAt)
        base.version = maxOf(local.version, remote.version)
        return base
    }

    private fun BackupChapter.asChapterTombstone(ledgerKey: String): BackupChapter {
        val tombstone = BackupChapter(url = url, name = name)
        tombstone.deletedAt = deletedAt.takeIf { it > 0L } ?: SyncClock.next(context, ledgerKey)
        tombstone.read = false
        tombstone.bookmark = false
        return tombstone
    }

    // SY -->
    /**
     * History merges like the other sections, except that a reading date can also be *cleared*:
     * mihon models that as `last_read = 0` with the row kept, so a cleared entry has to carry its
     * own timestamp or the plain "larger reading time wins" rule would silently undo the clearing.
     */
    private fun mergeHistoryLists(
        manga: BackupManga,
        localHistory: List<BackupHistory>,
        remoteHistory: List<BackupHistory>,
        localSyncs: Boolean,
        remoteSyncs: Boolean,
    ): List<BackupHistory> {
        if (localHistory.isEmpty() && remoteHistory.isEmpty()) return emptyList()

        // A side that does not sync history has nothing to say about removals here
        if (!localSyncs) return remoteHistory
        if (!remoteSyncs) return localHistory

        val localMap = localHistory.associateBy { it.url }
        val remoteMap = remoteHistory.associateBy { it.url }

        return (localMap.keys + remoteMap.keys).distinct().mapNotNull { url ->
            val local = localMap[url]
            val remote = remoteMap[url]

            when {
                local != null && remote != null -> {
                    val localClearedAt = effectiveClearedAt(manga, local)
                    val remoteClearedAt = effectiveClearedAt(manga, remote)

                    // SY -->
                    // The history channel decides the same question on its own file, so the comparison
                    // is shared: a value arriving through either path is resolved the same way.
                    val localWins = MergeRules.historyLocalWins(
                        localReadAtMillis = local.lastRead,
                        localClearedAt = localClearedAt,
                        localReadDuration = local.readDuration,
                        remoteReadAtMillis = remote.lastRead,
                        remoteClearedAt = remoteClearedAt,
                        remoteReadDuration = remote.readDuration,
                    )
                    // SY <--
                    val winner = if (localWins) local else remote
                    val winnerClearedAt = if (localWins) localClearedAt else remoteClearedAt

                    if (winnerClearedAt > 0L) winner.asCleared(winnerClearedAt) else winner.asLive()
                }

                // Only one side has it. A vanished row is deliberately not treated as a removal, so
                // the surviving entry is simply kept.
                local != null -> local.asLive()
                else -> remote?.asLive()
            }
        }
    }

    /**
     * Timestamp of the clearing for [entry], or 0 when it still holds a reading date.
     *
     * A cleared entry either already carries a timestamp (it came from another device) or was
     * cleared here since the last sync. The ledger decides which: still holding a reading time means
     * the clearing is fresh and needs a *new* timestamp, while an already-zero reading time means it
     * was cleared earlier and the existing stamp must be reused to keep the remote file stable.
     */
    private fun effectiveClearedAt(manga: BackupManga, entry: BackupHistory): Long {
        if (entry.lastRead > 0L) return 0L
        if (entry.clearedAt > 0L) return entry.clearedAt

        val key = SyncLedger.historyKey(manga.source, manga.url, entry.url)
        return if ((ledger[key] ?: 0L) > 0L) {
            logcat(LogPriority.DEBUG, "MergeHistory") {
                "Reading date cleared since the last sync: ${entry.url}"
            }
            SyncClock.next(context, key)
        } else {
            SyncClock.stamp(context, key)
        }
    }

    private fun BackupHistory.asCleared(clearedAt: Long): BackupHistory {
        lastRead = 0L
        this.clearedAt = clearedAt
        return this
    }

    private fun BackupHistory.asLive(): BackupHistory {
        clearedAt = 0L
        return this
    }
    // SY <--

    private fun mergeCategoriesLists(
        localCategoriesList: List<BackupCategory>?,
        remoteCategoriesList: List<BackupCategory>?,
        localSyncs: Boolean,
        remoteSyncs: Boolean,
    ): List<BackupCategory> {
        val logTag = "MergeCategories"
        if (localCategoriesList == null) return remoteCategoriesList ?: emptyList()
        if (remoteCategoriesList == null) return localCategoriesList

        val localByName = localCategoriesList.associateBy { it.name }
        val remoteByName = remoteCategoriesList.associateBy { it.name }

        val merged = linkedMapOf<String, BackupCategory>()

        (localByName.keys + remoteByName.keys).forEach { name ->
            val local = localByName[name]
            val remote = remoteByName[name]
            when {
                local != null && remote != null -> {
                    if (local.deletedAt > 0L || remote.deletedAt > 0L) {
                        val winner = when {
                            local.deletedAt > 0L && remote.deletedAt > 0L ->
                                if (local.deletedAt >= remote.deletedAt) local else remote
                            local.deletedAt > 0L ->
                                if (remote.lastModifiedAt > local.deletedAt) remote else local
                            else ->
                                if (local.lastModifiedAt > remote.deletedAt) local else remote
                        }
                        merged[name] = if (winner.deletedAt > 0L) winner.asCategoryTombstone() else winner
                    } else {
                        // Order and flags follow the version, which is the field the editor bumps
                        val preferLocal = local.version >= remote.version
                        val base = if (preferLocal) local else remote
                        base.version = maxOf(local.version, remote.version)
                        base.lastModifiedAt = maxOf(local.lastModifiedAt, remote.lastModifiedAt)
                        // A category keeps its local uid so the restore matches it by identity
                        base.uid = local.uid.takeIf { it != 0L } ?: remote.uid
                        merged[name] = base
                    }
                }

                local != null -> {
                    if (remoteSyncs && SyncLedger.categoryKey(name) in ledger) {
                        logcat(LogPriority.DEBUG, logTag) { "Tombstoning category deleted remotely: $name" }
                        merged[name] = local.asCategoryTombstone()
                    } else {
                        merged[name] = local
                    }
                }

                else -> {
                    remote?.let {
                        SyncClock.observe(context, SyncLedger.categoryKey(name), it.lastModifiedAt)
                        if (localSyncs && SyncLedger.categoryKey(name) in ledger) {
                            logcat(LogPriority.DEBUG, logTag) { "Tombstoning category deleted locally: $name" }
                            merged[name] = it.asCategoryTombstone()
                        } else {
                            merged[name] = it
                        }
                    }
                }
            }
        }

        return merged.values.sortedBy { it.order }
    }

    private fun BackupCategory.asCategoryTombstone(): BackupCategory {
        val tombstone = BackupCategory(name = name, order = order, id = id, uid = uid)
        tombstone.deletedAt = deletedAt.takeIf { it > 0L } ?: SyncClock.next(context, SyncLedger.categoryKey(name))
        return tombstone
    }

    private fun mergeBookmarksLists(
        localBookmarks: List<BackupBookmark>?,
        remoteBookmarks: List<BackupBookmark>?,
        localSyncs: Boolean,
        remoteSyncs: Boolean,
    ): List<BackupBookmark> {
        // SY -->
        // The rules live in MergeRules because the bookmark channel applies the very same ones to its
        // own file; a second copy here would be free to drift away from it.
        return MergeRules.mergeBookmarks(
            localBookmarks = localBookmarks,
            remoteBookmarks = remoteBookmarks,
            localSyncs = localSyncs,
            remoteSyncs = remoteSyncs,
            ledger = ledger,
            context = context,
        )
        // SY <--
    }
    // SY <--

    private fun mergeSourcesLists(
        localSources: List<BackupSource>?,
        remoteSources: List<BackupSource>?,
    ): List<BackupSource> {
        val logTag = "MergeSources"
        val localSourceMap = localSources?.associateBy { it.sourceId } ?: emptyMap()
        val remoteSourceMap = remoteSources?.associateBy { it.sourceId } ?: emptyMap()

        val mergedSources = (localSourceMap.keys + remoteSourceMap.keys).distinct().mapNotNull { sourceId ->
            val localSource = localSourceMap[sourceId]
            val remoteSource = remoteSourceMap[sourceId]

            logcat(LogPriority.DEBUG, logTag) {
                "Processing source ID: $sourceId. Local source: ${localSource != null}, " +
                    "Remote source: ${remoteSource != null}"
            }

            when {
                localSource != null && remoteSource == null -> {
                    logcat(LogPriority.DEBUG, logTag) { "Using local source: ${localSource.name}." }
                    localSource
                }
                remoteSource != null && localSource == null -> {
                    logcat(LogPriority.DEBUG, logTag) { "Using remote source: ${remoteSource.name}." }
                    remoteSource
                }
                else -> {
                    logcat(LogPriority.DEBUG, logTag) { "Remote and local have the same source ID: $sourceId. Keeping local." }
                    localSource
                }
            }
        }

        logcat(LogPriority.DEBUG, logTag) { "Source merge completed. Total merged sources: ${mergedSources.size}" }

        return mergedSources
    }

    private fun mergePreferencesLists(
        localPreferences: List<BackupPreference>?,
        remotePreferences: List<BackupPreference>?,
    ): List<BackupPreference> {
        val logTag = "MergePreferences"
        val localPreferencesMap = localPreferences?.associateBy { it.key } ?: emptyMap()
        val remotePreferencesMap = remotePreferences?.associateBy { it.key } ?: emptyMap()

        val mergedPreferences = (localPreferencesMap.keys + remotePreferencesMap.keys).distinct().mapNotNull { key ->
            val localPreference = localPreferencesMap[key]
            val remotePreference = remotePreferencesMap[key]

            logcat(LogPriority.DEBUG, logTag) {
                "Processing preference key: $key. Local preference: ${localPreference != null}, " +
                    "Remote preference: ${remotePreference != null}"
            }

            when {
                localPreference != null && remotePreference == null -> {
                    logcat(LogPriority.DEBUG, logTag) { "Using local preference: ${localPreference.key}." }
                    localPreference
                }
                remotePreference != null && localPreference == null -> {
                    logcat(LogPriority.DEBUG, logTag) { "Using remote preference: ${remotePreference.key}." }
                    remotePreference
                }
                else -> {
                    logcat(LogPriority.DEBUG, logTag) { "Both remote and local have the same preference key: $key. Keeping local." }
                    localPreference
                }
            }
        }

        logcat(LogPriority.DEBUG, logTag) {
            "Preferences merge completed. Total merged preferences: ${mergedPreferences.size}"
        }

        return mergedPreferences
    }

    private fun mergeSourcePreferencesLists(
        localPreferences: List<BackupSourcePreferences>?,
        remotePreferences: List<BackupSourcePreferences>?,
    ): List<BackupSourcePreferences> {
        val logTag = "MergeSourcePreferences"
        val localPreferencesMap = localPreferences?.associateBy { it.sourceKey } ?: emptyMap()
        val remotePreferencesMap = remotePreferences?.associateBy { it.sourceKey } ?: emptyMap()

        val mergedSourcePreferences = (localPreferencesMap.keys + remotePreferencesMap.keys).distinct()
            .mapNotNull { sourceKey ->
                val localSourcePreference = localPreferencesMap[sourceKey]
                val remoteSourcePreference = remotePreferencesMap[sourceKey]

                when {
                    localSourcePreference != null && remoteSourcePreference == null -> localSourcePreference
                    remoteSourcePreference != null && localSourcePreference == null -> remoteSourcePreference
                    localSourcePreference != null && remoteSourcePreference != null -> {
                        val mergedPrefs =
                            mergeIndividualPreferences(localSourcePreference.prefs, remoteSourcePreference.prefs)
                        BackupSourcePreferences(sourceKey, mergedPrefs)
                    }
                    else -> null
                }
            }

        logcat(LogPriority.DEBUG, logTag) {
            "Source preferences merge completed. Total merged source preferences: ${mergedSourcePreferences.size}"
        }

        return mergedSourcePreferences
    }

    private fun mergeIndividualPreferences(
        localPrefs: List<BackupPreference>,
        remotePrefs: List<BackupPreference>,
    ): List<BackupPreference> {
        val mergedPrefsMap = (localPrefs + remotePrefs).associateBy { it.key }
        return mergedPrefsMap.values.toList()
    }

    // SY -->
    private fun mergeSavedSearchesLists(
        localSearches: List<BackupSavedSearch>?,
        remoteSearches: List<BackupSavedSearch>?,
    ): List<BackupSavedSearch> {
        val logTag = "MergeSavedSearches"

        fun searchCompositeKey(search: BackupSavedSearch): String {
            return "${search.name}|${search.source}"
        }

        val localSearchMap = localSearches?.associateBy { searchCompositeKey(it) } ?: emptyMap()
        val remoteSearchMap = remoteSearches?.associateBy { searchCompositeKey(it) } ?: emptyMap()

        val mergedSearches = (localSearchMap.keys + remoteSearchMap.keys).distinct().mapNotNull { compositeKey ->
            localSearchMap[compositeKey] ?: remoteSearchMap[compositeKey]
        }

        logcat(LogPriority.DEBUG, logTag) {
            "Saved searches merge completed. Total merged saved searches: ${mergedSearches.size}"
        }

        return mergedSearches
    }
    // SY <--
}
