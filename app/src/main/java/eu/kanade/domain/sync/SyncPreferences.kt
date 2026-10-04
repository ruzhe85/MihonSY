package eu.kanade.domain.sync

import eu.kanade.domain.sync.models.SyncSettings
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
    val syncShowSuccessNotification: Preference<Boolean> =
        preferenceStore.getBoolean("sync_show_success_notification", true)

    /**
     * Sync sections the other devices are using, as seen during the last sync. Non-empty and
     * different from the local selection means the devices do not agree on what to sync, which
     * makes merges unpredictable. Only reported to the user, never applied automatically.
     */
    val remoteSyncSettings: Preference<String> = preferenceStore.getString("sync_remote_settings", "")
    // SY <--

    // SY -->
    fun encodeSyncSettings(settings: SyncSettings): String = listOf(
        settings.libraryEntries,
        settings.categories,
        settings.chapters,
        settings.tracking,
        settings.history,
        settings.appSettings,
        settings.sourceSettings,
        settings.savedSearches,
        settings.bookmarks,
    ).joinToString(",") { if (it) "1" else "0" }

    fun decodeSyncSettings(value: String): SyncSettings? {
        val parts = value.split(",")
        if (parts.size != 9) return null
        val flags = parts.map { it.trim() == "1" }
        return SyncSettings(
            libraryEntries = flags[0],
            categories = flags[1],
            chapters = flags[2],
            tracking = flags[3],
            history = flags[4],
            appSettings = flags[5],
            sourceSettings = flags[6],
            savedSearches = flags[7],
            bookmarks = flags[8],
        )
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

    fun getSyncTriggerOptions(): SyncTriggerOptions {
        return SyncTriggerOptions(
            syncOnChapterRead = preferenceStore.getBoolean("sync_on_chapter_read", false).get(),
            syncOnChapterOpen = preferenceStore.getBoolean("sync_on_chapter_open", false).get(),
            syncOnAppStart = preferenceStore.getBoolean("sync_on_app_start", false).get(),
            syncOnAppResume = preferenceStore.getBoolean("sync_on_app_resume", false).get(),
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
    }
}
