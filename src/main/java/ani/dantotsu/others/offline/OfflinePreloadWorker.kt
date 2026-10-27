package ani.dantotsu.others.offline

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.R
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger

/**
 * Runs [OfflinePreloadEngine] as a foreground worker (progress notification with a Stop button),
 * so Android does not cut it off after the usual ~10 minutes of background work.
 *
 * Results map to WorkManager like this:
 *  - Completed / NotReady / Busy -> success.
 *  - Interrupted (rate limit, no network ...) -> retry with exponential backoff. The engine has
 *    already saved its progress, so the retry continues where it stopped. After [MAX_ATTEMPTS]
 *    the work ends; the next trigger (button, auto-refresh, app start) still resumes.
 *  - Stopped by the user or the system -> the engine marks the run interrupted before this
 *    coroutine ends, so nothing is lost.
 */
class OfflinePreloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val manual get() = inputData.getBoolean(OfflinePreloadScheduler.KEY_MANUAL, false)

    override suspend fun doWork(): Result {
        PrefManager.init(applicationContext)
        OfflineCache.init(applicationContext)
        if (!OfflineCache.isEnabled()) return Result.success()

        ensureChannel(applicationContext)
        runCatching { setForeground(progressInfo(applicationContext, id, applicationContext.getString(R.string.offline_notif_phase_lists), 0, 0)) }
            .onFailure { Logger.log("OfflinePreloadWorker: no foreground: ${it.message}") }

        var lastUpdate = 0L
        val outcome = OfflinePreloadEngine.run(applicationContext) { p ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate < 1000 && p.done != p.total) return@run // at most ~1 update/second
            lastUpdate = now
            val info = progressInfo(applicationContext, id, phaseText(applicationContext, p), p.done, p.total)
            notificationManager(applicationContext).notify(NOTIF_PROGRESS, info.notification)
        }

        return when (outcome) {
            is OfflineRunOutcome.Completed -> {
                if (manual) {
                    val s = outcome.stats
                    notifyResult(
                        applicationContext.getString(R.string.offline_notif_done_title),
                        applicationContext.getString(
                            R.string.offline_notif_done_text,
                            s.titles, s.detailsFetched, s.episodeInfoFetched, s.imagesDownloaded
                        )
                    )
                }
                Result.success()
            }
            is OfflineRunOutcome.Interrupted -> {
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    notifyResult(
                        applicationContext.getString(R.string.offline_notif_paused_title),
                        applicationContext.getString(R.string.offline_notif_paused_text, outcome.reason)
                    )
                    Result.failure()
                } else Result.retry()
            }
            is OfflineRunOutcome.NotReady, OfflineRunOutcome.Busy -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel(applicationContext)
        return progressInfo(applicationContext, id, applicationContext.getString(R.string.offline_notif_phase_lists), 0, 0)
    }

    private fun notifyResult(title: String, text: String) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_round_sync_24)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager(applicationContext).notify(NOTIF_RESULT, n)
    }

    companion object {
        const val CHANNEL_ID = "offline_preload"
        private const val NOTIF_PROGRESS = 8841
        private const val NOTIF_RESULT = 8842
        private const val MAX_ATTEMPTS = 6

        private fun notificationManager(c: Context) =
            c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager(c).createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, c.getString(R.string.offline_notif_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        fun phaseText(c: Context, p: OfflineProgress): String = when (p.phase) {
            OfflinePhase.LISTS -> c.getString(R.string.offline_notif_phase_lists)
            OfflinePhase.DETAILS -> c.getString(R.string.offline_notif_phase_details, p.done, p.total)
            OfflinePhase.EPISODES -> c.getString(R.string.offline_notif_phase_episodes, p.done, p.total)
            OfflinePhase.IMAGES -> c.getString(R.string.offline_notif_phase_images, p.done, p.total)
            OfflinePhase.CLEANUP -> c.getString(R.string.offline_notif_phase_cleanup)
        }

        fun canNotify(c: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun progressInfo(c: Context, workId: java.util.UUID, text: String, done: Int, total: Int): ForegroundInfo {
            val cancel = WorkManager.getInstance(c).createCancelPendingIntent(workId)
            val n = NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_round_sync_24)
                .setContentTitle(c.getString(R.string.offline_notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(total, done, total <= 0)
                .addAction(0, c.getString(R.string.offline_notif_cancel), cancel)
                .build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else ForegroundInfo(NOTIF_PROGRESS, n)
        }
    }
}
