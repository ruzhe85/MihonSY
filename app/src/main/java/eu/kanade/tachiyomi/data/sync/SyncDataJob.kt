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
            // The timer and the event triggers share one anchor: when a full sync already happened
            // inside this period there is nothing left for this run to add. Without the check the
            // timer would still fire on its own schedule right after an event-triggered run and
            // double the frequency the user asked for.
            if (!isFullSyncDue()) {
                logcat(LogPriority.DEBUG) { "Skipping scheduled sync: still within the sync frequency" }
                return Result.success()
            }
            // SY <--
        }

        setForegroundSafely()

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

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG_JOB)
        }

        // SY -->
        /**
         * True when a full sync is allowed to run under the synchronization frequency.
         *
         * That frequency is reused as the minimum interval between full syncs, so raising it also
         * calms down the event triggers instead of only the timer. Manual runs never ask here.
         *
         * Everything is throttled only for WebDAV: it is the only service with the lightweight
         * channels, and holding a full sync back on the others would leave those triggers with
         * nothing to do at all. A frequency of 0 keeps the previous behaviour too — the user turned
         * the timer off, not the triggers.
         */
        fun isFullSyncDue(): Boolean {
            val syncPreferences = Injekt.get<SyncPreferences>()
            if (!isWebdav(syncPreferences)) return true

            val interval = syncPreferences.syncInterval.get()
            if (interval <= 0) return true

            val last = syncPreferences.syncLastCompletedAt.get()
            // 0 means no full sync ever completed on this device, which must not be held back
            return last <= 0L || System.currentTimeMillis() - last >= interval.toLong() * 60_000L
        }

        /**
         * Runs a full sync for an automatic trigger, unless the sync frequency says it is too early.
         *
         * Returns true when the sync was queued, so a caller holding a pending change can drop it:
         * anything that stayed throttled has to keep waiting for the next opportunity.
         */
        fun startIfDue(context: Context): Boolean {
            val syncPreferences = Injekt.get<SyncPreferences>()
            if (!syncPreferences.isSyncEnabled()) return false

            // A run that is already in flight may have been built before the change happened
            if (isRunning(context)) return false

            if (!isFullSyncDue()) {
                logcat(LogPriority.DEBUG) { "Holding the full sync back: still within the sync frequency" }
                return false
            }

            startNow(context)
            return true
        }

        private fun isWebdav(syncPreferences: SyncPreferences): Boolean =
            SyncManager.SyncService.fromInt(syncPreferences.syncService.get()) == SyncManager.SyncService.WEBDAV
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
            val tag = if (manual) TAG_MANUAL else TAG_AUTO
            val request = OneTimeWorkRequestBuilder<SyncDataJob>()
                .addTag(TAG_JOB)
                .addTag(tag)
                .build()
            context.workManager.enqueueUniqueWork(tag, ExistingWorkPolicy.KEEP, request)
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
