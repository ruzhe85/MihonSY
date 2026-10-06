package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class SyncDataJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val notifier = SyncNotifier(context)

    override suspend fun doWork(): Result {
        if (tags.contains(TAG_AUTO)) {
            if (!context.isOnline()) {
                return Result.retry()
            }
            // Find a running manual worker. If exists, try again later
            if (context.workManager.isRunning(TAG_MANUAL)) {
                return Result.retry()
            }
            // SY -->
            // Only the timer stands down when this period already had a full sync: it shares one
            // anchor with the switched moments, and without this it would fire on its own schedule
            // right after one of them and double the frequency the user asked for. A switched moment
            // asked for its run explicitly, so it is never held back.
            if (tags.contains(TAG_TIMER) && !isFullSyncDue()) {
                logcat(LogPriority.DEBUG) { "Skipping scheduled sync: still within the sync frequency" }
                return Result.success()
            }
            // SY <--
        }

        // SY -->
        // A silent sync does not enter a foreground service: that service exists to carry the
        // notification, and WorkManager would post it even if the notifier stayed quiet. The trade-off
        // is that the run falls under the background execution limit instead of being protected from
        // it, which is acceptable because a sync is conditional writes and can simply be retried.
        if (Injekt.get<SyncPreferences>().syncNotificationsEnabled()) {
            setForegroundSafely()
        }
        // SY <--

        return try {
            SyncManager(context).syncData()
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            notifier.showSyncError(e.message)
            Result.success() // try again next time
        } finally {
            context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_RESTORE_PROGRESS,
            notifier.showSyncProgress().build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG_JOB = "SyncDataJob"
        private const val TAG_AUTO = "$TAG_JOB:auto"
        const val TAG_MANUAL = "$TAG_JOB:manual"

        // SY -->
        /** Marks the periodic run, the only one the sync frequency may hold back. */
        private const val TAG_TIMER = "$TAG_JOB:timer"

        /**
         * Unique name of the run a switched moment queues.
         *
         * It must not be the timer's own name: WorkManager keeps a single work per unique name, and
         * periodic work never completes, so sharing that name would make every switched full sync
         * disappear as soon as a sync frequency is set. The tag stays shared, so stopping or observing
         * sync still covers both kinds of run.
         */
        private const val WORK_EVENT = "$TAG_JOB:event"
        // SY <--

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG_JOB)
        }

        // SY -->
        /**
         * True when the timer's run has something left to add under the synchronization frequency.
         *
         * The timer and the switched moments share one anchor, and a full sync that already happened
         * inside this period means this run would only repeat it. The switched moments and the manual
         * button never ask here: ticking one of them means the sync happens that time.
         *
         * A frequency of 0 means the timer is off entirely, and a device that never completed a sync
         * has nothing to compare against, so neither is ever held back.
         */
        fun isFullSyncDue(): Boolean {
            val syncPreferences = Injekt.get<SyncPreferences>()

            val interval = syncPreferences.syncInterval.get()
            if (interval <= 0) return true

            val last = syncPreferences.syncLastCompletedAt.get()
            // 0 means no full sync ever completed on this device, which must not be held back
            return last <= 0L || System.currentTimeMillis() - last >= interval.toLong() * 60_000L
        }
        // SY <--

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val syncPreferences = Injekt.get<SyncPreferences>()
            val interval = prefInterval ?: syncPreferences.syncInterval.get()

            if (interval > 0) {
                val request = PeriodicWorkRequestBuilder<SyncDataJob>(
                    interval.toLong(),
                    TimeUnit.MINUTES,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG_JOB)
                    .addTag(TAG_AUTO)
                    .addTag(TAG_TIMER)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(TAG_AUTO, ExistingPeriodicWorkPolicy.UPDATE, request)
            } else {
                context.workManager.cancelUniqueWork(TAG_AUTO)
            }
        }

        fun startNow(context: Context, manual: Boolean = false) {
            val wm = context.workManager
            if (wm.isRunning(TAG_JOB)) {
                // Already running either as a scheduled or manual job
                return
            }
            val uniqueName = if (manual) TAG_MANUAL else WORK_EVENT
            val tag = if (manual) TAG_MANUAL else TAG_AUTO
            val request = OneTimeWorkRequestBuilder<SyncDataJob>()
                .addTag(TAG_JOB)
                .addTag(tag)
                .build()
            context.workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            val wm = context.workManager
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG_JOB, TAG_AUTO, TAG_MANUAL))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                // Should only return one work but just in case
                .forEach {
                    wm.cancelWorkById(it.id)

                    // Re-enqueue cancelled scheduled work
                    if (it.tags.contains(TAG_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
