package eu.kanade.tachiyomi.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

// SY --> Komiho: 阅读器内「按页书签」列表对话框：在当前页加书签、跳转到某页、删除书签。
@Composable
fun ReaderBookmarksDialog(
    viewModel: ReaderViewModel,
    onJump: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var reloadKey by remember { mutableStateOf(0) }
    val bookmarks by produceState(initialValue = emptyList<BookmarkEntry>(), key1 = reloadKey) {
        value = runCatching { viewModel.getBookmarksForCurrentChapter() }
            .getOrDefault(emptyList())
            .map { BookmarkEntry(it.id, it.page) }
    }
    val currentPage = viewModel.state.value.currentPage

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {},
        title = { Text(stringResource(R.string.reader_bookmarks_title)) },
        text = {
            Column {
                TextButton(
                    onClick = {
                        viewModel.addBookmarkAtCurrentPage()
                        reloadKey++
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.reader_bookmark_add_current, currentPage))
                }
                LazyColumn {
                    items(bookmarks, key = { it.id }) { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onJump(bm.page)
                                    onDismissRequest()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Filled.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.reader_bookmark_page, bm.page + 1),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    viewModel.removeBookmark(bm.id)
                                    reloadKey++
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.reader_bookmark_remove),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

// SY <--

private data class BookmarkEntry(val id: Long, val page: Int)
