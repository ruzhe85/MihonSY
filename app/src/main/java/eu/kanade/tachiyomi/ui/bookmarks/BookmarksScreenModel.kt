package eu.kanade.tachiyomi.ui.bookmarks

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.sync.BookmarkSyncManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.BookmarkItem
import tachiyomi.domain.chapter.repository.BookmarkRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// SY --> Komiho: 书签 tab 的 ScreenModel，一次加载全部按页书签，搜索在内存过滤。
class BookmarksScreenModel(
    private val bookmarkRepository: BookmarkRepository = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
) : StateScreenModel<BookmarksScreenModel.State>(State()) {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    // SY -->
    /** Lightweight bookmark channel, so a bookmark edited elsewhere shows up without a full sync. */
    private val bookmarkSyncManager: BookmarkSyncManager by lazy {
        BookmarkSyncManager(Injekt.get<Application>())
    }
    // SY <--

    init {
        refresh()
    }

    fun refresh() {
        screenModelScope.launchIO {
            // SY -->
            // This screen reads its list once instead of subscribing to the database, so the remote
            // bookmarks have to be pulled and applied before the list is built.
            bookmarkSyncManager.pullAndApply()
            // SY <--
            val bookmarks = runCatching { bookmarkRepository.getAllBookmarks() }
                .onFailure { logcat(LogPriority.ERROR, it) }
                .getOrNull()
            mutableState.update {
                it.copy(
                    allBookmarks = bookmarks,
                    dialog = if (bookmarks == null) Dialog.InternalError else it.dialog,
                )
            }
        }
    }

    fun deleteBookmark(bookmark: BookmarkItem) {
        screenModelScope.launchIO {
            // SY -->
            // Recorded before the row disappears: a tombstone is the only way to state that this
            // bookmark was deleted rather than simply never seen here.
            bookmarkSyncManager.recordLocalDeletion(
                source = bookmark.sourceId,
                mangaUrl = bookmark.mangaUrl,
                chapterUrl = bookmark.chapterUrl,
                page = bookmark.page,
            )
            // SY <--
            runCatching { bookmarkRepository.removeBookmark(bookmark.id) }
                .onFailure {
                    logcat(LogPriority.ERROR, it)
                    _events.send(Event.InternalError)
                }
            mutableState.update {
                it.copy(
                    allBookmarks = it.allBookmarks?.filterNot { bm -> bm.id == bookmark.id },
                    dialog = null,
                )
            }
        }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    @Immutable
    data class State(
        val allBookmarks: List<BookmarkItem>? = null,
        val searchQuery: String? = null,
        val dialog: Dialog? = null,
    ) {
        val filteredBookmarks: List<BookmarkItem>?
            get() = allBookmarks?.let { all ->
                val query = searchQuery?.trim()?.lowercase()
                if (query.isNullOrEmpty()) {
                    all
                } else {
                    all.filter { bm ->
                        bm.mangaTitle.lowercase().contains(query) || bm.chapterName.lowercase().contains(query)
                    }
                }
            }
    }

    sealed interface Dialog {
        data class Delete(val bookmark: BookmarkItem) : Dialog
        data object InternalError : Dialog
    }

    sealed interface Event {
        data object InternalError : Event
    }
}
// SY <--
