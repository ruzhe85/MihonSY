package eu.kanade.tachiyomi.data.download

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.lang.chop
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.util.regex.Pattern

/**
 * DownloadNotifier is used to show notifications when downloading one or multiple chapters.
 *
 * @param context context of application
 */
internal class DownloadNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    // SY -->
    // A NotificationCompat.Builder is not thread-safe, and the downloader runs several sources in
    // parallel, all reporting their progress through this single notifier. Reusing one instance
    // meant one coroutine could rewrite the action list while another was building the
    // notification, so the builder is recreated per call and only the (read-only) icon is shared.
    private val largeIcon: Bitmap by lazy {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    }

    private fun progressBuilder(): NotificationCompat.Builder = context.notificationBuilder(
        Notifications.CHANNEL_DOWNLOADER_PROGRESS,
    ) {
        setLargeIcon(largeIcon)
        setAutoCancel(false)
        setOnlyAlertOnce(true)
    }

    private fun errorBuilder(): NotificationCompat.Builder = context.notificationBuilder(
        Notifications.CHANNEL_DOWNLOADER_ERROR,
    ) {
        setAutoCancel(false)
    }

    /**
     * Shows a notification from this builder.
     *
     * @param id the id of the notification.
     */
    private fun NotificationCompat.Builder.show(id: Int) {
        try {
            context.notify(id, build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Downloads must not fail because a notification could not be posted
            logcat(LogPriority.ERROR, e) { "Failed to post download notification $id" }
        }
    }
    // SY <--

    /**
     * Dismiss the downloader's notification. Downloader error notifications use a different id, so
     * those can only be dismissed by the user.
     */
    fun dismissProgress() {
        context.cancelNotification(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS)
    }

    /**
     * Called when download progress changes.
     *
     * @param download download object containing download information.
     */
    fun onProgressChange(download: Download) {
        // SY -->
        val builder = progressBuilder().apply {
            setSmallIcon(android.R.drawable.stat_sys_download)
            // Open download manager when clicked
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            // Pause action
            addAction(
                R.drawable.ic_pause_24dp,
                context.stringResource(MR.strings.action_pause),
                NotificationReceiver.pauseDownloadsPendingBroadcast(context),
            )
            addAction(
                R.drawable.ic_book_24dp,
                context.stringResource(MR.strings.action_show_manga),
                NotificationReceiver.openEntryPendingActivity(context, download.manga.id),
            )

            val downloadingProgressText = context.stringResource(
                MR.strings.chapter_downloading_progress,
                download.downloadedImages,
                download.pages!!.size,
            )

            if (preferences.hideNotificationContent.get()) {
                setContentTitle(downloadingProgressText)
                setContentText(null)
            } else {
                val title = download.manga.title.chop(15)
                val quotedTitle = Pattern.quote(title)
                val chapter = download.chapter.name.replaceFirst(
                    "$quotedTitle[\\s]*[-]*[\\s]*".toRegex(RegexOption.IGNORE_CASE),
                    "",
                )
                setContentTitle("$title - $chapter".chop(30))
                setContentText(downloadingProgressText)
            }

            setProgress(download.pages!!.size, download.downloadedImages, false)
            setOngoing(true)
        }

        builder.show(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS)
        // SY <--
    }

    /**
     * Show notification when download is paused.
     */
    fun onPaused() {
        // SY -->
        val builder = progressBuilder().apply {
            setContentTitle(context.stringResource(MR.strings.chapter_paused))
            setContentText(context.stringResource(MR.strings.download_notifier_download_paused))
            setSmallIcon(R.drawable.ic_pause_24dp)
            setProgress(0, 0, false)
            setOngoing(false)
            // Open download manager when clicked
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            // Resume action
            addAction(
                R.drawable.ic_play_arrow_24dp,
                context.stringResource(MR.strings.action_resume),
                NotificationReceiver.resumeDownloadsPendingBroadcast(context),
            )
            // Clear action
            addAction(
                R.drawable.ic_close_24dp,
                context.stringResource(MR.strings.action_cancel_all),
                NotificationReceiver.clearDownloadsPendingBroadcast(context),
            )
        }

        builder.show(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS)
        // SY <--
    }

    /**
     * Resets the state once downloads are completed.
     */
    fun onComplete() {
        dismissProgress()
    }

    /**
     * Called when the downloader receives a warning.
     *
     * @param reason the text to show.
     * @param timeout duration after which to automatically dismiss the notification.
     * @param mangaId the id of the entry being warned about
     * Only works on Android 8+.
     */
    fun onWarning(reason: String, timeout: Long? = null, contentIntent: PendingIntent? = null, mangaId: Long? = null) {
        // SY -->
        val builder = errorBuilder().apply {
            setContentTitle(context.stringResource(MR.strings.download_notifier_downloader_title))
            setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setAutoCancel(true)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            if (mangaId != null) {
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openEntryPendingActivity(context, mangaId),
                )
            }
            setProgress(0, 0, false)
            timeout?.let { setTimeoutAfter(it) }
            contentIntent?.let { setContentIntent(it) }
        }

        builder.show(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)
        // SY <--
    }

    /**
     * Called when the downloader receives an error. It's shown as a separate notification to avoid
     * being overwritten.
     *
     * @param error string containing error information.
     * @param chapter string containing chapter title.
     * @param mangaId the id of the entry that the error occurred on
     */
    fun onError(error: String? = null, chapter: String? = null, mangaTitle: String? = null, mangaId: Long? = null) {
        // Create notification
        // SY -->
        val builder = errorBuilder().apply {
            setContentTitle(
                mangaTitle?.plus(": $chapter") ?: context.stringResource(MR.strings.download_notifier_downloader_title),
            )
            setContentText(error ?: context.stringResource(MR.strings.download_notifier_unknown_error))
            setSmallIcon(R.drawable.ic_warning_white_24dp)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            if (mangaId != null) {
                addAction(
                    R.drawable.ic_book_24dp,
                    context.stringResource(MR.strings.action_show_manga),
                    NotificationReceiver.openEntryPendingActivity(context, mangaId),
                )
            }
            setProgress(0, 0, false)
        }

        builder.show(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)
        // SY <--
    }
}
