package eu.kanade.tachiyomi.util.chapter

import eu.kanade.domain.chapter.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.manga.ChapterList
import exh.source.isEhBasedManga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.model.Manga

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<Chapter>.getNextUnread(
    manga: Manga,
    downloadManager: DownloadManager /* SY --> */,
    mergedManga: Map<Long, Manga>, /* SY <-- */
): Chapter? {
    return applyFilters(manga, downloadManager/* SY --> */, mergedManga/* SY <-- */).let { chapters ->
        // SY -->
        if (manga.isEhBasedManga()) {
            return@let if (manga.sortDescending()) {
                chapters.firstOrNull()?.takeUnless { it.read }
            } else {
                chapters.lastOrNull()?.takeUnless { it.read }
            }
        }
        // SY <--
        if (manga.sortDescending()) {
            chapters.findLast { !it.read }
        } else {
            chapters.find { !it.read }
        }
    }
}

/**
 * Komiho: 「继续阅读」应该打开的那一章。
 *
 * 与 [getNextUnread]（= 最早未读）的区别：它**先看阅读历史**。
 * - 有历史且历史章**没读完** → 回到该章（读者会按 `last_page_read` 续页）；
 * - 有历史且历史章**已标记读完** → 取阅读方向上紧随其后的下一章（没有下一章则回到它自己）；
 * - 没有历史 / 历史章已不在章节列表里（换源、删章）→ 退回 [getNextUnread]。
 *
 * 为什么要单独一个函数：`getNextUnread` 的「最早未读」语义还被下载等路径复用，不能为「继续」改它。
 *
 * ⚠️ [lastReadChapterId] 必须在**未过滤**的 `this` 上找 —— [applyFilters] 会按漫画的 unreadFilter
 * 把**已读**章节整章剔除（`ChapterFilter.kt` 的 `applyFilter(unreadFilter) { !chapter.read }`），
 * 而历史章通常正是已读章；若在过滤后的列表里查，开着「只显示未读」的用户永远查不到，会静默退化成
 * 「最早未读」。
 */
fun List<Chapter>.getContinueChapter(
    manga: Manga,
    downloadManager: DownloadManager,
    mergedManga: Map<Long, Manga>,
    lastReadChapterId: Long?,
): Chapter? {
    val historyChapter = lastReadChapterId?.let { id -> find { it.id == id } }
    if (historyChapter != null) {
        if (!historyChapter.read) return historyChapter

        // 已读完 → 阅读方向上的下一章。方向刻意取「与 getNextUnread 取用方向一致」而不是另立一套：
        // getNextUnread 在 sortDescending 时用 findLast（从列表尾部往回找「最早未读」）、EH 特例则是
        // 「取列表另一侧的最未读」—— 两者都说明此时「往后读」= 列表里往前走，所以后继取前一项；
        // sortDescending=false 时反过来。这样「继续」与既有的「下一未读」在四种排序下方向自洽。
        val ordered = sortedWith(getChapterSort(manga))
        val index = ordered.indexOfFirst { it.id == historyChapter.id }
        val next = if (manga.sortDescending()) {
            ordered.getOrNull(index - 1)
        } else {
            ordered.getOrNull(index + 1)
        }
        return next ?: historyChapter
    }

    return getNextUnread(manga, downloadManager, mergedManga)
}

/**
 * Gets next unread chapter with filters and sorting applied
 */
fun List<ChapterList.Item>.getNextUnread(manga: Manga): Chapter? {
    return applyFilters(manga).let { chapters ->
        // SY -->
        if (manga.isEhBasedManga()) {
            return@let if (manga.sortDescending()) {
                chapters.firstOrNull()?.takeUnless { it.chapter.read }
            } else {
                chapters.lastOrNull()?.takeUnless { it.chapter.read }
            }
        }
        // SY <--
        if (manga.sortDescending()) {
            chapters.findLast { !it.chapter.read }
        } else {
            chapters.find { !it.chapter.read }
        }
    }?.chapter
}
