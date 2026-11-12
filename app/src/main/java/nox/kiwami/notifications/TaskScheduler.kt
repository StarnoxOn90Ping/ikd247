package nox.kiwami.notifications

import android.content.Context
import nox.kiwami.notifications.anilist.AnilistNotificationWorker
import nox.kiwami.notifications.comment.CommentNotificationWorker
import nox.kiwami.notifications.subscription.SubscriptionNotificationWorker
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName

interface TaskScheduler {
    fun scheduleRepeatingTask(taskType: TaskType, interval: Long)
    fun cancelTask(taskType: TaskType)

    fun cancelAllTasks() {
        for (taskType in TaskType.entries) {
            cancelTask(taskType)
        }
    }

    fun scheduleAllTasks(context: Context) {
        for (taskType in TaskType.entries) {
            val interval = when (taskType) {
                TaskType.COMMENT_NOTIFICATION -> CommentNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.CommentNotificationInterval
                )]

                TaskType.ANILIST_NOTIFICATION -> AnilistNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.AnilistNotificationInterval
                )]

                TaskType.SUBSCRIPTION_NOTIFICATION -> SubscriptionNotificationWorker.checkIntervals[PrefManager.getVal(
                    PrefName.SubscriptionNotificationInterval
                )]
            }
            scheduleRepeatingTask(taskType, interval)
        }
    }

    companion object {
        fun create(context: Context, useAlarmManager: Boolean): TaskScheduler {
            return if (useAlarmManager) {
                AlarmManagerScheduler(context)
            } else {
                WorkManagerScheduler(context)
            }
        }

        fun scheduleSingleWork(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(
                CommentNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(CommentNotificationWorker::class.java)
                    .build()
            )
            workManager.enqueueUniqueWork(
                AnilistNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(AnilistNotificationWorker::class.java)
                    .build()
            )
            workManager.enqueueUniqueWork(
                SubscriptionNotificationWorker.WORK_NAME + "_single",
                androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequest.Builder(SubscriptionNotificationWorker::class.java)
                    .build()
            )
        }
    }

    enum class TaskType {
        COMMENT_NOTIFICATION,
        ANILIST_NOTIFICATION,
        SUBSCRIPTION_NOTIFICATION
    }
}

interface Task {
    suspend fun execute(context: Context): Boolean
}
