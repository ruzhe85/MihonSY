package eu.kanade.tachiyomi.data.sync.models

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.sy.SYMR

// SY -->
/**
 * What this device does at each moment the user asked it to sync.
 *
 * Reading data (progress, history, bookmarks) travels in its own channels and the library travels in
 * the full payload, so a switch says which of the two it acts on instead of "sync something". Full
 * syncs are not held back by the sync interval: ticking one means it runs every time that moment
 * happens, which is what the label promises.
 */
data class SyncTriggerOptions(
    /** Leaving a chapter: push progress, history and bookmarks right away. */
    val syncOnChapterRead: Boolean = false,
    /** Opening a chapter: pull progress and bookmarks right away. */
    val syncOnChapterOpen: Boolean = false,
    /** App start: pull history and bookmarks. */
    val syncOnAppStart: Boolean = false,
    /** Coming back to the app: pull history and bookmarks. */
    val syncOnAppResume: Boolean = false,
    /** App start: run a full sync as well. */
    val fullSyncOnAppStart: Boolean = false,
    /** Coming back to the app: run a full sync as well. */
    val fullSyncOnAppResume: Boolean = false,
    /** Opening the library: run a full sync. */
    val syncOnLibrary: Boolean = false,
) {
    // SY <--

    companion object {
        /** One row, one switch: these moments have nothing to choose between. */
        val switchOptions = listOf(
            Entry(
                label = SYMR.strings.sync_trigger_chapter_open,
                getter = SyncTriggerOptions::syncOnChapterOpen,
                setter = { options, enabled -> options.copy(syncOnChapterOpen = enabled) },
            ),
            Entry(
                label = SYMR.strings.sync_trigger_chapter_read,
                getter = SyncTriggerOptions::syncOnChapterRead,
                setter = { options, enabled -> options.copy(syncOnChapterRead = enabled) },
            ),
            Entry(
                label = SYMR.strings.sync_trigger_library,
                getter = SyncTriggerOptions::syncOnLibrary,
                setter = { options, enabled -> options.copy(syncOnLibrary = enabled) },
            ),
        )

        /**
         * Moments whose options exclude each other, so each one is a single choice.
         *
         * A full sync already carries history and bookmarks, which makes it and "history and
         * bookmarks" alternatives rather than additions — picking one has to clear the other. The
         * third option is what switching the moment off means now that the options cannot simply be
         * left unticked, and it is the state a fresh install starts in.
         */
        val singleChoiceGroups = listOf(
            group(
                label = SYMR.strings.sync_trigger_app_start,
                children = listOf(
                    Entry(
                        label = SYMR.strings.sync_trigger_history_bookmarks,
                        getter = SyncTriggerOptions::syncOnAppStart,
                        setter = { options, enabled ->
                            options.copy(
                                syncOnAppStart = enabled,
                                fullSyncOnAppStart = if (enabled) false else options.fullSyncOnAppStart,
                            )
                        },
                    ),
                    Entry(
                        label = SYMR.strings.sync_trigger_full_sync,
                        getter = SyncTriggerOptions::fullSyncOnAppStart,
                        setter = { options, enabled ->
                            options.copy(
                                fullSyncOnAppStart = enabled,
                                syncOnAppStart = if (enabled) false else options.syncOnAppStart,
                            )
                        },
                    ),
                    Entry(
                        label = SYMR.strings.sync_trigger_do_nothing,
                        getter = { !it.syncOnAppStart && !it.fullSyncOnAppStart },
                        setter = { options, selected ->
                            if (selected) {
                                options.copy(syncOnAppStart = false, fullSyncOnAppStart = false)
                            } else {
                                options
                            }
                        },
                    ),
                ),
            ),
            group(
                label = SYMR.strings.sync_trigger_app_resume,
                children = listOf(
                    Entry(
                        label = SYMR.strings.sync_trigger_history_bookmarks,
                        getter = SyncTriggerOptions::syncOnAppResume,
                        setter = { options, enabled ->
                            options.copy(
                                syncOnAppResume = enabled,
                                fullSyncOnAppResume = if (enabled) false else options.fullSyncOnAppResume,
                            )
                        },
                    ),
                    Entry(
                        label = SYMR.strings.sync_trigger_full_sync,
                        getter = SyncTriggerOptions::fullSyncOnAppResume,
                        setter = { options, enabled ->
                            options.copy(
                                fullSyncOnAppResume = enabled,
                                syncOnAppResume = if (enabled) false else options.syncOnAppResume,
                            )
                        },
                    ),
                    Entry(
                        label = SYMR.strings.sync_trigger_do_nothing,
                        getter = { !it.syncOnAppResume && !it.fullSyncOnAppResume },
                        setter = { options, selected ->
                            if (selected) {
                                options.copy(syncOnAppResume = false, fullSyncOnAppResume = false)
                            } else {
                                options
                            }
                        },
                    ),
                ),
            ),
        )

        /** A group titles the options of one moment; it carries no toggle of its own. */
        private fun group(label: StringResource, children: List<Entry>) = Entry(
            label = label,
            getter = { false },
            setter = { options, _ -> options },
            children = children,
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (SyncTriggerOptions) -> Boolean,
        val setter: (SyncTriggerOptions, Boolean) -> SyncTriggerOptions,
        val enabled: (SyncTriggerOptions) -> Boolean = { true },
        // SY -->
        /** Empty for a plain switch; the options it titles for a group. */
        val children: List<Entry> = emptyList(),
        // SY <--
    )
}
