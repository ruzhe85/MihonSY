package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences

// SY -->
import eu.kanade.domain.sync.SyncPreferences
// SY <--

import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.TimeUnit

class BackupNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    // SY -->
    private val syncPreferences: SyncPreferences by injectLazy()
    // SY <--

    // SY -->
    // The icon is decoded once, but the builder never is: a NotificationCompat.Builder is not
    // thread-safe. A restore updates the same notification from every coroutine of its concurrent
    // block *and* from WorkManager's foreground thread, so a shared instance let clearActions() /
    // addAction() mutate the action list while another thread iterated it in build(), which threw
    // a ConcurrentModificationException and aborted the whole restore.
    private val largeIcon: Bitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    private fun progressBuilder(): NotificationCompat.Builder = context.notificationBuilder(
        Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS,
    ) {
        setLargeIcon(largeIcon)
        setSmallIcon(R.drawable.ic_tachi)
        setAutoCancel(false)
        setOngoing(true)
        setOnlyAlertOnce(true)
    }

    private fun completeBuilder(): NotificationCompat.Builder = context.notificationBuilder(
        Notifications.CHANNEL_BACKUP_RESTORE_COMPLETE,
    ) {
        setLargeIcon(largeIcon)
        setSmallIcon(R.drawable.ic_tachi)
        setAutoCancel(false)
    }
    // SY <--

    // SY -->
    // Notifications are a side channel: failing to post one must never abort a backup or a restore
    // that is already in progress.
    private fun NotificationCompat.Builder.show(id: Int) {
        try {
            context.notify(id, build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to post backup notification $id" }
        }
    }
    // SY <--

    fun showBackupProgress(): NotificationCompat.Builder {
        val builder = progressBuilder().apply {
            setContentTitle(context.stringResource(MR.strings.creating_backup))

            setProgress(0, 0, true)
        }

        builder.show(Notifications.ID_BACKUP_PROGRESS)

        return builder
    }

    fun showBackupError(error: String?) {
        context.cancelNotification(Notifications.ID_BACKUP_PROGRESS)

        with(completeBuilder()) {
            setContentTitle(context.stringResource(MR.strings.creating_backup_error))
            setContentText(error)

            show(Notifications.ID_BACKUP_COMPLETE)
        }
    }

    fun showBackupComplete(file: UniFile) {
        context.cancelNotification(Notifications.ID_BACKUP_PROGRESS)

        with(completeBuilder()) {
            setContentTitle(context.stringResource(MR.strings.backup_created))
            setContentText(file.displayablePath)

            clearActions()
            addAction(
                R.drawable.ic_share_24dp,
                context.stringResource(MR.strings.action_share),
                NotificationReceiver.shareBackupPendingActivity(context, file.uri),
            )

            show(Notifications.ID_BACKUP_COMPLETE)
        }
    }

    fun showRestoreProgress(
        content: String = "",
        progress: Int = 0,
        maxAmount: Int = 100,
        sync: Boolean = false,
    ): NotificationCompat.Builder {
        val builder = progressBuilder().apply {
            val contentTitle = if (sync) {
                context.stringResource(MR.strings.syncing_library)
            } else {
                context.stringResource(MR.strings.restoring_backup)
            }
            setContentTitle(contentTitle)

            if (!preferences.hideNotificationContent.get()) {
                setContentText(content)
            }

            setProgress(maxAmount, progress, false)
            setOnlyAlertOnce(true)

            clearActions()
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel),
                NotificationReceiver.cancelRestorePendingBroadcast(context, Notifications.ID_RESTORE_PROGRESS),
            )
        }

        // SY -->
        // A restore that is part of a sync follows the sync notification setting and stays silent
        // with it; a manual restore always shows its progress, cancel button included.
        if (!sync || syncPreferences.syncNotificationsEnabled()) {
            builder.show(Notifications.ID_RESTORE_PROGRESS)
        }
        // SY <--

        return builder
    }

    fun showRestoreError(error: String?, logFile: File? = null) {
        context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)

        with(completeBuilder()) {
            setContentTitle(context.stringResource(MR.strings.restoring_backup_error))
            setContentText(error)

            // SY -->
            // The fatal error is also written to a log file; without this the user is told the
            // restore failed but has no way to see why.
            if (logFile != null && logFile.exists()) {
                val uri = logFile.getUriCompat(context)
                val errorLogIntent = NotificationReceiver.openErrorLogPendingActivity(context, uri)
                setContentIntent(errorLogIntent)
                addAction(
                    R.drawable.ic_folder_24dp,
                    context.stringResource(MR.strings.action_show_errors),
                    errorLogIntent,
                )
            }
            // SY <--

            show(Notifications.ID_RESTORE_COMPLETE)
        }
    }

    fun showRestoreComplete(
        time: Long,
        errorCount: Int,
        path: String?,
        file: String?,
        sync: Boolean,
    ) {
        val contentTitle = if (sync) {
            context.stringResource(MR.strings.library_sync_complete)
        } else {
            context.stringResource(MR.strings.restore_completed)
        }

        context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)

        // SY -->
        // The "library sync complete" notification at the end of a sync restore
        // honors the same toggle as the SyncNotifier success notification
        if (sync && !syncPreferences.syncNotificationsEnabled()) return
        // SY <--

        val timeString = context.stringResource(
            MR.strings.restore_duration,
            TimeUnit.MILLISECONDS.toMinutes(time),
            TimeUnit.MILLISECONDS.toSeconds(time) - TimeUnit.MINUTES.toSeconds(
                TimeUnit.MILLISECONDS.toMinutes(time),
            ),
        )

        with(completeBuilder()) {
            setContentTitle(contentTitle)
            setContentText(
                context.pluralStringResource(
                    MR.plurals.restore_completed_message,
                    errorCount,
                    timeString,
                    errorCount,
                ),
            )

            clearActions()
            if (errorCount > 0 && !path.isNullOrEmpty() && !file.isNullOrEmpty()) {
                val destFile = File(path, file)
                val uri = destFile.getUriCompat(context)

                val errorLogIntent = NotificationReceiver.openErrorLogPendingActivity(context, uri)
                setContentIntent(errorLogIntent)
                addAction(
                    R.drawable.ic_folder_24dp,
                    context.stringResource(MR.strings.action_show_errors),
                    errorLogIntent,
                )
            }

            show(Notifications.ID_RESTORE_COMPLETE)
        }
    }
}
