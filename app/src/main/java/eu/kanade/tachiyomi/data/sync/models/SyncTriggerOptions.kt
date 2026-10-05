package eu.kanade.tachiyomi.data.sync.models

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.sy.SYMR

data class SyncTriggerOptions(
    val syncOnChapterRead: Boolean = false,
    val syncOnChapterOpen: Boolean = false,
    val syncOnAppStart: Boolean = false,
    val syncOnAppResume: Boolean = false,
) {
    fun asBooleanArray() = booleanArrayOf(
        syncOnChapterRead,
        syncOnChapterOpen,
        syncOnAppStart,
        syncOnAppResume,
    )

    fun anyEnabled() = syncOnChapterRead ||
        syncOnChapterOpen ||
        syncOnAppStart ||
        syncOnAppResume

    companion object {
        /**
         * The labels say what each option actually does: reading data travels in its own channels and
         * is not controlled by these switches, while the full sync follows the sync frequency. The
         * subtitles are what make that difference visible to the user.
         */
        val mainOptions = listOf(
            Entry(
                label = SYMR.strings.sync_trigger_chapter_read,
                subtitle = SYMR.strings.sync_trigger_chapter_read_summ,
                getter = SyncTriggerOptions::syncOnChapterRead,
                setter = { options, enabled -> options.copy(syncOnChapterRead = enabled) },
            ),
            Entry(
                label = SYMR.strings.sync_trigger_chapter_open,
                subtitle = SYMR.strings.sync_trigger_chapter_open_summ,
                getter = SyncTriggerOptions::syncOnChapterOpen,
                setter = { options, enabled -> options.copy(syncOnChapterOpen = enabled) },
            ),
            Entry(
                label = SYMR.strings.sync_trigger_app_start,
                subtitle = SYMR.strings.sync_trigger_app_start_summ,
                getter = SyncTriggerOptions::syncOnAppStart,
                setter = { options, enabled -> options.copy(syncOnAppStart = enabled) },
            ),
            Entry(
                label = SYMR.strings.sync_trigger_app_resume,
                subtitle = SYMR.strings.sync_trigger_app_resume_summ,
                getter = SyncTriggerOptions::syncOnAppResume,
                setter = { options, enabled -> options.copy(syncOnAppResume = enabled) },
            ),
        )

        fun fromBooleanArray(array: BooleanArray) = SyncTriggerOptions(
            syncOnChapterRead = array[0],
            syncOnChapterOpen = array[1],
            syncOnAppStart = array[2],
            syncOnAppResume = array[3],
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (SyncTriggerOptions) -> Boolean,
        val setter: (SyncTriggerOptions, Boolean) -> SyncTriggerOptions,
        val enabled: (SyncTriggerOptions) -> Boolean = { true },
        // SY -->
        val subtitle: StringResource? = null,
        // SY <--
    )
}
