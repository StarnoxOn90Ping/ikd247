package ani.dantotsu.others.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Every way a preload gets started goes through here, always as WorkManager work, so it survives
 * the app being closed and is retried (and resumed, see [OfflinePreloadEngine]) when interrupted.
 *
 *  - [runNow]: the "Preload now" button and the automatic run after a settings change. Unique
 *    work (KEEP), so pressing it twice never starts two runs. Needs any network, or Wi-Fi when
 *    "Wi-Fi only" is on.
 *  - Auto-refresh: a periodic job (about once a day) that only runs on an unmetered network
 *    (Wi-Fi) WHILE CHARGING and with the battery not low. Ignores nothing: the same incremental
 *    engine runs, so a day with no changes sends almost no requests.
 *  - [onAppStart]: re-applies the auto-refresh schedule and continues a run that was cut short
 *    (only if nothing is queued, and not within [RESUME_COOLDOWN_MS] of the last interruption).
 */
object OfflinePreloadScheduler {
    const val WORK_NOW = "ani.dantotsu.offline.preload.now"
    const val WORK_AUTO = "ani.dantotsu.offline.preload.auto"
    const val KEY_MANUAL = "manual"

    private const val AUTO_INTERVAL_HOURS = 24L
    private const val RESUME_COOLDOWN_MS = 15L * 60 * 1000

    /** Queues a run. [manual] runs show a result notification when they finish. */
    fun runNow(context: Context, manual: Boolean = true): Boolean {
        if (!OfflineCache.isEnabled()) return false
        val network = if (OfflineCache.wifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<OfflinePreloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_MANUAL to manual))
            .build()
        val wm = WorkManager.getInstance(context.applicationContext)
        val active = isActive(wm, WORK_NOW)
        wm.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
        return !active
    }

    /** True while a run is queued or running. Call off the main thread. */
    fun isRunQueuedOrRunning(context: Context): Boolean =
        isActive(WorkManager.getInstance(context.applicationContext), WORK_NOW)

    private fun isActive(wm: WorkManager, name: String): Boolean = runCatching {
        wm.getWorkInfosForUniqueWork(name).get().any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }
    }.getOrDefault(false)

    /** Schedules or cancels the daily Wi-Fi + charging refresh to match the settings. */
    fun applyAutoRefresh(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!OfflineCache.isEnabled() || !OfflineCache.autoRefresh()) {
            wm.cancelUniqueWork(WORK_AUTO)
            return
        }
        val request = PeriodicWorkRequestBuilder<OfflinePreloadWorker>(AUTO_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_MANUAL to false))
            .build()
        wm.enqueueUniquePeriodicWork(WORK_AUTO, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Feature turned off: stop everything that is queued or running. */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(WORK_NOW)
        wm.cancelUniqueWork(WORK_AUTO)
    }

    /** Called once at app start (background thread). */
    fun onAppStart(context: Context) {
        if (!OfflineCache.isEnabled()) {
            cancelAll(context)
            return
        }
        applyAutoRefresh(context)
        val state = OfflineCache.getJobState()
        val unfinished = state.status == OfflineJobStatus.RUNNING || state.status == OfflineJobStatus.INTERRUPTED
        val cooledDown = System.currentTimeMillis() - state.updatedAt > RESUME_COOLDOWN_MS
        val wm = WorkManager.getInstance(context.applicationContext)
        if (unfinished && cooledDown && !isActive(wm, WORK_NOW)) runNow(context, manual = false)
    }
}
