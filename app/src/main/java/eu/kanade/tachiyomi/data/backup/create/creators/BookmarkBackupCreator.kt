package eu.kanade.tachiyomi.data.backup.create.creators

import app.cash.sqldelight.async.coroutines.awaitAsList
import eu.kanade.tachiyomi.data.backup.models.BackupBookmark
import eu.kanade.tachiyomi.data.backup.models.backupBookmarkMapper
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BookmarkBackupCreator(
    private val database: Database = Injekt.get(),
) {

    suspend operator fun invoke(): List<BackupBookmark> {
        return database.bookmarksQueries
            .bookmarksForBackup(backupBookmarkMapper)
            .awaitAsList()
    }
}
