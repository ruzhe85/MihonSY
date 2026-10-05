package tachiyomi.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterMemo

class ShouldUpdateDbChapter {

    fun await(dbChapter: Chapter, sourceChapter: Chapter): Boolean {
        return dbChapter.scanlator != sourceChapter.scanlator ||
            dbChapter.name != sourceChapter.name ||
            dbChapter.dateUpload != sourceChapter.dateUpload ||
            dbChapter.chapterNumber != sourceChapter.chapterNumber ||
            dbChapter.sourceOrder != sourceChapter.sourceOrder ||
            // SY -->
            // Only the source's own memo keys count as a change; the keys this app keeps there (the
            // recorded page count) would otherwise look like a change on every single library update.
            ChapterMemo.withoutAppKeys(dbChapter.memo) != ChapterMemo.withoutAppKeys(sourceChapter.memo)
            // SY <--
    }
}
