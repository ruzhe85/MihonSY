package eu.kanade.presentation.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.tachiyomi.ui.bookmarks.BookmarksScreenModel
import eu.kanade.tachiyomi.util.lang.toTimestampString
import tachiyomi.domain.chapter.model.BookmarkItem
import tachiyomi.domain.manga.model.MangaCover as MangaCoverData
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.util.Date

private val BookmarkItemHeight = 96.dp

// SY --> Komiho: 书签 tab 列表页（跨作品按页书签，仿 HistoryScreen）。
@Composable
fun BookmarksScreen(
    state: BookmarksScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String?) -> Unit,
    onClickBookmark: (BookmarkItem) -> Unit,
    onClickDelete: (BookmarkItem) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                titleContent = { AppBarTitle(stringResource(MR.strings.label_bookmarks)) },
                searchQuery = state.searchQuery,
                onChangeSearchQuery = onSearchQueryChange,
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        val bookmarks = state.filteredBookmarks
        if (bookmarks == null) {
            LoadingScreen(Modifier.padding(contentPadding))
        } else if (bookmarks.isEmpty()) {
            EmptyScreen(
                stringRes = if (!state.searchQuery.isNullOrEmpty()) {
                    MR.strings.no_results_found
                } else {
                    MR.strings.bookmarks_empty
                },
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            BookmarksScreenContent(
                bookmarks = bookmarks,
                contentPadding = contentPadding,
                onClickBookmark = onClickBookmark,
                onClickDelete = onClickDelete,
            )
        }
    }
}

@Composable
private fun BookmarksScreenContent(
    bookmarks: List<BookmarkItem>,
    contentPadding: PaddingValues,
    onClickBookmark: (BookmarkItem) -> Unit,
    onClickDelete: (BookmarkItem) -> Unit,
) {
    FastScrollLazyColumn(
        contentPadding = contentPadding,
    ) {
        items(
            items = bookmarks,
            key = { "bookmark-${it.id}" },
            contentType = { "bookmark" },
        ) { bookmark ->
            BookmarkItemRow(
                modifier = Modifier.animateItemFastScroll(),
                bookmark = bookmark,
                onClick = { onClickBookmark(bookmark) },
                onClickDelete = { onClickDelete(bookmark) },
            )
        }
    }
}

@Composable
private fun BookmarkItemRow(
    bookmark: BookmarkItem,
    onClick: () -> Unit,
    onClickDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .height(BookmarkItemHeight)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.fillMaxHeight(),
            data = MangaCoverData(
                mangaId = bookmark.mangaId,
                sourceId = bookmark.sourceId,
                isMangaFavorite = bookmark.mangaFavorite,
                ogUrl = bookmark.thumbnailUrl,
                lastModified = 0L,
            ),
            onClick = onClick,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
        ) {
            val textStyle = MaterialTheme.typography.bodyMedium
            Text(
                text = bookmark.mangaTitle,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = textStyle,
            )
            Text(
                text = bookmark.chapterName,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = textStyle,
            )
            val createdAt = remember(bookmark.createdAt) {
                Date(bookmark.createdAt).toTimestampString()
            }
            Text(
                text = listOf(
                    stringResource(MR.strings.reader_bookmark_page, bookmark.page + 1),
                    createdAt,
                ).filter { it.isNotBlank() }.joinToString(" · "),
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = textStyle,
            )
        }

        IconButton(onClick = onClickDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.reader_bookmark_remove),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
// SY <--
