package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

class SyncNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    // SY -->
    private val syncPreferences: eu.kanade.domain.sync.SyncPreferences by injectLazy()
    // SY <--

    // SY -->
    // Same reasoning as BackupNotifier: the builder is recreated per call because it is not
    // thread-safe and a sync restore updates this notification from several coroutines at once.
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

    // Notifications are a side channel: a failure to post one must never abort a sync.
    private fun NotificationCompat.Builder.show(id: Int) {
        try {
            context.notify(id, build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to post sync notification $id" }
        }
    }
    // SY <--

    fun showSyncProgress(content: String = "", progress: Int = 0, maxAmount: Int = 100): NotificationCompat.Builder {
        val builder = progressBuilder().apply {
            setContentTitle(context.getString(R.string.syncing_library))

            if (!preferences.hideNotificationContent.get()) {
                setContentText(content)
            }

            setProgress(maxAmount, progress, true)
            setOnlyAlertOnce(true)

            clearActions()
            addAction(
                R.drawable.ic_close_24dp,
                context.getString(R.string.action_cancel),
                NotificationReceiver.cancelSyncPendingBroadcast(context, Notifications.ID_RESTORE_PROGRESS),
            )
        }

        builder.show(Notifications.ID_RESTORE_PROGRESS)

        return builder
    }

    fun showSyncError(error: String?) {
        context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)

        with(completeBuilder()) {
            setContentTitle(context.getString(R.string.sync_error))
            setContentText(error)

            show(Notifications.ID_RESTORE_COMPLETE)
        }
    }

    fun showSyncSuccess(message: String?) {
        context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)

        // SY -->
        if (!syncPreferences.syncShowSuccessNotification.get()) return
        // SY <--

        with(completeBuilder()) {
            setContentTitle(context.getString(R.string.sync_complete))
            setContentText(message)

            show(Notifications.ID_RESTORE_COMPLETE)
        }
    }
}
