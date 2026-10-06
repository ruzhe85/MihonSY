package eu.kanade.domain.sync

import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.sync.models.SyncTriggerOptions
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.util.UUID

class SyncPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val clientHost: Preference<String> = preferenceStore.getString("sync_client_host", "https://sync.tachiyomi.org")
    val clientAPIKey: Preference<String> = preferenceStore.getString("sync_client_api_key", "")
    val lastSyncTimestamp: Preference<Long> = preferenceStore.getLong(Preference.appStateKey("last_sync_timestamp"), 0L)

    val lastSyncEtag: Preference<String> = preferenceStore.getString("sync_etag", "")

    val syncInterval: Preference<Int> = preferenceStore.getInt("sync_interval", 0)
    val syncService: Preference<Int> = preferenceStore.getInt("sync_service", 0)

    val googleDriveAccessToken: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("google_drive_access_token"),
        "",
    )

    val googleDriveRefreshToken: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("google_drive_refresh_token"),
        "",
    )

    // SY -->
    val webdavUrl: Preference<String> = preferenceStore.getString("sync_webdav_url", "")
    val webdavUsername: Preference<String> = preferenceStore.getString("sync_webdav_username", "")
    val webdavPassword: Preference<String> = preferenceStore.getString("sync_webdav_password", "")
    val webdavTrustAllCerts: Preference<Boolean> = preferenceStore.getBoolean("sync_webdav_trust_all", false)
    /**
     * Whether sync may use notifications at all.
     *
     * Off by default: the progress notification belongs to a foreground service, so having it on
     * means every sync posts a notification that is cancelled again at the end, which is what fills
     * the system notification history with entries the user never wanted. Turning it on is for
     * watching a sync run, and for the cancel button it carries.
     *
     * Stored as app state rather than as an ordinary preference: this describes what one device
     * shows, not data worth carrying elsewhere. As an ordinary preference it travelled inside the
     * "app settings" section, so a device with notifications off could have them turned back on by a
     * device that still had them on — the opposite of what turning the switch off promises. The
     * trade-off is that a restored backup no longer carries the switch, which is the intent.
     */
    val syncShowSuccessNotification: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("sync_show_success_notification"), false)

    /**
     * Whether sync may notify, which also decides whether it may become a foreground service.
     *
     * The two have to agree: the foreground service is what carries the notification, so a run that
     * is meant to be silent must not enter it either, or WorkManager posts the notification for us.
     */
    fun syncNotificationsEnabled(): Boolean = syncShowSuccessNotification.get()

    /**
     * Sync sections the other devices are using, as seen during the last sync. Non-empty and
     * different from the local selection means the devices do not agree on what to sync, which
     * makes merges unpredictable. Only reported to the user, never applied automatically.
     */
    val remoteSyncSettings: Preference<String> = preferenceStore.getString("sync_remote_settings", "")

    /**
     * Remote-only entries the last full sync had to apply locally. 0 means both sides already
     * agreed; the initial -1 means no sync has completed yet.
     */
    val syncLastAppliedCount: Preference<Int> = preferenceStore.getInt("sync_last_applied_count", -1)

    /** Epoch millis of the last completed full sync, 0 when it never ran. */
    val syncLastCompletedAt: Preference<Long> = preferenceStore.getLong("sync_last_completed_at", 0L)
    // SY <--

    // SY -->
    /**
     * Section selection as a fixed-width flag string, in the order [BackupOptions.asBooleanArray]
     * defines, which covers every section. Reusing that order means a newly added sync section can
     * no longer silently drop out of this encoding (an earlier hand-written list missed four).
     */
    fun encodeSyncSettings(settings: SyncSettings): String =
        settings.toBackupOptions().asBooleanArray().joinToString(",") { if (it) "1" else "0" }

    fun decodeSyncSettings(value: String): SyncSettings? {
        if (value.isBlank()) return null

        val defaults = BackupOptions().asBooleanArray()
        val flags = value.split(",").map { it.trim() == "1" }

        // Payloads written before the encoding covered every section carry fewer flags; a short tail
        // falls back to the defaults instead of failing to parse.
        val filled = BooleanArray(defaults.size) { index ->
            flags.getOrElse(index) { defaults[index] }
        }
        return BackupOptions.fromBooleanArray(filled).toSyncSettings()
    }

    /**
     * Indexes of [BackupOptions.asBooleanArray] that belong to the library group, derived from the
     * very list the settings screen renders, so a new section cannot be classified wrongly.
     */
    private val librarySectionIndices: Set<Int> by lazy {
        val defaults = BackupOptions().asBooleanArray()
        defaults.indices.filterTo(mutableSetOf()) { index ->
            val probe = BackupOptions.fromBooleanArray(
                BooleanArray(defaults.size) { it == index },
            )
            BackupOptions.libraryOptions.any { it.getter(probe) }
        }
    }

    /**
     * Library sections are unioned: a device that missed a checkbox must not silently drop data the
     * others still keep. Settings sections follow this device's own selection.
     */
    fun mergeSyncSections(local: SyncSettings, remote: SyncSettings): SyncSettings {
        val localFlags = local.toBackupOptions().asBooleanArray()
        val remoteFlags = remote.toBackupOptions().asBooleanArray()

        val merged = BooleanArray(localFlags.size) { index ->
            if (index in librarySectionIndices) {
                localFlags[index] || remoteFlags[index]
            } else {
                localFlags[index]
            }
        }
        return BackupOptions.fromBooleanArray(merged).toSyncSettings()
    }

    /** True when the two selections disagree on a settings section (library ones are unioned). */
    fun settingsSectionsDiffer(local: SyncSettings, remote: SyncSettings): Boolean {
        val localFlags = local.toBackupOptions().asBooleanArray()
        val remoteFlags = remote.toBackupOptions().asBooleanArray()
        return localFlags.indices.any { index ->
            index !in librarySectionIndices && localFlags[index] != remoteFlags[index]
        }
    }
    // SY <--

    fun uniqueDeviceID(): String {
        val uniqueIDPreference = preferenceStore.getString(Preference.appStateKey("unique_device_id"), "")

        // Retrieve the current value of the preference
        var uniqueID = uniqueIDPreference.get()
        if (uniqueID.isBlank()) {
            uniqueID = UUID.randomUUID().toString()
            uniqueIDPreference.set(uniqueID)
        }

        return uniqueID
    }

    fun isSyncEnabled(): Boolean {
        return syncService.get() != 0
    }

    fun getSyncSettings(): SyncSettings {
        return SyncSettings(
            libraryEntries = preferenceStore.getBoolean("library_entries", true).get(),
            categories = preferenceStore.getBoolean("categories", true).get(),
            chapters = preferenceStore.getBoolean("chapters", true).get(),
            tracking = preferenceStore.getBoolean("tracking", true).get(),
            history = preferenceStore.getBoolean("history", true).get(),
            appSettings = preferenceStore.getBoolean("appSettings", true).get(),
            extensionStores = preferenceStore.getBoolean("extensionRepoSettings", true).get(),
            sourceSettings = preferenceStore.getBoolean("sourceSettings", true).get(),
            privateSettings = preferenceStore.getBoolean("privateSettings", true).get(),

            // SY -->
            customInfo = preferenceStore.getBoolean("customInfo", true).get(),
            readEntries = preferenceStore.getBoolean("readEntries", true).get(),
            savedSearches = preferenceStore.getBoolean("savedSearches", true).get(),
            bookmarks = preferenceStore.getBoolean("bookmarks", true).get(),
            // SY <--
        )
    }

    fun setSyncSettings(syncSettings: SyncSettings) {
        preferenceStore.getBoolean("library_entries", true).set(syncSettings.libraryEntries)
        preferenceStore.getBoolean("categories", true).set(syncSettings.categories)
        preferenceStore.getBoolean("chapters", true).set(syncSettings.chapters)
        preferenceStore.getBoolean("tracking", true).set(syncSettings.tracking)
        preferenceStore.getBoolean("history", true).set(syncSettings.history)
        preferenceStore.getBoolean("appSettings", true).set(syncSettings.appSettings)
        preferenceStore.getBoolean("extensionRepoSettings", true).set(syncSettings.extensionStores)
        preferenceStore.getBoolean("sourceSettings", true).set(syncSettings.sourceSettings)
        preferenceStore.getBoolean("privateSettings", true).set(syncSettings.privateSettings)

        // SY -->
        preferenceStore.getBoolean("customInfo", true).set(syncSettings.customInfo)
        preferenceStore.getBoolean("readEntries", true).set(syncSettings.readEntries)
        preferenceStore.getBoolean("savedSearches", true).set(syncSettings.savedSearches)
        preferenceStore.getBoolean("bookmarks", true).set(syncSettings.bookmarks)
        // SY <--
    }

    // SY -->
    companion object {
        /**
         * Pseudo preference key carrying the sync section selection inside the synced payload.
         * Lives here because both the sync manager and the merge need it and the manager has its
         * own nested `SyncService` enum that would shadow the service class of the same name.
         */
        const val SYNC_SETTINGS_KEY = "__sync_settings__"
    }
    // SY <--

    fun getSyncTriggerOptions(): SyncTriggerOptions {
        // SY -->
        // Before the app moments were split into "reading data" and "full sync", their one switch
        // meant "run a full sync here". The new pair falls back to it so those users keep syncing the
        // library the way they asked for, which makes an upgraded device read both as on. A full sync
        // already carries history and bookmarks, so that state is reported as the full sync alone: it
        // is what the page shows and what the moment does. Nothing is written back here — the first
        // click is what stores the normalised pair.
        val fullOnAppStart = preferenceStore
            .getBoolean("sync_full_on_app_start", preferenceStore.getBoolean("sync_on_app_start", false).get())
            .get()
        val fullOnAppResume = preferenceStore
            .getBoolean("sync_full_on_app_resume", preferenceStore.getBoolean("sync_on_app_resume", false).get())
            .get()
        // SY <--

        return SyncTriggerOptions(
            syncOnChapterRead = preferenceStore.getBoolean("sync_on_chapter_read", false).get(),
            syncOnChapterOpen = preferenceStore.getBoolean("sync_on_chapter_open", false).get(),
            syncOnAppStart = !fullOnAppStart && preferenceStore.getBoolean("sync_on_app_start", false).get(),
            syncOnAppResume = !fullOnAppResume && preferenceStore.getBoolean("sync_on_app_resume", false).get(),
            // SY -->
            fullSyncOnAppStart = fullOnAppStart,
            fullSyncOnAppResume = fullOnAppResume,
            // SY <--
            syncOnLibrary = preferenceStore.getBoolean("sync_on_library", false).get(),
        )
    }

    fun setSyncTriggerOptions(syncTriggerOptions: SyncTriggerOptions) {
        preferenceStore.getBoolean("sync_on_chapter_read", false)
            .set(syncTriggerOptions.syncOnChapterRead)
        preferenceStore.getBoolean("sync_on_chapter_open", false)
            .set(syncTriggerOptions.syncOnChapterOpen)
        preferenceStore.getBoolean("sync_on_app_start", false)
            .set(syncTriggerOptions.syncOnAppStart)
        preferenceStore.getBoolean("sync_on_app_resume", false)
            .set(syncTriggerOptions.syncOnAppResume)
        // SY -->
        preferenceStore.getBoolean("sync_full_on_app_start", false)
            .set(syncTriggerOptions.fullSyncOnAppStart)
        preferenceStore.getBoolean("sync_full_on_app_resume", false)
            .set(syncTriggerOptions.fullSyncOnAppResume)
        preferenceStore.getBoolean("sync_on_library", false)
            .set(syncTriggerOptions.syncOnLibrary)
        // SY <--
    }
}
// SY -->

/**
 * The two types carry the same thirteen flags; these conversions exist so the sync section encoding
 * can lean on [BackupOptions.asBooleanArray] instead of maintaining a parallel list of field names.
 */
fun SyncSettings.toBackupOptions(): BackupOptions = BackupOptions(
    libraryEntries = libraryEntries,
    categories = categories,
    chapters = chapters,
    tracking = tracking,
    history = history,
    readEntries = readEntries,
    appSettings = appSettings,
    extensionStores = extensionStores,
    sourceSettings = sourceSettings,
    privateSettings = privateSettings,
    customInfo = customInfo,
    savedSearches = savedSearches,
    bookmarks = bookmarks,
)

fun BackupOptions.toSyncSettings(): SyncSettings = SyncSettings(
    libraryEntries = libraryEntries,
    categories = categories,
    chapters = chapters,
    tracking = tracking,
    history = history,
    appSettings = appSettings,
    extensionStores = extensionStores,
    sourceSettings = sourceSettings,
    privateSettings = privateSettings,
    customInfo = customInfo,
    readEntries = readEntries,
    savedSearches = savedSearches,
    bookmarks = bookmarks,
)
// SY <--
