package eu.kanade.tachiyomi.util.system

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.seconds

val Context.workManager: WorkManager
    get() = WorkManager.getInstance(this)

fun WorkManager.isRunning(tag: String): Boolean {
    val list = this.getWorkInfosByTag(tag).get()
    return list.any { it.state == WorkInfo.State.RUNNING }
}

/**
 * Makes this worker run in the context of a foreground service.
 *
 * Note that this function is a no-op if the process is subject to foreground
 * service restrictions.
 *
 * Moving to foreground service context requires the worker to run a bit longer,
 * allowing Service.startForeground() to be called and avoiding system crash.
 */
suspend fun CoroutineWorker.setForegroundSafely() {
    try {
        setForeground(getForegroundInfo())
        delay(0.5.seconds)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Recent Android versions refuse the transition in more ways than one: a background start,
        // a type the service does not declare, a permission that is missing. None of them says
        // anything about the work itself, so the worker keeps running without the service instead of
        // failing before it starts.
        logcat(LogPriority.ERROR, e) { "Not allowed to set foreground job" }
    }
}
