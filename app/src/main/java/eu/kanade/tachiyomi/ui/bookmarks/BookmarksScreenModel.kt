package eu.kanade.tachiyomi.ui.bookmarks

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
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

    init {
        refresh()
    }

    fun refresh() {
        screenModelScope.launchIO {
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
