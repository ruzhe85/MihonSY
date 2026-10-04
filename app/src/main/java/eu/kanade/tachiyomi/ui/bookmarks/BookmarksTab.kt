package eu.kanade.tachiyomi.ui.bookmarks

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.bookmarks.BookmarksScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// SY --> Komiho: 底部「书签」tab，跨作品按页书签列表（仿 HistoryTab）。
data object BookmarksTab : Tab {

    private val snackbarHostState = SnackbarHostState()

    private val refreshEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_bookmark_enter)
            return TabOptions(
                index = 5u,
                title = stringResource(MR.strings.label_bookmarks),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    // SY -->
    @Composable
    override fun isEnabled(): Boolean {
        val scope = rememberCoroutineScope()
        return remember {
            Injekt.get<UiPreferences>().showNavBookmarks.asState(scope)
        }.value
    }
    // SY <--

    override suspend fun onReselect(navigator: Navigator) {
        // 再点一次 tab 时刷新列表，覆盖从阅读器返回后新增/删除的书签。
        refreshEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val screenModel = rememberScreenModel { BookmarksScreenModel() }
        val state by screenModel.state.collectAsState()

        BookmarksScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onSearchQueryChange = screenModel::updateSearchQuery,
            onClickBookmark = { bookmark ->
                context.startActivity(
                    ReaderActivity.newIntent(
                        context,
                        bookmark.mangaId,
                        bookmark.chapterId,
                        bookmark.page,
                    ),
                )
            },
            onClickDelete = { bookmark -> screenModel.setDialog(BookmarksScreenModel.Dialog.Delete(bookmark)) },
        )

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BookmarksScreenModel.Dialog.Delete -> {
                AlertDialog(
                    title = { Text(text = stringResource(MR.strings.reader_bookmark_remove)) },
                    text = { Text(text = stringResource(MR.strings.bookmarks_delete_confirm)) },
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = {
                            screenModel.deleteBookmark(dialog.bookmark)
                        }) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            BookmarksScreenModel.Dialog.InternalError -> {}
            null -> {}
        }

        LaunchedEffect(state.dialog) {
            if (state.dialog is BookmarksScreenModel.Dialog.InternalError) {
                snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                screenModel.setDialog(null)
            }
        }

        LaunchedEffect(screenModel.events) {
            screenModel.events.collect { e ->
                when (e) {
                    BookmarksScreenModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                }
            }
        }

        LaunchedEffect(state.allBookmarks) {
            if (state.allBookmarks != null) {
                (context as? MainActivity)?.ready = true
            }
        }

        LaunchedEffect(Unit) {
            refreshEvent.receiveAsFlow().collectLatest {
                screenModel.refresh()
            }
        }
    }
}
// SY <--
